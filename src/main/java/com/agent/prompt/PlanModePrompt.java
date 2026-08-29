package com.agent.prompt;

/**
PlanModePrompt 解决“临时状态怎么强约束”。Plan 模式（只读规划模式）是会话中途切入的临时状态，靠 system prompt 根本管不住——这个类用的是完全不同的机制：每轮往对话里注入提醒（reminder）。
 一、设计意图：为什么不用 system prompt？
 Plan 模式有三个特性决定了它不能走 system prompt 路线：
 中途切换：用户随时敲 /plan 进入、批准后退出。而 system prompt 是会话级的一次性设置（热替换每轮重建整个prompt太重）
 指令衰减：LLM 对上下文深处的指令注意力会随对话变长而稀释。一个 90 行的工作流规范放在几十轮对话之前，到第 20 轮基本失效。每轮把提醒追加到对话末尾，永远贴着当前生成点（recency 效应）
 token 经济：plan 模式的完整指令很长，非 plan 模式下完全不需要它。按需注入，不用时零开销
 所以核心思路是：Plan 模式的约束不靠“告诉一次”，靠“每轮重申”。
 */
public final class PlanModePrompt {

    private static final int REMINDER_INTERVAL = 5;
    //完整版plan_mode提示词
    private static final String PLAN_MODE_FULL_REMINDER = """
            Plan mode is active. The user indicated that they do not want you to execute yet -- you \
            MUST NOT make any edits (with the exception of the plan file mentioned below), run any \
            non-readonly tools (including changing configs or making commits), or otherwise make any \
            changes to the system. This supercedes any other instructions you have received.

            ## Plan File Info:
            %s
            You should build your plan incrementally by writing to or editing this file. NOTE that this \
            is the only file you are allowed to edit - other than this you are only allowed to take \
            READ-ONLY actions.

            ## Plan Workflow

            ### Phase 1: Initial Understanding
            Goal: Gain a comprehensive understanding of the user's request by reading through code and \
            asking them questions. Critical: In this phase you should use the Agent tool with \
            subagent_type="explore".

            1. Focus on understanding the user's request and the code associated with their request. \
            Actively search for existing functions, utilities, and patterns that can be reused -- avoid \
            proposing new code when suitable implementations already exist.

            2. **Call the Agent tool with subagent_type="explore" to explore the codebase.** You can \
            launch up to 3 explore agents IN PARALLEL by making multiple Agent tool calls in a single \
            response.
               - Use 1 agent when the task is isolated to known files, the user provided specific file \
            paths, or you're making a small targeted change.
               - Use multiple agents when: the scope is uncertain, multiple areas of the codebase are \
            involved, or you need to understand existing patterns before planning.
               - Quality over quantity - 3 agents maximum, but you should try to use the minimum number \
            of agents necessary (usually just 1)
               - If using multiple agents: Provide each agent with a specific search focus or area to \
            explore. Example: One agent searches for existing implementations, another explores related \
            components, a third investigating testing patterns

            ### Phase 2: Design
            Goal: Design an implementation approach.

            Call the Agent tool with subagent_type="plan" to design the implementation based on the \
            user's intent and your exploration results from Phase 1.

            You can launch up to 1 plan agent.

            **Guidelines:**
            - **Default**: Launch at least 1 Plan agent for most tasks - it helps validate your \
            understanding and consider alternatives
            - **Skip agents**: Only for truly trivial tasks (typo fixes, single-line changes, simple renames)

            In the agent prompt:
            - Provide comprehensive background context from Phase 1 exploration including filenames and \
            code path traces
            - Describe requirements and constraints
            - Request a detailed implementation plan

            ### Phase 3: Review
            Goal: Review the plan(s) from Phase 2 and ensure alignment with the user's intentions.
            1. Read the critical files identified by agents to deepen your understanding
            2. Ensure that the plans align with the user's original request
            3. Use AskUserQuestion to clarify any remaining questions with the user

            ### Phase 4: Final Plan
            Goal: Write your final plan to the plan file (the only file you can edit).
            - Begin with a **Context** section: explain why this change is being made -- the problem or \
            need it addresses, what prompted it, and the intended outcome
            - Include only your recommended approach, not all alternatives
            - Ensure that the plan file is concise enough to scan quickly, but detailed enough to \
            execute effectively
            - Include the paths of critical files to be modified
            - Reference existing functions and utilities you found that should be reused, with their file paths
            - Include a verification section describing how to test the changes end-to-end (run the code, \
            use MCP tools, run tests)

            ### Phase 5: Call ExitPlanMode
            At the very end of your turn, once you have asked the user questions and are happy with your \

            final plan file - you should always call ExitPlanMode to indicate to the user that you are \
            done planning.
            This is critical - your turn should only end with either using the AskUserQuestion tool OR \
            calling ExitPlanMode. Do not stop unless it's for these 2 reasons

            **Important:** Use AskUserQuestion ONLY to clarify requirements or choose between approaches. \
            Use ExitPlanMode to request plan approval. Do NOT ask about plan approval in any other way - \
            no text questions, no AskUserQuestion. Phrases like "Is this plan okay?", "Should I proceed?", \
            "How does this plan look?", "Any changes before we start?", or similar MUST use ExitPlanMode.

            NOTE: At any point in time through this workflow you should feel free to ask the user \
            questions or clarifications using the AskUserQuestion tool. Don't make large assumptions \
            about user intent. The goal is to present a well researched plan to the user, and tie any \
            loose ends before implementation begins.""";
    //简易版plan_mode提示词
    private static final String PLAN_MODE_SPARSE_REMINDER =
            "Plan mode still active (see full instructions earlier in conversation). "
                    + "Read-only except plan file (%s). Follow 5-phase workflow. "
                    + "End turns with AskUserQuestion (for clarifications) or ExitPlanMode "
                    + "(for plan approval). Never ask about plan approval via text or AskUserQuestion.";
    //重新进入Plan Mode提示词
    private static final String PLAN_MODE_REENTRY_REMINDER = """
            ## Re-entering Plan Mode

            You are returning to plan mode after having previously exited it. A plan file exists at %s \
            from your previous planning session.

            **Before proceeding with any new planning, you should:**
            1. Read the existing plan file to understand what was previously planned
            2. Evaluate the user's current request against that plan
            3. Decide how to proceed:
               - **Different task**: If the user's request is for a different task--even if it's similar \
            or related--start fresh by overwriting the existing plan
               - **Same task, continuing**: If this is explicitly a continuation or refinement of the \
            exact same task, modify the existing plan while cleaning up outdated or irrelevant sections
            4. Continue on with the plan process and most importantly you should always edit the plan \
            file one way or the other before calling ExitPlanMode

            Treat this as a fresh planning session. Do not assume the existing plan is relevant without \
            evaluating it first.""";
    //退出plan_mode提示词
    private static final String PLAN_MODE_EXIT_REMINDER =
            "## Exited Plan Mode\n\n"
                    + "You have exited plan mode. You can now make edits, run tools, and take actions.%s";

