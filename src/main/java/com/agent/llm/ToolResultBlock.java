package com.agent.llm;

public record ToolResultBlock(String toolUseId, String content,
                              boolean isError) {
}
