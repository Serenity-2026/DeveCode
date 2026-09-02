package com.agent.agent;

import com.agent.permission.PermissionResponse;

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
    //todo:在tui中让 Agent 可以向用户发起结构化问卷（多个问题，每个问题有独立回答），不仅仅是「允许/拒绝」的二元选择。这在需要收集多项用户输入的场景下非常有用。
//    record AskUserRequestEvent(
//            List<AskUserDialog.Question> questions,
//            CompletableFuture<Map<String, String>> future
//    ) implements AgentEvent {}
}