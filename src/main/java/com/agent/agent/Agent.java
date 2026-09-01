package com.agent.agent;

import com.agent.compact.ContextCompactor;
import com.agent.compact.RecoveryState;
import com.agent.history.ConversationManager;
import com.agent.hook.HookEngine;
import com.agent.infra.ProviderConfig;
import com.agent.llm.*;
import com.agent.permission.PermissionChecker;
import com.agent.permission.PermissionMode;
import com.agent.plan.PlanFile;
import com.agent.prompt.PlanModePrompt;
import com.agent.tool.FileHistory;
import com.agent.tool.ToolRegistry;
import com.agent.tool.result.ContentReplacementRecord;
import com.agent.tool.result.ContentReplacementState;
import com.agent.tool.result.ReplacementRecordsIO;
import com.agent.tool.result.ToolResultBudget;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
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

    private FileHistory fileHistory;

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

    private PermissionChecker checker;

    public void setReplacementState(ContentReplacementState replacementState) {
        this.replacementState = replacementState;
    }

    private ContentReplacementState replacementState = new ContentReplacementState();

    private HookEngine hookEngine;
    //LoopComplete事件是否已发送"的幂等标志——保证正常退出和异常退出两条路径下 LoopComplete 都恰好发一次，让UI不会因为信号缺失而卡死、也不会因为信号重复而错乱。
    boolean loopCompleted = false;

    // 非阻塞 memory recall：prefetch 与主 LLM 调用并行，工具执行后注入
    //memoryRecallFuture是Agent 用于非阻塞记忆检索的占位句柄——TUI在用户发消息时并行启动记忆检索(prefetch),
    // 把这个"将来才有结果"的future 注入 agent，agent 在第一轮工具执行后 非阻塞 地检查它是否就绪，就绪就把检索结果作为system reminder注入对话。
    private CompletableFuture<String> memoryRecallFuture;
    private boolean memoryRecallConsumed;



    public void setMemoryRecallFuture(CompletableFuture<String> future) {
        this.memoryRecallFuture = future;
        this.memoryRecallConsumed = false;
    }

    public FileHistory getFileHistory() {
        return fileHistory;
    }

    public void setFileHistory(FileHistory fileHistory) {
        this.fileHistory = fileHistory;
    }


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

    // ── 依赖注入（UI/宿主组装 Agent 时调用）──
    public void setChecker(PermissionChecker checker) { this.checker = checker; }
    public void setHookEngine(HookEngine hookEngine) { this.hookEngine = hookEngine; }
    public void setInstructions(String instructions) {
        this.instructions = instructions == null ? "" : instructions;
    }
    public void setMemoryContent(String memoryContent) {
        this.memoryContent = memoryContent == null ? "" : memoryContent;
    }
    public void setToolNameFilter(Predicate<String> toolNameFilter) {
        this.toolNameFilter = toolNameFilter;
    }

    // ── 中断支持：UI 按 Esc 时可停止当前 agent 循环 ──
    private volatile Thread agentThread;
    /** 请求中断当前正在运行的 agent 循环（幂等，未运行时无副作用）。 */
    public void stop() {
        Thread t = agentThread;
        if (t != null) t.interrupt();
    }

    public BlockingQueue<AgentEvent> run(ConversationManager conv) {
        var queue = new LinkedBlockingQueue<AgentEvent>(64);
        Thread.startVirtualThread(() -> {
            agentThread = Thread.currentThread();
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

        int contextRetries = 0;
        int totalInput = 0, totalOutput = 0;
        //升级标识,只提升一次上限,后续走续写路线
        boolean maxTokensEscalated = false;
        int outputRecoveries = 0;
        try{
        for (int iteration = 1; ; iteration++) {
            // 1. 检查迭代上限
            if (iteration > maxIterations) {
                putSafe(queue, new AgentEvent.ErrorEvent(
                        "Agent reached maximum iterations (%d)".formatted(maxIterations)));
                break;
            }
            // 2. 检查线程中断,已中断线程退出agent循环
            if (Thread.currentThread().isInterrupted()) break;
            // 3. 每轮注入最新的ltm信息
            conv.injectLongTermMemory(instructions, memoryContent);
            // 4. 注入延迟工具清单
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
            // 5. 获取工具 schema，调用 LLM
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
            // 可选项:添加plan_mode提示词
            if (checker != null && checker.getMode() == PermissionMode.PLAN) {
                String wd = workDir != null ? workDir : System.getProperty("user.dir");
                String planPath = PlanFile.getOrCreatePlanPath(wd);
                checker.setPlanFilePath(planPath);
                boolean planExists = PlanFile.planExists();
                String reminder = PlanModePrompt.buildReminder(planPath, planExists, iteration);
                conv.addSystemReminder(reminder);
            }
            // Layer 1: apply tool-result budget（就地修改 conv，Design A）
            Path sessionDir = Paths.get(workDir == null ? "." : workDir, ".devecode/session");
            //返回需要新落盘的文件记录,即溢写成功的文件
            List<ContentReplacementRecord> newRecords = ToolResultBudget.apply(conv, sessionDir, replacementState);
            if (!newRecords.isEmpty()) {
                try {
                    ReplacementRecordsIO.append(sessionDir, newRecords);
                } catch (Exception ignored) {}
            }

            // Layer 2: auto-compact check
            // 用 Layer 1 就地裁剪后的 conv 消息估算 token，判断更精确
            try {
                String wd = workDir != null ? workDir : System.getProperty("user.dir");
                int sizeBefore = conv.size();
                String compactMsg = ContextCompactor.manage(
                        conv, client, contextWindow, maxOutput, wd, sessionId, compactTracking,
                        recoveryState, iterToolSchemas, usageAnchor,
                        conv.getMessages());
                if (compactMsg != null && !compactMsg.isEmpty()) {
                    putSafe(queue, new AgentEvent.CompactEvent(compactMsg));
                }
                // 压缩把旧消息替换成摘要，旧锚点失效，下次 stream 重新锚定
                if (conv.size() < sizeBefore) {
                    usageAnchor = null;
                    conv.resetLtmInjected();
                    conv.injectLongTermMemory(instructions, memoryContent);
                    // 压缩后 conv 已变，重新应用 tool-result budget
                    newRecords = ToolResultBudget.apply(conv, sessionDir, replacementState);
                }
            } catch (Exception ignored) {}
            var tools = iterToolSchemas;
            var streamQueue = client.stream(conv, tools);
            var text = new StringBuilder();
            var thinkingBlocks = new ArrayList<ThinkingBlock>();
            var toolUseBlocks = new ArrayList<ToolUseBlock>();
            String stopReason = "end_turn";
            int turnInput = 0, turnOutput = 0;
            int turnCacheRead = 0, turnCacheCreation = 0;
            boolean streamError = false;
            // 6. 消费流式响应
            while (true) {
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
                    case StreamEvent.ThinkingDelta td -> putSafe(queue, new AgentEvent.ThinkingText(td.text()));
                    case StreamEvent.ThinkingComplete tc -> {
                        thinkingBlocks.add(new ThinkingBlock(tc.thinking(), tc.signature()));
                        putSafe(queue, new AgentEvent.ThinkingComplete(tc.thinking(), tc.signature()));
                    }
                    case StreamEvent.ToolCallStart tcs ->
                            putSafe(queue, new AgentEvent.ToolUseEvent(tcs.toolId(), tcs.toolName(), Map.of()));
                    //等待参数,无需展示
                    case StreamEvent.ToolCallDelta tcd -> {
                    }
                    case StreamEvent.ToolCallComplete tcc -> {
                        toolUseBlocks.add(new ToolUseBlock(tcc.toolId(), tcc.toolName(), tcc.arguments()));
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

            // 7. 错误恢复
            if (streamError) {
                if (lastStreamError != null && (lastStreamError.contains("context") || lastStreamError.contains("too long")
                        || lastStreamError.contains("prompt"))) {
                    if (contextRetries < 3) {
                        contextRetries++;
                        ContextCompactor.forceCompact(conv, client, contextWindow, recoveryState, tools);
                        continue; // 重试
                    }
                }
                if (lastStreamError != null && lastStreamError.toLowerCase().contains("rate limit")) {
                    putSafe(queue, new AgentEvent.RetryEvent("Rate limited, waiting 5s...", 5000));
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        break;
                    }
                    continue;
                }
                break;
            }
            totalInput += turnInput;
            totalOutput += turnOutput;
            putSafe(queue, new AgentEvent.UsageEvent(totalInput, totalOutput));
            // 8. max_tokens 恢复
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
            // 9. 保存 assistant 消息
            conv.addAssistantFull(text.toString(), thinkingBlocks, toolUseBlocks);
            // 10. 没有工具调用 → 结束
            if (toolUseBlocks.isEmpty()) {
                if (fileHistory != null) {
                    String summary = text.length() > 60 ? text.substring(0, 60) + "..." : text.toString();
                    fileHistory.makeSnapshot(conv.size(), summary);
                }
                putSafe(queue, new AgentEvent.LoopComplete(iteration));
                loopCompleted = true;
                break;
            }
            // 11. 执行工具 + 收集结果
            var executor = new StreamingExecutor(registry, checker, hookEngine, queue, recoveryState);
            var results = executor.executeAll(toolUseBlocks);
            // Add results to conversation
            conv.addToolResultsMessage(results);

            // 非阻塞memory recall:工具执行完后检查prefetch是否就绪
            // 记忆在第1轮工具执行后、第2轮迭代前注入
            if (memoryRecallFuture != null && !memoryRecallConsumed) {
                if (memoryRecallFuture.isDone()) {
                    try {
                        String recall = memoryRecallFuture.getNow("");
                        if (recall != null && !recall.isEmpty()) {
                            conv.addSystemReminder(recall);
                        }
                    } catch (Exception ignored) {
                    }
                    memoryRecallConsumed = true;
                }
            }
        }
    } finally {
            // 12. turn_end 通知，使用loopCompleted
            if (!loopCompleted) {
                putSafe(queue, new AgentEvent.LoopComplete(0));
            }
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
