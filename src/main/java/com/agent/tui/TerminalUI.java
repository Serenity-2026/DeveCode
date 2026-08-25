package com.agent.tui;

import com.agent.history.ConversationManager;
import com.agent.infra.ProviderConfig;
import com.agent.llm.LlmClient;
import com.agent.llm.StreamEvent;
import com.agent.llm.ThinkingBlock;
import com.agent.llm.ToolUseBlock;
import com.agent.llm.ToolResultBlock;
import com.agent.tool.Tool;
import com.agent.tool.ToolRegistry;
import com.agent.tool.ToolResult;
import com.agent.tool.FileHistory;
import com.agent.tool.FileStateCache;
import com.agent.tool.impl.ToolSearchTool;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import com.sun.management.OperatingSystemMXBean;

/**
 * 全功能终端 UI，承载输入、流式输出、多轮对话与状态展示。
 *
 * <h2>架构</h2>
 * <pre>
 *   ┌─────────────┐         ┌──────────────────┐
 *   │ inputLoop   │──事件──→│ 主线程 (run)     │
 *   │ (虚拟线程)  │  队列   │ ├─ handleEvent   │
 *   │ 读取原始字节│         │ ├─ submitMessage │──→ LlmClient.stream()
 *   └─────────────┘         │ ├─ render()      │←── StreamEvent 队列
 *                           │ └─ 状态面板      │
 *                           └──────────────────┘
 * </pre>
 *
 * <h2>外部依赖</h2>
 * <ul>
 *   <li>{@link ProviderConfig} — provider 配置（名称、协议、模型、API Key、上下文窗口大小）</li>
 *   <li>{@link LlmClient} — LLM 流式客户端，调用 {@code stream()} 返回 StreamEvent 队列</li>
 *   <li>{@link ConversationManager} — 对话历史管理，维护发给 LLM 的消息列表</li>
 *   <li>{@link StreamEvent} — 流式事件密封接口（ThinkingDelta/TextDelta/StreamEnd/Error）</li>
 *   <li>{@link MarkdownRenderer} — 将 Markdown 转为带 ANSI 颜色的终端字符串</li>
 * </ul>
 *
 * <h2>线程模型</h2>
 * <ul>
 *   <li>主线程：事件消费 + 渲染 + 状态机（16ms 节流 ≈ 60fps）</li>
 *   <li>输入线程（虚拟）：阻塞读取终端字节，控制字符直接处理，可打印字符走队列</li>
 *   <li>流式线程（虚拟）：每次 submitMessage 创建，消费 StreamEvent 队列</li>
 * </ul>
 */
public class TerminalUI {

    private static final String APP_NAME    = "DeveCode";
    private static final String APP_VERSION = "v1.0.0";

    // ── 右侧状态面板 ──
    private static final int PANEL_WIDTH = 36;
    private static final int PANEL_MIN_COLS = 100;  // 终端宽度 >= 此值才显示面板

    // ── 终端 ──
    private final Terminal terminal;
    private final PrintWriter writer;

    // ── 应用状态（外部依赖）──
    private final ProviderConfig provider;       // 当前选中的 provider 配置
    private final LlmClient client;              // LLM 流式客户端
    private final ConversationManager conversation; // 对话历史管理器
    private final ToolRegistry toolRegistry;     // 工具注册中心
    private final FileHistory fileHistory;       // 文件编辑历史（备份/快照/回退）
    private final FileStateCache fileStateCache; // 先读后改强制缓存

    // ── 消息记录 ──
    private final List<UIMessage> messages = new ArrayList<>();
    private volatile int scrollOffset = 0;

   // ── 输入状态 ──
   private final StringBuilder inputBuffer = new StringBuilder();
   private int cursorCol = 0;
   private int cursorRow = 0;  // 多行光标行号（相对于输入第一行）
   private final List<String> inputHistory = new ArrayList<>();

    // ── 流式状态 ──
    private volatile boolean streaming = false;
    private final StringBuilder streamAccum = new StringBuilder();
    private final StringBuilder thinkingAccum = new StringBuilder();
    private volatile boolean firstTokenReceived = false;
    private long streamStartMs;
    private long firstTokenMs;

    // ── 控制 ──
    private volatile boolean running = true;
    private volatile boolean needsRedraw = true;
    private volatile boolean terminalResized = false;
    private volatile boolean panelVisible = true;  // 右侧状态面板开关 (Ctrl+P 切换)

