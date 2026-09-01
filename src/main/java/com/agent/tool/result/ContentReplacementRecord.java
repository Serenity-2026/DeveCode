package com.agent.tool.result;

/**
  决策日志的最小持久化单元
  kind:记录类型标签,目前只有tool_result
  toolUseId:被替换的工具结果 id——主键
  replacement:替换后的完整占位符字符串（<persisted-output>... 全文）。注意存的是替换后的内容，不是原文路径或摘要——重放时直接 state.replacements().put(id, replacement) 即可逐字节复原
 */
public record ContentReplacementRecord(String kind, String toolUseId, String replacement) {

    public static final String KIND_TOOL_RESULT = "tool-result";

    public static ContentReplacementRecord toolResult(String toolUseId, String replacement) {
        return new ContentReplacementRecord(KIND_TOOL_RESULT, toolUseId, replacement);
    }
}