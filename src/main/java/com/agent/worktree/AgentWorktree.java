package com.agent.worktree;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * 子Agent真正使用的“轻量 API”,它不碰 WorktreeSessionStore 全局会话状态——AgentWorktree 是临时的、一次性的，不需要进全局会话。
 * AgentWorktree 是“子 Agent 专用的一次性 worktree 工具箱”，只负责四件事——建、续用、删、告诉子 Agent 自己在哪。它自己不记任何状态。
 * 为什么必须是这个定位？因为子 Agent worktree 的生命周期和主 Agent 完全不同：
 * - 主 Agent 的 worktree：要持久、要能恢复现场、要进全局 session；
 * - 子 Agent 的 worktree：跑完就完了，可能保留、可能删除，没有“恢复现场”需求。
 * WorktreeManager 是“有状态的管理器”，AgentWorktree 是“无状态的静态工具”。
 */
public final class AgentWorktree {

    private static final Logger log = Logger.getLogger(AgentWorktree.class.getName());

    public record Result(String worktreePath, String worktreeBranch, String headCommit, String gitRoot) {}

    private AgentWorktree() {}

    /**
     * 为子进程创建WorkTree,目的:返回一个干净的隔离工作目录worktreePath告诉我在哪个分支，还要告诉我“现在处于哪个 commit”，这样跑完我能判断有没有产出。
     */
    public static Result create(String slug, String repoRoot, List<String> symlinkDirs) throws Exception {
        SlugValidator.validate(slug);

        Path wtPath = Path.of(repoRoot, ".devecode", "worktrees", SlugValidator.flatten(slug));
        String branch = "worktree-" + SlugValidator.flatten(slug);

        // Fast-resume: check if worktree already exists
        if (Files.isDirectory(wtPath)) {
            // Bump mtime to prevent stale cleanup
            Files.setLastModifiedTime(wtPath, FileTime.from(Instant.now()));
            String head = readHead(wtPath.toString());
            return new Result(wtPath.toString(), branch, head != null ? head : "", repoRoot);
        }

        Files.createDirectories(wtPath.getParent());

        ProcessBuilder pb = new ProcessBuilder("git", "worktree", "add", "-B", branch, wtPath.toString(), "HEAD");
        pb.directory(Path.of(repoRoot).toFile());
        //强制git遇到需要交互就失败而不是等你输入
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        pb.environment().put("GIT_ASKPASS", "");
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String output = new String(proc.getInputStream().readAllBytes());
        boolean finished = proc.waitFor(60, TimeUnit.SECONDS);
        if (!finished || proc.exitValue() != 0) {
            throw new IOException("Failed to create agent worktree: " + output);
        }

        PostCreationSetup.perform(repoRoot, wtPath.toString(), symlinkDirs);

        String head = readHead(wtPath.toString());
        return new Result(wtPath.toString(), branch, head != null ? head : "", repoRoot);
    }

    /**
     * 删除worktree和分支,连分支一起删是因为子 Agent 的 worktree 是一次性资源。如果只删目录不删分支，
     * git 里会积累一堆 worktree-xxxx 孤儿分支，越来越乱。WorktreeManager.remove 故意不删分支是另一套策略（主 Agent 可能想保留提交），
     * 而 AgentWorktree 是“子 Agent 用完即弃”，所以删干净。
     */
    public static boolean remove(String worktreePath, String worktreeBranch, String gitRoot)    {
        if (gitRoot == null || gitRoot.isBlank()) return false;
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "worktree", "remove", "--force", worktreePath);
            pb.directory(Path.of(gitRoot).toFile());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            proc.getInputStream().readAllBytes();
            proc.waitFor(30, TimeUnit.SECONDS);
            if (proc.exitValue() != 0) return false;

