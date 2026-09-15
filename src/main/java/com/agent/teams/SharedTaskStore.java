

package com.agent.teams;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.regex.Pattern;

// 让多个同队 agent 在同一份任务清单上认领工作、声明依赖的任务台账（对应 Claude Code 的 TaskCreate/TaskList/TaskUpdate）。
//
// 存储形态：append-only 事件日志 <teamDir>/tasks.jsonl，一行一个 JSON 事件：
//   {"op":"create","id":1,"title":"parser","createdBy":"lead","ts":"..."}
//   {"op":"update","id":1,"status":"in_progress","assignee":"bob","ts":"..."}
//   {"op":"snapshot","tasks":[...],"ts":"..."}        <- 压缩时写入的一条全量快照
//
// 为什么不再用旧的"整表重写 tasks.json"：
//   1) 复杂度：旧版每次 create/update 都要序列化整份列表再写回，n 次操作 O(n^2)；append 一行是 O(1)。
//   2) 并发：旧版是"读-改-写"，两个写者互相覆盖（丢任务）；append-only 下各写各的，谁的都不丢。
//   3) 崩溃安全：append 是单次 write，读不到"半张表"；最坏是最后一行被截断，重放时跳过即可。
//
// 跨进程一致性的边界（重要）：
//   append 本身是安全的——O_APPEND 由内核保证"定位到 EOF 再写"，两个进程各写各的、互不覆盖；
//   一行很小、单次 write，本地文件系统上不会被撕裂（实测 4/8 进程并发各 500/250 行：行行完整、零重复）。
//   但有两处是"读-改-写"决策，append 救不了，必须互斥：
//     a) create 分配 id（两个进程各自 max+1 会撞号）
//     b) compact 读全量再整体替换（替换会覆盖掉别人在窗口内追加的事件）
//   所以这两处各自用一把"短锁"（tasks.jsonl.lock，临界区是毫秒级）：
//   等待有 5 秒预算；抢不到就报错（绝不无锁分配 id——那会直接产出重复 id），
//   压缩抢不到锁则跳过（压缩只是优化，不是正确性）。
//   update / get / listTasks 完全不加锁——这正是 append-only 换来的好处。
// sync用于保证同进程对该类内部对象操作的互斥,文件锁+append_only保证跨进程操作的互斥
public class SharedTaskStore {
    // 任务条目：id 自增主键；assignee 空串 = 无人认领；blocks/blockedBy 是任务 id 列表（下游/上游）
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SharedTask(
            int id, String title, String description, String status,
            String assignee, List<Integer> blocks, List<Integer> blockedBy,
            String createdBy
    ) {
        public SharedTask withStatus(String s) {
            return new SharedTask(id, title, description, s, assignee, blocks, blockedBy, createdBy);
        }

        public SharedTask withAssignee(String a) {
            return new SharedTask(id, title, description, status, a, blocks, blockedBy, createdBy);
        }
    }

    // 日志里的一行事件，三种 op：
    //   create   : 新建（用 id/title/description/createdBy）
    //   update   : 局部更新（status/assignee/blocks/blockedBy，合并语义与 update() 完全一致）
    //   snapshot : 压缩后的全量状态（tasks 字段），重放时直接替换内存表
    // NON_NULL 让用不到的字段不写进 JSON；字段全用包装类型，缺失即 null = "这条事件不改该字段"。
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record TaskEvent(
            String op, Integer id, String title, String description,
            String status, String assignee,
            List<Integer> blocks, List<Integer> blockedBy,
            List<SharedTask> tasks, String createdBy, String ts
    ) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger log = Logger.getLogger(SharedTaskStore.class.getName());

    // 日志里事件超过这么多条就压成一条 snapshot
    private static final int COMPACT_THRESHOLD = 200;

    // 短锁参数：临界区只有"读日志算 max id / 追加一行 / 写快照"这几步。
    // 注意临界区里包含一次 fsync，实测单次耗时可达十几毫秒，多个进程竞争时等待时间会长一些，
    // 所以等待预算给到 5 秒（而不是几十毫秒——那会导致抢不到锁进而产生重复 id）。
    private static final long LOCK_WAIT_NANOS = 5_000_000_000L;
    // 持锁者若 30 秒没有动静 = 崩溃残留（正常临界区是毫秒级）
    private static final long LOCK_STALE_MS = 30_000L;

