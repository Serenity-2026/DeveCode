

package com.agent.worktree;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 把WorktreeSession会话记录持久化到 .devecode/worktree_session.json，并维护进程内单例。
 */
public final class WorktreeSessionStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static volatile WorktreeSession currentSession;

    private WorktreeSessionStore() {}

    public static WorktreeSession getCurrentSession() {
        return currentSession;
    }

    public static void restoreSession(WorktreeSession session) {
        currentSession = session;
    }

    public static void save(String repoRoot, WorktreeSession session) throws IOException {
        Path path = sessionPath(repoRoot);
        if (session == null) {
            Files.deleteIfExists(path);
            return;
        }
        Files.createDirectories(path.getParent());
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), session);
    }

    public static WorktreeSession load(String repoRoot) {
        Path path = sessionPath(repoRoot);
        if (!Files.exists(path)) {
            return null;
        }
        try {
            return MAPPER.readValue(path.toFile(), WorktreeSession.class);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 启动时把磁盘上的会话记录恢复进内存单例——这是"重启之后还能用 ExitWorktree"的前提：
     * 内存单例是进程级的，重启就没了，而 ExitWorktree 靠 getCurrentSession() 判断自己在不在树里。
     *
     * 三种情况：
     *   - 没有记录：返回 null，什么都不做；
     *   - 记录有效（树目录还在）：恢复进内存并返回，调用方负责把 Agent 的工作目录指过去；
     *   - 记录已失效（树目录被手工删掉了）：清掉这条陈旧记录后返回 null，
     *     否则工具会一直以为自己在树里，而 ExitWorktree 又拿着一棵不存在的树去操作。
     */
    public static WorktreeSession restoreFromDisk(String repoRoot) {
        WorktreeSession session = load(repoRoot);
        if (session == null) return null;

        String rawPath = session.worktreePath();
        boolean alive = rawPath != null && !rawPath.isBlank() && Files.isDirectory(Path.of(rawPath));
        if (!alive) {
            try {
                save(repoRoot, null);      // 树都不在了，记录没有意义
            } catch (IOException ignored) {
                // 清不掉也不影响启动：下次进树时会覆盖它
            }
            return null;
        }

        currentSession = session;
        return session;
    }

    static void clearForTesting() {
        currentSession = null;
    }

    /** 注意目录名是 .devecode（全小写）：写成 .deveCode 在 Windows 上能跑，换 Linux 就会另建一个目录。 */
    private static Path sessionPath(String repoRoot) {
        return Path.of(repoRoot, ".devecode", "worktree_session.json");
    }
}
