

package com.agent.teams;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

/**
 * FileMailBox — 跨进程收件箱
 * 整个Teams包唯一的通信基础设施。因为队友可能是另一个操作系统进程（tmux/iTerm 后端）,
 * 共享内存队列不可用，所以通信必须落成磁盘文件。每个 agent 一个 JSON 文件当收件箱。
 *
 * 并发模型分两层：
 *   ① 同 JVM：按 agentId 分片的 ReentrantLock——进程内队友之间的争用不进文件系统；
 *   ② 跨进程：锁文件 + Files.createFile 的原子性，配合"归属信息 + 心跳租约"。
 * 写入一律走"临时文件 + ATOMIC_MOVE 原子替换"，因此读者永远看到完整快照，读侧无需加锁。
 */
public class FileMailBox {
    /**
     * @param from 发件人 agent 名，用于在收件人侧渲染 From xxx: ...
     * @param text 正文，[shutdown] 前缀是控制指令
     * @param timestamp
     * @param read 已读标志
     * @param color 预留:UI 给不同队友分配颜色，当前全写 ""
     * @param summary 预留:消息摘要（对应 Claude 那种折叠展示），当前全写 ""
     */
    public record MailMessage(String from, String text, String timestamp,
                              boolean read, String color, String summary) {
        public MailMessage(String from, String text) {
            this(from, text, DateTimeFormatter.ISO_INSTANT.format(Instant.now()), false, "", "");
        }
    }

