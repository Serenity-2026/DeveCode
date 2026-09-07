package com.agent.skill;

import com.agent.tool.Tool;
import com.agent.tool.ToolCategory;
import com.agent.tool.result.ToolResult;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Skill 激活工具（skill 包对 Agent Loop 的暴露面）。
 *
 * 参照 Claude Code 的 Skill 机制：模型的可见上下文里只有 skill 的
 * name + description 清单（由 Agent.agentLoop 注入 system-reminder），
 * 当模型判断任务匹配某个 skill 时，调用本工具激活——skill 的完整
 * SKILL.md prompt body 作为工具结果返回给模型，模型据此接管任务。
 *
 * 好处：skill 正文不常驻上下文（省 token），只在真正需要时按需加载。
 */
public class SkillTool implements Tool {

    private static final String DESCRIPTION = """
            Activate a skill by name. Skills are named capability modules \
            (reusable prompts) installed in this environment — the available skills \
            and their descriptions are listed in the system context.

            When the user's task matches a skill, activate it BEFORE responding, \
            then follow the instructions it returns.

            The tool result contains the skill's full instructions. Follow them \
            for the remainder of the current task.""";

    private final SkillCatalog catalog;

    public SkillTool(SkillCatalog catalog) {
        this.catalog = catalog;
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
        var opt = catalog.getFull(skillName);
        if (opt.isEmpty()) {
            String available = catalog.list().stream()
                    .map(SkillCatalog.SkillMeta::name)
                    .collect(Collectors.joining(", "));
            return ToolResult.error("Error: skill not found: " + skillName
                    + (available.isEmpty() ? ". No skills installed."
                        : ". Available skills: " + available));
        }
        // getFull 每次从磁盘现读，skill 热更新后无需重启
        String body = SkillExecutor.substituteArguments(opt.get().promptBody(),
                stringArg(args, "args", ""));
        return ToolResult.success(
                "<skill name=\"" + skillName + "\">\n"
                        + body
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
