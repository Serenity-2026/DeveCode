package com.agent.tool;

import java.util.Map;

public interface Tool {
    String name();
    String description();
    ToolCategory category();
    Map<String, Object> schema();
    ToolResult execute(Map<String, Object> args);
    //大多数工具不需要覆盖它。只有需要延迟加载的工具才覆盖shouldDefer()返回true，其余工具自动继承默认值false。
    default boolean shouldDefer() {
        return false;
    }
}
