package com.agent.agent;

import com.agent.compact.ContextCompactor;
import com.agent.compact.RecoveryState;
import com.agent.history.ConversationManager;
import com.agent.skill.SkillCatalog;
import com.agent.skill.SkillHost;
import com.agent.hook.HookEngine;
import com.agent.config.ProviderConfig;
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
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class Agent implements SkillHost {
    private final LlmClient client;
    private final ToolRegistry registry;
    private final String protocol;
    private final int contextWindow;
    private final int maxOutput;
    // 激活中的 inline skill 工具白名单（skill name → allowedTools）。
    // 跨 Agent Loop 持久（skill = 模式语义，激活后一直生效），退出（deactivateSkill）时
    // 移除该 skill 的贡献；实际过滤 = 所有激活 skill 白名单的并集，并集为空 = 不限制。
    private final Map<String, List<String>> activeSkillTools = new ConcurrentHashMap<>();
    private final RecoveryState recoveryState = new RecoveryState();

    private FileHistory fileHistory;

    private int maxIterations=5;
    private String workDir;
    private String lastStreamError;

    private String instructions = "";
    private String memoryContent = "";
    private SkillCatalog skillCatalog;

    private record ToolCallInfo(String toolId, String toolName, Map<String, Object> args) {}
    private record ToolCallResult(String toolId, String output, boolean isError) {}

    //升级上限,首次遇到max_tokens截断时把客户端的maxOutputTokens一次性抬高到这个值（64K）。
    private static final int MAX_TOKENS_CEILING = 64_000;
    //恢复次数上限,升级之后若仍被截断最多再让模型续写 3 次。
    private static final int MAX_OUTPUT_RECOVERIES = 3;

    private PermissionChecker checker;

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    private String sessionId;
    //生成摘要时的熔断器
    private final ContextCompactor.AutoCompactTrackingState compactTracking = new ContextCompactor.AutoCompactTrackingState();
    private ContextCompactor.UsageAnchor usageAnchor;

    public void setReplacementState(ContentReplacementState replacementState) {
        this.replacementState = replacementState;
    }

    private ContentReplacementState replacementState = new ContentReplacementState();

    /**
     * 可选的外部通知源：agentLoop 每轮迭代开始前调用一次，返回的每条文本都作为
     * system-reminder 注入对话。teams 包用它把队友的汇报 / idle 通知喂给 lead。
     * 之所以用回调而不是直接调用，是为了避免 agent 包反向依赖 teams 包（teams 依赖 agent）。
     */
    private Supplier<List<String>> notificationSource;

    public void setNotificationSource(Supplier<List<String>> source) {
        this.notificationSource = source;
    }

    /** 供 fork / 队友共享父级的工具结果裁剪决策（保证 prompt cache 前缀一致） */
    public ContentReplacementState getReplacementState() {
        return replacementState;
    }

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

    /** 手动 /compact 时由 UI 取用：压缩摘要需附带文件/skill 恢复快照 */
    public RecoveryState getRecoveryState() {
        return recoveryState;
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

    // ── SkillHost：inline skill 激活时宿主要提供的能力 ──
    // Agent 即 inline skill 的宿主：SkillTool / 用户命令激活 skill 时回调这里。
    // （inline 正文经 Skill 工具结果自然进入对话，激活无需通知宿主。）

    /** 激活 skill 的白名单贡献（并集语义，同名重复激活覆盖，跨 loop 持久）。 */
    @Override
    public void addSkillTools(String skillName, List<String> allowedTools) {
        if (skillName == null || skillName.isEmpty()) return;
        activeSkillTools.put(skillName, allowedTools == null ? List.of() : allowedTools);
    }

    /** 退出 skill：移除其白名单贡献，返回该 skill 此前是否处于激活态。 */
    public boolean removeSkillTools(String skillName) {
        return skillName != null && activeSkillTools.remove(skillName) != null;
    }

    /** 当前处于激活态的 skill 名（inline 白名单集 ∪ 恢复记录），供命令补全列出可退出的 skill。 */
    public Set<String> getActiveSkillNames() {
        var names = new HashSet<>(activeSkillTools.keySet());
        for (var r : recoveryState.snapshotSkills()) {
            names.add(r.name());
        }
        return names;
    }

    /**
     * 当前生效的工具过滤器：所有激活 skill 白名单的并集。
     * 无激活 skill 或并集为空（全部声明空名单）→ null = 不限制（全量工具）。
     * 每轮迭代取一次快照，中途退出下一轮生效。
     */
    private Predicate<String> currentToolFilter() {
        Set<String> union = new HashSet<>();
        for (var tools : activeSkillTools.values()) {
            union.addAll(tools);
        }
        return union.isEmpty() ? null : union::contains;
    }

    public void setWorkDir(String workDir) { this.workDir = workDir; }

    // ── 依赖注入（UI/宿主组装 Agent 时调用）──
    public void setChecker(PermissionChecker checker) { this.checker = checker; }
    public void setHookEngine(HookEngine hookEngine) { this.hookEngine = hookEngine; }
    public void setSkillCatalog(SkillCatalog skillCatalog) { this.skillCatalog = skillCatalog; }
    public void setInstructions(String instructions) {
        this.instructions = instructions == null ? "" : instructions;
    }
    public void setMemoryContent(String memoryContent) {
        this.memoryContent = memoryContent == null ? "" : memoryContent;
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
        /**
         * 按照以前的写法:在startVirtual中才给agentThread=currentThread,有可能出现startVirtual代码还没执行run已经执行完返回,解锁,
         * 外部调用canceled方法->subAgent.stop()->agentThread为空,no op->消费线程停止但agentLoop还在执行
         * 修改关键:start()之前就发布，stop()永远有目标.现在如果
         */
        agentThread = Thread.ofVirtual().unstarted(() -> {
            try {
                agentLoop(conv, queue);
            }
            catch (Exception e) {
                putSafe(queue, new AgentEvent.ErrorEvent("Agent error: " + e.getMessage()));
            }
        });
        agentThread.start();
        return queue;
    }
    private void agentLoop(ConversationManager conv, BlockingQueue<AgentEvent> queue) {

        int contextRetries = 0;
        int totalInput = 0, totalOutput = 0;
        //升级标识,只提升一次上限,后续走续写路线
        boolean maxTokensEscalated = false;
        int outputRecoveries = 0;
        // Skill 元数据（name + description）注入上下文：模型据此判断任务是否匹配
        // 某个 skill，再通过 Skill 工具激活（完整 prompt body 按需加载，不常驻）。
        // 每次 agentLoop 重读 catalog，skill 安装/热更新后下一条消息即生效。
        conv.injectLongTermMemory(instructions, memoryContent, buildSkillSection());
        try{
        // Hook：单轮对话开始（每个用户请求对应一次 run()）
        fireAgentHooks(HookEngine.EventName.TURN_START,
                null, null, lastUserText(conv), null, conv);
        for (int iteration = 1; ; iteration++) {
            // 1. 检查迭代上限
            if (iteration > maxIterations) {
                putSafe(queue, new AgentEvent.ErrorEvent(
                        "Agent reached maximum iterations (%d)".formatted(maxIterations)));
                break;
            }
            // 2. 检查线程中断,已中断线程退出agent循环
            if (Thread.currentThread().isInterrupted()) break;
            // 3. 注入延迟工具清单
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
            // 3.5 注入外部通知（teams 包的队友消息、后台子 Agent 的完成通知…）
            if (notificationSource != null) {
                try {
                    for (String note : notificationSource.get()) {
                        if (note != null && !note.isBlank()) conv.addSystemReminder(note);
                    }
                } catch (Exception ignored) {
                    // 通知源失败绝不能影响主循环
                }
            }
            // 4. 获取工具 schema，调用 LLM（激活 skill 白名单并集过滤 schema 侧）
            var iterToolSchemas = registry.getAllSchemas(protocol);
            var toolFilter = currentToolFilter();
            if (toolFilter != null) {
                //保留指定name的工具方法
                iterToolSchemas = iterToolSchemas.stream()
                        .filter(schema -> {
                            Object name = schema.get("name");
                            //没有 "name" 字段（即 name == null），则默认放行
                            return name == null || toolFilter.test(name.toString());
                        })
                        .toList();
            }
            // 5. 可选项:添加plan_mode提示词
            if (checker != null && checker.getMode() == PermissionMode.PLAN) {
                String wd = workDir != null ? workDir : System.getProperty("user.dir");
                String planPath = PlanFile.getOrCreatePlanPath(wd);
                checker.setPlanFilePath(planPath);
                boolean planExists = PlanFile.planExists();
                String reminder = PlanModePrompt.buildReminder(planPath, planExists, iteration);
                conv.addSystemReminder(reminder);
            }
            // Layer 1: 裁剪大tool_use
            Path sessionDir = Paths.get(workDir == null ? "." : workDir, ".devecode/session");
            //返回需要新落盘的文件记录,即溢写成功的文件
            List<ContentReplacementRecord> newRecords = ToolResultBudget.apply(conv, sessionDir, replacementState);
            if (!newRecords.isEmpty()) {
                try {
                    ReplacementRecordsIO.append(sessionDir, newRecords);
                } catch (Exception ignored) {}
            }

            // Layer 2: auto-compact check
            // 用Layer 1就地裁剪后的conv消息估算token，判断更精确
            try {
                String wd = workDir != null ? workDir : System.getProperty("user.dir");
                int sizeBefore = conv.size();
                /**
                 * compactTracking:熔断器
                 * usageAnchor:上次API usage的锚点（精确计数）
                 */
                String compactMsg = ContextCompactor.manage(
                        conv, client, contextWindow, maxOutput, wd, sessionId, compactTracking,
                        recoveryState, iterToolSchemas, usageAnchor,
                        conv.getMessages());
                if (compactMsg != null && !compactMsg.isEmpty()) {
                    putSafe(queue, new AgentEvent.CompactEvent(compactMsg));
                }
                // 压缩把旧消息替换成摘要，旧锚点失效，下次stream重新锚定
                if (conv.size() < sizeBefore) {
                    usageAnchor = null;
                    conv.injectLongTermMemory(instructions, memoryContent, buildSkillSection());
                }
            } catch (Exception ignored) {}
            var tools = iterToolSchemas;
            // Hook：每次发送 LLM 请求前（工具循环中多次迭代会多次触发）
            fireAgentHooks(HookEngine.EventName.PRE_SEND,
                    null, null, lastUserText(conv), null, conv);
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

            // Hook：收到一次 LLM 响应后（含出错）
            fireAgentHooks(HookEngine.EventName.POST_RECEIVE,
                    null, null, text.toString(), lastStreamError, null);
            // 7. 错误恢复,在错误现场立即裁剪、压缩，如果让下一轮来判断不会触发压缩、裁剪动作
            if (streamError) {
                if (lastStreamError != null && (lastStreamError.contains("context") || lastStreamError.contains("too long")
                        || lastStreamError.contains("prompt"))) {
                    if (contextRetries < 3) {
                        contextRetries++;
                        putSafe(queue, new AgentEvent.RetryEvent("Context too long, compacting...", 0));
                        // 先裁剪再压缩，确保预算内的结果不会被误压缩
                        Path forceSessionDir = Paths.get(workDir == null ? "." : workDir, ".devecode/session");
                        List<ContentReplacementRecord> forceRecords = ToolResultBudget.apply(conv, forceSessionDir, replacementState);
                        if (!forceRecords.isEmpty()) {
                            try {
                                ReplacementRecordsIO.append(forceSessionDir, forceRecords);
                            } catch (Exception ignored) {}
                        }
                        int sizeBeforeForce = conv.size();
                        try {
                            String wdForce = workDir != null ? workDir : System.getProperty("user.dir");
                           ContextCompactor.forceCompact(
                                    conv, client, contextWindow, wdForce, sessionId,
                                    recoveryState, iterToolSchemas,
                                    conv.getMessages());
                        } catch (Exception ignored) {}
                        //确实发生了压缩,usage重标
                        if (conv.size() < sizeBeforeForce) {
                            usageAnchor = null;
                            conv.injectLongTermMemory(instructions, memoryContent, buildSkillSection());
                        }
                        continue;
                    }
                }
                if (lastStreamError != null && lastStreamError.toLowerCase().contains("rate limit")) {
                    putSafe(queue, new AgentEvent.RetryEvent("Rate limited, waiting 5s...", 5000));
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) { break; }
                    continue;
                }
                break;
            }
            totalInput += turnInput;
            totalOutput += turnOutput;
            putSafe(queue, new AgentEvent.UsageEvent(totalInput, totalOutput));
            // 8. max_tokens 恢复，stopReason=end_turn（正常说完了）、tool_use（要调工具）、max_tokens（输出长度配额用完，被硬掐断）
            if ("max_tokens".equals(stopReason)) {
                //先永久提高output上限
                if (!maxTokensEscalated) {
                    maxTokensEscalated = true;
                    client.setMaxOutputTokens(MAX_TOKENS_CEILING);
                    if (!text.isEmpty()) {
                        //continue回到循环顶部后会发起一次全新的LLM请求，而LLM是无状态的——它不记得自己上一句话说到哪了。所以必须把它被掐断前的半截输出（text + thinkingBlocks）作为assistant消息存进对话
                        //1.被掐时tool call的JSON可能是残缺的；2.“半截输出 + 完整工具调用”的语义是混乱的 故显示清空工具
                        conv.addAssistantFull(text.toString(), thinkingBlocks, List.of());
                        conv.addUserMessage("Output token limit hit. Resume directly from where you stopped. Do not apologize or repeat previous content. Pick up mid-thought if needed.");
                    }
                    putSafe(queue, new AgentEvent.RetryEvent("max_tokens escalation", 0));
                    continue;
                }
                //已升级上限,重试
                else if (outputRecoveries < MAX_OUTPUT_RECOVERIES) {
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
            if (turnInput > 0 || turnOutput > 0 || turnCacheRead > 0 || turnCacheCreation > 0) {
                int baseline = turnInput + turnCacheRead + turnCacheCreation + turnOutput;
                //每轮对话结束,记录真实token数和消息条数
                usageAnchor = new ContextCompactor.UsageAnchor(
                        baseline, conv.size());
            }
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
            // 11. 执行工具 + 收集结果（激活 skill 白名单并集：执行侧硬拦截）
            var executor = new StreamingExecutor(registry, checker, hookEngine, queue, recoveryState, toolFilter);
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
            // Hook：单轮对话结束（正常结束 / 中断 / 异常都会走到这里）
            fireAgentHooks(HookEngine.EventName.TURN_END,
                    null, null, "", null, null);
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

    /**
     * Agent 生命周期 hook 的统一触发点：执行匹配 event 的 hook；PROMPT 型同步结果
     * 在 conv 非空时作为 system-reminder 注入对话（只对 TURN_START / PRE_SEND 生效，
     * 这两个点注入后会在随后的 LLM 请求里真正进入上下文）。
     */
    private void fireAgentHooks(HookEngine.EventName event,
                                Map<String, Object> toolArgs,
                                String filePath,
                                String message,
                                String error,
                                ConversationManager conv) {
        if (hookEngine == null) return;
        try {
            var ctx = new HookEngine.HookContext(event, null, toolArgs, filePath, message, error);
            var results = hookEngine.runHooks(ctx);
            if (conv == null) return;
            for (var r : results) {
                if (r.type() != HookEngine.ActionType.PROMPT) continue;
                if (!r.success() || r.output() == null || r.output().isBlank()) continue;
                conv.addSystemReminder(r.output().strip());
            }
        } catch (Exception ignored) {
            // hook 失败不能影响 Agent 主循环
        }
    }

    /** 取最近一条真实用户消息文本（跳过 system-reminder 与工具结果占位消息）。 */
    private static String lastUserText(ConversationManager conv) {
        var msgs = conv.getMessages();
        for (int i = msgs.size() - 1; i >= 0; i--) {
            var m = msgs.get(i);
            String content = m.getContent();
            if (!"user".equals(m.getRole()) || content == null || content.isBlank()) continue;
            if (content.startsWith("<system-reminder>")) continue;
            return content;
        }
        return "";
    }

    /**
     * 构建 skill 清单 section（参照 Claude Code 的 Skill 机制）：
     * 只注入 name + description 让模型感知可用能力，命中任务时模型调用
     * Skill 工具按需加载完整 prompt body，避免 skill 正文常驻上下文。
     */
    private String buildSkillSection() {
        if (skillCatalog == null) return "";
        var metas = skillCatalog.list();
        if (metas.isEmpty()) return "";
        var sb = new StringBuilder();
        sb.append("# Skills\n")
          .append("Skills are named capability modules available via the Skill tool. ")
          .append("When the user's task matches one of the skills below, activate it with the Skill tool ")
          .append("before proceeding, and follow the instructions it returns.\n\n");
        for (var meta : metas) {
            sb.append("- ").append(meta.name()).append(": ")
              .append(oneLine(meta.description())).append('\n');
        }
        return sb.toString();
    }

    /** 多行描述压成单行（system-reminder 中保持清单格式紧凑）。 */
    private static String oneLine(String s) {
        if (s == null || s.isBlank()) return "(no description)";
        return s.strip().replaceAll("\\s*\\n\\s*", " ");
    }
}
