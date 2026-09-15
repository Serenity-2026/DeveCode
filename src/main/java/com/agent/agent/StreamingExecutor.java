package com.agent.agent;

import com.agent.compact.RecoveryState;
import com.agent.hook.HookEngine;
import com.agent.llm.ToolResultBlock;
import com.agent.llm.ToolUseBlock;
import com.agent.permission.PermissionChecker;
import com.agent.permission.PermissionResponse;
import com.agent.tool.impl.AskUserQuestionTool;
import com.agent.tool.PathContext;
import com.agent.tool.Tool;
import com.agent.tool.ToolCategory;
import com.agent.tool.ToolRegistry;
import com.agent.tool.result.ToolResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Predicate;

/**
 并发工具执行器，将工具调用分为只读（并行）和写入/命令（顺序）批处理。
 当 LLM 一次性返回多个工具调用时，把"相邻的只读工具"合成一个并行批次用虚拟线程同时跑，
 而写/命令工具各自独占一批串行执行——既享受并发加速，又避免对文件系统的并发写冲突
 */
public class StreamingExecutor {

    private final ToolRegistry registry;
    //权限检查器
    //守门员，多层规则（Plan模式 → 安全命令 → 危险命令 → 沙箱保护路径 → 路径沙箱 → YAML 规则 → 会话规则 → 沙箱模式 → 模式矩阵）逐层裁决。
    private final PermissionChecker checker;
    //Hook引擎
    //观察者+拦截器。在生命周期的9个事件点触发用户配置的命令/提示/HTTP/子Agent动作,支持条件匹配、once去重、async、reject拦截。
    private final HookEngine hookEngine;
    //与UI/主循环通信的事件队列，所有ToolResult/PermissionRequest都从这里流出
    private final BlockingQueue<AgentEvent> eventQueue;
    private final RecoveryState recoveryState;
    //工具名过滤器（激活 skill 白名单的并集）：null = 不过滤。
    //schema 侧由 Agent 在每轮迭代过滤，这里做执行侧硬拦截——
    //即使模型幻觉调用被过滤的工具名，也不会真正执行。
    private final java.util.function.Predicate<String> toolFilter;
    /** 这个 Agent 的路径根（一般是它的 workDir）：工具里的相对路径都相对它解析 */
    private final String pathRoot;
    /**
     *- concurrent=true：该批可并行执行（只读工具集合）
     *- concurrent=false：该批必须串行执行（写/命令工具）
     *- calls：该批包含的工具调用列表（可变 List，因为要追加）
     */
    private record ToolBatch(boolean concurrent, List<ToolUseBlock> calls) {}
//    public record ToolCallInfo(String toolId, String toolName, Map<String, Object> args) {}
//    public record ToolExecResult(String toolId, String output, boolean isError) {}

    public StreamingExecutor(ToolRegistry registry, PermissionChecker checker,
                             HookEngine hookEngine, BlockingQueue<AgentEvent> eventQueue) {
        this(registry, checker, hookEngine, eventQueue, null, null, null);
    }

    public StreamingExecutor(ToolRegistry registry, PermissionChecker checker,
                             HookEngine hookEngine, BlockingQueue<AgentEvent> eventQueue,
                             RecoveryState recoveryState) {
        this(registry, checker, hookEngine, eventQueue, recoveryState, null, null);
    }

    public StreamingExecutor(ToolRegistry registry, PermissionChecker checker,
                             HookEngine hookEngine, BlockingQueue<AgentEvent> eventQueue,
                             RecoveryState recoveryState,
                             Predicate<String> toolFilter,
                             String pathRoot) {
        this.registry = registry;
        this.checker = checker;
        this.hookEngine = hookEngine;
        this.eventQueue = eventQueue;
        this.recoveryState = recoveryState;
        this.toolFilter = toolFilter;
        this.pathRoot = pathRoot;
    }

