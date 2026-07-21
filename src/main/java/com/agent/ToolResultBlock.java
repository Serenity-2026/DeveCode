package com.agent;

public record ToolResultBlock(String toolUseId, String content,
                              boolean isError) {
}
