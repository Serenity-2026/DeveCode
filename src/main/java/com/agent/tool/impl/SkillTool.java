package com.agent.tool.impl;

import com.agent.skill.SkillCatalog;
import com.agent.skill.SkillExecutor;
import com.agent.skill.SkillForkHost;
import com.agent.tool.Tool;
import com.agent.tool.ToolCategory;
import com.agent.tool.ToolRegistry;
import com.agent.tool.result.ToolResult;

import java.util.List;
import java.util.Map;

/**
 * Skill 激活工具（skill 包对 Agent Loop 的暴露面）。
 *
 * 参照 Claude Code 的 Skill 机制：模型的可见上下文里只有 skill 的
 * name + description 清单（由 Agent.agentLoop 注入 system-reminder），
 * 当模型判断任务匹配某个 skill 时，调用本工具激活：
 * <ul>
 *   <li>inline 模式（默认）：skill 正文作为工具结果返回给模型，模型据此接管任务；</li>
 *   <li>fork 模式：skill 在隔离子 Agent 中执行（经 SkillForkHost），
 *       只把结果摘要作为工具结果返回，不占用主对话上下文。</li>
 * </ul>
 *
 * 好处：skill 正文不常驻上下文（省 token），只在真正需要时按需加载。
 */
public class SkillTool implements Tool {

    private static final String DESCRIPTION = """
            Activate or deactivate a skill by name. Skills are named capability modules \
            (reusable prompts) installed in this environment — the available skills \
            and their descriptions are listed in the system context.

            When the user's task matches a skill, activate it BEFORE responding, \
            then follow the instructions it returns. Once active, a skill stays in \
            effect (including its tool restrictions) until the task is done or the \
            user asks to stop — then deactivate it.

            The tool result contains either the skill's full instructions (inline mode) \
            or a result summary from an isolated sub-agent (fork mode).""";

    private final SkillCatalog catalog;
    // 主 registry：fail-fast 依赖检查 + fork 过滤 registry 的构建来源
    private final ToolRegistry registry;
    // 宿主：inline 过滤回调 + fork 子 Agent 执行
    private final SkillForkHost host;

    public SkillTool(SkillCatalog catalog, ToolRegistry registry, SkillForkHost host) {
        this.catalog = catalog;
        this.registry = registry;
        this.host = host;
    }

    @Override
    public String name() {
        return "Skill";
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.COMMAND;
    }

    // skill prompt body 必须完整注入（参照 Claude Code：skill 内容不受常规工具输出限制），
    // 超长时由 ToolResultBudget 在上下文溢出时统一裁剪兜底
    @Override
    public int maxOutputChars() {
        return MAX_SKILL_OUTPUT_CHARS;
    }

    private static final int MAX_SKILL_OUTPUT_CHARS = 64_000;

    @Override
    public Map<String, Object> schema() {
        return Map.of(
                "name", name(),
                "description", description(),
                "input_schema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "skill", Map.of(
                                        "type", "string",
                                        "description", "Name of the skill to activate, exactly as listed in the system context."
                                ),
                                "args", Map.of(
                                        "type", "string",
                                        "description",
                                        "Optional task-specific arguments. Substituted into the skill's $ARGUMENTS placeholder."
                                ),
                                "deactivate", Map.of(
                                        "type", "boolean",
                                        "description",
                                        "Set to true to deactivate the skill: stop following its instructions "
                                                + "and release its tool restrictions."
                                )
                        ),
                        "required", List.of("skill")
                )
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String skillName = stringArg(args, "skill", "");
        if (skillName.isEmpty()) {
            return ToolResult.error("Error: skill name is required");
        }
        // 退出分支：不查 catalog（skill 可能已被删除也要能退出），只清激活状态
        if (Boolean.TRUE.equals(args.get("deactivate"))) {
            return ToolResult.success(host.deactivateSkill(skillName));
        }
        var opt = catalog.getFull(skillName);
        if (opt.isEmpty()) {
            String available = catalog.list().stream()
                    .map(SkillCatalog.SkillMeta::name)
                    .collect(java.util.stream.Collectors.joining(", "));
            return ToolResult.error("Error: skill not found: " + skillName
                    + (available.isEmpty() ? ". No skills installed."
                        : ". Available skills: " + available));
        }
        boolean fork = "fork".equals(opt.get().meta().mode());
        String result;
        try {
            // 统一分发：fail-fast 依赖检查 → inline 注入 / fork 隔离执行
            result = SkillExecutor.dispatch(opt.get(), stringArg(args, "args", ""), host, registry);
        } catch (IllegalArgumentException e) {
            // fail-fast：allowedTools 里有主 registry 找不到的工具名，立即报错
            return ToolResult.error("Error: " + e.getMessage());
        }
        if (fork) {
            return ToolResult.success(
                    "<skill name=\"" + skillName + "\" mode=\"fork\">\n"
                            + result
                            + "\n</skill>\n\n"
                            + "Fork skill '" + skillName + "' completed in an isolated sub-agent. "
                            + "The result summary is above; it has not entered the main conversation context."
            );
        }
        return ToolResult.success(
                "<skill name=\"" + skillName + "\">\n"
                        + result
                        + "\n</skill>\n\n"
                        + "Skill '" + skillName + "' is now active. "
                        + "Follow the instructions above for this task."
        );
    }

    private static String stringArg(Map<String, Object> args, String key, String def) {
        var v = args.get(key);
        return v instanceof String s ? s : def;
    }
}