    // ── 系统监控 ──
    private final OperatingSystemMXBean osBean =
            ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);

    // ── 终端尺寸（每次渲染前刷新）──
    private int termWidth = 80;
    private int termHeight = 24;

    // ── 事件队列（输入线程 → 主线程）──
    private final BlockingQueue<UIEvent> eventQueue = new LinkedBlockingQueue<>();

    // ── ANSI ──
    private static final String ESC = "\033";
    private static final String CLEAR   = ESC + "[2J";
    private static final String HOME    = ESC + "[H";
    private static final String CURSOR_HIDE = ESC + "[?25l";
    private static final String CURSOR_SHOW = ESC + "[?25h";
    private static final String RESET   = ESC + "[0m";
    private static final String BOLD    = ESC + "[1m";
    private static final String DIM     = ESC + "[2m";
    private static final String ITALIC  = ESC + "[3m";
    private static final String RED     = ESC + "[31m";
    private static final String GREEN   = ESC + "[32m";
    private static final String YELLOW  = ESC + "[33m";
    private static final String CYAN    = ESC + "[36m";
    private static final String GRAY    = ESC + "[90m";
    private static final String WHITE   = ESC + "[97m";
    private static final String REVERSE = ESC + "[7m";

    // ── 入口 ──

    /**
     * 启动终端 UI。由 {@link DeveCodeApp} 在 provider 选择完成后调用。
     *
     * @param provider 用户选中的 provider 配置（含 API Key、模型名、协议等）
     */
    public static void launch(ProviderConfig provider) {
        try {
            new TerminalUI(provider).run();
        } catch (IOException e) {
            System.err.println("Failed to initialize terminal: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * 构造函数：初始化所有外部依赖和终端连接。
     *
     * 步骤：
     *   1. 保存 provider 配置
     *   2. 创建 JLine Terminal（JNA 模式 + 忽略默认信号处理）
     *   3. 注册 SIGINT 处理器（Ctrl+C → 推入 Exit 事件）
     *   4. 创建 LlmClient（传入 provider 配置 + 系统提示词）
     *   5. 创建 ConversationManager（空对话历史）
     *
     * @param provider 用户选中的 provider 配置
     */
    private TerminalUI(ProviderConfig provider) throws IOException {
        this.provider = provider;
        // 步骤 2：JLine Terminal — JNA 提供原生终端控制，SIG_IGN 防止 Ctrl+C 直接杀进程
        this.terminal = TerminalBuilder.builder()
                .jna(true)
                .system(true)
                .signalHandler(Terminal.SignalHandler.SIG_IGN)
                .build();
        // 步骤 3：SIGINT 处理器 — Ctrl+C 时设置 running=false 并推入 Exit 事件
        this.terminal.handle(Terminal.Signal.INT, s -> {
            running = false;
            eventQueue.add(new UIEvent.Exit());
        });
        this.writer = terminal.writer();
        // 步骤 4：LlmClient.create — 根据 provider 的 protocol（anthropic/openai）创建对应客户端
        this.client = LlmClient.create(provider,
                "You are a helpful coding assistant. Respond concisely.");
        // 步骤 5：ConversationManager — 管理对话历史，每次 addUserMessage/addAssistantMessage 会追加到内部列表
        this.conversation = new ConversationManager();
        // 步骤 6：初始化工具基础设施
        // FileStateCache — 记录 ReadFile 读取过的文件内容和 mtime，EditFile/WriteFile 据此强制"先读后改"
        this.fileStateCache = new FileStateCache();
        // FileHistory — 文件编辑备份/快照管理，每次 AI 轮次结束时打快照，支持回退
        String sessionId = java.util.UUID.randomUUID().toString().substring(0, 8);
        this.fileHistory = new FileHistory(System.getProperty("user.dir"), sessionId);
        // ToolRegistry — 用 createDefault() 创建所有工具，再通过 getTool() 注入依赖
        this.toolRegistry = ToolRegistry.createDefault();
        // 向需要文件依赖的工具注入 FileHistory / FileStateCache
        ((com.agent.tool.impl.ReadFileTool) toolRegistry.getTool("ReadFile")).setFileStateCache(fileStateCache);
        ((com.agent.tool.impl.EditFileTool) toolRegistry.getTool("EditFile")).setFileHistory(fileHistory);
        ((com.agent.tool.impl.EditFileTool) toolRegistry.getTool("EditFile")).setFileStateCache(fileStateCache);
        ((com.agent.tool.impl.WriteFileTool) toolRegistry.getTool("WriteFile")).setFileHistory(fileHistory);
        ((com.agent.tool.impl.WriteFileTool) toolRegistry.getTool("WriteFile")).setFileStateCache(fileStateCache);
        // ToolSearchTool 需要持有 registry 引用，用于延迟工具发现
        toolRegistry.register(new ToolSearchTool(toolRegistry, provider.getProtocol()));
    }

    // ═══════════════════════════════════════════════════════════════
    //  主循环
    // ═══════════════════════════════════════════════════════════════

    private void run() {
        terminal.enterRawMode();
        readTerminalSize();
        // 不开启 trackMouse：保留终端原生 QuickEdit / 文本选区 / I-beam 光标。
        // 滚动改用键盘（PageUp/PageDown/↑/↓）。

        // 输入线程：阻塞读取按键 → 事件队列
        Thread inputThread = Thread.startVirtualThread(this::inputLoop);

        try {
            long lastRenderMs = 0;
            while (running) {
                readTerminalSize(); // 检测窗口变化

                // 处理事件（非阻塞）
                UIEvent event;
                while ((event = eventQueue.poll()) != null) {
                    handleEvent(event);
                }

                // 渲染（最多 30 fps）
                long now = System.currentTimeMillis();
                if (needsRedraw || terminalResized) {
                    terminalResized = false;
                    render();
                    needsRedraw = false;
                    lastRenderMs = now;
                }

                // 流式模式下定时刷新计时器
                if (streaming && !firstTokenReceived) {
                    if (now - lastRenderMs >= 500) {
                        long elapsed = (System.currentTimeMillis() - streamStartMs) / 1000;
                        if (!messages.isEmpty() && messages.getLast().streaming()) {
                            String think = thinkingAccum.toString();
                            if (think.isEmpty()) {
                                messages.set(messages.size() - 1,
                                    UIMessage.streaming("Imagining… (" + elapsed + "s)"));
                            } else {
                                // thinking 阶段也更新计时器
                                messages.set(messages.size() - 1,
                                    UIMessage.streamingThinking(think, elapsed));
                            }
                        }
                        needsRedraw = true;
                    }
                }

                // 短暂休眠避免忙等
                try { Thread.sleep(16); } catch (InterruptedException e) { break; }
            }
        } finally {
            cleanup();
        }
    }

    private void cleanup() {
        writer.print(CURSOR_SHOW);
        writer.println();
        writer.flush();
        try { terminal.close(); } catch (Exception ignored) {}
    }

    // ═══════════════════════════════════════════════════════════════
    //  事件处理
    // ═══════════════════════════════════════════════════════════════

    private void handleEvent(UIEvent event) {
        switch (event) {
            case UIEvent.Submit s -> {
                if (!streaming) submitMessage(s.text());
            }
            case UIEvent.KeyTyped kt -> {
                if (!streaming) handleKeyTyped(kt.ch());
            }
            case UIEvent.TerminalResize r -> {
                termWidth = r.cols();
                termHeight = r.rows();
                terminalResized = true;
            }
            case UIEvent.Exit ignored -> running = false;
            default -> {}
        }
    }

    /**
     * 处理可打印字符输入（由主线程从事件队列消费）。
     *
     * 注意：Enter、Backspace、Ctrl+C、Ctrl+P 等控制字符在 inputLoop 中直接处理，
     * 不会到达此方法。此处只处理 ch >= 32 的可打印字符（含中日韩）。
     */
    private void handleKeyTyped(int ch) {
        inputHistory.removeIf(String::isEmpty);
        inputBuffer.insert(linearPos(), String.valueOf((char) ch));
        cursorCol++;
        needsRedraw = true;
    }

    private void handleBackspace() {
        int pos = linearPos();
        if (pos > 0) {
            inputBuffer.deleteCharAt(pos - 1);
            if (cursorCol > 0) {
                cursorCol--;
            } else if (cursorRow > 0) {
                cursorRow--;
                cursorCol = countCharsInLine(cursorRow);
            }
        }
        needsRedraw = true;
    }

    // 将 (row, col) 转成线性位置
    private int linearPos() {
        String[] lines = inputBuffer.toString().split("\n", -1);
        int pos = 0;
        for (int i = 0; i < cursorRow && i < lines.length; i++) {
            pos += lines[i].length() + 1; // +1 for \n
        }
        pos += Math.min(cursorCol, lines.length > cursorRow ? lines[cursorRow].length() : 0);
        return pos;
    }

    private int countCharsInLine(int row) {
        String[] lines = inputBuffer.toString().split("\n", -1);
        if (row >= 0 && row < lines.length) return lines[row].length();
        return 0;
    }

    // ═══════════════════════════════════════════════════════════════
    //  消息提交 & 流式接收
    // ═══════════════════════════════════════════════════════════════

    /**
     * 提交用户消息并启动 Agent Loop。
     *
     * Agent Loop 是 Coding Agent 的核心循环：
     *   1. 添加用户消息 → 发送给 LLM（携带工具列表）
     *   2. 流式接收响应：思考、正文、工具调用
     *   3. 如果 LLM 请求工具调用（stopReason=tool_use）：
     *      a. 保存助手消息（含 tool_uses）到对话历史
     *      b. 逐个执行工具，展示结果
     *      c. 将工具结果添加到对话历史
     *      d. 回到步骤 2，继续下一轮
     *   4. 如果 LLM 自然结束（stopReason=end_turn）：
     *      a. 渲染最终 Markdown，保存到对话历史
     *      b. 打文件快照（FileHistory.makeSnapshot）
     *      c. 退出循环
     *
     * @param text 用户输入的文本
     */
    private void submitMessage(String text) {
        if (text == null || text.isBlank()) return;

        // /exit 命令
        if (text.trim().equals("/exit")) {
            running = false;
            return;
        }

        // 添加用户消息到 UI 列表 + ConversationManager
        messages.add(UIMessage.user(text));
        conversation.addUserMessage(text);
        inputBuffer.setLength(0);
        cursorCol = 0;
        cursorRow = 0;
        scrollToBottom();

        // 启动流式状态
        streaming = true;
        messages.add(UIMessage.streaming("Imagining… (0s)"));
        needsRedraw = true;

        final String userText = text;
        Thread.startVirtualThread(() -> {
            try {
                runAgentLoop(userText);
            } catch (InterruptedException e) {
                messages.add(UIMessage.error("Request interrupted."));
            } finally {
                streaming = false;
                needsRedraw = true;
            }
        });
    }

    /**
     * Agent Loop 主循环 — 在虚拟线程中执行。
     *
     * 每次迭代代表一轮 LLM 请求-响应：
     *   流式接收 → 如果有工具调用 → 执行工具 → 继续循环
     *   流式接收 → 如果自然结束 → 渲染最终文本 → 退出循环
     *
     * @param userText 原始用户输入（用于 FileHistory 快照标签）
     */
    private void runAgentLoop(String userText) throws InterruptedException {
        String protocol = provider.getProtocol();
        List<Map<String, Object>> toolSchemas = toolRegistry.getAllSchemas(protocol);

        while (true) {
            // ── 重置本轮状态 ──
            streamAccum.setLength(0);
            thinkingAccum.setLength(0);
            firstTokenReceived = false;
            streamStartMs = System.currentTimeMillis();

            // 本轮工具调用累积
            var pendingToolUses = new ArrayList<ToolUseBlock>();
            var toolCallArgsMap = new HashMap<String, StringBuilder>();
            String[] currentTool = {"", ""};  // [toolId, toolName]，用数组绕过 lambda effectively-final 限制

            // 本轮思考块（含 signature，用于对话历史回传）
            var thinkingBlocks = new ArrayList<ThinkingBlock>();

            // 替换占位消息
            replaceLastMessage(UIMessage.streaming("Imagining… (0s)"));
            needsRedraw = true;

            // ── 发送请求并消费流式事件 ──
            BlockingQueue<StreamEvent> events = client.stream(conversation, toolSchemas);

            String stopReason = "";
            boolean gotError = false;
            String errorMsg = null;

            while (true) {
                StreamEvent evt = events.take();
                switch (evt) {
                    case StreamEvent.ThinkingDelta td -> {
                        thinkingAccum.append(td.text());
                        updateStreamingMessage();
                        needsRedraw = true;
                    }
                    case StreamEvent.ThinkingComplete tc -> {
                        thinkingBlocks.add(new ThinkingBlock(tc.thinking(), tc.signature()));
                        updateStreamingMessage();
                        needsRedraw = true;
                    }
                    case StreamEvent.TextDelta td -> {
                        if (!firstTokenReceived) {
                            firstTokenReceived = true;
                            firstTokenMs = System.currentTimeMillis();
                        }
                        streamAccum.append(td.text());
                        updateStreamingMessage();
                        needsRedraw = true;
                    }
                    case StreamEvent.ToolCallStart tcs -> {
                        // 如果流式消息已有文本/思考内容，先定稿为助手消息，再添加工具调用
                        // 这样助手正文和工具调用各自独立显示，不会互相覆盖
                        if (!messages.isEmpty() && messages.getLast().streaming()
                                && (!streamAccum.isEmpty() || !thinkingAccum.isEmpty())) {
                            finalizeStreamingAssistant();
                            messages.add(UIMessage.streamingToolCall(tcs.toolName(), "…"));
                        } else {
                            // 无文本内容，直接替换占位消息
                            replaceLastMessage(UIMessage.streamingToolCall(tcs.toolName(), "…"));
                        }
                        currentTool[0] = tcs.toolId();
                        currentTool[1] = tcs.toolName();
                        toolCallArgsMap.put(tcs.toolId(), new StringBuilder());
                        needsRedraw = true;
                    }
                    case StreamEvent.ToolCallDelta tcd -> {
                        // 累积当前工具的参数片段
                        if (!currentTool[0].isEmpty()) {
                            toolCallArgsMap.get(currentTool[0]).append(tcd.text());
                            String partial = toolCallArgsMap.get(currentTool[0]).toString();
                            replaceLastMessage(UIMessage.streamingToolCall(currentTool[1], partial));
                            needsRedraw = true;
                        }
                    }
                    case StreamEvent.ToolCallComplete tcc -> {
                        pendingToolUses.add(new ToolUseBlock(tcc.toolId(), tcc.toolName(), tcc.arguments()));
                        currentTool[0] = "";
                        currentTool[1] = "";
                        // 显示完整的工具调用
                        replaceLastMessage(UIMessage.toolCall(tcc.toolName(), formatToolArgs(tcc.arguments())));
                        needsRedraw = true;
                    }
                    case StreamEvent.StreamEnd se -> {
                        stopReason = se.stopReason();
                    }
                    case StreamEvent.Error e -> {
                        gotError = true;
                        errorMsg = e.message();
                    }
                    default -> {}
                }
                // StreamEnd 或 Error 时退出内层循环
                if (evt instanceof StreamEvent.StreamEnd || evt instanceof StreamEvent.Error) break;
            }

            // ── 错误处理 ──
            if (gotError) {
                String errText = errorMsg;
                if (firstTokenReceived) {
                    errText += "\n\n[Partial reply] " + streamAccum.toString();
                }
                messages.add(UIMessage.error(errText));
                return;
            }

            // ── 渲染本轮最终文本 ──
            String finalText = streamAccum.toString();
            String thinkText = thinkingAccum.toString();
            String rendered;
            try {
                rendered = MarkdownRenderer.render(finalText);
            } catch (Exception e) {
                rendered = finalText;
            }
            // 如有思考内容，拼接在正文前
            if (!thinkText.isEmpty()) {
                String thinkBlock = GRAY + "✻ Thinking…" + RESET + "\n"
                    + UIMessage.grayLines(thinkText) + "\n"
                    + GRAY + "✻ Done" + RESET + "\n\n";
                rendered = thinkBlock + rendered;
            }

            // ── 根据是否有工具调用决定下一步 ──
            if ("tool_use".equals(stopReason) && !pendingToolUses.isEmpty()) {
                // ★ 工具调用分支：保存助手消息（含 tool_uses）→ 执行工具 → 添加结果 → 继续循环
                conversation.addAssistantFull(finalText, thinkingBlocks, pendingToolUses);

                // 如果流式消息仍有内容（无工具调用前缀文本的边缘情况），定稿它
                if (!messages.isEmpty() && messages.getLast().streaming()
                        && (!streamAccum.isEmpty() || !thinkingAccum.isEmpty())) {
                    finalizeStreamingAssistant();
                }

                // 逐个执行工具（toolCall 消息已在 ToolCallComplete 时显示，这里只追加结果）
                var toolResults = new ArrayList<ToolResultBlock>();
                for (var tu : pendingToolUses) {
                    // 执行工具
                    ToolResultBlock result = executeToolCall(tu);
                    toolResults.add(result);

                    // 显示结果（↳ 缩进表示工具输出）
                    messages.add(UIMessage.toolResult(tu.toolName(), result.content(), result.isError()));
                    needsRedraw = true;
                }

                // 将工具结果添加到对话历史（Anthropic 用 user 角色 + tool_result block）
                conversation.addToolResultsMessage(toolResults);

                // 添加下一轮的占位消息
                messages.add(UIMessage.streaming("Imagining… (0s)"));
                needsRedraw = true;
                // 继续循环 → 下一轮 LLM 请求
            } else {
                // ★ 自然结束分支：保存助手消息 → 打快照 → 退出循环
                conversation.addAssistantFull(finalText,
                        thinkingBlocks.isEmpty() ? null : thinkingBlocks, null);

                long elapsed = (firstTokenReceived ? firstTokenMs : System.currentTimeMillis()) - streamStartMs;
                double secs = Math.max(elapsed, 0) / 1000.0;
                replaceLastMessage(UIMessage.assistant(rendered, String.format("DeveCode  (%.1fs)", secs)));

                // 打文件快照（用于后续回退）
                if (fileHistory != null) {
                    fileHistory.makeSnapshot(conversation.size(), userText);
                }
                return;
            }
        }
    }

    /**
     * 执行单个工具调用。
     *
     * 步骤：
     *   1. 从 ToolRegistry 查找工具实例
     *   2. 调用 tool.execute(args)
     *   3. 截断超长输出（MAX_OUTPUT_CHARS）
     *   4. 返回 ToolResultBlock（含 toolUseId 关联到对应的 tool_use）
     *
     * @param toolUse LLM 请求的工具调用（工具名 + 参数）
     * @return 工具执行结果（含 toolUseId、输出内容、是否出错）
     */
    private ToolResultBlock executeToolCall(ToolUseBlock toolUse) {
        String toolName = toolUse.toolName();
        Tool tool = toolRegistry.getTool(toolName);
        if (tool == null) {
            return new ToolResultBlock(toolUse.toolId(),
                    "Error: unknown tool '" + toolName + "'", true);
        }
        try {
            ToolResult result = tool.execute(toolUse.arguments());
            String output = result.output();
            // 截断超长输出，避免撑爆 LLM 上下文窗口
            if (output.length() > ToolRegistry.MAX_OUTPUT_CHARS) {
                output = output.substring(0, ToolRegistry.MAX_OUTPUT_CHARS)
                        + "\n... (truncated at " + ToolRegistry.MAX_OUTPUT_CHARS + " chars)";
            }
            return new ToolResultBlock(toolUse.toolId(), output, result.isError());
        } catch (Exception e) {
            return new ToolResultBlock(toolUse.toolId(),
                    "Error executing tool: " + e.getMessage(), true);
        }
    }

    /** 格式化工具参数为 "key: value, key: value" 形式，超长值截断 */
    private String formatToolArgs(Map<String, Object> args) {
        if (args == null || args.isEmpty()) return "";
        var sb = new StringBuilder();
        for (var entry : args.entrySet()) {
            if (!sb.isEmpty()) sb.append(", ");
            String val = String.valueOf(entry.getValue());
            if (val.length() > 50) val = val.substring(0, 47) + "…";
            sb.append(entry.getKey()).append(": ").append(val);
        }
        return sb.toString();
    }

    /** 更新最后一条流式消息的显示内容（根据当前 thinking 和 text 状态） */
    private void updateStreamingMessage() {
        if (messages.isEmpty()) return;
        var msg = messages.getLast();
        if (!msg.streaming()) return;

        String think = thinkingAccum.toString();
        String text = streamAccum.toString();

        if (!think.isEmpty() && !text.isEmpty()) {
            messages.set(messages.size() - 1,
                UIMessage.streamingWithThinking(think, text));
        } else if (!think.isEmpty()) {
            long elapsed = (System.currentTimeMillis() - streamStartMs) / 1000;
            messages.set(messages.size() - 1,
                UIMessage.streamingThinking(think, elapsed));
        } else if (!text.isEmpty()) {
            messages.set(messages.size() - 1,
                UIMessage.streaming(text));
        }
    }

    /**
     * 将流式消息定稿为最终助手消息（Markdown 渲染 + 时间标签 + 思考块）。
     * 在 ToolCallStart 到达时调用，把已收到的文本"封存"为独立消息，
     * 然后添加新的工具调用消息，避免互相覆盖。
     */
    private void finalizeStreamingAssistant() {
        if (messages.isEmpty()) return;
        if (!messages.getLast().streaming()) return;

        String finalText = streamAccum.toString();
        String thinkText = thinkingAccum.toString();
        String rendered;
        try {
            rendered = MarkdownRenderer.render(finalText);
        } catch (Exception e) {
            rendered = finalText;
        }
        if (!thinkText.isEmpty()) {
            String thinkBlock = GRAY + "✻ Thinking…" + RESET + "\n"
                + UIMessage.grayLines(thinkText) + "\n"
                + GRAY + "✻ Done" + RESET + "\n\n";
            rendered = thinkBlock + rendered;
        }
        long elapsed = (firstTokenReceived ? firstTokenMs : System.currentTimeMillis()) - streamStartMs;
        double secs = Math.max(elapsed, 0) / 1000.0;
        replaceLastMessage(UIMessage.assistant(rendered, String.format("DeveCode  (%.1fs)", secs)));
    }

    /** 替换最后一条消息（不管是否流式） */
    private void replaceLastMessage(UIMessage msg) {
        if (messages.isEmpty()) {
            messages.add(msg);
        } else {
            messages.set(messages.size() - 1, msg);
        }
    }

    private void scrollToBottom() {
        scrollOffset = 0; // 0 = 底部对齐
    }

    // ═══════════════════════════════════════════════════════════════
    //  输入处理（后台线程）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 后台输入线程：阻塞读取终端原始字节，解析为按键事件。
     *
     * 处理流程：
     *   1. 读取一个字节
     *   2. 若为 ESC (0x1B)：解析 CSI / SS3 转义序列（方向键、PageUp/Down、Home/End、Delete）
     *   3. 若为控制字符（CR/LF/Backspace/Ctrl+C/Ctrl+P）：直接处理
     *   4. 若为可打印字符（>= 32 或 Tab）：封装为 KeyTyped 事件推入队列
     *
     * 注意：控制字符直接处理，不经过事件队列，确保响应即时。
     *      可打印字符走队列，由主线程的 handleKeyTyped 处理。
     */
    private void inputLoop() {
        try {
            var reader = (org.jline.utils.NonBlockingReader) terminal.reader();
            while (running) {
                int ch = reader.read();
                if (ch == -1) { running = false; break; }

                // --- ESC sequences (function keys) ---
                if (ch == 0x1B) {
                    int c2 = reader.read();
                    if (c2 == -1) break;

                    if (c2 == '[') {
                        int c3 = reader.read();
                        if (c3 == -1) break;

                        // Read rest of CSI parameter bytes
                        StringBuilder csiParams = new StringBuilder();
                        csiParams.append((char) c3);
                        int cp;
                        while ((cp = reader.read()) != -1) {
                            csiParams.append((char) cp);
                            // CSI sequences end with a letter (A-Z, a-z) or ~
                            if ((cp >= 'A' && cp <= 'Z') || (cp >= 'a' && cp <= 'z') || cp == '~') break;
                        }

                        String csi = csiParams.toString();
                        switch (csi) {
                            case "A" -> { if (!streaming) { scrollOffset++; needsRedraw = true; } }
                            case "B" -> { if (!streaming) { scrollOffset = Math.max(0, scrollOffset - 1); needsRedraw = true; } }
                            case "C" -> handleCursorRight();
                            case "D" -> handleCursorLeft();
                            case "H" -> handleHome();
                            case "F" -> handleEnd();
                            case "3~" -> handleDelete();
                            case "5~" -> { if (!streaming) { scrollOffset += pageScrollAmount(); needsRedraw = true; } }
                            case "6~" -> { if (!streaming) { scrollOffset = Math.max(0, scrollOffset - pageScrollAmount()); needsRedraw = true; } }
                            default -> {} // ignore unknown CSI
                        }
                        if (!csi.equals("A") && !csi.equals("B") && !csi.equals("5~") && !csi.equals("6~")) needsRedraw = true;
                        continue;
                    }

                    // ESC O sequences (SS3: function keys)
                    if (c2 == 'O') {
                        int c3 = reader.read();
                        if (c3 == -1) break;
                        switch (c3) {
                            case 'A' -> { if (!streaming) { scrollOffset++; needsRedraw = true; } }
                            case 'B' -> { if (!streaming) { scrollOffset = Math.max(0, scrollOffset - 1); needsRedraw = true; } }
                            case 'C' -> handleCursorRight();
                            case 'D' -> handleCursorLeft();
                            case 'H' -> handleHome();
                            case 'F' -> handleEnd();
                            default -> {}
                        }
                        continue;
                    }

                    // Alt+Enter: ESC CR or ESC LF
                    if (c2 == '\r' || c2 == '\n') {
                        insertNewline();
                        continue;
                    }

                    // Unknown ESC, ignore
                    continue;
                }

                // --- Control characters ---
                if (ch == '\r' || ch == '\n') {
                    handleEnter();
                    continue;
                }
                if (ch == '\b' || ch == 127) {
                    handleBackspace();
                    needsRedraw = true;
                    continue;
                }
                if (ch == 3) {
                    running = false;
                    eventQueue.add(new UIEvent.Exit());
                    continue;
                }
                if (ch == 16) {                   // Ctrl+P → 切换右侧面板
                    panelVisible = !panelVisible;
                    needsRedraw = true;
                    continue;
                }

                // --- Printable characters (incl. CJK) ---
                if (ch >= 32 || ch == '\t') {
                    eventQueue.add(new UIEvent.KeyTyped(ch));
                    needsRedraw = true;
                }
            }
        } catch (Exception e) {
            eventQueue.add(new UIEvent.Exit());
        }
    }

    /**
     * 处理 Enter 键提交：将输入缓冲区内容封装为 Submit 事件推入队列。
     * 由 inputLoop 直接调用（不经过 KeyTyped 事件，避免 Windows CRLF 双触发）。
     */
    private void handleEnter() {
        if (!streaming) {
            String text = inputBuffer.toString();
            if (!text.isBlank()) {
                inputHistory.add(text);
            }
            eventQueue.add(new UIEvent.Submit(text));
        }
    }

    private void insertNewline() {
        inputBuffer.insert(linearPos(), '\n');
        cursorRow++;
        cursorCol = 0;
        needsRedraw = true;
    }

    private void handleCursorLeft() {
        if (cursorCol > 0) {
            cursorCol--;
        } else if (cursorRow > 0) {
            cursorRow--;
            cursorCol = countCharsInLine(cursorRow);
        }
        needsRedraw = true;
    }

    private void handleCursorRight() {
        int maxCol = countCharsInLine(cursorRow);
        if (cursorCol < maxCol) {
            cursorCol++;
        } else {
            String[] lines = inputBuffer.toString().split("\n", -1);
            if (cursorRow < lines.length - 1) {
                cursorRow++;
                cursorCol = 0;
            }
        }
        needsRedraw = true;
    }

    private void handleHome() { cursorCol = 0; cursorRow = 0; needsRedraw = true; }

    private void handleEnd() {
        String[] lines = inputBuffer.toString().split("\n", -1);
        cursorRow = lines.length - 1;
        cursorCol = lines[cursorRow].length();
        needsRedraw = true;
    }

    private void handleDelete() {
        int pos = linearPos();
        if (pos < inputBuffer.length()) {
            inputBuffer.deleteCharAt(pos);
            needsRedraw = true;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  渲染
    // ═══════════════════════════════════════════════════════════════

    /** PageUp/PageDown 每次滚动的行数（对话区可见行数 - 1，至少 1）。 */
    private int pageScrollAmount() {
        int n = estimateConvAvailRows() - 1;
        return Math.max(1, n);
    }

    /**
     * 全屏渲染：将整个终端画面一次性写入 StringBuilder 再 flush。
     *
     * 布局（从上到下）：
     *   ┌────────────────────────────────────────────────┬───────────┐
     *   │ 状态行 (全宽)                                    │           │ row 0
     *   │ ──────────────────────────────────────────────  │           │ row 1 (分隔线)
     *   │                                                │  右侧     │
     *   │  对话区 (左侧)                                  │  状态     │ rows 2~sep2-1
     *   │  (消息列表 + 滚动条)                            │  面板     │
     *   │                                                │           │
     *   │ ──────────────────────────────────────────────  │           │ sep2 (分隔线)
     *   │  输入区 (左侧, ASCII 边框)                      │           │ sep2+1~statusBar-1
     *   │ provider名                          model名    │           │ statusBar (反白)
     *   └────────────────────────────────────────────────┴───────────┘
     *
     * 渲染顺序：状态行 → 分隔线1 → 对话区 → 分隔线2 → 状态面板 → 状态栏 → 输入区
     * 输入区最后渲染，确保光标最终定位在输入框内。
     */
    private void render() {
        StringBuilder buf = new StringBuilder(4096);
        buf.append(CURSOR_HIDE);
        buf.append(HOME);

        int rows = termHeight;
        int cols = termWidth;

        // 右侧状态面板：终端足够宽时显示
        boolean showPanel = cols >= PANEL_MIN_COLS && panelVisible;
        int panelW = showPanel ? PANEL_WIDTH : 0;
        int leftCols = showPanel ? cols - panelW - 1 : cols;  // -1 给竖线分隔
        int panelX = showPanel ? cols - panelW : 0;            // 面板起始列

        // 布局：状态行(1) | 分隔(1) | {对话区 | 分隔(1) | 输入区} + 状态面板 | 状态栏(1)
        int statusRow = 0;
        int sep1Row = 1;
        int convStart = 2;
        int inputHeight = Math.max(countInputLines() + 1, 3); // +1 边框
        int sep2Row = rows - inputHeight - 2;
        int inputTop = sep2Row + 1;
        int statusBarRow = rows - 1;

        // 确保对话区至少有 3 行
        int convEnd = sep2Row - 1;
        if (convEnd - convStart < 3) {
            convEnd = convStart + 3;
            sep2Row = convEnd;
            inputTop = sep2Row + 1;
            if (inputTop + inputHeight >= rows) {
                inputHeight = rows - inputTop - 1;
                if (inputHeight < 1) inputHeight = 1;
            }
        }

        // ── 状态行（全宽）──
        moveTo(buf, statusRow, 0);
        String statusLine;
        if (streaming) {
            if (!firstTokenReceived) {
                long elapsed = (System.currentTimeMillis() - streamStartMs) / 1000;
                statusLine = BOLD + YELLOW + "DeveCode: Imagining\u2026  (" + elapsed + "s)" + RESET;
            } else {
                statusLine = BOLD + GREEN + "DeveCode: Streaming\u2026" + RESET;
            }
        } else {
            statusLine = BOLD + "DeveCode: " + GREEN + "Ready" + RESET;
        }
        buf.append("\033[K");
        buf.append(truncate(statusLine, cols));

        // ── 分隔线1（全宽）──
        moveTo(buf, sep1Row, 0);
        buf.append(GRAY).append(repeat('-', cols)).append(RESET);

        // ── 对话区（左侧）──
        int convWidth = leftCols - 1;
        renderConversation(buf, convStart, convEnd, convWidth, leftCols);

        // ── 分隔线2（仅左侧）──
        moveTo(buf, sep2Row, 0);
        buf.append("\033[K");
        buf.append(GRAY).append(repeat('-', leftCols)).append(RESET);

        // ── 右侧状态面板 ──
        if (showPanel) {
            // 竖线分隔（从 convStart 到 statusBarRow-1）
            for (int y = convStart; y < statusBarRow; y++) {
                moveTo(buf, y, leftCols);
                buf.append(GRAY).append('│').append(RESET);
            }
            renderStatusPanel(buf, convStart, statusBarRow - 1, leftCols + 1, panelW);
        }

        // ── 状态栏（全宽）──
        renderStatusBar(buf, statusBarRow, cols);

        // ── 输入区（仅左侧）── 最后渲染，确保光标定位在输入框
        renderInputArea(buf, inputTop, inputHeight, leftCols);

        // 写入终端
        writer.print(buf.toString());
        writer.flush();
    }

    /** 左侧可用宽度（扣除右侧面板和竖线） */
    private int leftContentWidth() {
        int cols = termWidth;
        boolean showPanel = cols >= PANEL_MIN_COLS && panelVisible;
        return showPanel ? cols - PANEL_WIDTH - 1 - 1 : cols - 1;
    }

    private List<RenderLine> buildAllRenderLines() {
        List<RenderLine> allLines = new ArrayList<>();
        int textWidth = Math.max(1, leftContentWidth());
        for (UIMessage msg : messages) { allLines.addAll(msg.toRenderLines(textWidth)); }
        return allLines;
    }
    private int estimateConvAvailRows() {
        int rows = termHeight;
        int inputHeight = Math.max(countInputLines() + 1, 3);
        int sep2Row = rows - inputHeight - 2;
        int convStart = 2, convEnd = sep2Row - 1;
        if (convEnd - convStart < 3) convEnd = convStart + 3;
        return convEnd - convStart + 1;
    }
    private void renderConversation(StringBuilder buf, int startRow, int endRow, int textWidth, int termCols) {
        int availRows = endRow - startRow + 1;
        if (availRows <= 0) return;
        List<RenderLine> allLines = buildAllRenderLines();
        int totalLines = allLines.size();
        int maxScroll = Math.max(0, totalLines - availRows);
        if (scrollOffset > maxScroll) scrollOffset = maxScroll;
        int visibleStart = Math.max(0, totalLines - availRows - scrollOffset);
        if (visibleStart < 0) visibleStart = 0;
        int y = startRow;
        for (int i = visibleStart; i < totalLines && y <= endRow; i++) {
            moveTo(buf, y, 0);
            buf.append("\033[K");
            if (i < allLines.size()) {
                RenderLine rl = allLines.get(i);
                String text = truncate(rl.text(), textWidth);
                buf.append(rl.style()).append(text).append(RESET);
                int vl = visibleLength(text);
                if (vl < textWidth) buf.append(repeat(' ', textWidth - vl));
                drawScrollbarCell(buf, y, termCols, i, visibleStart, totalLines, availRows);
            }
            y++;
        }
        for (; y <= endRow; y++) { moveTo(buf, y, 0); buf.append("\033[K"); }
    }
    private void drawScrollbarCell(StringBuilder buf, int row, int termCols, int lineIdx, int visibleStart, int totalLines, int availRows) {
        if (totalLines <= availRows) return;
        int maxScroll = totalLines - availRows;
        int thumbHeight = Math.max(1, availRows * availRows / totalLines);
        int thumbTop = maxScroll > 0 ? (maxScroll - scrollOffset) * (availRows - thumbHeight) / maxScroll : availRows - thumbHeight;
        int thumbBottom = thumbTop + thumbHeight - 1;
        int rowInTrack = lineIdx - visibleStart;
        moveTo(buf, row, termCols - 1);
        if (rowInTrack >= thumbTop && rowInTrack <= thumbBottom) {
            buf.append(REVERSE).append(' ').append(RESET);
        } else {
            buf.append(GRAY).append('│').append(RESET);
        }
    }


    private void renderInputArea(StringBuilder buf, int topRow, int height, int cols) {
        // 只清空左侧区域，不擦掉右侧竖线和面板
        for (int y = topRow; y < topRow + height; y++) {
            moveTo(buf, y, 0);
            buf.append(repeat(' ', cols));
        }

        // ASCII border
        moveTo(buf, topRow, 0);
        buf.append(GRAY).append("+").append(repeat('-', cols - 2)).append("+").append(RESET);
        for (int y = topRow + 1; y < topRow + height - 1; y++) {
            moveTo(buf, y, 0);
            buf.append(GRAY).append("|").append(RESET);
            moveTo(buf, y, cols - 1);
            buf.append(GRAY).append("|").append(RESET);
        }
        moveTo(buf, topRow + height - 1, 0);
        buf.append(GRAY).append("+").append(repeat('-', cols - 2)).append("+").append(RESET);

        // prompt + input content
        if (height >= 2) {
            moveTo(buf, topRow + 1, 1);
            buf.append(BOLD).append("> ").append(RESET);

            if (inputBuffer.isEmpty() && !streaming) {
                buf.append(DIM).append("Send a message, or ctrl + c to quit, ctrl + p to toggle panel").append(RESET);
                moveTo(buf, topRow + 1, 3);
                buf.append(CURSOR_SHOW);
            } else {
                String[] lines = inputBuffer.toString().split("\n", -1);
                int maxDisplayLines = height - 2;
                int startLine = Math.max(0, lines.length - maxDisplayLines);
                for (int i = startLine; i < lines.length; i++) {
                    if (i > startLine) {
                        moveTo(buf, topRow + 1 + (i - startLine), 1);
                    }
                    buf.append(lines[i]);
                }

                if (!streaming) {
                    int cursorDisplayRow = Math.min(cursorRow, lines.length - 1) - startLine;
                    if (cursorDisplayRow < 0) cursorDisplayRow = 0;
                    int cursorColClamped = Math.min(cursorCol, lines.length > cursorRow ? lines[cursorRow].length() : 0);
                    // Convert logical cursor pos to display column (CJK = 2 cols)
                    String curLine = lines.length > cursorRow ? lines[cursorRow] : "";
                    int displayCol = 0;
                    for (int ci = 0; ci < Math.min(cursorColClamped, curLine.length()); ci++) {
                        displayCol += displayCharWidth(curLine.charAt(ci));
                    }
                    moveTo(buf, topRow + 1 + cursorDisplayRow, 3 + displayCol);
                    buf.append(CURSOR_SHOW);
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  右侧状态面板
    // ═══════════════════════════════════════════════════════════════

    /**
     * 渲染右侧系统状态监控面板。
     * 区块化垂直堆叠，每块左侧有灰色竖线，用 ▰ 图标和颜色区隔。
     */
    private void renderStatusPanel(StringBuilder buf, int startRow, int endRow, int x, int width) {
        // 先清空面板区域
        for (int y = startRow; y <= endRow; y++) {
            moveTo(buf, y, x);
            buf.append("\033[K");
        }

        // ── 收集数据 ──
        int usedTokens = estimateTokens();
        // provider.resolvedContextWindow() — 从 ProviderConfig 获取上下文窗口大小（如 200000）
        int contextWindow = provider.resolvedContextWindow();
        double pct = contextWindow > 0 ? usedTokens * 100.0 / contextWindow : 0;
        double freePct = 100.0 - pct;
        // provider.getModel() — 从 ProviderConfig 获取模型名（如 "deepseek-v4-flash"）
        String modelName = provider.getModel();
        if (modelName.length() > 18) modelName = modelName.substring(0, 17) + "…";

        int cpuThreads = osBean.getAvailableProcessors();
        double cpuLoad = osBean.getCpuLoad() * 100;
        if (cpuLoad < 0) cpuLoad = 0;

        // ── 逐行渲染 ──
        int y = startRow;
        int padX = x + 1;    // 竖线位置
        int maxW = width - 2; // 内容最大宽度

        // 区块一：Context
        y = panelHeader(buf, y, padX, maxW, "Context");
        y = panelLine(buf, y, padX, maxW,
                WHITE + BOLD + formatTokens(usedTokens) + RESET +
                YELLOW + " (" + String.format("%.1f%%", pct) + ")" + RESET);
        y++;

        // 区块二：Context Detail
        y = panelHeader(buf, y, padX, maxW, "Context Detail");
        y = panelKV(buf, y, padX, maxW, "Context window:", formatTokens(contextWindow) + " (" + String.format("%.1f%%", pct) + ")");
        y = panelKV(buf, y, padX, maxW, "Model:", modelName);
        y = panelKV(buf, y, padX, maxW, "Mode:", "default");
        y = panelKV(buf, y, padX, maxW, "Tools:", String.valueOf(
                toolRegistry.getAllSchemas(provider.getProtocol()).size()));
        y = panelKV(buf, y, padX, maxW, "Free Space:", String.format("%.1f%%", freePct));
        y++;

        // 区块三：Compact（柱状图）
        y = panelHeader(buf, y, padX, maxW, "Compact");
        y = panelBar(buf, y, padX, maxW, pct);
        y++;  // 柱状图和图例之间空一行
        // 图例
        String usageColor = pct > 80 ? RED : (pct > 50 ? YELLOW : GREEN);
        y = panelLine(buf, y, padX, maxW,
                usageColor + "█" + RESET + GRAY + " usage  " + RESET +
                GRAY + "░" + RESET + GRAY + " usable" + RESET);
        y++;

        // 区块四：Sandbox
        y = panelHeader(buf, y, padX, maxW, "Sandbox");
        y = panelKV(buf, y, padX, maxW, "Sandbox ID:", "212aab8b");
        y = panelKV(buf, y, padX, maxW, "Status:", GREEN + "●" + RESET + WHITE + " active" + RESET);
        y++;

        // 区块五：MCP
        y = panelHeader(buf, y, padX, maxW, "MCP");
        y = panelKV(buf, y, padX, maxW, "servers:", "none");
        y++;

        // 区块六：CPU
        y = panelHeader(buf, y, padX, maxW, "CPU");
        y = panelKV(buf, y, padX, maxW, "Threads:", String.valueOf(cpuThreads));
        y = panelKV(buf, y, padX, maxW, "Usage:", String.format("%.1f%%", cpuLoad));

        // 页脚（固定在面板底部）
        panelFooter(buf, endRow, padX, maxW, DIM + APP_NAME.toLowerCase() + " " + APP_VERSION + RESET);
    }

    /** 区块标题：│ ▰ Title（竖线 + 青色粗体） */
    private int panelHeader(StringBuilder buf, int y, int x, int maxW, String title) {
        moveTo(buf, y, x);
        buf.append("\033[K");
        buf.append(truncate(GRAY + "│ " + RESET + CYAN + BOLD + "▰ " + title + RESET, maxW));
        return y + 1;
    }

    /** 水平柱状图：│ ████████░░░░░░ 22.2% */
    private int panelBar(StringBuilder buf, int y, int x, int maxW, double pct) {
        moveTo(buf, y, x);
        buf.append("\033[K");

        int barW = maxW - 10;  // 留给 │ + 空格 + 百分比
        if (barW < 8) barW = 8;
        int filled = (int) Math.round(pct * barW / 100.0);
        if (filled > barW) filled = barW;
        if (filled == 0 && pct > 0) filled = 1;  // 至少 1 格
        int empty = barW - filled;

        String usageColor = pct > 80 ? RED : (pct > 50 ? YELLOW : GREEN);
        StringBuilder bar = new StringBuilder();
        bar.append(GRAY).append("│ ").append(RESET);
        bar.append(usageColor);
        for (int i = 0; i < filled; i++) bar.append('█');
        bar.append(RESET);
        bar.append(GRAY);
        for (int i = 0; i < empty; i++) bar.append('░');
        bar.append(RESET);
        bar.append(" ").append(YELLOW).append(String.format("%.1f%%", pct)).append(RESET);

        buf.append(truncate(bar.toString(), maxW));
        return y + 1;
    }

    /** 渲染面板中一行纯文本（带竖线前缀） */
    private int panelLine(StringBuilder buf, int y, int x, int maxW, String content) {
        moveTo(buf, y, x);
        buf.append("\033[K");
        buf.append(truncate(GRAY + "│ " + RESET + content, maxW));
        return y + 1;
    }

    /** 渲染面板中一行键值对：│ · label: value */
    private int panelKV(StringBuilder buf, int y, int x, int maxW, String label, String value) {
        moveTo(buf, y, x);
        buf.append("\033[K");
        String line = GRAY + "│ · " + label + " " + RESET + WHITE + value + RESET;
        buf.append(truncate(line, maxW));
        return y + 1;
    }

    /** 渲染页脚（无竖线前缀，固定底部） */
    private void panelFooter(StringBuilder buf, int y, int x, int maxW, String content) {
        moveTo(buf, y, x);
        buf.append("\033[K");
        buf.append(truncate(content, maxW));
    }

    private void renderStatusBar(StringBuilder buf, int row, int cols) {
        moveTo(buf, row, 0);
        buf.append("\033[K");
        buf.append(REVERSE);

        String left = " " + provider.getName() + " ";
        String right = " " + provider.getModel() + " ";
        int padding = cols - left.length() - right.length();
        if (padding < 0) padding = 0;

        buf.append(left);
        buf.append(repeat(' ', padding));
        buf.append(right);
        buf.append(RESET);
    }

    // ═══════════════════════════════════════════════════════════════
    //  工具方法
    // ═══════════════════════════════════════════════════════════════

    private int countInputLines() {
        if (inputBuffer.isEmpty()) return 1;
        return inputBuffer.toString().split("\n", -1).length;
    }

    // ── Token 估算 ──

    /**
     * 粗略估算当前对话已消耗的 token 数（~4 字符/token）。
     *
     * 数据来源：
     *   - ConversationManager.getMessages()：已完成的对话历史
     *   - streamAccum：当前流式输出中尚未完成的文本
     *
     * 用于右侧状态面板的 Context 占用率显示。
     */
    private int estimateTokens() {
        int totalChars = 0;
        // conversation.getMessages() 返回的是发给 LLM 的消息列表（Message 类型）
        for (var msg : conversation.getMessages()) {
            String content = msg.getContent();
            if (content != null) totalChars += content.length();
        }
        // 加上流式累积中的文本（尚未存入 ConversationManager）
        if (streaming) totalChars += streamAccum.length();
        return totalChars / 4;
    }

    /** 格式化 token 数为紧凑形式：1234 → "1.2K"，1000000 → "1.0M" */
    private static String formatTokens(int tokens) {
        if (tokens >= 1_000_000) return String.format("%.1fM", tokens / 1_000_000.0);
        if (tokens >= 1_000) return String.format("%.1fK", tokens / 1_000.0);
        return String.valueOf(tokens);
    }

    private static String repeat(char c, int n) {
        if (n <= 0) return "";
        return String.valueOf(c).repeat(n);
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        // 移除 ANSI 序列再计算长度
        String stripped = s.replaceAll("\u001b\\[[0-9;]*[a-zA-Z]", "");
        if (stripped.length() <= maxLen) return s;
        return s.substring(0, Math.min(s.length(), maxLen));
    }

    private static void moveTo(StringBuilder buf, int row, int col) {
        buf.append(ESC).append('[').append(row + 1).append(';').append(col + 1).append('H');
    }

    private void readTerminalSize() {
        Integer h = terminal.getHeight();
        Integer w = terminal.getWidth();
        if (h != null && h > 0) termHeight = h;
        if (w != null && w > 0) termWidth = w;
    }

    // ═══════════════════════════════════════════════════════════════
    //  内部类
    // ═══════════════════════════════════════════════════════════════

    /** UI 消息记录 */
    /**
     * Return the display width of a character: 1 for ASCII, 2 for CJK/fullwidth.
     */
    private static int displayCharWidth(int codePoint) {
        if (codePoint < 0x80) return 1;
        if (codePoint >= 0x1100 && codePoint <= 0x115F) return 2;
        if (codePoint >= 0x2E80 && codePoint <= 0xA4CF) return 2;
        if (codePoint >= 0xAC00 && codePoint <= 0xD7A3) return 2;
        if (codePoint >= 0xF900 && codePoint <= 0xFAFF) return 2;
        if (codePoint >= 0xFE10 && codePoint <= 0xFE19) return 2;
        if (codePoint >= 0xFE30 && codePoint <= 0xFE6F) return 2;
        if (codePoint >= 0xFF01 && codePoint <= 0xFF60) return 2;
        if (codePoint >= 0xFFE0 && codePoint <= 0xFFE6) return 2;
        if (codePoint >= 0x1F000 && codePoint <= 0x1F9FF) return 2;
        if (codePoint >= 0x20000) return 2;
        return 1;
    }


    private static int visibleLength(String s) {
        if (s == null) return 0;
        String stripped = s.replaceAll("\u001b\\[[0-9;]*[a-zA-Z]", "");
        int len = 0;
        for (int i = 0; i < stripped.length(); i++) len += displayCharWidth(stripped.charAt(i));
        return len;
    }
    /**
     * UI 消息记录。封装一条消息在终端中显示所需的全部信息。
     *
     * @param role      消息角色："user" / "assistant" / "error" / "banner" / "tool"
     * @param content   已格式化的内容（含 ANSI 颜色码），按 \n 分行
     * @param timeLabel 时间标签（如 "14:30"），显示在首行前；null 表示不显示
     * @param streaming 是否为流式进行中的消息（true 时不加空行分隔）
     * @param error     是否为错误消息
     */
    private record UIMessage(String role, String content, String timeLabel, boolean streaming, boolean error) {
        private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

        static UIMessage banner() {
            String banner = BOLD + APP_NAME + " " + APP_VERSION + RESET;
            String cwd = System.getProperty("user.dir");
            return new UIMessage("banner", banner + "\n" + DIM + cwd + RESET, null, false, false);
        }

        static UIMessage user(String text) {
            return new UIMessage("user",
                    BOLD + GREEN + "You" + RESET + ": " + text,
                    LocalTime.now().format(TIME_FMT), false, false);
        }

        static UIMessage assistant(String text, String timeLabel) {
            return new UIMessage("assistant",
                    BOLD + CYAN + "DeveCode" + RESET + "\n" + text,
                    timeLabel, false, false);
        }

        static UIMessage streaming(String text) {
            return new UIMessage("assistant",
                    BOLD + CYAN + "DeveCode" + RESET + "\n" + text,
                    null, true, false);
        }

        /** 流式显示思考过程（每行浅灰色，带计时器） */
        static UIMessage streamingThinking(String thinkText, long elapsed) {
            return new UIMessage("assistant",
                    BOLD + CYAN + "DeveCode" + RESET + "\n" +
                    GRAY + "✻ Thinking… (" + elapsed + "s)" + RESET + "\n" +
                    grayLines(thinkText),
                    null, true, false);
        }

        /** 思考完成，显示结束标记 */
        static UIMessage streamingThinkingDone(String thinkText) {
            return new UIMessage("assistant",
                    BOLD + CYAN + "DeveCode" + RESET + "\n" +
                    GRAY + "✻ Thinking…\n" + grayLines(thinkText) + "\n" +
                    GRAY + "✻ Done" + RESET,
                    null, true, false);
        }

        /** 流式显示思考过程（含结束标记）+ 正文 */
        static UIMessage streamingWithThinking(String thinkText, String responseText) {
            return new UIMessage("assistant",
                    BOLD + CYAN + "DeveCode" + RESET + "\n" +
                    GRAY + "✻ Thinking…\n" + grayLines(thinkText) + "\n" +
                    GRAY + "✻ Done" + RESET + "\n\n" +
                    responseText,
                    null, true, false);
        }

        // ── 工具调用相关的工厂方法 ──

        /** 工具调用流式中（参数正在推送） */
        static UIMessage streamingToolCall(String toolName, String argsDisplay) {
            return new UIMessage("tool",
                    YELLOW + "⚙ " + CYAN + toolName + RESET +
                    GRAY + "(" + argsDisplay + ")" + RESET,
                    null, true, false);
        }

        /** 工具调用完成（显示完整参数） */
        static UIMessage toolCall(String toolName, String argsDisplay) {
            return new UIMessage("tool",
                    YELLOW + "⚙ " + CYAN + toolName + RESET +
                    GRAY + "(" + argsDisplay + ")" + RESET,
                    LocalTime.now().format(TIME_FMT), false, false);
        }

        /** 工具执行中 */
        static UIMessage toolExecuting(String toolName) {
            return new UIMessage("tool",
                    YELLOW + "⚙ " + CYAN + toolName + RESET +
                    GRAY + "  executing…" + RESET,
                    null, false, false);
        }

        /** 工具执行结果（超长输出截断为前 500 字符） */
        static UIMessage toolResult(String toolName, String output, boolean isError) {
            String color = isError ? RED : GRAY;
            String display = output;
            if (display != null && display.length() > 500) {
                display = display.substring(0, 500) + "\n…";
            }
            return new UIMessage("tool",
                    color + "↳ " + (display != null ? display : "") + RESET,
                    null, false, false);
        }

        /** 将多行文本逐行包裹 GRAY 颜色（防止 \n 分割后丢失颜色） */
        private static String grayLines(String text) {
            if (text == null || text.isEmpty()) return "";
            String[] lines = text.split("\n", -1);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < lines.length; i++) {
                if (i > 0) sb.append("\n");
                sb.append(GRAY).append(lines[i]).append(RESET);
            }
            return sb.toString();
        }

        static UIMessage error(String text) {
            return new UIMessage("error",
                    BOLD + RED + "Error" + RESET + "\n" + RED + text + RESET,
                    LocalTime.now().format(TIME_FMT), false, true);
        }

        List<RenderLine> toRenderLines(int width) {
            List<RenderLine> result = new ArrayList<>();
            String[] parts = content.split("\n", -1);
            for (String part : parts) {
                // 分词换行
                List<String> wrapped = wrapText(part, width);
                for (String w : wrapped) {
                    result.add(new RenderLine("  " + w, ""));
                }
            }
            if (!result.isEmpty()) {
                // 为第一条加上时间标签
                if (timeLabel != null && !timeLabel.isEmpty()) {
                    var first = result.getFirst();
                    result.set(0, new RenderLine(
                            DIM + "[" + timeLabel + "]" + RESET + " " + first.text(),
                            first.style()));
                }
            }
            // 消息间加空行
            if (!result.isEmpty() && !streaming) {
                result.addFirst(new RenderLine("", ""));
            }
            return result;
        }

        static List<String> wrapText(String text, int width) {
            List<String> result = new ArrayList<>();
            if (text == null || text.isEmpty()) { result.add(""); return result; }
            if (width <= 0) { result.add(text); return result; }
            int totalDisplayWidth = visibleLength(text);
            if (totalDisplayWidth <= width) { result.add(text); return result; }
            StringBuilder currentLine = new StringBuilder();
            int currentLineWidth = 0;
            String plainText = text.replaceAll("\u001b\\[[0-9;]*[a-zA-Z]", "");
            int plainPos = 0, origPos = 0;
            while (plainPos < plainText.length()) {
                char c = plainText.charAt(plainPos);
                int cw = displayCharWidth(c);
                if (currentLineWidth + cw > width && !currentLine.isEmpty()) {
                    // 换行时传递 ANSI 颜色状态
                    String ansiState = extractAnsiState(currentLine.toString());
                    if (!ansiState.isEmpty()) currentLine.append(RESET);
                    result.add(currentLine.toString());
                    currentLine.setLength(0);
                    if (!ansiState.isEmpty()) currentLine.append(ansiState);
                    currentLineWidth = 0;
                }
                while (origPos < text.length()) {
                    char oc = text.charAt(origPos);
                    if (oc == '\u001b') {
                        int seqEnd = origPos;
                        while (seqEnd < text.length() && !Character.isLetter(text.charAt(seqEnd))) seqEnd++;
                        if (seqEnd < text.length()) seqEnd++;
                        currentLine.append(text, origPos, seqEnd);
                        origPos = seqEnd;
                    } else {
                        currentLine.append(oc);
                        origPos++;
                        break;
                    }
                }
                currentLineWidth += cw;
                plainPos++;
            }
            if (!currentLine.isEmpty()) result.add(currentLine.toString());
            if (result.isEmpty()) result.add("");
            return result;
        }

        /** 提取文本末尾活跃的 ANSI 颜色码（遇 RESET 清空，遇设置码追加） */
        private static String extractAnsiState(String text) {
            StringBuilder state = new StringBuilder();
            int i = 0;
            while (i < text.length()) {
                if (text.charAt(i) == '\u001b') {
                    int start = i;
                    i++;
                    while (i < text.length() && !Character.isLetter(text.charAt(i))) i++;
                    if (i < text.length()) i++;
                    String code = text.substring(start, i);
                    if (code.equals(RESET)) {
                        state.setLength(0);
                    } else {
                        state.append(code);
                    }
                } else {
                    i++;
                }
            }
            return state.toString();
        }

    }

    /** 渲染行：text 含 ANSI 颜色码，style 预留（目前未使用） */
    private record RenderLine(String text, String style) {}

    /**
     * 输入线程 → 主线程的事件（密封接口）。
     *
     * 事件类型：
     *   - KeyTyped：可打印字符（ch >= 32 或 Tab），由主线程 handleKeyTyped 处理
     *   - Submit：用户按 Enter 提交的文本，由主线程 submitMessage 处理
     *   - TerminalResize：终端尺寸变化（目前由 readTerminalSize 轮询检测，此事件未使用）
     *   - Exit：退出请求（Ctrl+C 或 EOF）
     */
    private sealed interface UIEvent {
        record KeyTyped(int ch) implements UIEvent {}
        record Submit(String text) implements UIEvent {}
        record TerminalResize(int cols, int rows) implements UIEvent {}
        record Exit() implements UIEvent {}
    }
}