    // 从任意一行 JSON 里抓 "id":N —— create 事件和 snapshot 里的任务数组都能抓到
    private static final Pattern ID_FIELD = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");

    // 事件日志
    private final Path logPath;

    private final AtomicInteger nextId = new AtomicInteger(1);
    private final List<SharedTask> tasks = new ArrayList<>();
    // 距上一条 snapshot 之后累积的事件数（含重建时遇到的）
    private int eventsSinceSnapshot = 0;

    public SharedTaskStore(Path teamDir) {
        this.logPath = teamDir.resolve("tasks.jsonl");
        rebuildFromDisk();
    }

    // ── 对外 API：签名与旧版完全一致，调用方（TaskTools）无需改动 ──

    public synchronized SharedTask create(String title, String description, String createdBy) {
        // id 分配是"读-改-写"决策：必须与其它进程互斥，否则两个进程会各自算出同一个 max+1。
        // 锁的范围包含"算 id + 追加事件"两步——只锁算号是不够的：解锁到 append 之间，
        SharedTask created = withFileLock(() -> {
            int id = Math.max(nextId.get(), maxIdOnDisk() + 1);
            nextId.set(id + 1);
            var task = new SharedTask(id, title, description, "todo", "", List.of(), List.of(), createdBy);
            tasks.add(task);
            appendEvent(new TaskEvent("create", id, title, description,
                    null, null, null, null, null, createdBy, Instant.now().toString()));
            return task;
        });
        maybeCompact();
        return created;
    }

    public synchronized SharedTask get(int id) {
        return tasks.stream().filter(t -> t.id() == id).findFirst().orElse(null);
    }

    public synchronized List<SharedTask> listTasks(String status, String assignee) {
        return tasks.stream()
                .filter(t -> status == null || status.isEmpty() || t.status().equals(status))
                .filter(t -> assignee == null || assignee.isEmpty() || t.assignee().equals(assignee))
                .toList();
    }

    public synchronized SharedTask update(int id, String status, String assignee,
                                          List<Integer> addBlocks, List<Integer> addBlockedBy) {
        // 不需要锁：只是往日志尾部追加一条 delta，不覆盖任何东西
        if (!applyUpdate(id, status, assignee, addBlocks, addBlockedBy)) return null;
        appendEvent(new TaskEvent("update", id, null, null,
                status, assignee, addBlocks, addBlockedBy, null, null, Instant.now().toString()));
        maybeCompact();
        return get(id);
    }

    // ── 事件重放 ──

    // 把一条事件应用到内存状态；返回 false 表示这条事件用不了（缺 id / 目标任务不存在等）
    private boolean applyEvent(TaskEvent ev) {
        if (ev == null || ev.op() == null) return false;
        switch (ev.op()) {
            case "snapshot" -> {
                tasks.clear();
                if (ev.tasks() != null) tasks.addAll(ev.tasks());
                eventsSinceSnapshot = 0;
                return true;
            }
            case "create" -> {
                if (ev.id() == null) return false;
                tasks.add(new SharedTask(ev.id(), nz(ev.title()), nz(ev.description()),
                        "todo", "", List.of(), List.of(), nz(ev.createdBy())));
                eventsSinceSnapshot++;
                return true;
            }
            case "update" -> {
                if (ev.id() == null) return false;
                if (applyUpdate(ev.id(), ev.status(), ev.assignee(), ev.blocks(), ev.blockedBy())) {
                    eventsSinceSnapshot++;
                    return true;
                }
                return false;
            }
            default -> {
                log.warning("unknown task event op '" + ev.op() + "' in " + logPath + " (ignored)");
                return false;
            }
        }
    }

    // status 非空才替换；assignee 非 null 就替换（传空串 = 取消认领）；blocks/blockedBy 追加且不去重；
    // title/description/createdBy 创建后不可改。
    // 在线路径和重放路径共用这一份实现，保证"运行中的语义"和"重启后重放出来的状态"不会漂移。
    private boolean applyUpdate(int id, String status, String assignee,
                                List<Integer> addBlocks, List<Integer> addBlockedBy) {
        for (int i = 0; i < tasks.size(); i++) {
            if (tasks.get(i).id() == id) {
                var old = tasks.get(i);
                String newStatus = (status != null && !status.isEmpty()) ? status : old.status();
                String newAssignee = (assignee != null) ? assignee : old.assignee();

                var newBlocks = new ArrayList<>(old.blocks());
                if (addBlocks != null) newBlocks.addAll(addBlocks);

                var newBlockedBy = new ArrayList<>(old.blockedBy());
                if (addBlockedBy != null) newBlockedBy.addAll(addBlockedBy);

                tasks.set(i, new SharedTask(id, old.title(), old.description(), newStatus,
                        newAssignee, newBlocks, newBlockedBy, old.createdBy()));
                return true;
            }
        }
        return false;
    }

