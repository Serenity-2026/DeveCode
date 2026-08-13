package com.agent.tool.impl;

import com.agent.tool.Tool;
import com.agent.tool.ToolCategory;
import com.agent.tool.ToolRegistry;
import com.agent.tool.ToolResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
//AI Agent 的 工具发现引擎 ——让 AI 能搜索和加载"延迟加载"的工具。
public class ToolSearchTool implements Tool {
    //- 作用 :把工具 schema 序列化成 JSON 字符串返回给 LLM
    //- INDENT_OUTPUT :美化输出，带缩进，让 LLM 和人类都易读
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private static final String DESCRIPTION = """
            Search for and load additional tools that are not immediately available. \
            Some tools are deferred (not loaded by default) to save context space. \
            Use this tool to discover and load them.

            Query forms:
            - "select:ToolName,AnotherTool" -- fetch exact tools by name
            - "keyword search" -- keyword search, returns up to max_results matches

            When you need a tool that isn't in your current tool list, use this to find it.""";

    private final ToolRegistry registry;
    private final String protocol;

    public ToolSearchTool(ToolRegistry registry) {
        this(registry, "anthropic");
    }

    public ToolSearchTool(ToolRegistry registry, String protocol) {
        this.registry = registry;
        this.protocol = protocol;
    }

    @Override
    public String name() {
        return "ToolSearch";
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.READ;
    }


    @Override
    public Map<String, Object> schema() {
        return Map.of(
                "name", name(),
                "description", description(),
                "input_schema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "query", Map.of(
                                        "type", "string",
                                        "description", "Query to find deferred tools. Use \"select:Name1,Name2\" for direct selection, or keywords to search."
                                ),
                                "max_results", Map.of(
                                        "type", "integer",
                                        "description", "Maximum results to return (default: 5)",
                                        "default", 5
                                )
                        ),
                        "required", List.of("query")
                )
        );
    }

    /**
     *
     * @param args query,支持精确查询和模糊匹配
     * @return tool schemas
     */
    @Override
    public ToolResult execute(Map<String, Object> args) {
        String query = stringArg(args, "query", "");
        if (query.isEmpty()) {
            return ToolResult.error("Error: query is required");
        }

        int maxResults = intArg(args, "max_results", 5);
        if (maxResults < 1) {
            maxResults = 5;
        }
        if (maxResults > 20) {
            maxResults = 20;
        }

        List<Map<String, Object>> schemas;
        //已知工具名,直接获取:query = "select:AskUser,EnterWorktree"
        if (query.startsWith("select:")) {
            List<String> names = Arrays.stream(query.substring("select:".length()).split(","))
                    .map(String::trim)
                    .toList();
            schemas = registry.findDeferredByNames(names, protocol);
        }
        //未知工具名,模糊查询,只要name和description中包含即返回
        else {
            schemas = registry.searchDeferred(query, maxResults, protocol);
        }
        //无结果处理,返回所有延迟工具名
        if (schemas.isEmpty()) {
            List<Tool> deferred = registry.getDeferredTools();
            String nameList = deferred.stream()
                    .map(Tool::name)
                    .collect(Collectors.joining(", "));
            return ToolResult.success(
                    "No matching deferred tools found for query \"" + query
                            + "\". Available deferred tools: " + nameList
            );
        }

        // 把找到的工具加入discoveredTools集合。标记后该工具的schema会在后续请求中自动出现在发给LLM的工具列表里。
        for (var s : schemas) {
            Object nameObj = s.get("name");
            if (nameObj instanceof String n) {
                registry.markDiscovered(n);
            }
        }

        String schemasJson;
        try {
            schemasJson = MAPPER.writeValueAsString(schemas);
        } catch (JsonProcessingException e) {
            return ToolResult.error("Error serializing schemas: " + e.getMessage());
        }

        return ToolResult.success(
                "Found " + schemas.size() + " tool(s). Their full schemas are now loaded and will be available in subsequent requests.\n\n" + schemasJson
        );
    }

    private static String stringArg(Map<String, Object> args, String key, String def) {
        var v = args.get(key);
        return v instanceof String s ? s : def;
    }

    private static int intArg(Map<String, Object> args, String key, int def) {
        var v = args.get(key);
        if (v instanceof Number n) return n.intValue();
        return def;
    }
}
