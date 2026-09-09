

package com.agent.worktree;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 负责创建/删除/列出/清理由主 Agent 管理的工作树，并维护内存索引。
 * 主Agent进入worktree:
 * EnterWorktreeTool.execute()
 *   ├─ 检查 WorktreeSessionStore.getCurrentSession() != null
 *   │     → 如果已经在 worktree 里，拒绝重复进入
 *   ├─ SlugValidator.validate(slug)
 *   ├─ worktreeManager.create(slug, null)
 *   │     ├─ SlugValidator 再校验一次（双保险）
 *   │     ├─ git worktree add -B ...
 *   │     ├─ PostCreationSetup.perform(...)
 *   │     └─ 把 WorktreeInfo 记进自己的 Map
 *   ├─ new WorktreeSession(original_cwd, path, name, branch, ...)
 *   ├─ WorktreeSessionStore.restoreSession(session)   // 内存单例
 *   └─ WorktreeSessionStore.save(root, session)       // 磁盘持久化
 *
 *
 *   退出worktree:
 *   ExitWorktreeTool.execute()
 *   ├─ WorktreeSessionStore.getCurrentSession()  // 没有就 no-op
 *   ├─ WorktreeChanges.countChanges(path, originalHeadCommit)
 *   │     ├─ 有改动且没显式 discard → 拒绝删除
 *   │     └─ 干净 → 允许 remove
 *   ├─ WorktreeSessionStore.restoreSession(null)
 *   ├─ WorktreeSessionStore.save(root, null)      // 清磁盘记录
 *   └─ worktreeManager.remove(session.worktreeName())
 *         └─ 从自己的 Map 找到 path → git worktree remove → 从 Map 移除
 */
public class WorktreeManager {

    public record WorktreeInfo(String path, String branch, Instant createdAt) {}

    private final String projectRoot;
    //需要从主目录软链进worktree的目录（如 node_modules），避免每个worktree都装一遍依赖。
    private final List<String> symlinkDirs;
    //超过多久h算过期
    private final int staleCutoffHours;
    //本进程创建并拥有的worktree台账，按branch名索引的进程内记录
    private final Map<String, WorktreeInfo> worktrees = new LinkedHashMap<>();

    public WorktreeManager(String projectRoot, List<String> symlinkDirs, int staleCutoffHours) {
        this.projectRoot = projectRoot;
        this.symlinkDirs = symlinkDirs != null ? symlinkDirs : List.of();
        this.staleCutoffHours = staleCutoffHours > 0 ? staleCutoffHours : 24;
    }

    public String getProjectRoot() { return projectRoot; }
    public List<String> getSymlinkDirs() { return symlinkDirs; }
    public int getStaleCutoffHours() { return staleCutoffHours; }

    /**
     * Creates a new git worktree for the given branch under
     * {@code .devecode/worktrees/<branch>}.
     *
     * @param branch    the new branch name
     * @param targetDir optional override for the worktree directory; when
     *                  {@code null}, defaults to {@code .devecode/worktrees/<branch>}
     * @return metadata about the created worktree
     */
    public synchronized WorktreeInfo create(String branch, Path targetDir) throws Exception {
        // 在执行任何 git 操作前校验分支名，防止路径穿越和非法字符
        SlugValidator.validate(branch);
        //targetDir不为null和空串时用targetDir,否则创建<root>/.devecode/worktrees/<branch>
        Path wtDir = targetDir != null && !targetDir.toString().isEmpty()
                ? targetDir
                : Path.of(projectRoot, ".devecode", "worktrees", branch);

        // 如果之前删除 worktree 时留下了同名孤儿分支，它会把该分支重置到当前HEAD而不是报错
        // 运行 git worktree add -B <branch> <dir>
        String output = runGit(projectRoot, "git", "worktree", "add", "-B", branch, wtDir.toString());

        // Post-creation setup: settings, hooks, symlinks, .worktreeinclude
        PostCreationSetup.perform(projectRoot, wtDir.toString(), symlinkDirs);

        var info = new WorktreeInfo(wtDir.toString(), branch, Instant.now());
        worktrees.put(branch, info);
        return info;
    }

    /**
     * Removes a worktree by branch name.
     */
    public synchronized void remove(String branch) throws Exception {
        WorktreeInfo info = worktrees.get(branch);
        if (info == null) {
            throw new IllegalArgumentException("worktree not found: " + branch);
        }

        runGit(projectRoot, "git", "worktree", "remove", info.path(), "--force");
        worktrees.remove(branch);
    }

    /**
     * Lists worktrees by parsing {@code git worktree list --porcelain} output.
     */
    public synchronized List<WorktreeInfo> list() {
        try {
            String output = runGit(projectRoot, "git", "worktree", "list", "--porcelain");
            //解析 git worktree list --porcelain 的输出
            List<WorktreeInfo> result = parsePorcelain(output);
            if (!result.isEmpty()) {
                return result;
            }
        } catch (Exception ignored) {
            // fall through to in-memory map
        }
        return new ArrayList<>(worktrees.values());
    }