    private static final Logger log = Logger.getLogger(FileMailBox.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── 租约与重试参数 ──────────────────────────────────────────────
    /** 心跳静默超过它 → 认定持有人已死，锁可以被清理 */
    private static final Duration LEASE_TTL = Duration.ofSeconds(30);
    /** 续约间隔，取 TTL 的 1/6，留足容错空间 */
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(5);
    /** 抢锁总预算：超时就抛异常，绝不静默丢消息 */
    private static final Duration ACQUIRE_TIMEOUT = Duration.ofSeconds(10);
    /** 退避起点，之后每次翻倍 */
    private static final long BACKOFF_MIN_MS = 10;
    /** 退避上限 */
    private static final long BACKOFF_MAX_MS = 200;
    /** 僵尸锁被改名成"墓碑"后保留多久再删（给原持有人在 release 时一个察觉点） */
    private static final Duration TOMBSTONE_GRACE = Duration.ofSeconds(5);

    /** 全进程共享的心跳线程（daemon，不阻止 JVM 退出） */
    private static final ScheduledExecutorService HEARTBEAT_POOL =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "mailbox-heartbeat");
                //守护线程,主线程退出时它自动退出
                t.setDaemon(true);
                return t;
            });

    private final Path baseDir;
    /** agentId → 一把JVM内的锁同JVM的快速互斥：进程内队友之间的争用不必进文件系统 */
    private final ConcurrentHashMap<String, ReentrantLock> jvmLocks = new ConcurrentHashMap<>();

    public FileMailBox(Path baseDir) {
        this.baseDir = baseDir;
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            log.warning("cannot create mailbox dir " + baseDir + ": " + e);
        }
    }

    private Path inboxPath(String agentId) {
        return baseDir.resolve(agentId + ".json");
    }
    //锁文件路径,使用Files.createFile当锁原因:把"检查文件是否存在"和"创建文件"合并成了一个原子操作，
    // 由操作系统内核保证。谁创建成功，谁就持锁；谁收到 FileAlreadyExistsException，谁就知道锁在别人手里。锁的"状态"就是锁文件存在与否，文件内容是什么根本不重要（所以它是个空文件）。
    //不用sync:队友可能是另一个操作系统进程;不用FileChannel.lock():文件锁是"以整个JVM为单位"持有的，不适合用来在同一JVM内控制多个线程对文件的访问。
    // IN_PROCESS模式恰恰就是"同一JVM里多个队友线程"
    //（改造后锁文件不再为空：里面写入持有者 token/pid/host/心跳时间，用于判断死活与校验归属）
    private Path lockPath(String agentId) {
        return baseDir.resolve(agentId + ".json.lock");
    }

    // ══════════════════ 锁的支撑类型 ══════════════════

    /** 锁文件内容：谁持有、最后心跳是什么时候。没有它就无法判断死活、无法校验归属。 */
    /**
     * @param token 每次抢锁都新生成的UUID。这是"身份"，用来回答"这把锁是不是我的"。
     * @param pid pid/host:纯诊断用，日志里能看出是谁占着
     * @param host
     * @param heartbeatMillis
     */
    private record LockOwner(String token, long pid, String host, long heartbeatMillis) {}

    /** 临界区的返回值：要写回的新收件箱 + 本次操作的结果值 */
    private record Mutation<T>(List<MailMessage> messages, T value) {}
    /*
    * 纯函数式接口,定义了要对临界区做的事
    * */
    @FunctionalInterface
    private interface CriticalSection<T> {
        Mutation<T> apply(List<MailMessage> current);
    }

    /** 抢锁超时 / 读写失败：抛出来让调用方看见，不再静默丢消息 */
    public static class MailboxBusyException extends RuntimeException {
        public MailboxBusyException(String message) { super(message); }
    }

    // ══════════════════ 对外 API ══════════════════

    /** 投递一条消息。抢不到锁或写盘失败会抛 {@link MailboxBusyException}，不再静默丢弃。 */
    public void send(String recipient, MailMessage msg) {
        withLock(recipient, current -> {
            var next = new ArrayList<>(current);
            next.add(new MailMessage(msg.from(), msg.text(), msg.timestamp(), false, msg.color(), msg.summary()));
            return new Mutation<>(next, null);
        });
    }

    /**
     * 取出未读并原子地标记已读——"读"和"标记"在同一个临界区里完成，
     * 因此中间到达的新消息不会被误标，一条消息也只会被投递一次。
     * 调用方不要再单独调用 {@link #markAllRead(String)}。
     */
    public List<MailMessage> drainUnread(String agentId) {
        return withLock(agentId, current -> {
            var unread = current.stream().filter(m -> !m.read()).toList();
            if (unread.isEmpty()) return new Mutation<>(current, List.of());
            //有未读的消息则全部更新为已读
            var updated = current.stream()
                    .map(m -> m.read() ? m
                            : new MailMessage(m.from(), m.text(), m.timestamp(), true, m.color(), m.summary()))
                    .toList();
            return new Mutation<>(updated, unread);
        });
    }

    /** 只读快照：写入是原子替换，所以读者不需要加锁。 */
    public List<MailMessage> readUnread(String agentId) {
        var unread = new ArrayList<MailMessage>();
        for (var m : readInbox(agentId)) {
            if (!m.read()) unread.add(m);
        }
        return unread;
    }

    /** 把收件箱整体标记为已读。内部投递路径请改用 {@link #drainUnread(String)}。 */
    public void markAllRead(String agentId) {
        withLock(agentId, current -> new Mutation<>(current.stream()
                .map(m -> new MailMessage(m.from(), m.text(), m.timestamp(), true, m.color(), m.summary()))
                .toList(), null));
    }

    // ══════════════════ 锁入口：先 JVM 锁，后文件锁 ══════════════════

    private <T> T withLock(String agentId, CriticalSection<T> section) {
        // 同JVM:进程内队友之间用它互斥，零文件系统开销
        ReentrantLock jvmLock = jvmLocks.computeIfAbsent(agentId, k -> new ReentrantLock());
        try {
            //一直阻塞直到获取锁，相应线程中断
            jvmLock.lockInterruptibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MailboxBusyException("interrupted while waiting for mailbox lock: " + agentId);
        }
        try {
            // 跨进程：文件锁
            return withFileLock(agentId, section);
        } finally {
            jvmLock.unlock();
        }
    }

    private <T> T withFileLock(String agentId, CriticalSection<T> section) {
        Path lockFile = lockPath(agentId);
        LockOwner me = newOwner();
        long deadline = System.nanoTime() + ACQUIRE_TIMEOUT.toNanos();
        long backoffMs = BACKOFF_MIN_MS;

        while (true) {
            try {
                //原子抢锁
                Files.createFile(lockFile);
                //创建后立刻写归属,这中间有个极短窗口:文件已存在但内容为空.
                // 此时别的进程readOwner解析失败 → 返回 null → 按"无法判断"处理，不抢锁。所以窗口是安全的，代价只是竞争者短暂地无法清理僵尸锁。
                writeOwner(lockFile, me);
                break;
            }
            //有进程持有锁
            catch (FileAlreadyExistsException e)
            {
                // 先看是不是僵尸锁；清掉了就立刻重试，不计入退避
                if (evictIfStale(lockFile)) continue;
                if (System.nanoTime() >= deadline) {
                    throw new MailboxBusyException("mailbox '%s' busy after %s; message NOT delivered"
                            .formatted(agentId, ACQUIRE_TIMEOUT));
                }
                //随机退避后再次尝试
                sleepJittered(backoffMs);
                backoffMs = Math.min(backoffMs * 2, BACKOFF_MAX_MS);
            } catch (IOException e) {
                throw new MailboxBusyException("cannot acquire mailbox lock for " + agentId + ": " + e);
            }
        }

        ScheduledFuture<?> beat = startHeartbeat(lockFile, me);
        try {
            //拿到锁后进行具体操作
            Mutation<T> result = section.apply(readInbox(agentId));
            writeInboxAtomic(agentId, result.messages());
            return result.value();
        }
        //无论如何都要释放锁
        finally {
            beat.cancel(false);
            release(lockFile, me);
        }
    }

    // ══════════════════ 租约：归属、心跳、清理、释放 ══════════════════

    private static LockOwner newOwner() {
        return new LockOwner(UUID.randomUUID().toString(),
                ProcessHandle.current().pid(), hostName(), System.currentTimeMillis());
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }


    private void writeOwner(Path lockFile, LockOwner owner) {
        try {
            Files.writeString(lockFile, MAPPER.writeValueAsString(owner));
        } catch (IOException e) {
            // 写不上不影响持锁，只是别人无法通过心跳判断我们是否还活着
            log.fine("cannot write lock owner: " + e);
        }
    }

    /**
     * 读出锁的归属信息。解析失败（含"刚创建还没写内容"的空文件）一律返回 null，
     * 调用方必须把 null 当成"无法判断"——保守起见不抢锁。
     */
    private LockOwner readOwner(Path lockFile) {
        try {
            String json = Files.readString(lockFile);
            if (json.isBlank()) return null;
            return MAPPER.readValue(json, LockOwner.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 清理僵尸锁：只有"能读到归属信息"且"心跳静默超过 LEASE_TTL"才动手。
     * 清理用原子改名而不是删除——删除是"谁都能成功"的操作，两个清理者同时删，
     * 后一个可能删掉前一个刚建立的新锁；改名只有一个赢家。
     */
    private boolean evictIfStale(Path lockFile) {
        LockOwner owner = readOwner(lockFile);
        //看不懂不清理
        if (owner == null) return false;

        long ageMs = System.currentTimeMillis() - owner.heartbeatMillis();
        //还在用不清理
        if (ageMs < LEASE_TTL.toMillis()) return false;

        Path tomb = lockFile.resolveSibling(lockFile.getFileName() + ".stale-" + UUID.randomUUID());
        try {
            Files.move(lockFile, tomb, StandardCopyOption.ATOMIC_MOVE);
            log.warning("evicted stale mailbox lock %s (pid=%d host=%s silent=%dms)"
                    .formatted(lockFile.getFileName(), owner.pid(), owner.host(), ageMs));
            //墓碑保留5秒:给"被抢走锁的原持有者"一个可见痕迹——它的锁文件变成了 bob.json.lock.stale-<uuid>，据此可以排查"谁抢了谁的锁".5秒后由心跳线程回收。
            HEARTBEAT_POOL.schedule(() -> {
                try {
                    Files.deleteIfExists(tomb);
                } catch (IOException ignored) {}
            }, TOMBSTONE_GRACE.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (NoSuchFileException e) {
            // 别的进程已经清掉了，直接重试即可
            return true;
        } catch (IOException e) {
            log.fine("cannot evict stale lock " + lockFile.getFileName() + ": " + e);
            return false;
        }
    }

    /** 持锁期间定期续约，把"死活判断"与"临界区耗时"解耦：慢操作不再被误判成僵尸。
     *  刷新心跳,让临界区不管跑多久都不会被当成僵尸。把"死活判断"和"操作耗时"彻底解耦——旧代码的 10 秒阈值误伤合法慢操作的问题，从根上消失。
     *  每次续约前先确认token还是自己的。如果锁已经被别人抢走（极端情况下心跳停了 30 秒以上）,这里会直接返回、不再去写别人的锁文件。
     * */
    private ScheduledFuture<?> startHeartbeat(Path lockFile, LockOwner me) {
        return HEARTBEAT_POOL.scheduleAtFixedRate(() -> {
            LockOwner current = readOwner(lockFile);
            if (current == null || !current.token().equals(me.token())) return;   // 锁已经不是我的了
            writeOwner(lockFile, new LockOwner(me.token(), me.pid(), me.host(), System.currentTimeMillis()));
        }, HEARTBEAT_INTERVAL.toMillis(), HEARTBEAT_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** 释放前校验归属：锁一旦被别人抢走过，绝不删别人的锁。 */
    private void release(Path lockFile, LockOwner me) {
        LockOwner current = readOwner(lockFile);
        if (current == null || !current.token().equals(me.token())) {
            log.warning("mailbox lock " + lockFile.getFileName() + " no longer belongs to us; skip delete");
            return;
        }
        try {
            Files.deleteIfExists(lockFile);
        } catch (IOException e) {
            log.fine("cannot release mailbox lock " + lockFile.getFileName() + ": " + e);
        }
    }

    /** 指数退避 + 抖动：避免多个抢锁者同步重试导致活锁。
     * 指数增长：低争用时第一次重试就成功，高争用时把重试频率压下来，减少无意义的文件系统操作。
     * 随机抖动：如果两个竞争者都用固定间隔，它们会永远同步地撞在一起（A 醒来抢、B 也醒来抢，A 失败 B 也失败，下一轮继续撞）
     * */
    private static void sleepJittered(long baseMs) {
        long ms = baseMs + ThreadLocalRandom.current().nextLong(baseMs / 2 + 1);
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new MailboxBusyException("interrupted while waiting for mailbox lock");
        }
    }

    // ══════════════════ 读写 ══════════════════

    private List<MailMessage> readInbox(String agentId) {
        Path path = inboxPath(agentId);
        if (!Files.exists(path)) return new ArrayList<>();
        try {
            byte[] data = Files.readAllBytes(path);
            return MAPPER.readValue(data, new TypeReference<>() {});
        } catch (IOException e) {
            // 不再假装"收件箱是空的"：读失败必须让调用方看见
            log.warning("cannot read mailbox " + path + ": " + e);
            throw new MailboxBusyException("cannot read mailbox " + path);
        }
    }

    /**
     * 先写临时文件、再原子改名覆盖目标：读者要么看到旧版本、要么看到新版本，
     * 永远看不到"写了一半"的 JSON，因此读侧不需要加锁。
     */
    private void writeInboxAtomic(String agentId, List<MailMessage> messages) {
        Path target = inboxPath(agentId);
        Path tmp = baseDir.resolve(agentId + ".json.tmp-" + UUID.randomUUID());
        try {
            String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(messages);
            Files.writeString(tmp, json);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new MailboxBusyException("cannot write mailbox " + target + ": " + e);
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {}
        }
    }
}
