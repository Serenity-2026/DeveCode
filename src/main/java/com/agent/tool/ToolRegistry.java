package com.agent.tool;

import com.agent.tool.impl.*;

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
    /**根据工具名查找工具实例，供 Agent Loop 执行工具调用时使用*/
    public Tool getTool(String name) {
        return tools.get(name);
    }
    public static ToolRegistry createDefault() {
        var reg = new ToolRegistry();
        //注册方法,直接在此处添加
        reg.register(new ReadFileTool());
        reg.register(new EditFileTool());
        reg.register(new WriteFileTool());
        reg.register(new GlobTool());
        reg.register(new GrepTool());
        reg.register(new BashTool());
        reg.register(new MathTool());
        return reg;
    }
    //过未发现的延迟工具，根据 protocol 参数适配 Anthropic/OpenAI 两种 API 格式。
    //每个工具自己实现 schema() 方法返回 JSON Schema。
    //shouldDefer() == true的工具默认不会出现在发送给LLM的工具列表里，需要LLM通过搜索（调用ToolSearchTool）主动"发现"后才暴露出来。
    public List<Map<String, Object>> getAllSchemas(String protocol) {
        var schemas = new ArrayList<Map<String, Object>>();
        for (var tool : tools.values()) {
            if (tool.shouldDefer() && !discoveredTools.contains(tool.name()))
                continue;
            var base = tool.schema();
            //对open_ai参数格式做特殊处理以适配
            //OpenAI 标准 Chat Completions API 要求 tools 格式为 {type:"function", function:{name, description, parameters}}
            if ("openai".equals(protocol)) {
                var fn = new LinkedHashMap<String, Object>();
                fn.put("name", base.get("name"));
                fn.put("description", base.get("description"));
                fn.put("parameters", base.get("input_schema"));
                schemas.add(Map.of("type", "function", "function", fn));
            } else {
                schemas.add(base);
            }
        }
        return schemas;
    }

    /**
     * 根据传入的工具列表名及协议查找对应工具
     * @param names tool name
     * @param protocol openai or anthropic
     * @return tool schemas
     */
    public List<Map<String, Object>> findDeferredByNames(List<String> names, String protocol) {
        var nameSet = new HashSet<String>();
        for (var n : names) nameSet.add(n.toLowerCase());

        var matches = new ArrayList<Map<String, Object>>();
        for (var tool : tools.values()) {
            if (nameSet.contains(tool.name().toLowerCase())) {
                var base = tool.schema();
                if (isOpenAIProtocol(protocol)) {
                    //openai格式特殊处理：嵌套在 function 键下
                    var fn = new LinkedHashMap<String, Object>();
                    fn.put("name", base.get("name"));
                    fn.put("description", base.get("description"));
                    fn.put("parameters", base.get("input_schema"));
                    matches.add(Map.of("type", "function", "function", fn));
                } else {
                    matches.add(base);
                }
            }
        }
        return matches;
    }
    private static boolean isOpenAIProtocol(String protocol) {
        return "openai".equals(protocol);
    }

    /**根据关键词搜索
     * - 大小写不敏感
     * - 在工具名和描述中搜索关键词子串
     * - 简单但有效，无需引入搜索引擎
     * @param query keyword
     * @param maxResults 最大返回tool数量
     * @param protocol 协议
     * @return tool schemas
     */
    public List<Map<String, Object>> searchDeferred(String query, int maxResults, String protocol) {
        String lower = query.toLowerCase();
        var matches = new ArrayList<Map<String, Object>>();
        for (var tool : tools.values()) {
            if (!tool.shouldDefer()) continue;
            if (tool.name().toLowerCase().contains(lower)
                    || tool.description().toLowerCase().contains(lower)) {
                var base = tool.schema();
                if (isOpenAIProtocol(protocol)) {
                    var fn = new LinkedHashMap<String, Object>();
                    fn.put("name", base.get("name"));
                    fn.put("description", base.get("description"));
                    fn.put("parameters", base.get("input_schema"));
                    matches.add(Map.of("type", "function", "function", fn));
                } else {
                    matches.add(base);
                }
                if (matches.size() >= maxResults) break;
            }
        }
        return matches;
    }

    /**
     * @return 所有未被发现的延迟工具名,帮助 LLM 知道"还有哪些工具可以搜索"。
     */
    public List<String> getDeferredToolNames() {
        return tools.values().stream()
                .filter(t -> t.shouldDefer() && !discoveredTools.contains(t.name()))
                .map(Tool::name)
                .toList();
    }
    public List<Tool> getDeferredTools() {
        return tools.values().stream()
                .filter(Tool::shouldDefer)
                .toList();
    }
    public void markDiscovered(String name) {
        discoveredTools.add(name);
    }
}
