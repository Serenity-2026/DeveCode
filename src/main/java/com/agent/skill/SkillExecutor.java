
package com.agent.skill;



import java.util.ArrayList;
import java.util.List;

import com.agent.llm.Message;
import com.agent.skill.SkillCatalog;
import com.agent.tool.ToolRegistry;
/**
 * Executes skills in inline or fork mode.
 */
public final class SkillExecutor {

    private static final int FORK_RECENT_COUNT = 5;

    private SkillExecutor() {}

    /**
     * 统一分发入口（模型经 SkillTool 调用、用户经 /skillname 命令调用都走这里）：
     * <ol>
     *   <li>fail-fast 依赖检查：allowedTools 里每个名字必须能在主 registry 找到，
     *       任何一个找不到立刻抛错，不等 Agent 跑起来再失败；</li>
     *   <li>fork 模式：构建过滤后的工具注册中心（空名单 = 主 registry 全量，
     *       向后兼容），交给隔离子 Agent Loop 用，只返回结果摘要；</li>
     *   <li>inline 模式（默认）：正文注入当前对话，走正常 Agent Loop。</li>
     * </ol>
     *
     * @return 注入主对话的文本（inline = skill 正文；fork = 子 Agent 结果摘要）
     * @throws IllegalArgumentException fail-fast 依赖检查未通过
     */
    public static String dispatch(SkillCatalog.Skill skill, String args,
                                   SkillForkHost host, ToolRegistry mainRegistry) {
        List<String> missing = missingTools(skill.meta().allowedTools(), mainRegistry);
        //查找模型有没有使用不存在的工具
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "skill '%s' allowedTools references unknown tools: %s (fail-fast check)"
                            .formatted(skill.meta().name(), String.join(", ", missing)));
        }
        //fork模式执行
        if ("fork".equals(skill.meta().mode())) {
            // 空名单 = 不限制直接用全部工具（向后兼容 + 信任环境）
            ToolRegistry tools = skill.meta().allowedTools().isEmpty()
                    ? mainRegistry
                    : mainRegistry.filteredSubset(skill.meta().allowedTools());
            return executeFork(skill, args, host, tools);
        }
        return executeInline(skill, args, host);
    }

    /**
     * Fail-fast 依赖检查：返回 allowedTools 中在主 registry 里找不到的工具名
     * （空列表 = 全部通过）。在 Skill 执行的最开头调用，配置写错立即暴露。
     */
    public static List<String> missingTools(List<String> allowedTools, ToolRegistry main) {
        if (allowedTools == null || allowedTools.isEmpty()) {
            return List.of();
        }
        return allowedTools.stream()
                .filter(name -> main.getTool(name) == null)
                .toList();
    }

    /**
     * 以inline模式激活 skill——正文注入当前对话，返回渲染后的 prompt。
     * 白名单贡献并入宿主激活集（并集、跨 loop 持久，退出时经 deactivateSkill 移除）；
     * 空 allowedTools 也登记（无贡献、不限制），保证生命周期统一。
     */
    public static String executeInline(SkillCatalog.Skill skill, String args, SkillHost host) {
        String body = substituteArguments(skill.promptBody(), args);
        host.addSkillTools(skill.meta().name(), skill.meta().allowedTools());
        host.recordSkillInvocation(skill.meta().name(), body);
        return body;
    }

    /**
     * Executes the skill in an isolated sub-agent and returns the
     * final assistant text.
     * tools 为该子 Agent 专用的（可能已过滤的）工具注册中心。
     */
    public static String executeFork(SkillCatalog.Skill skill, String args,
                                     SkillForkHost host, ToolRegistry tools) {
        String body = substituteArguments(skill.promptBody(), args);
        host.recordSkillInvocation(skill.meta().name(), skill.promptBody());
        List<Message> seed = buildForkSeed(skill.meta().forkContext(), host.snapshotParentMessages());
        return host.runSubAgent(skill.meta().name(), body, seed, skill.meta().model(), tools);
    }

    /**
     * 将skill body中的$ARGUMENTS替换为args
     * @param body
     * @param args
     * @return
     */
    public static String substituteArguments(String body, String args) {
        if (args == null || args.isBlank()) {
            return body;
        }
        if (body.contains("$ARGUMENTS")) {
            return body.replace("$ARGUMENTS", args);
        }
        return body + "\n\n## User Request\n\n" + args;
    }

    /**
     * 根据mode返回子列表
     * @param mode full - 全量复制,recent - 复制FORK_RECENT_COUNT条
     * @param parent 父messages
     * @return submessage
     */
    static List<Message> buildForkSeed(String mode, List<Message> parent) {
        if (parent == null || parent.isEmpty()) {
            return List.of();
        }
        return switch (mode != null ? mode : "none") {
            case "full" -> new ArrayList<>(parent);
            case "recent" -> {
                if (parent.size() <= FORK_RECENT_COUNT) {
                    yield new ArrayList<>(parent);
                }
                yield new ArrayList<>(parent.subList(parent.size() - FORK_RECENT_COUNT, parent.size()));
            }
            default -> List.of();
        };
    }

}
