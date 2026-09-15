package com.agent.tool.impl;

import com.agent.tool.PathContext;
import com.agent.tool.Tool;
import com.agent.tool.ToolCategory;
import com.agent.tool.result.ToolResult;
import com.agent.worktree.SlugValidator;
import com.agent.worktree.WorktreeManager;
import com.agent.worktree.WorktreeSession;
import com.agent.worktree.WorktreeSessionStore;

import java.io.InputStream;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 进入隔离工作树：创建一棵 git worktree，并把 {@link PathContext} 的相对路径根切到它。
 *
 * 用途：要动一批文件、又不想污染主工作区时，先 EnterWorktree，干完再 ExitWorktree
 * （keep = 保留改动与分支；remove = 删掉目录，若有未提交改动会要求显式确认）。
 *
 * 一次只能进一个：已经在 worktree 会话里再调会被拒绝（WorktreeSessionStore 是进程级单例）。
 */
public class EnterWorktreeTool implements Tool {

    private final WorktreeManager manager;
    private final String projectRoot;
    private final Consumer<String> onRootChanged;

    public EnterWorktreeTool(WorktreeManager manager, String projectRoot, Consumer<String> onRootChanged) {
        this.manager = manager;
        this.projectRoot = projectRoot;
        this.onRootChanged = onRootChanged;
    }

    @Override public String name() { return "EnterWorktree"; }
    @Override public ToolCategory category() { return ToolCategory.COMMAND; }

    @Override
    public String description() {
        return "Create and switch into an isolated git worktree. While inside, relative paths "
                + "(ReadFile / WriteFile / EditFile / Grep / Glob / Bash) resolve against the worktree instead of "
                + "the main working directory, so experiments and large edits do not touch the user's working tree.\n"
                + "Only one worktree session can be active at a time: call ExitWorktree before entering another.\n"
                + "Keep the tree (action=keep) when the changes are worth reviewing; remove it (action=remove) otherwise.";
    }

    @Override
    public Map<String, Object> schema() {
        var props = new LinkedHashMap<String, Object>();
        props.put("slug", Map.of("type", "string",
                "description", "Worktree name / branch, letters-digits-dot-underscore-dash only (optional)"));
        return Map.of("name", name(), "description", description(),
                "input_schema", Map.of("type", "object", "properties", props, "required", List.of()));
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        //先看是否已经在一颗worktree下,如果是需要先退出
        if (WorktreeSessionStore.getCurrentSession() != null) {
            WorktreeSession cur = WorktreeSessionStore.getCurrentSession();
            return ToolResult.error("Error: already in a worktree ('" + cur.worktreeName()
                    + "' at " + cur.worktreePath() + "). Call ExitWorktree first.");
        }

        String slug = args.get("slug") instanceof String s && !s.isBlank() ? s.trim() : randomSlug();
        try {
            SlugValidator.validate(slug);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("Error: " + e.getMessage());
        }

        String originalCwd = PathContext.getRoot();
        String originalBranch = git(originalCwd, "rev-parse", "--abbrev-ref", "HEAD");
        String originalHead = git(originalCwd, "rev-parse", "HEAD");

        long t0 = System.nanoTime();
        WorktreeManager.WorktreeInfo info;
        try {
            info = manager.create(slug, null);
        } catch (Exception e) {
            return ToolResult.error("Error creating worktree: " + e.getMessage());
        }
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        WorktreeSession session = new WorktreeSession(
                originalCwd, info.path(), slug, info.branch(),
                originalBranch, originalHead, null, elapsedMs);

        // 先记会话（内存 + 磁盘），再切路径根：即使切根时抛异常，会话也是完整的
        WorktreeSessionStore.restoreSession(session);
        try {
            WorktreeSessionStore.save(originalCwd, session);
        } catch (Exception ignored) {
            // 落盘失败不影响本次会话，只是重启后无法恢复现场
        }
        PathContext.setRoot(info.path());
        if (onRootChanged != null) onRootChanged.accept(info.path());

        String msg = ("Entered worktree '%s'\n  path:   %s\n  branch: %s\n  base:   %s (%s)\n"
                + "Relative paths now resolve inside the worktree. "
                + "Call ExitWorktree (action=keep|remove) when done.")
                .formatted(slug, info.path(), info.branch(),
                        originalBranch == null ? "HEAD" : originalBranch,
                        originalHead == null ? "unknown" : originalHead);
        return ToolResult.success(msg);
    }

    private static String randomSlug() {
        byte[] rnd = new byte[4];
        new SecureRandom().nextBytes(rnd);
        return "wt-" + HexFormat.of().formatHex(rnd);
    }

    /** 跑一条 git 命令并返回 stdout（失败返回 null）。ExitWorktreeTool 复用。 */
    static String git(String cwd, String... args) {
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
            String out;
            try (InputStream in = proc.getInputStream()) {
                out = new String(in.readAllBytes());
            }
            if (!proc.waitFor(30, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                return null;
            }
            return proc.exitValue() == 0 ? out.strip() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
