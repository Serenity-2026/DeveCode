package com.agent;

import java.util.Map;

//消息实体类，使用sealed+接口的方式嵌套定义,相比枚举不会有空字段、方便构造、提前发现null问题
public sealed interface StreamEvent {
    record TextDelta(String text) implements StreamEvent {}
    record ThinkingDelta(String text) implements StreamEvent {}
    record ThinkingComplete(String thinking, String signature) implements StreamEvent {}
    record ToolCallStart(String toolId, String toolName) implements StreamEvent {}
    record ToolCallDelta(String text) implements StreamEvent {}
    record ToolCallComplete(String toolId, String toolName,
                            Map<String, Object> arguments) implements StreamEvent {}
    record StreamEnd(String stopReason, int inputTokens, int outputTokens)
            implements StreamEvent {}
    record Error(String message) implements StreamEvent {}
}