            if (worktreeBranch != null && !worktreeBranch.isBlank()) {
                Thread.sleep(100); // wait for git lockfile release
                ProcessBuilder delBranch = new ProcessBuilder("git", "branch", "-D", worktreeBranch);
                delBranch.directory(Path.of(gitRoot).toFile());
                delBranch.redirectErrorStream(true);
                Process branchProc = delBranch.start();
                branchProc.getInputStream().readAllBytes();
                branchProc.waitFor(30, TimeUnit.SECONDS);
            }
            return true;
        } catch (Exception e) {
            log.fine("Failed to remove agent worktree: " + e.getMessage());
            return false;
        }
    }

    /**
     * 子 Agent 是从父 Agent 的上下文里“继承记忆”启动的，buildNotice 是在告诉模型三件事从而让模型知道自己在隔离环境里：
     * 1. 你继承了父上下文，但工作目录已经变了；
     * 2. 旧路径要翻译成新 worktree 的路径；
     * 3. 你在这里的改动不会影响父目录。
     */
    public static String buildNotice(String parentCwd, String worktreeCwd) {
        return "You've inherited the conversation context above from a parent agent working in %s. "
                .formatted(parentCwd)
                + "You are operating in an isolated git worktree at %s — same repository, same relative "
                .formatted(worktreeCwd)
                + "file structure, separate working copy. Paths in the inherited context refer to the "
                + "parent's working directory; translate them to your worktree root. Re-read files before "
                + "editing if the parent may have modified them since they appear in the context. Your "
                + "changes stay in this worktree and will not affect the parent's files.";
    }

    // SHA-1（40 位）或 SHA-256（64 位）十六进制校验
    private static final Pattern SHA_PATTERN = Pattern.compile("^[0-9a-f]{40}([0-9a-f]{24})?$");
    // ref 名称安全字符集：字母、数字、/、.、_、+、-、@
    private static final Pattern SAFE_REF = Pattern.compile("^[a-zA-Z0-9/._+@-]+$");

    /**
     * 纯文件系统 HEAD 读取，不启动 git 子进程。
     * <p>
     * Worktree 的 .git 是一个指向 gitdir 的指针文件（{@code gitdir: <path>}）。
     * 读取该指针定位到实际 git 目录，再解析 HEAD 获取 commit SHA。
     * 在大仓库中可节省 ~15ms 的进程创建开销。
     */
    private static String readHead(String worktreePath) {
        try {
            Path dotGit = Path.of(worktreePath, ".git");
            if (!Files.exists(dotGit)) return null;

            String gitDir;
            if (Files.isDirectory(dotGit)) {
                // 普通仓库：.git 是目录
                gitDir = dotGit.toString();
            } else {
                // Worktree：.git 是指针文件，内容为 "gitdir: <path>"
                String pointer = Files.readString(dotGit).strip();
                if (!pointer.startsWith("gitdir:")) return null;
                String rel = pointer.substring("gitdir:".length()).strip();
                Path resolved = Path.of(rel).isAbsolute()
                        ? Path.of(rel)
                        : Path.of(worktreePath, rel).normalize();
                gitDir = resolved.toString();
            }

            // 读取 HEAD 文件
            Path headFile = Path.of(gitDir, "HEAD");
            if (!Files.exists(headFile)) return null;
            String content = Files.readString(headFile).strip();

            if (content.startsWith("ref:")) {
                // 指向分支：解析 ref 到 SHA
                String ref = content.substring("ref:".length()).strip();
                if (!SAFE_REF.matcher(ref).matches() || ref.contains("..")) return null;
                return resolveRef(gitDir, ref);
            }
            // 分离 HEAD：直接是 SHA
            return SHA_PATTERN.matcher(content).matches() ? content : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析 ref 到 commit SHA：先查松散 ref 文件，再查 packed-refs。
     * 对 worktree 会额外检查 commondir 指向的共享 git 目录。
     */
    private static String resolveRef(String gitDir, String ref) {
        try {
            // 尝试松散 ref 文件
            String sha = resolveRefInDir(gitDir, ref);
            if (sha != null) return sha;

            // Worktree 场景：ref 可能在 commondir 指向的共享目录中
            Path commonFile = Path.of(gitDir, "commondir");
            if (Files.exists(commonFile)) {
                String commonRel = Files.readString(commonFile).strip();
                String commonDir = Path.of(commonRel).isAbsolute()
                        ? commonRel
                        : Path.of(gitDir, commonRel).normalize().toString();
                if (!commonDir.equals(gitDir)) {
                    return resolveRefInDir(commonDir, ref);
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 在单个 git 目录中解析 ref：先查松散文件，再查 packed-refs。
     */
    private static String resolveRefInDir(String dir, String ref) throws IOException {
        // 松散 ref 文件
        Path loosePath = Path.of(dir, ref);
        if (Files.exists(loosePath)) {
            String content = Files.readString(loosePath).strip();
            if (content.startsWith("ref:")) {
                // 符号引用链
                String target = content.substring("ref:".length()).strip();
                if (!SAFE_REF.matcher(target).matches() || target.contains("..")) return null;
                return resolveRef(dir, target);
            }
            return SHA_PATTERN.matcher(content).matches() ? content : null;
        }

        // packed-refs 回退
        Path packed = Path.of(dir, "packed-refs");
        if (!Files.exists(packed)) return null;
        for (String line : Files.readAllLines(packed)) {
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("^")) continue;
            int sp = line.indexOf(' ');
            if (sp == -1) continue;
            if (line.substring(sp + 1).equals(ref)) {
                String sha = line.substring(0, sp);
                return SHA_PATTERN.matcher(sha).matches() ? sha : null;
            }
        }
        return null;
    }
}