    /**
     * 接收LLM一次性返回的所有工具调用,返回所有结果
     * @param calls ToolUseBlock
     * @return
     */
    public List<ToolResultBlock> executeAll(List<ToolUseBlock> calls) {
        // 按相邻性分批：连续的只读工具合成一个并行批次，写/命令工具各自独占一批
        var batches = partitionToolCalls(calls);
        var results = new ArrayList<ToolResultBlock>();

        for (var batch : batches) {
            //只读工具通常是IO密集（读文件/Grep/Glob），虚拟线程代价极低，可大批量并发可并行直接用虚拟线程
            if (batch.concurrent && batch.calls.size() > 1) {
                try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                    var futures = batch.calls.stream()
                            .map(call -> executor.submit(() -> executeSingle(call)))
                            .toList();
                    for (int i = 0; i < futures.size(); i++) {
                        ToolUseBlock call = batch.calls.get(i);
                        try {
                            results.add(futures.get(i).get());
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            results.add(skippedResult(call, "Interrupted while executing"));
                        } catch (ExecutionException e) {
                            Throwable cause = e.getCause() != null ? e.getCause() : e;
                            results.add(failedResult(call, "Tool execution failed: " + cause.getMessage()));
                        }
                    }
                }
            } else {
                boolean batchFailed = false;
                for (var call : batch.calls) {
                    if (batchFailed) {
                        // 串行批次：一个失败则跳过当前批次剩余工具，但必须补上
                        // tool_result —— Anthropic 协议要求每个 tool_use 都有配对的
                        // tool_result，缺失会导致 API 400。
                        results.add(skippedResult(call, "Skipped: earlier tool in batch failed"));
                        continue;
                    }
                    try {
                        results.add(executeSingle(call));
                    } catch (Exception e) {
                        results.add(failedResult(call, "Tool execution failed: " + e.getMessage()));
                        batchFailed = true;
                    }
                }
            }
        }

