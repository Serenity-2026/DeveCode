package com.agent.tool.impl;

import com.agent.tool.Tool;
import com.agent.tool.ToolCategory;
import com.agent.tool.result.ToolResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AskUserQuestion 元工具（schema 载体）。
 *
 * 该工具让模型可以向用户发起结构化问卷（多问题、每问题可带选项或自由文本）。
 * 工具本身只提供 name/description/schema：真实调用由 {@code StreamingExecutor}
 * 按工具名拦截并路由到 UI（经 {@code AgentEvent.AskUserRequestEvent} 呈现问卷），
 * 因此 execute() 不会被执行——保留兜底错误，防止绕过路由直接调用时静默无果。
 *
 * 注册为延迟工具：prompt 指引模型先经 ToolSearch "select:AskUserQuestion" 加载。
 */
public class AskUserQuestionTool implements Tool {

    public static final String NAME = "AskUserQuestion";

    private static final int MAX_QUESTIONS = 4;
    private static final int MAX_OPTIONS = 6;

    private static final String DESCRIPTION = """
            Ask the user structured questions and collect their answers, one at a time. \
            Use this when you need input that affects what you do next — clarifying ambiguous \
            requirements, choosing between approaches, or confirming important decisions. \
            Each question may offer 1-6 predefined answer options (press the matching number), \
            or no options for a free-text answer.

            Usage guidance:
            - Ask at most 4 questions in one call; each question must be concise and self-contained.
            - Prefer options over free text when the choices are enumerable; reserve free-text \
            questions for input that cannot be guessed (paths, names, preferences, extra context).
            - Do not ask for plan approval here; in plan mode use ExitPlanMode for approval.""" ;

    public AskUserQuestionTool() {
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    /** COMMAND：串行批次执行，避免多个问卷同时进入 UI 互相抢占。 */
    @Override
    public ToolCategory category() {
        return ToolCategory.COMMAND;
    }

    /** 延迟加载：初始不出现在工具列表，模型经 ToolSearch 主动发现。 */
    @Override
    public boolean shouldDefer() {
        return true;
    }

    /**
     * {
     *   "questions": [
     *     {
     *       "question": "你出生于哪一年？",
     *       "header": "出生年份",
     *       "options": [
     *         { "label": "1990年代", "description": "1990-1999" },
     *         { "label": "自由输入" }
     *       ]
     *     },
     *     {
     *       "question": "简单描述一下你的喜好",
     *       "header": "喜好"
     *     }
     *   ]
     * }
     */
    @Override
    public Map<String, Object> schema() {
        // 用 LinkedHashMap 逐步构建，避免深层 Map.of 嵌套可读性差、易括号错位
        var schema = new LinkedHashMap<String, Object>();
        schema.put("name", name());
        schema.put("description", description());

        // input_schema.properties.questions.items.properties
        var label = new LinkedHashMap<String, Object>();
        label.put("type", "string");
        label.put("description", "The answer text the user sees and the model receives.");

        var optionDesc = new LinkedHashMap<String, Object>();
        optionDesc.put("type", "string");
        optionDesc.put("description", "Optional short clarification shown under the label.");

        var optionProps = new LinkedHashMap<String, Object>();
        optionProps.put("label", label);
        optionProps.put("description", optionDesc);

        var optionItem = new LinkedHashMap<String, Object>();
        optionItem.put("type", "object");
        optionItem.put("properties", optionProps);
        optionItem.put("required", List.of("label"));

        var options = new LinkedHashMap<String, Object>();
        options.put("type", "array");
        options.put("description",
                "Optional answer choices (1-%d); omit for free text.".formatted(MAX_OPTIONS));
        options.put("maxItems", MAX_OPTIONS);
        options.put("items", optionItem);

        var question = new LinkedHashMap<String, Object>();
        question.put("type", "string");
        question.put("description", "The question text, concise and self-contained.");

        var header = new LinkedHashMap<String, Object>();
        header.put("type", "string");
        header.put("description", "Optional short label shown above the question.");

        var itemProps = new LinkedHashMap<String, Object>();
        itemProps.put("question", question);
        itemProps.put("header", header);
        itemProps.put("options", options);

        var questionItem = new LinkedHashMap<String, Object>();
        questionItem.put("type", "object");
        questionItem.put("properties", itemProps);
        questionItem.put("required", List.of("question"));

        var questions = new LinkedHashMap<String, Object>();
        questions.put("type", "array");
        questions.put("description",
                ("1-%d questions to ask. Each item: {question, header?, options?}; "
                        + "options omitted means free-text answer.").formatted(MAX_QUESTIONS));
        questions.put("minItems", 1);
        questions.put("maxItems", MAX_QUESTIONS);
        questions.put("items", questionItem);

        var properties = new LinkedHashMap<String, Object>();
        properties.put("questions", questions);

        var inputSchema = new LinkedHashMap<String, Object>();
        inputSchema.put("type", "object");
        inputSchema.put("properties", properties);
        inputSchema.put("required", List.of("questions"));

        schema.put("input_schema", inputSchema);
        return schema;
    }

    /**
     * 兜底：真实调用被 StreamingExecutor 拦截路由（工具调用期间用户与 UI 交互），
     * 走到这里说明路由缺失，返回明确错误而非静默成功。
     */
    @Override
    public ToolResult execute(Map<String, Object> args) {
        return ToolResult.error(
                "Error: AskUserQuestion must be routed through the agent executor to the UI. "
                        + "The tool cannot be executed directly.");
    }
}
