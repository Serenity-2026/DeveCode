package com.agent.llm;

public record ToolResultBlock(String toolId, String content,
                              boolean isError) {
}