        return results;
    }

    /**
     * 把LLM一次性返回的多个工具调用，按"相邻只读"原则切成若干批次（ToolBatch），让 executeAll决定哪些可以并行、哪些必须串行。
     * @param calls toolcall
     * @return
     */
    private List<ToolBatch> partitionToolCalls(List<ToolUseBlock> calls) {
        var batches = new ArrayList<ToolBatch>();
        for (var call : calls) {
            var tool = registry.getTool(call.toolName());
            //READ分类的Tool线程安全
            boolean safe = tool != null && tool.category() == ToolCategory.READ;
            /*
            * 合并条件（三个都必须满足）：
              1.safe ：当前是只读工具
              2.!batches.isEmpty() ：已经有批次存在
              3.batches.getLast().concurrent()：上一批 也是可并行的（即上一批是只读批次）
                满足 → 把当前 call 追加到上一批 的 calls 列表里（合并批次）。
                不满足 → 新开一批，concurrent=safe，calls初始化为只含当前call的可变列表。
            * */
            if (safe && !batches.isEmpty() && batches.getLast().concurrent()) {
                batches.getLast().calls().add(call);
            } else {
                batches.add(new ToolBatch(safe, new ArrayList<>(List.of(call))));
            }
        }
        return batches;
    }

    private ToolResultBlock executeSingle(ToolUseBlock call) {
        // 每个工具调用都在"本 Agent 的路径根"上下文里执行：队友各自活在自己的隔离树时，
        // 相对路径不会串台；并行批次用的是新线程，所以根必须在调用内部设置而不是在外面
        return PathContext.callWith(pathRoot, () -> executeSingleInRoot(call));
    }

    private ToolResultBlock executeSingleInRoot(ToolUseBlock call) {
        //工具查找
        Tool tool = registry.getTool(call.toolName());
        if (tool == null) {
            putSafe(new AgentEvent.ToolResultEvent(call.toolId(), call.toolName(), "Unknown tool", true, 0));
            return new ToolResultBlock(call.toolId(), "Error: unknown tool '" + call.toolName() + "'", true);
        }
        // 执行侧硬拦截：inline skill 的 allowedTools 白名单（schema 侧已过滤，
        // 这里兜住模型幻觉调用的白名单外工具名）
        if (toolFilter != null && !toolFilter.test(call.toolName())) {
            String msg = "Tool '" + call.toolName()
                    + "' is not allowed by the active skill's allowed-tools list";
            putSafe(new AgentEvent.ToolResultEvent(call.toolId(), call.toolName(), msg, true, 0));
            return new ToolResultBlock(call.toolId(), "Error: " + msg, true);
        }
        // AskUserQuestion 元工具：不经过权限检查/hook（交互对象是用户而非文件系统），
        // 直接发结构化问卷事件给 UI 并阻塞等待答案，语义与下方权限询问一致。
        if (AskUserQuestionTool.NAME.equals(call.toolName())) {
            return executeAskUserQuestions(call);
        }
        // 权限检查优先于hook：先拦截无权操作，再让 hook 介入
        if (checker != null) {
            var check = checker.check(tool, call.arguments());
            switch (check.decision()) {
                case DENY -> {
                    String msg = "Permission denied: " + check.reason();
                    putSafe(new AgentEvent.ToolResultEvent(call.toolId(), call.toolName(), msg, true, 0));
                    return new ToolResultBlock(call.toolId(), msg, true);
                }
                case ASK -> {
                    var future = new CompletableFuture<PermissionResponse>();
                    String desc = checker.describeToolAction(call.toolName(), call.arguments());
                    putSafe(new AgentEvent.PermissionRequestEvent(call.toolName(), desc, future));
                    PermissionResponse response;


                    try {
                        response = future.get(5, TimeUnit.MINUTES);
                    } catch (Exception e) {
                        response = PermissionResponse.DENY;
                    }
                    if (response == PermissionResponse.DENY) {
                        putSafe(new AgentEvent.ToolResultEvent(
                                call.toolId(), call.toolName(), "Permission denied by user", true, 0));
                        return new ToolResultBlock(call.toolId(), "User denied permission", true);
                    }
                    if (response == PermissionResponse.ALLOW_ALWAYS) {
                        String content = extractContent(call.toolName(), call.arguments());
                        if (content != null) {
                            checker.addAllowAlwaysRule(call.toolName(), content);
                        }
                    }
                }
                case ALLOW -> {}
            }
        }

        // Pre-tool hook 在权限通过后执行，可拦截特定工具调用
        if (hookEngine != null) {
            var hookResult = hookEngine.runPreToolHooks(call.toolName(), call.arguments());
            if (hookResult.rejected()) {
                String msg = "Rejected by hook: " + hookResult.message();
                putSafe(new AgentEvent.ToolResultEvent(call.toolId(), call.toolName(), msg, true, 0));
                return new ToolResultBlock(call.toolId(), msg, true);
            }
        }

        long start = System.nanoTime();
        ToolResult result;
        try {
            result = tool.execute(call.arguments());
        } catch (Exception e) {
            result = ToolResult.error("Tool execution error: " + e.getMessage());
        }
        double elapsed = (System.nanoTime() - start) / 1_000_000_000.0;

        snapshotForRecovery(call, result);

        String output = result.output();
        int outputCap = tool.maxOutputChars();
        if (output.length() > outputCap) {
            output = output.substring(0, outputCap) + "\n... (truncated)";
        }

        putSafe(new AgentEvent.ToolResultEvent(call.toolId(), call.toolName(), output, result.isError(), elapsed));

        // Post-tool hooks
        if (hookEngine != null) {
            var ctx = new HookEngine.HookContext(
                    HookEngine.EventName.POST_TOOL_USE, call.toolName(), call.arguments(), null, null, null);
            hookEngine.runHooks(ctx);
        }

        return new ToolResultBlock(call.toolId(), output, result.isError());
    }

    /**
     * AskUserQuestion 工具的真实执行路径：
     * 解析 arguments 中的 questions → 发 AskUserRequestEvent 给 UI（全屏问卷）→
     * 阻塞等待 future（最长 5 分钟）→ 把用户答案格式化成工具结果返回给模型。
     * 用户取消/超时按“拒绝回答”处理，提示模型改用假设推进，而不是原样重问。
     */
    private ToolResultBlock executeAskUserQuestions(ToolUseBlock call) {
        var questions = parseAskQuestions(call.arguments());
        if (questions.isEmpty()) {
            return failedResult(call,
                    "AskUserQuestion requires a non-empty 'questions' array (each item needs a 'question' string).");
        }
        if (questions.size() > 4) {
            return failedResult(call,
                    "AskUserQuestion supports at most 4 questions per call, got " + questions.size() + ".");
        }
        for (int i = 0; i < questions.size(); i++) {
            if (questions.get(i).options().size() > 6) {
                return failedResult(call,
                        "Question " + (i + 1) + " has " + questions.get(i).options().size()
                                + " options; at most 6 are supported.");
            }
        }
        //UI的consumeAgentEvents()正在这个队列上take()阻塞，拿到事件后调用handleAskUserRequest()，弹出全屏问卷，用户作答后答案会放入future中,future.complete()
        var future = new CompletableFuture<Map<String, String>>();
        putSafe(new AgentEvent.AskUserRequestEvent(questions, future));
        Map<String, String> answers;
        try {
            answers = future.get(5, TimeUnit.MINUTES);
        } catch (Exception e) {
            answers = Map.of();
        }
        //超时用户没有作答
        if (answers == null || answers.isEmpty()) {
            String msg = "User declined to answer the questions (dialog cancelled or unanswered). "
                    + "Do not ask the same questions again; proceed with explicit reasonable assumptions "
                    + "or a different approach.";
            //立刻通知前端,并由ToolResultBlock向模型返回结果
            putSafe(new AgentEvent.ToolResultEvent(
                    call.toolId(), call.toolName(), msg, true, 0));
            return new ToolResultBlock(call.toolId(), "Error: " + msg, true);
        }
        String output = formatAskAnswers(questions, answers);
        putSafe(new AgentEvent.ToolResultEvent(
                call.toolId(), call.toolName(), output, false, 0));
        return new ToolResultBlock(call.toolId(), output, false);
    }

    /** 把 AskUserQuestion 的 arguments（questions 数组）解析为结构化 Question 列表。 */
    private static List<AgentEvent.AskUserRequestEvent.Question> parseAskQuestions(Map<String, Object> args) {
        var questions = new ArrayList<AgentEvent.AskUserRequestEvent.Question>();
        if (args == null) return questions;
        if (!(args.get("questions") instanceof List<?> rawList)) return questions;
        for (Object item : rawList) {
            if (!(item instanceof Map<?, ?> qm)) continue;
            String question = asString(qm.get("question"));
            if (question == null || question.isBlank()) continue;
            String header = asString(qm.get("header"));
            var options = new ArrayList<AgentEvent.AskUserRequestEvent.Option>();
            if (qm.get("options") instanceof List<?> rawOptions) {
                for (Object o : rawOptions) {
                    if (!(o instanceof Map<?, ?> om)) continue;
                    String label = asString(om.get("label"));
                    if (label == null || label.isBlank()) continue;
                    options.add(new AgentEvent.AskUserRequestEvent.Option(label, asString(om.get("description"))));
                }
            }
            questions.add(new AgentEvent.AskUserRequestEvent.Question(
                    question, header, List.copyOf(options)));
        }
        return questions;
    }

    /** 把答案映射格式化为模型易读的 Q&A 文本（答案键 = 1-based 题号）。 */
    private static String formatAskAnswers(
            List<AgentEvent.AskUserRequestEvent.Question> questions,
            Map<String, String> answers) {
        var sb = new StringBuilder("User answers to your questions:\n");
        for (int i = 0; i < questions.size(); i++) {
            sb.append(i + 1).append(". ").append(questions.get(i).question()).append('\n')
              .append("   Answer: ")
              .append(answers.getOrDefault(String.valueOf(i + 1), "(no answer)"))
              .append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString().trim();
    }

    private void putSafe(AgentEvent event) {
        try {
            eventQueue.put(event);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 构造"执行失败"结果并同步通知 UI。 */
    private ToolResultBlock failedResult(ToolUseBlock call, String msg) {
        putSafe(new AgentEvent.ToolResultEvent(call.toolId(), call.toolName(), msg, true, 0));
        return new ToolResultBlock(call.toolId(), msg, true);
    }

    /** 构造"被跳过"结果（批次中前序工具失败/中断）并同步通知 UI。 */
    private ToolResultBlock skippedResult(ToolUseBlock call, String msg) {
        return failedResult(call, msg);
    }

    /**
     * 每当ReadFile工具执行完,把读到的文件内容送进RecoveryState存档。
     */
    private void snapshotForRecovery(ToolUseBlock call, ToolResult result) {
        if (recoveryState == null || result.isError()) return;
        //只对ReadFile工具生效
        if (!"ReadFile".equals(call.toolName())) return;
        Object pathObj = call.arguments() == null ? null : call.arguments().get("file_path");
        if (!(pathObj instanceof String) || ((String) pathObj).isEmpty()) return;
        String path = (String) pathObj;
        try {
            String content = Files.readString(PathContext.resolve(path));
            recoveryState.recordFileRead(path, content);
        } catch (IOException ignored) {
            // Best-effort snapshot; if the file vanished between the tool
            // call and now, just skip — the model has the tool output it
            // already saw.
        }
    }

    private static String extractContent(String toolName, Map<String, Object> args) {
        String field = switch (toolName) {
            case "Bash" -> "command";
            case "ReadFile", "WriteFile", "EditFile" -> "file_path";
            case "Glob", "Grep" -> "pattern";
            default -> null;
        };
        if (field == null) return null;
        var v = args.get(field);
        return v instanceof String s ? s : null;
    }
}
