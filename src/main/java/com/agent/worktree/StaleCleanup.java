package com.agent.worktree;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 正常的 worktree 生命周期是：创建 → 使用 → remove 删除。但 coding agent 会崩溃、被杀、断电、重启。一旦发生，负责“用完删除”的代码根本没机会执行，磁盘上就会留下：
 * - 孤立的 worktree 目录；
 * - 被它独占的 worktree-xxx 分支；
 * - .git/worktrees 里的元数据。
 * WorktreeManager 救不了这种情况：它靠进程内内存 Map，进程重启后 Map 是空的，它甚至不知道残留存在。所以需要另一个不依赖内存、直接扫磁盘的清理者——这就是 StaleCleanup。
 *
 *
 */
public final class StaleCleanup {

    private static final Logger log = Logger.getLogger(StaleCleanup.class.getName());
    //哪些命名属于自动生成的临时 worktree,只认识这几类名字，其余一律不碰
    private static final List<Pattern> EPHEMERAL_PATTERNS = List.of(
            Pattern.compile("^agent-a[0-9a-f]{7}$"),
            Pattern.compile("^wf_[0-9a-f]{8}-[0-9a-f]{3}-\\d+$"),
            Pattern.compile("^wf-\\d+$"),
            Pattern.compile("^bridge-[A-Za-z0-9_]+(-[A-Za-z0-9_]+)*$"),
            Pattern.compile("^job-[a-zA-Z0-9._-]{1,55}-[0-9a-f]{8}$")
    );

    private StaleCleanup() {}

    static boolean isEphemeral(String slug) {
        return EPHEMERAL_PATTERNS.stream().anyMatch(p -> p.matcher(slug).matches());
    }

    /**
     * Scans the worktrees directory and removes stale ephemeral worktrees
     * older than cutoff. Three-layer safety filter.
     */
    public static int cleanup(String repoRoot, Instant cutoff) {
        Path dir = Path.of(repoRoot, ".devecode", "worktrees");
        if (!Files.isDirectory(dir)) return 0;

        String currentPath = null;
        var session = WorktreeSessionStore.getCurrentSession();
        if (session != null) currentPath = session.worktreePath();

        int removed = 0;
        try (Stream<Path> entries = Files.list(dir)) {
            for (Path entry : entries.toList()) {
                String slug = entry.getFileName().toString();

                // Layer 1:命名必须匹配 ephemeral 模式,跳过当前worktree
                if (!isEphemeral(slug)) continue;

                String wtPath = entry.toString();
                if (wtPath.equals(currentPath)) continue;

                // Layer 2:mtime 必须早于 cutoff
                try {
                    var attrs = Files.readAttributes(entry, BasicFileAttributes.class);
                    if (attrs.lastModifiedTime().toInstant().isAfter(cutoff)) continue;
                } catch (IOException e) {
                    continue;
                }

                // Layer 3:必须没有任何已跟踪改动,-uno（= --untracked-files=no）:忽略untracked文件,只关注已跟踪文件是否有真实修改
                String statusOut = runGitQuiet(wtPath, "--no-optional-locks", "status", "--porcelain", "-uno");
                if (statusOut == null || !statusOut.isBlank()) continue;
                // Layer 3:必须没有未推送的 commit
                String unpushedOut = runGitQuiet(wtPath, "rev-list", "--max-count=1", "HEAD", "--not", "--remotes");
                if (unpushedOut == null || !unpushedOut.isBlank()) continue;
                //还原为分支名
                String branch = SlugValidator.branchName(slug);
                //删除worktree和分支
                if (AgentWorktree.remove(wtPath, branch, repoRoot)) {
                    removed++;
                }
            }
        } catch (IOException e) {
            log.fine("Failed to list worktrees directory: " + e.getMessage());
        }

        if (removed > 0) {
            runGitQuiet(repoRoot, "worktree", "prune");
        }
        return removed;
    }

    /**
     * 自动运行清理程序
     */
    public static void startCleanupLoop(
            ScheduledExecutorService executor,
            String repoRoot,
            int intervalSeconds,
            int cutoffHours
    ) {
        if (intervalSeconds <= 0) return;
        executor.scheduleAtFixedRate(() -> {
            try {
                Instant cutoff = Instant.now().minusSeconds((long) cutoffHours * 3600);
                int removed = cleanup(repoRoot, cutoff);
                if (removed > 0) {
                    log.fine("Cleaned up " + removed + " stale worktree(s)");
                }
            } catch (Exception e) {
                log.fine("Stale cleanup error: " + e.getMessage());
            }
        }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    private static String runGitQuiet(String cwd, String... args) {
        try {
            String[] cmd = new String[args.length + 1];
            cmd[0] = "git";
            System.arraycopy(args, 0, cmd, 1, args.length);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(Path.of(cwd).toFile());
            pb.environment().put("GIT_TERMINAL_PROMPT", "0");
            pb.environment().put("GIT_ASKPASS", "");
            pb.redirectErrorStream(false);
            Process proc = pb.start();
            String stdout = new String(proc.getInputStream().readAllBytes());
            boolean finished = proc.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                return null;
            }
            return proc.exitValue() == 0 ? stdout : null;
        } catch (Exception e) {
            return null;
        }
    }
}
