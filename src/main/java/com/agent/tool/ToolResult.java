package com.agent.tool;
//成功路径一律用 ToolResult.success() ，失败路径一律用 ToolResult.error() ，代码的一致性非常好。
public record ToolResult(String output, boolean isError) {

    public static ToolResult success(String output) {
        return new ToolResult(output, false);
    }

    public static ToolResult error(String message) {
        return new ToolResult(message, true);
    }
}
