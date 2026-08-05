package com.agent.tool;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class ToolRegistry {
    //返回内容上限10k
    public static final int MAX_OUTPUT_CHARS = 10_000;
    //LinkedHashMap 保证遍历顺序和插入顺序一致。这意味着 getAllSchemas() 返回的工具列表每次都是同样的顺序
    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final Set<String> discoveredTools = new HashSet<>();


}
