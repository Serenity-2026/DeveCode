

package com.agent.teams;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * FileMailBox — 跨进程收件箱
 * 整个Teams包唯一的通信基础设施。因为队友可能是另一个操作系统进程（tmux/iTerm 后端）,
 * 共享内存队列不可用，所以通信必须落成磁盘文件。每个 agent 一个 JSON 文件当收件箱。
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

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_RETRIES = 10;
    private static final int MIN_SLEEP_MS = 5;
    private static final int MAX_SLEEP_MS = 100;

    private final Path baseDir;

    public FileMailBox(Path baseDir) {
        this.baseDir = baseDir;
        try {
            Files.createDirectories(baseDir);
        } catch (IOException ignored) {}
    }

    private Path inboxPath(String agentId) {
        return baseDir.resolve(agentId + ".json");
    }
    //锁文件路径,使用Files.createFile当锁原因:把"检查文件是否存在"和"创建文件"合并成了一个原子操作，
    // 由操作系统内核保证。谁创建成功，谁就持锁；谁收到 FileAlreadyExistsException，谁就知道锁在别人手里。锁的"状态"就是锁文件存在与否，文件内容是什么根本不重要（所以它是个 0 字节空文件）。
    //不用sync:队友可能是另一个操作系统进程;不用FileChannel.lock():文件锁是"以整个JVM为单位"持有的，不适合用来在同一JVM内控制多个线程对文件的访问。
    // IN_PROCESS模式恰恰就是"同一JVM里多个队友线程"
    private Path lockPath(String agentId) {
        return baseDir.resolve(agentId + ".json.lock");
    }

    public void send(String recipient, MailMessage msg) {
        withLock(recipient, messages -> {
            var m = new MailMessage(msg.from(), msg.text(), msg.timestamp(), false, msg.color(), msg.summary());
            messages.add(m);
            return messages;
        });
    }

    public List<MailMessage> readUnread(String agentId) {
        List<MailMessage> messages = readInbox(agentId);
        List<MailMessage> unread = new ArrayList<>();
        for (var m : messages) {
            if (!m.read()) unread.add(m);
        }
        return unread;
    }

    public void markAllRead(String agentId) {
        withLock(agentId, messages -> {
            List<MailMessage> updated = new ArrayList<>();
            for (var m : messages) {
                updated.add(new MailMessage(m.from(), m.text(), m.timestamp(), true, m.color(), m.summary()));
            }
            return updated;
        });
    }

    private interface MutationFn {
        List<MailMessage> apply(List<MailMessage> messages);
    }

    private void withLock(String agentId, MutationFn fn) {
        Path lock = lockPath(agentId);
        boolean acquired = false;

        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                //原子抢锁
                Files.createFile(lock);
                acquired = true;
                break;
            }
            //有进程持有锁
            catch (FileAlreadyExistsException e)
            {
                // Check for stale lock (>10s old)
                try {
                    //清理僵尸锁
                    var modTime = Files.getLastModifiedTime(lock).toInstant();
                    if (Instant.now().minusSeconds(10).isAfter(modTime)) {
                        Files.deleteIfExists(lock);
                    }
                } catch (IOException ignored) {}
                //随机退避后再次尝试
                int sleepMs = MIN_SLEEP_MS + ThreadLocalRandom.current().nextInt(MAX_SLEEP_MS - MIN_SLEEP_MS);
                try { Thread.sleep(sleepMs); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } catch (IOException e) {
                return;
            }
        }

        if (!acquired) return;

        try {
            //拿到锁后进行具体操作
            List<MailMessage> messages = readInbox(agentId);
            messages = fn.apply(messages);
            writeInbox(agentId, messages);
        }
        //无论如何都要释放锁
        finally {
            try {
                Files.deleteIfExists(lock);
            } catch (IOException ignored) {}
        }
    }

    private List<MailMessage> readInbox(String agentId) {
        Path path = inboxPath(agentId);
        if (!Files.exists(path)) return new ArrayList<>();
        try {
            byte[] data = Files.readAllBytes(path);
            return MAPPER.readValue(data, new TypeReference<>() {
            });
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    private void writeInbox(String agentId, List<MailMessage> messages) {
        Path path = inboxPath(agentId);
        try {
            String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(messages);
            Files.writeString(path, json);
        } catch (IOException ignored) {}
    }
}