    // ── 追加 ──

    // 追加一条事件：APPEND 打开 + 单次 write + fsync。
    // APPEND 由内核保证每次写入定位到文件末尾、不覆盖别人已写下的事件；一行很小，单次 write 不会被读者看到半行。
    // 每次重新 open（而不是长期持有 channel）：压缩会原子替换日志文件，旧 channel 指向的是被替换掉的旧文件。
    private void appendEvent(TaskEvent ev) {
        String line;
        try {
            line = MAPPER.writeValueAsString(ev) + "\n";
        } catch (IOException e) {
            log.warning("cannot serialize task event: " + e);
            return;
        }
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        try (FileChannel ch = FileChannel.open(logPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ch.write(ByteBuffer.wrap(bytes));
            // 落盘：进程崩溃/断电后事件不丢。任务板写入频率很低，这点开销可以接受。
            ch.force(false);
        } catch (IOException e) {
            log.warning("cannot append task event to " + logPath + ": " + e
                    + " - in-memory board still has this change but it is NOT durable");
            return;
        }
        eventsSinceSnapshot++;
    }

    private void maybeCompact() {
        if (eventsSinceSnapshot < COMPACT_THRESHOLD) return;
        try {
            compact();
        } catch (Exception e) {
            // 压缩只是优化（折叠历史、缩短重放）；拿不到锁或写失败都不该影响这次操作本身
            log.warning("task-log compaction skipped: " + e.getMessage());
        }
    }

    // ── 压缩 ──

    // 压缩 = "读全量 → 写快照 → 整体替换日志"，这是一处读-改-写，必须与其它进程互斥；
    // 否则 A 读完之后 B 追加的那条事件会被 A 的替换动作覆盖掉（真的丢数据）。
    // 锁内先 rebuildFromDisk()：把内存与磁盘收敛到同一时刻（含别的进程刚写下的事件），再据此生成快照。
    private void compact() {
        withFileLock(() -> {
            rebuildFromDisk();
            TaskEvent snap = new TaskEvent("snapshot", null, null, null, null, null, null, null,
                    List.copyOf(tasks), null, Instant.now().toString());
            Path tmp = logPath.resolveSibling(logPath.getFileName() + ".tmp-" + UUID.randomUUID());
            try {
                Files.writeString(tmp, MAPPER.writeValueAsString(snap) + "\n", StandardCharsets.UTF_8);
                // 原子替换：压缩过程崩溃时读者要么看到旧日志、要么看到新日志，绝不会看到写了一半的日志
                Files.move(tmp, logPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                eventsSinceSnapshot = 0;
            } catch (IOException e) {
                log.warning("cannot compact task log " + logPath + ": " + e);
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            }
            return null;
        });
    }

    // ── 短锁 ──

    // 用"锁文件的存在性"做跨进程互斥：Files.createFile 把"检查是否存在"和"创建"合并成一次原子系统调用。
    // 与 FileMailBox 那套相比这里刻意做得更简单——临界区有界且短，不需要归属 token 和心跳续约，
    // 一个 mtime 过期阈值就够（30s 没被动过 = 崩溃残留）。
    //
    // 抢不到锁时**抛异常**而不是"无锁继续"：这是实测换来的教训——
    // 一旦降级成无锁写入，两个进程会各自算出同一个 max+1，直接产生重复 id
    // （实测 6 进程 × 20 次：预算只有 275ms 时出现 2~5 个重复 id；根因就是走了降级分支）。
    // 调用方（TaskCreate 工具）会把异常转成工具错误，让模型稍后重试，而不是悄悄写坏数据。
    private <T> T withFileLock(Supplier<T> body) {
        Path lockFile = logPath.resolveSibling(logPath.getFileName() + ".lock");
        long deadline = System.nanoTime() + LOCK_WAIT_NANOS;
        while (true) {
            try {
                Files.createFile(lockFile);
                break;                                  // 拿到锁
            } catch (FileAlreadyExistsException | AccessDeniedException e) {
                // 有人持锁。注意 Windows 上"createFile 撞上别人正在删除锁文件"返回的是 ACCESS_DENIED
                // 而不是 FILE_EXISTS —— 这是瞬时竞态，同样按"重试"处理。
                if (evictIfStaleLock(lockFile)) continue;   // 清掉残留锁后立刻重试
                if (System.nanoTime() >= deadline) {
                    throw new IllegalStateException("task board is locked by another process (" + lockFile
                            + "); gave up after 5s — retry shortly");
                }
                sleepBackoff();
            } catch (IOException e) {
                throw new IllegalStateException("cannot create task-board lock " + lockFile + ": " + e, e);
            }
        }
        try {
            return body.get();
        } finally {
            try { Files.deleteIfExists(lockFile); } catch (IOException ignored) {}
        }
    }

    // 退避：临界区通常十几毫秒，所以先短睡（快速轮转），避免不必要的长等待
    private static void sleepBackoff() {
        try {
            Thread.sleep(1 + ThreadLocalRandom.current().nextInt(6));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for task-board lock", ie);
        }
    }

    // 清理崩溃残留的锁：用"原子改名"而不是删除——删除是"谁都能成功"的操作，
    // 两个清理者同时删，后一个可能删掉前一个刚建立的新锁；改名只有一个赢家。
    private boolean evictIfStaleLock(Path lockFile) {
        try {
            var mtime = Files.getLastModifiedTime(lockFile).toInstant();
            if (!Instant.now().minusMillis(LOCK_STALE_MS).isAfter(mtime)) return false;
            Path tomb = lockFile.resolveSibling(lockFile.getFileName() + ".stale-" + UUID.randomUUID());
            Files.move(lockFile, tomb, StandardCopyOption.ATOMIC_MOVE);
            log.warning("removed stale task-log lock " + lockFile + " (holder likely crashed)");
            try { Files.deleteIfExists(tomb); } catch (IOException ignored) {}
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // ── 加载 / 重建 ──

    // 从磁盘重放日志，重建内存状态与 nextId。构造时调用一次；压缩时在锁内再调用一次（用于与磁盘收敛）。
    private void rebuildFromDisk() {
        tasks.clear();
        eventsSinceSnapshot = 0;
        if (!Files.exists(logPath)) {
            nextId.set(1);
            return;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(logPath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warning("cannot read task log " + logPath + ": " + e
                    + " - treating the task board as EMPTY (new task ids restart at 1)");
            nextId.set(1);
            return;
        }
        int bad = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty()) continue;
            TaskEvent ev;
            try {
                ev = MAPPER.readValue(line, TaskEvent.class);
            } catch (Exception e) {
                // 典型场景：上次崩溃时最后一行只写了一半 -> 跳过它，前面的状态依然可用
                bad++;
                log.fine("skipping unreadable task event at line " + (i + 1) + " of " + logPath + ": " + e);
                continue;
            }
            if (!applyEvent(ev)) bad++;
        }
        if (bad > 0) {
            log.warning("skipped " + bad + " unusable task event(s) in " + logPath
                    + " (likely a torn append from a crash); the rest of the board was replayed");
        }
        nextId.set(tasks.stream().mapToInt(SharedTask::id).max().orElse(0) + 1);
    }

    // 磁盘上出现过的最大 id（不重建状态，只扫 "id":N）。
    // create 在锁内用它来兜底：本进程启动后，别的进程可能已经建了新任务，内存里的 nextId 是陈旧的。
    private int maxIdOnDisk() {
        if (!Files.exists(logPath)) return 0;
        int max = 0;
        try {
            for (String line : Files.readAllLines(logPath, StandardCharsets.UTF_8)) {
                var m = ID_FIELD.matcher(line);
                while (m.find()) {
                    try {
                        max = Math.max(max, Integer.parseInt(m.group(1)));
                    } catch (NumberFormatException ignored) {}
                }
            }
        } catch (IOException e) {
            log.warning("cannot scan task log " + logPath + " for max id: " + e);
        }
        return max;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
