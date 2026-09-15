package com.agent.tool;

import java.nio.file.Path;
import java.util.function.Supplier;

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
 * 路径根是**按 Agent 隔离**的：StreamingExecutor 在调用每个工具前，会用该 Agent 的 workDir
 * 调 {@link #callWith}，所以 lead、各个队友、各个子 Agent 可以同时活在不同的隔离树里，
 * 并发执行工具也不会互相串台。进入/退出 worktree 由 EnterWorktree / ExitWorktree 改变
 * 宿主 Agent 的 workDir 来生效。
 */
public final class PathContext {

    /**
     * 当前线程正在为哪个 Agent 执行工具。null = 没有 Agent 上下文（回退进程启动目录）。
     * 用 ThreadLocal 而不是静态字段，是为了让**每个 Agent 有自己的路径根**：
     * 多个队友各自活在自己的隔离树里时，并发执行工具也不会互相串台。
     */
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private PathContext() {}

    public static String getRoot() {
        String r = CURRENT.get();
        return (r == null || r.isBlank()) ? System.getProperty("user.dir") : r;
    }

    public static boolean isWorktreeActive() {
        return CURRENT.get() != null;
    }

    /** 在以 root 为路径根的上下文里执行 body（可嵌套；结束后恢复上一个值）。 */
    public static <T> T callWith(String root, Supplier<T> body) {
        String prev = CURRENT.get();
        if (root != null && !root.isBlank()) CURRENT.set(root);
        try {
            return body.get();
        } finally {
            if (prev == null) CURRENT.remove(); else CURRENT.set(prev);
        }
    }

    /** 把（可能是相对的）路径解析到当前根下；绝对路径原样归一化返回 */
    public static Path resolve(String rawPath) {
        Path p = Path.of(rawPath);
        return p.isAbsolute() ? p.normalize() : Path.of(getRoot()).resolve(p).normalize();
    }
}
