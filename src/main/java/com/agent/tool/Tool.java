package com.agent.tool;

import com.agent.tool.result.ToolResult;

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
    //本工具输出的截断上限（字符）。默认全局 10k；需要注入大块内容的工具
    //（如 Skill 激活返回完整 prompt body）可覆盖为更大的值。
    default int maxOutputChars() {
        return ToolRegistry.MAX_OUTPUT_CHARS;
    }
}
