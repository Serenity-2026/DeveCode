package com.agent.llm;

import java.util.Map;

public record ToolUseBlock(String toolId, String toolName,
                           Map<String, Object> arguments) {
}
