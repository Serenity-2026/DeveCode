
package com.agent.subAgent;


import com.agent.tool.Tool;
import com.agent.tool.ToolRegistry;

import java.util.HashSet;
import java.util.Set;

/**
 * 工具函数:
 *      对于预定义的子Agent:父 Agent 完整工具表 → 子 Agent 受限工具表,输入父注册表和SubAgentSpec,输出一个新的 ToolRegistry,不修改原表。
 *      对于Agent自己fork的子Agent:克隆新的ToolRegistry,遇到 AgentTool 时浅复制并标记 querySource确保 fork 子 Agent 不能再次 fork（运行时拦截）
 *
 */
public final class ToolFilter {

    /** 子Agent永久禁止工具. */
    private static final Set<String> ALWAYS_DISALLOWED = Set.of(
            "TaskOutput", "ExitPlanMode", "EnterPlanMode",
            "Agent", "AskUserQuestion", "TaskStop", "Workflow",
            // 子 Agent / 队友禁止切换"进程级路径根"：它们各自有独立的隔离树（AgentWorktree），
            // 而 EnterWorktree 会改动全局 PathContext，两者会互相干扰
            "EnterWorktree", "ExitWorktree"
    );

    /** 自定义黑名单预留集合 */
    private static final Set<String> CUSTOM_AGENT_DISALLOWED = Set.of(
            "TaskOutput", "ExitPlanMode", "EnterPlanMode",
             "AskUserQuestion", "TaskStop", "Workflow"
    );

    /** 后台异步执行允许的工具 */
    private static final Set<String> ASYNC_ALLOWED = Set.of(
            "ReadFile", "WebSearch", "TodoWrite", "Grep", "WebFetch", "Glob",
            "Bash", "EditFile", "WriteFile", "NotebookEdit", "Skill", "LoadSkill",
            "SyntheticOutput", "ToolSearch"
    );

    /** in-process teammate 允许使用的工具 */
    private static final Set<String> IN_PROCESS_TEAMMATE_ALLOWED = Set.of(
            "TaskCreate", "TaskGet", "TaskList", "TaskUpdate", "SendMessage",
            "CronCreate", "CronDelete", "CronList"
    );

    private ToolFilter() {}

    /**
     * Convenience overload that delegates to the full method with
     * {@code isAsync=false}, {@code isCustom=false}, {@code isInProcessTeammate=false}.
     */
    public static ToolRegistry filterForAgent(ToolRegistry source, SubAgentSpec spec) {
        return filterForAgent(source, spec, false, false, false);
    }

    /**
     * 返回过滤后的新ToolRegistry
     *
     * @param source              the parent registry to filter from
     * @param spec                the sub-agent specification whose disallowed/allowed tools to honour
     * @param isAsync             if {@code true}, 限制交互类工具
     * @param isCustom            if {@code true}, 预留了自定义Agent的额外限制集合
     * @param isInProcessTeammate if {@code true} 是否是进程内 teammate，team 模式下要额外给协作工具。
     */
    public static ToolRegistry filterForAgent(ToolRegistry source, SubAgentSpec spec,
                                              boolean isAsync, boolean isCustom,
                                              boolean isInProcessTeammate) {
        Set<String> disallowed = new HashSet<>(spec.disallowedTools());
        //null || 空 || [*]表示白名单不限制，否则白名单中的工具才可以被调用
        boolean hasWhitelist = spec.tools() != null && !spec.tools().isEmpty()
                && !(spec.tools().size() == 1 && "*".equals(spec.tools().get(0)));
        Set<String> allowed = hasWhitelist ? new HashSet<>(spec.tools()) : Set.of();

        ToolRegistry filtered = new ToolRegistry();
        for (Tool tool : source.listTools()) {
            String name = tool.name();

            // Layer 1: MCP tools always pass through
            if (isMcpTool(name)) {
                filtered.register(tool);
                continue;
            }

            // Layer 2: Globally blocked tools
            if (ALWAYS_DISALLOWED.contains(name)) {
                continue;
            }

            // Layer 3: Custom-agent specific blocks
            if (isCustom && CUSTOM_AGENT_DISALLOWED.contains(name)) {
                continue;
            }

            // Layer 4: In async mode, only permit the allow-listed tools
            if (isAsync) {
                boolean asyncAllowed = ASYNC_ALLOWED.contains(name);
                if (!asyncAllowed) {
                    // In-process teammate 额外可用工具
                    if (isInProcessTeammate
                            && ("Agent".equals(name) || IN_PROCESS_TEAMMATE_ALLOWED.contains(name))) {
                        // fall through — permitted
                    } else {
                        continue;
                    }
                }
            }

            // Layer 5: Per-spec disallowed tools
            if (disallowed.contains(name)) {
                continue;
            }

            // Layer 6: Per-spec whitelist intersection
            if (hasWhitelist && !allowed.contains(name)) {
                continue;
            }

            filtered.register(tool);
        }
        return filtered;
    }

    /**
     * Fork 专用：复制父注册表的全部工具，不做任何过滤。
     * 遇到 AgentTool 时浅复制并标记 querySource，
     * 确保 fork 子 Agent 不能再次 fork（运行时拦截），
     * 同时保持工具定义与父 Agent 字节一致以命中 prompt cache,tools在HTTP请求的独立字段,只要变化就会prompt cache miss
     */
    public static ToolRegistry cloneForFork(ToolRegistry source) {
        ToolRegistry forked = new ToolRegistry();
        for (Tool tool : source.listTools()) {
            if (tool instanceof AgentTool at) {
                forked.register(at.cloneWithQuerySource("agent:builtin:fork"));
            } else {
                forked.register(tool);
            }
        }
        return forked;
    }

    private static boolean isMcpTool(String name) {
        return name.startsWith("mcp__");
    }
}
