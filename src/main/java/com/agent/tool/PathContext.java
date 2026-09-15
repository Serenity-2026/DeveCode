package com.agent.tool;

import java.nio.file.Path;

/**
 * "相对路径解析到哪个根"的唯一来源。
 *
 * 背景：ReadFile / WriteFile / EditFile / Grep / Glob / Bash 这些工具原来都是
 * {@code Path.of(入参)} 直接解析——相对路径落到 JVM 启动目录（user.dir）。也就是说，
 * 哪怕 Agent 的 workDir 被切到 worktree，工具依然在改原来的仓库；此前的 worktree 隔离
 * 只是靠 prompt 让模型写绝对路径，属于"约定"而不是"机制"。
 *
 * 有了它之后：EnterWorktree 把根切到隔离树，ExitWorktree 切回来，工具侧不用改动语义。
 *
 * 注意这是**进程级单例**语义（与 WorktreeSessionStore 的设计一致）：同一时刻只支持
 * 一个活动的 worktree 会话。子 Agent / 队友的隔离树由 AgentWorktree 单独管理，
 * 它们被禁止调用 EnterWorktree（见 ToolFilter.ALWAYS_DISALLOWED）。
 */
public final class PathContext {

    /** null / 空 = 回退到进程启动目录 */
    private static volatile String root;

    private PathContext() {}

    public static String getRoot() {
        String r = root;
        return (r == null || r.isBlank()) ? System.getProperty("user.dir") : r;
    }

    /** 切换解析根；传 null 或空串表示回退到进程启动目录 */
    public static void setRoot(String newRoot) {
        root = (newRoot == null || newRoot.isBlank()) ? null : newRoot;
    }

    public static boolean isWorktreeActive() {
        return root != null;
    }

    /** 把（可能是相对的）路径解析到当前根下；绝对路径原样归一化返回 */
    public static Path resolve(String rawPath) {
        Path p = Path.of(rawPath);
        return p.isAbsolute() ? p.normalize() : Path.of(getRoot()).resolve(p).normalize();
    }
}
