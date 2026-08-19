package com.agent.agent;

import com.agent.compact.ContextCompactor;
import com.agent.compact.RecoveryState;
import com.agent.history.ConversationManager;
import com.agent.infra.ProviderConfig;
import com.agent.llm.AnthropicCodeClient;
import com.agent.llm.LlmClient;
import com.agent.llm.StreamEvent;
import com.agent.llm.ThinkingBlock;
import com.agent.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

public class Agent {
    private final LlmClient client;
    private final ToolRegistry registry;
    private final String protocol;
    private final int contextWindow;
    private final int maxOutput;
    private Predicate<String> toolNameFilter;
    private final RecoveryState recoveryState = new RecoveryState();


    private int maxIterations=5;
    private String workDir;
    private String lastStreamError;

    private String instructions = "";
    private String memoryContent = "";

    private record ToolCallInfo(String toolId, String toolName, Map<String, Object> args) {}
    private record ToolCallResult(String toolId, String output, boolean isError) {}

    //升级上限,首次遇到max_tokens截断时把客户端的maxOutputTokens一次性抬高到这个值（64K）。
    private static final int MAX_TOKENS_CEILING = 64_000;
    //恢复次数上限,升级之后若仍被截断最多再让模型续写 3 次。
    private static final int MAX_OUTPUT_RECOVERIES = 3;

