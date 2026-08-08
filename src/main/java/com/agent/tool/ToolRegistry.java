package com.agent.tool;

import com.agent.tool.impl.ReadFileTool;

import java.util.*;

public class ToolRegistry {
    //返回内容上限10k
    public static final int MAX_OUTPUT_CHARS = 10_000;
    //LinkedHashMap 保证遍历顺序和插入顺序一致。这意味着 getAllSchemas() 返回的工具列表每次都是同样的顺序
    private final Map<String, Tool> tools = new LinkedHashMap<>();
    //配合tool.shouldDefer实现按需加载，避免一次性把所有工具的 schema 都塞给 LLM（会占用 token 且降低模型决策质量）。延迟工具需要 LLM 主动"搜索发现"后才可见，类似于 Claude 的 Task 工具机制——只有需要时才加载相关工具。
    private final Set<String> discoveredTools = new HashSet<>();
    public void register(Tool tool) {
        tools.put(tool.name(), tool);
    }
    public static ToolRegistry createDefault() {
        var reg = new ToolRegistry();
        //注册方法,直接在此处添加
        reg.register(new ReadFileTool());
        return reg;
    }
    //过未发现的延迟工具，根据 protocol 参数适配 Anthropic/OpenAI 两种 API 格式。
    //每个工具自己实现 schema() 方法返回 JSON Schema。
    //shouldDefer() == true的工具默认不会 出现在发送给 LLM 的工具列表里，需要 LLM 通过搜索（调用ToolSearchTool）主动"发现"后才暴露出来。
    public List<Map<String, Object>> getAllSchemas(String protocol) {
        var schemas = new ArrayList<Map<String, Object>>();
        for (var tool : tools.values()) {
            if (tool.shouldDefer() && !discoveredTools.contains(tool.name()))
                continue;
            var base = tool.schema();
            //对open_ai参数格式做特殊处理以适配
            if ("openai".equals(protocol)) {
                schemas.add(Map.of("type", "function", "name", base.get("name"),
                        "parameters", base.get("input_schema")));
            } else {
                schemas.add(base);
            }
        }
        return schemas;
    }

}