    /**
     * Returns the worktree info for a branch if it is tracked in memory.
     */
    public synchronized Optional<WorktreeInfo> get(String branch) {
        return Optional.ofNullable(worktrees.get(branch));
    }

    /**
     * Removes worktrees older than the given number of hours.
     *
     * @param cutoffHours maximum age in hours; uses the configured default when {@code <= 0}
     * @return the number of worktrees removed
     */
    public synchronized int cleanupStale(int cutoffHours) {
        int hours = cutoffHours > 0 ? cutoffHours : staleCutoffHours;
        Instant cutoff = Instant.now().minusSeconds((long) hours * 3600);
        int removed = 0;

        var it = worktrees.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            WorktreeInfo info = entry.getValue();
            if (info.createdAt().isBefore(cutoff)) {
                try {
                    runGit(projectRoot, "git", "worktree", "remove", info.path(), "--force");
                    it.remove();
                    removed++;
                } catch (Exception ignored) {
                    // best-effort cleanup
                }
            }
        }
        return removed;
    }

    /**
     * Removes all tracked worktrees (best-effort).
     */
    public synchronized void removeAll() {
        var it = worktrees.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            try {
                runGit(projectRoot, "git", "worktree", "remove", entry.getValue().path(), "--force");
            } catch (Exception ignored) {
                // best-effort
            }
            it.remove();
        }
    }

    /**
     * 检测的是worktree里有没有“已跟踪文件的未暂存改动”。它返回的是给人看/给 UI 展示的 stat 文本，比如：
     *  3 files changed, 12 insertions(+), 2 deletions(-)
     */
    public static String detectChanges(String worktreePath) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("git", "diff", "--stat");
        pb.directory(Path.of(worktreePath).toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();

        String output;
        try (InputStream in = process.getInputStream()) {
            output = new String(in.readAllBytes());
        }

        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("git diff timed out in " + worktreePath);
        }
        if (process.exitValue() != 0) {
            throw new IOException("git diff failed: " + output);
        }
        return output.strip();
    }

    // ---- internal helpers ----

    private static String runGit(String workDir, String... command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(Path.of(workDir).toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();

        String output;
        try (InputStream in = process.getInputStream()) {
            output = new String(in.readAllBytes());
        }

        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("git command timed out: " + String.join(" ", command));
        }
        if (process.exitValue() != 0) {
            throw new IOException(String.join(" ", command) + ": " + output);
        }
        return output;
    }

    /**
     * WorktreeManager自己维护了一份内存Map，但Map只代表当前进程创建过的worktree。真实世界里有三种情况它会失灵：
     * - 别的进程/别的 Agent 也创建了 worktree；
     * - 上次运行残留的 worktree 还挂在 git 里；
     * - 主仓库的 worktree 是手动 git worktree add 出来的。
     * 这些都不在当前进程的 worktrees 内存表里。所以需要问 git 自己“现在到底有哪些 worktree”。git worktree list --porcelain
     * 就是 git 官方提供的稳定、机器可读的答案——它保证输出格式不变、不本地化、没有装饰性字符，专为脚本解析设计。parsePorcelain 就是这段输出的解析器。
     * porcelain示例如下:
     * worktree /path/to/main
     * HEAD a1b2c3...
     * branch refs/heads/main
     * (以空格分隔)
     * worktree /path/to/agent-wt
     * HEAD d4e5f6...
     * branch refs/heads/worktree-abc1234
     * </pre>
     */
    private static List<WorktreeInfo> parsePorcelain(String output) {
        List<WorktreeInfo> result = new ArrayList<>();
        String currentPath = null;
        String currentBranch = null;

        for (String line : output.split("\n")) {
            if (line.startsWith("worktree ")) {
                currentPath = line.substring("worktree ".length()).strip();
            } else if (line.startsWith("branch ")) {
                String ref = line.substring("branch ".length()).strip();
                // refs/heads/my-branch -> my-branch
                if (ref.startsWith("refs/heads/")) {
                    currentBranch = ref.substring("refs/heads/".length());
                } else {
                    currentBranch = ref;
                }
            } else if (line.isBlank()) {
                if (currentPath != null && currentBranch != null) {
                    result.add(new WorktreeInfo(currentPath, currentBranch, Instant.now()));
                }
                currentPath = null;
                currentBranch = null;
            }
        }
        // handle last block (no trailing blank line)
        if (currentPath != null && currentBranch != null) {
            result.add(new WorktreeInfo(currentPath, currentBranch, Instant.now()));
        }
        return result;
    }
}