    public Agent(LlmClient client, ToolRegistry registry, ProviderConfig providerConfig) {
        this.client = client;
        this.registry = registry;
        this.protocol = providerConfig.getProtocol();
        this.maxOutput=providerConfig.getMaxOutputTokens();
        this.contextWindow=providerConfig.getContextWindow();
    }
    public void setMaxIterations(int maxIterations) {
        if(maxIterations>0)this.maxIterations = maxIterations;
    }
    public void setWorkDir(String workDir) { this.workDir = workDir; }
    public BlockingQueue<AgentEvent> run(ConversationManager conv) {
        var queue = new LinkedBlockingQueue<AgentEvent>(64);
        Thread.startVirtualThread(() -> {
            try {
                agentLoop(conv, queue);
            } catch (Exception e) {
                putSafe(queue, new AgentEvent.ErrorEvent(
                        "Agent error: " + e.getMessage()));
            }
        });
        return queue;
    }
    private void agentLoop(ConversationManager conv, BlockingQueue<AgentEvent> queue) {
        conv.injectLongTermMemory(instructions,memoryContent);
        int contextRetries=0;
        int totalInput = 0, totalOutput = 0;
        //升级标识,只提升一次上限,后续走续写路线
        boolean maxTokensEscalated = false;
        int outputRecoveries = 0;
        for (int iteration = 1; ; iteration++) {
            // 1. 检查迭代上限
            if (iteration > maxIterations) {
                putSafe(queue, new AgentEvent.ErrorEvent(
                        "Agent reached maximum iterations (%d)".formatted(maxIterations)));
                break;
            }
            // 2. 检查线程中断,已中断线程退出agent循环
            if (Thread.currentThread().isInterrupted()) break;
            // 3. 消费通知队列
            // 4. 自动上下文压缩
            // 5. 注入延迟工具清单
            var deferredNames = registry.getDeferredToolNames();
            if (!deferredNames.isEmpty()) {
                var sb = new StringBuilder();
                sb.append("The following deferred tools are available via ToolSearch. ");
                sb.append("Their schemas are NOT loaded - use ToolSearch with ");
                sb.append("query \"select:<name>[,<name>...]\" to load tool schemas before calling them:\n");
                for (var dn : deferredNames) {
                    sb.append(dn).append("\n");
                }
                conv.addSystemReminder(sb.toString());
            }
            // 6. 获取工具 schema，调用 LLM
            var iterToolSchemas = registry.getAllSchemas(protocol);
            if (toolNameFilter != null) {
                //保留指定name的工具方法
                iterToolSchemas = iterToolSchemas.stream()
                        .filter(schema -> {
                            Object name = schema.get("name");
                            //没有 "name" 字段（即 name == null），则默认放行
                            return name == null || toolNameFilter.test(name.toString());
                        })
                        .toList();
            }
            var tools=iterToolSchemas;
            var streamQueue = client.stream(conv, tools);
            var text = new StringBuilder();
            var thinkingBlocks = new ArrayList<ThinkingBlock>();
            var toolCalls = new ArrayList<ToolCallInfo>();
            String stopReason = "end_turn";
            int turnInput = 0, turnOutput = 0;
            int turnCacheRead = 0, turnCacheCreation = 0;
            boolean streamError = false;
            // 7. 消费流式响应
            while(true){
                StreamEvent event;
                try {
                    event = streamQueue.poll(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (event == null) {
                    putSafe(queue, new AgentEvent.ErrorEvent("Stream timeout"));
                    return;
                }

                switch (event) {
                    case StreamEvent.TextDelta td -> {
                        text.append(td.text());
                        putSafe(queue, new AgentEvent.StreamText(td.text()));
                    }
                    case StreamEvent.ThinkingDelta td ->
                            putSafe(queue, new AgentEvent.ThinkingText(td.text()));
                    case StreamEvent.ThinkingComplete tc -> {
                        thinkingBlocks.add(new ThinkingBlock(tc.thinking(), tc.signature()));
                        putSafe(queue, new AgentEvent.ThinkingComplete(tc.thinking(), tc.signature()));
                    }
                    case StreamEvent.ToolCallStart tcs ->
                            putSafe(queue, new AgentEvent.ToolUseEvent(tcs.toolId(), tcs.toolName(), Map.of()));
                    //等待参数,无需展示
                    case StreamEvent.ToolCallDelta tcd -> {}
                    case StreamEvent.ToolCallComplete tcc -> {
                        toolCalls.add(new ToolCallInfo(tcc.toolId(), tcc.toolName(), tcc.arguments()));
                        putSafe(queue, new AgentEvent.ToolUseEvent(
                                tcc.toolId(), tcc.toolName(), tcc.arguments()));
                    }
                    case StreamEvent.StreamEnd se -> {
                        stopReason = se.stopReason();
                        turnInput = se.inputTokens();
                        turnOutput = se.outputTokens();
                        turnCacheRead = se.cacheReadTokens();
                        turnCacheCreation = se.cacheCreationTokens();
                    }
                    case StreamEvent.Error err -> {
                        lastStreamError = err.message();
                        putSafe(queue, new AgentEvent.ErrorEvent(err.message()));
                        streamError = true;
                    }
                }
                if (event instanceof StreamEvent.StreamEnd || event instanceof StreamEvent.Error) break;
            }

            // 8. 错误恢复
            if (streamError) {
                if (lastStreamError != null && (lastStreamError.contains("context") || lastStreamError.contains("too long")
                        || lastStreamError.contains("prompt"))) {
                    if (contextRetries < 3) {
                        contextRetries++;
                        ContextCompactor.forceCompact(conv, client, contextWindow,recoveryState,tools);
                        continue; // 重试
                    }
                }
                if (lastStreamError != null && lastStreamError.toLowerCase().contains("rate limit")) {
                    putSafe(queue, new AgentEvent.RetryEvent("Rate limited, waiting 5s...", 5000));
                    try { Thread.sleep(5000); } catch (InterruptedException e) { break; }
                    continue;
                }
                break;
            }
            totalInput += turnInput;
            totalOutput += turnOutput;
            putSafe(queue, new AgentEvent.UsageEvent(totalInput, totalOutput));
            // 9. max_tokens 恢复
            if ("max_tokens".equals(stopReason)) {
                if (!maxTokensEscalated) {
                    maxTokensEscalated = true;
                    client.setMaxOutputTokens(MAX_TOKENS_CEILING);
                    if (!text.isEmpty()) {
                        conv.addAssistantFull(text.toString(), thinkingBlocks, List.of());
                        conv.addUserMessage("Output token limit hit. Resume directly from where you stopped. Do not apologize or repeat previous content. Pick up mid-thought if needed.");
                    }
                    putSafe(queue, new AgentEvent.RetryEvent("max_tokens escalation", 0));
                    continue;
                } else if (outputRecoveries < MAX_OUTPUT_RECOVERIES) {
                    outputRecoveries++;
                    conv.addAssistantFull(text.toString(), thinkingBlocks, List.of());
                    conv.addUserMessage("Output token limit hit. Resume directly from where you stopped. Break remaining work into smaller pieces.");
                    putSafe(queue, new AgentEvent.RetryEvent(
                            "max_tokens recovery %d/%d".formatted(outputRecoveries, MAX_OUTPUT_RECOVERIES), 0));
                    continue;
                }
            } else {
                outputRecoveries = 0;
            }
            // 10. 保存 assistant 消息
            // 11. 没有工具调用 → 结束
            // 12. 执行工具 + 收集结果
            // 13. turn_end 通知
        }

    }
    private static void putSafe(BlockingQueue<AgentEvent> queue, AgentEvent event) {
        try {
            queue.put(event);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
