package com.agent.agent;

import com.agent.hook.HookEngine;
import com.agent.permission.PermissionChecker;
import com.agent.skill.SkillCatalog;
import com.agent.tool.FileHistory;

/**
 * 一个 Agent 的"外部依赖套装"。
 *
 * 为什么需要它：lead（主 Agent）的依赖是在 TerminalUI 里逐个 setXxx 注入的，
 * 而队友（teams 包）以及将来其它派生出来的 Agent 都需要同一套依赖。如果每个派生点
 * 都手写一遍 setChecker / setHookEngine / setFileHistory / ... 很容易漏——
 * 尤其是漏掉 checker 时，Agent 的工具调用会**完全跳过权限裁决**。
 * 把"要注入什么"收敛成一个不可变记录后，派生点只负责传递这一份。
 *
 * 注意：instructions / memoryContent 在 agentLoop 每轮都会重新注入对话，
 * 所以这里存的是"组装时的快照"，取值与 lead 保持一致即可。
 */
public record AgentDeps(
        PermissionChecker checker,
        HookEngine hookEngine,
        FileHistory fileHistory,
        String instructions,
        String memoryContent,
        SkillCatalog skillCatalog,
        int maxIterations
) {

    /** 把这份依赖套到一个（通常是新建的）Agent 上；null 字段跳过，不覆盖调用方已有的设置。 */
    public void applyTo(Agent agent) {
        if (agent == null) return;
        if (checker != null) agent.setChecker(checker);
        if (hookEngine != null) agent.setHookEngine(hookEngine);
        if (fileHistory != null) agent.setFileHistory(fileHistory);
        if (instructions != null) agent.setInstructions(instructions);
        if (memoryContent != null) agent.setMemoryContent(memoryContent);
        if (skillCatalog != null) agent.setSkillCatalog(skillCatalog);
        if (maxIterations > 0) agent.setMaxIterations(maxIterations);
    }
}
