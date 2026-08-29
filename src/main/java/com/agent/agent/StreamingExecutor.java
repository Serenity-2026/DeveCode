package com.agent.agent;

import com.agent.compact.RecoveryState;
import com.agent.hook.HookEngine;
import com.agent.llm.ToolResultBlock;
import com.agent.llm.ToolUseBlock;
import com.agent.permission.PermissionChecker;
import com.agent.permission.PermissionResponse;
import com.agent.tool.Tool;
import com.agent.tool.ToolCategory;
import com.agent.tool.ToolRegistry;
import com.agent.tool.ToolResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

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
        this(registry, checker, hookEngine, eventQueue, null);
    }

    public StreamingExecutor(ToolRegistry registry, PermissionChecker checker,
                             HookEngine hookEngine, BlockingQueue<AgentEvent> eventQueue,
                             RecoveryState recoveryState) {
        this.registry = registry;
        this.checker = checker;
        this.hookEngine = hookEngine;
        this.eventQueue = eventQueue;
        this.recoveryState = recoveryState;
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
        //工具查找
        Tool tool = registry.getTool(call.toolName());
        if (tool == null) {
            putSafe(new AgentEvent.ToolResultEvent(call.toolId(), call.toolName(), "Unknown tool", true, 0));
            return new ToolResultBlock(call.toolId(), "Error: unknown tool '" + call.toolName() + "'", true);
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
        if (output.length() > ToolRegistry.MAX_OUTPUT_CHARS) {
            output = output.substring(0, ToolRegistry.MAX_OUTPUT_CHARS) + "\n... (truncated)";
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
            String content = Files.readString(Path.of(path));
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