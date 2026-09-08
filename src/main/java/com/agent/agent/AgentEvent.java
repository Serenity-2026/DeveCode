package com.agent.agent;

import com.agent.permission.PermissionResponse;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public sealed interface AgentEvent {
    //模型正在输出的文字增量,携带一小段文本
    record StreamText(String text) implements AgentEvent {}
    //模型请求调用工具,携带工具名、工具输入、请求 ID
    record ToolUseEvent(String toolId, String toolName,
                        Map<String, Object> args) implements AgentEvent {}
    //工具执行完成,携带执行结果、是否出错、耗时
    record ToolResultEvent(String toolId, String toolName, String output,
                           boolean isError, double elapsed) implements AgentEvent {}
    //一轮 LLM 调用完,携带当前轮次序号
    record TurnComplete(int turn) implements AgentEvent {}
    //整个循环结束,总轮次
    record LoopComplete(int totalTurns) implements AgentEvent {}
    //Token 用量更新,携带累计输入/输出 token 数
    record UsageEvent(int inputTokens, int outputTokens) implements AgentEvent {}
    //发生错误,携带错误信息
    record ErrorEvent(String message) implements AgentEvent {}
    //已触发压缩事件
    record CompactEvent(String message) implements AgentEvent {}
    record RetryEvent(String reason, long waitMs) implements AgentEvent {}
    record ThinkingText(String text) implements AgentEvent {}

    record ThinkingComplete(String thinking, String signature) implements AgentEvent {}

    //权限请求,超时后默认拒绝
    record PermissionRequestEvent(String toolName, String description,
                                  CompletableFuture<PermissionResponse> future) implements AgentEvent {}

    /**
     * 结构化提问：Agent 经 AskUserQuestion 工具向用户发起问卷（多个问题，每个问题独立作答），
     * 不仅仅是「允许/拒绝」的二元选择。StreamingExecutor 构造本事件并阻塞等待；
     * TUI 以全屏对话框逐题收集答案，完成后通过 future 返回「题目序号(1-based) → 答案」映射，
     * 用户取消时以空 Map 完成。
     */
    record AskUserRequestEvent(
            List<Question> questions,
            CompletableFuture<Map<String, String>> future
    ) implements AgentEvent {

        /** 单个问题：header 为短标签；options 为空表示自由文本作答。 */
        public record Question(String question, String header, List<Option> options) {}

        /** 选项：label 为答案文本（必填），description 为补充说明（可空）。 */
        public record Option(String label, String description) {}
    }
}
