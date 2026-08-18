package com.agent.llm;

import java.util.Map;

//消息实体类，使用sealed+接口的方式嵌套定义,相比枚举不会有空字段、方便构造、提前发现null问题
//构成一套完整的代数数据类型,接口定义了所有可能的事件种类。
// 编译器知道所有子类型，后面用 switch 做模式匹配时就能检查穷尽性，遗漏了某个分支会直接报警告。
public sealed interface StreamEvent {
    record TextDelta(String text) implements StreamEvent {}
    record ThinkingDelta(String text) implements StreamEvent {}
    record ThinkingComplete(String thinking, String signature) implements StreamEvent {}
    record ToolCallStart(String toolId, String toolName) implements StreamEvent {}
    record ToolCallDelta(String text) implements StreamEvent {}
    record ToolCallComplete(String toolId, String toolName,
                            Map<String, Object> arguments) implements StreamEvent {}
    record StreamEnd(String stopReason, int inputTokens, int outputTokens,int cacheReadTokens, int cacheCreationTokens)
            implements StreamEvent {
        /** Cold-start / non-cache providers: usage carries no cache breakdown. */
        public StreamEnd(String stopReason, int inputTokens, int outputTokens) {
            this(stopReason, inputTokens, outputTokens, 0, 0);
        }
    }
    record Error(String message) implements StreamEvent {}
}
