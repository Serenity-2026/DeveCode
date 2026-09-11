
package com.agent.subAgent;

import java.util.List;

/**
 * 子Agent的定义模版
 */
public record SubAgentSpec(
        String name,
        //展示给父Agent的说明，帮助父Agent决定用哪个子Agent
        String description,
        //消息白名单,空或 ["*"] 表示不限制；
        List<String> tools,
        //消息黑名单,优先级高于白名单
        List<String> disallowedTools,
        //子Agent对话开头注入的 system-reminder
        String systemPromptOverride,
        //最大迭代轮数，0表示运行时按200处理
        int maxTurns,
        //模型选择,null/inherit 表示继承父模型。
        String model
) {

    private static final String PLAN_AGENT_SYSTEM_PROMPT = """
            You are a software architect and planning specialist.

            === CRITICAL: READ-ONLY MODE - NO FILE MODIFICATIONS ===
            You are STRICTLY PROHIBITED from creating, modifying, or deleting any files.
            Your role is EXCLUSIVELY to explore code and design implementation plans.

            ## Your Process

            1. **Understand Requirements**: Analyze the user's request carefully.

            2. **Explore Thoroughly**:
               - Read files with ReadFile to understand current architecture
               - Use Grep to find patterns, function definitions, and references
               - Use Glob to discover file structure
               - Use Bash ONLY for read-only operations (ls, find, grep, cat, head, tail)
               - NEVER use Bash for: mkdir, touch, rm, cp, mv, git add/commit, npm install

            3. **Design Solution**:
               - Create a concrete implementation approach
               - Consider trade-offs and explain your reasoning
               - Follow existing patterns in the codebase

            4. **Detail the Plan**:
               - Provide step-by-step implementation strategy
               - Identify file dependencies and sequencing
               - Anticipate potential challenges

            ## Required Output
            End your response with:

            ### Critical Files for Implementation
            List the most critical files for implementing this change:
            - path/to/file1 -- reason
            - path/to/file2 -- reason""";
    //全工具默认子Agent
    public static final SubAgentSpec GENERAL_PURPOSE = new SubAgentSpec(
            "general-purpose",
            "General-purpose agent for research and multi-step tasks",
            List.of(),
            List.of(),
            null,
            200,
            null
    );
    // 禁止写入的PLAN Agent
    public static final SubAgentSpec PLAN = new SubAgentSpec(
            "plan",
            "Software architect for designing implementation plans. Returns step-by-step plans, "
                    + "identifies critical files, and considers architectural trade-offs.",
            List.of(),
            List.of("EditFile", "WriteFile"),
            PLAN_AGENT_SYSTEM_PROMPT,
            15,
            null
    );

    // 禁止写入的EXPLORE Agent
    public static final SubAgentSpec EXPLORE = new SubAgentSpec(
            "explore",
            "Fast read-only search agent for locating code",
            List.of(),
            List.of("EditFile", "WriteFile"),
            null,
            0,
            null
    );
}