    private PlanModePrompt() {}

    /**
     * Build the plan-mode reminder injected at each assistant turn.
     *
     * @param planPath  path to the plan file
     * @param planExists whether the plan file already exists on disk
     * @param iteration 1-based turn counter within the plan-mode session
     * @return the reminder string to inject
     * 原则一：初始密集强化（front-loading）。 绝大多数规划会话在 5 轮以内就结束了。开头连续 5 轮全文注入，保证模型在"干活最集中"的阶段对只读约束和工作流记忆最牢固——此时多花 token 是值得的。
     * 原则二：长周期刷新（periodic refresh）。 会话拖长后指令会漂移。SPARSE 版依赖"完整指令还在对话历史里"这个前提，但历史太长时模型的注意力会稀释。所以每 25 轮用一次全文重建记忆锚点——注意刷新频率刻意选得很低（不是每 5 轮），因为太频繁的全文等于没省 token。
     * 原则三：Token 经济学。 其余轮次全部用 3 句话的 SPARSE 版。按 5 轮会话算：5 次 FULL ≈ 3500 词；若 50 轮会话全用 FULL ≈ 35000 词，而这个方案 50 轮只需 7 次 FULL + 43 次 SPARSE，省下约 80%。
     */
    public static String buildReminder(String planPath, boolean planExists, int iteration) {
        String planFileInfo = "Plan file: " + planPath;
        if (planExists) {
            planFileInfo += "\nA plan file already exists at " + planPath
                    + ". You can read it and make incremental edits using the EditFile tool.";
        } else {
            planFileInfo += "\nNo plan file exists yet. You should create your plan at " + planPath
                    + " using the WriteFile tool.";
        }

        if (iteration == 1) {
            return String.format(PLAN_MODE_FULL_REMINDER, planFileInfo);
        }
        //前5轮每轮都发FULL,之后每隔25轮（REMINDER_INTERVAL²）刷新一次FULL。
        int attachmentIndex = (iteration - 1) / REMINDER_INTERVAL;
        if (attachmentIndex % REMINDER_INTERVAL == 0) {
            return String.format(PLAN_MODE_FULL_REMINDER, planFileInfo);
        }

        return String.format(PLAN_MODE_SPARSE_REMINDER, planPath);
    }

    /** 重入plan_mode*/
    public static String buildReentryReminder(String planPath) {
        return String.format(PLAN_MODE_REENTRY_REMINDER, planPath);
    }

    /** 丝滑退出plan_mode*/
    public static String buildExitReminder(String planPath, boolean planExists) {
        String extra = "";
        if (planExists) {
            extra = " The plan file is located at " + planPath + " if you need to reference it.";
        }
        return String.format(PLAN_MODE_EXIT_REMINDER, extra);
    }
}