package com.agent.tui;

import com.agent.agent.Agent;
import com.agent.agent.AgentEvent;
import com.agent.command.Command;
import com.agent.command.CommandContext;
import com.agent.command.CommandLoader;
import com.agent.command.CommandRegistry;
import com.agent.compact.ContextCompactor;
import com.agent.config.McpServerConfig;
import com.agent.history.ConversationManager;
import com.agent.hook.HookEngine;
import com.agent.infra.ProviderConfig;
import com.agent.llm.LlmClient;
import com.agent.llm.Message;
import com.agent.llm.StreamEvent;
import com.agent.mcp.McpManager;
import com.agent.memory.MemoryManager;
import com.agent.memory.MemoryRecall;
import com.agent.permission.PermissionChecker;
import com.agent.permission.PermissionMode;
import com.agent.permission.PermissionResponse;
import com.agent.prompt.PromptBuilder;
import com.agent.session.SessionManager;
import com.agent.skill.SkillCatalog;
import com.agent.skill.SkillForkHost;
import com.agent.skill.SkillInstallReport;
import com.agent.skill.SkillInstaller;
import com.agent.skill.SkillSource;
import com.agent.skill.SkillExecutor;
import com.agent.skill.SkillTool;
import com.agent.tool.ToolRegistry;
import com.agent.tool.FileHistory;
import com.agent.tool.FileStateCache;
import com.agent.tool.impl.ToolSearchTool;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.NonBlockingReader;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import com.sun.management.OperatingSystemMXBean;

/**
 * 全功能终端 UI，承载输入、流式输出、多轮对话与状态展示。
 *
 * <h2>架构（后端/呈现端分离）</h2>
 * <pre>
 *   ┌─────────────┐         ┌──────────────────┐
 *   │ inputLoop   │──事件──→│ 主线程 (run)     │
 *   │ (虚拟线程)  │  队列   │ ├─ handleEvent   │
 *   │ 读取原始字节│         │ ├─ submitMessage │──→ Agent.run()
 *   └─────────────┘         │ ├─ render()      │←── AgentEvent 队列
 *                           │ └─ 状态面板      │    (consumeAgentEvents)
 *                           └──────────────────┘
 * </pre>
 *
 * <p>UI 不再自己实现 agent 循环：用户提交消息后调用 {@link Agent#run}，
 * 由 Agent 在后端完成 LLM 调用 → 工具执行（含权限检查/hook/并发分批）→
 * 上下文压缩 → 错误恢复的完整循环，UI 只消费 {@link AgentEvent} 做呈现：
 * 流式正文/思考、工具调用与结果（含耗时）、权限询问（y/a/n 交互）、
 * 压缩/重试提示、token 用量统计。
 *
 * <h2>线程模型</h2>
 * <ul>
 *   <li>主线程：事件消费 + 渲染 + 状态机（16ms 节流 ≈ 60fps）</li>
 *   <li>输入线程（虚拟）：阻塞读取终端字节，控制字符直接处理，可打印字符走队列；
 *       流式期间负责权限按键（y/a/n）和 Esc 中断</li>
 *   <li>消费线程（虚拟）：每次 submitMessage 创建，消费 AgentEvent 队列并更新 UI 状态</li>
 *   <li>Agent 线程（虚拟）：由 Agent.run 创建，执行后端 agent 循环</li>
 * </ul>
 */
public class TerminalUI implements SkillForkHost {

    private static final String APP_NAME    = "DeveCode";
    private static final String APP_VERSION = "v1.0.0";

    // ── 右侧状态面板 ──
    private static final int PANEL_WIDTH = 36;
    private static final int PANEL_MIN_COLS = 100;  // 终端宽度 >= 此值才显示面板
    private static final long PANEL_REFRESH_MS = 1000;  // 面板周期刷新间隔（CPU/Context 实时更新）
    private static final int MAX_COMMAND_HINTS = 8;  // 命令提示面板最多显示的候选条数

    // ── 终端 ──
    private final Terminal terminal;
    private final PrintWriter writer;

    // ── 应用状态（外部依赖）──
    private final ProviderConfig provider;       // 当前选中的 provider 配置
    private final LlmClient client;              // LLM 流式客户端（由 Agent 使用）
    private final ConversationManager conversation; // 对话历史管理器
    private final ToolRegistry toolRegistry;     // 工具注册中心
    private FileHistory fileHistory;             // 文件编辑历史（备份/快照/回退，随会话切换重建）
    private final FileStateCache fileStateCache; // 先读后改强制缓存
    private final PermissionChecker permissionChecker; // 多层权限裁决器
    private final HookEngine hookEngine;         // Hook 引擎（生命周期钩子）
    private final Agent agent;                   // 后端 agent（事件驱动）
    private volatile String sessionId;           // 会话 ID（session 包持久化 / 快照目录 / 面板显示）
    private final String workDir;                // 工作目录（session/memory/command 存储根）

    // ── 上下文管理（session 包）：.devecode/sessions/<id>.jsonl 持久化 ──

    // ── 记忆系统（memory 包）──
    private final MemoryManager memoryManager;   // 记忆文件管理（提取/索引/注入）
    private final Set<String> surfacedMemories = new HashSet<>(); // 已注入过的记忆路径（去重）

    // ── 命令系统（command 包）──
    private final CommandRegistry commandRegistry; // 斜杠命令注册中心（含 .devecode/commands/ 自定义命令）

    // ── Skill 系统（skill 包）──
    private final SkillCatalog skillCatalog;       // builtin/user/project 三层 skill 目录
    private volatile boolean skillInstalling = false; // /skill install 后台执行期间禁止重复安装
    // fork 嵌套深度（子Agent 内再 fork 最多一层，防止 fork-skill 递归爆炸）
    private final AtomicInteger forkDepth = new AtomicInteger();
    // fork 子Agent 复用的注入内容（构造时从主 Agent 保存）
    private String loadedInstructions = "";
    private String loadedMemoryReminder = "";

    // ── 全屏选择器（/resume 会话列表、/rewind 快照列表）──
    private volatile PickerState activePicker;   // null = 无选择器，正常对话界面
    private volatile int pickerIndex;            // 当前选中项（循环导航）

    // ── 命令提示（输入 / 时实时过滤候选命令）──
    private volatile int commandHintIndex = 0;     // 当前选中候选（循环导航）
    private volatile String hintTokenCache = null; // 上次计算候选时的命令 token（变化时重置索引）

    // ── 手动压缩进行中标志（/compact 后台执行期间禁止提交）──
    private volatile boolean compacting = false;

    // ── MCP（providers.yaml mcp_servers 段）──
    private final McpManager mcpManager;                    // null = 未配置任何 MCP server
    private final List<McpManager.ServerInfo> mcpServers = new ArrayList<>(); // 已连接的 server
    private final Map<String, Integer> mcpToolCounts = new LinkedHashMap<>(); // server 名 → 注册工具数
    private final List<String> mcpErrors = new ArrayList<>();                 // 连接失败的错误

    // ── system prompt（prompt 包组装）──
    private final String systemPrompt;

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

    // ── 权限询问（Agent → UI）──
    private volatile AgentEvent.PermissionRequestEvent pendingPermission;

    // ── token 用量（UsageEvent 累计）──
    private volatile int usageInTokens;
    private volatile int usageOutTokens;

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

    // ── 边框专用色：256 色亮天蓝 (75)，与欢迎屏一致 ──
    private static final String BORDER  = ESC + "[38;5;75m";

    // ── 入口 ──

    /**
     * 启动终端 UI。由 {@link DeveCodeApp} 在 provider 选择完成后调用。
     *
     * @param provider    用户选中的 provider 配置（含 API Key、模型名、协议等）
     * @param mcpServers  providers.yaml 中 mcp_servers 段解析出的 MCP server 配置（可为空）
     */
    public static void launch(ProviderConfig provider, List<McpServerConfig> mcpServers) {
        try {
            new TerminalUI(provider, mcpServers).run();
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
     *   4. 创建 ConversationManager（空对话历史）
     *   5. 初始化工具基础设施（ToolRegistry / FileHistory / FileStateCache）
     *   6. 连接 MCP server，把 MCP 工具注册进 ToolRegistry
     *   7. 由 prompt 包组装 system prompt（含 MCP server instructions），创建 LlmClient
     *   8. 组装 Agent
     *
     * @param provider         provider 配置
     * @param mcpServerConfigs MCP server 配置列表（可为 null）
     */
    private TerminalUI(ProviderConfig provider, List<McpServerConfig> mcpServerConfigs) throws IOException {
        this.provider = provider;
        this.workDir = System.getProperty("user.dir");
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
        // 步骤 4：ConversationManager — 管理对话历史，每次 addUserMessage/addAssistantMessage 会追加到内部列表
        this.conversation = new ConversationManager();
        // 步骤 5：初始化工具基础设施
        // FileStateCache — 记录 ReadFile 读取过的文件内容和 mtime，EditFile/WriteFile 据此强制"先读后改"
        this.fileStateCache = new FileStateCache();
        // FileHistory — 文件编辑备份/快照管理，每次 AI 轮次结束时打快照，支持回退
        // sessionId 由 session 包生成（yyyyMMdd-HHmmss-xxxx），同时作为 .jsonl 持久化文件名
        this.sessionId = SessionManager.newId();
        this.fileHistory = new FileHistory(workDir, sessionId);
        // 启动时清理超过 30 天的过期会话文件（尽力而为，失败静默）
        SessionManager.cleanExpiredSessions(workDir);
        // ToolRegistry — 用 createDefault() 创建所有工具，再通过 getTool() 注入依赖
        this.toolRegistry = ToolRegistry.createDefault();
        // 向需要文件依赖的工具注入 FileHistory / FileStateCache
        attachFileHistoryToTools();
        // ToolSearchTool 需要持有 registry 引用，用于延迟工具发现
        toolRegistry.register(new ToolSearchTool(toolRegistry, provider.getProtocol()));
        // 步骤 6：MCP — 依次连接配置的 server（stdio 子进程 / Streamable HTTP），
        // 把 MCP 工具包装成 mcp__<server>__<tool> 注册进 ToolRegistry（延迟加载，经 ToolSearch 发现）
        if (mcpServerConfigs == null || mcpServerConfigs.isEmpty()) {
            this.mcpManager = null;
        } else {
            System.out.println("Connecting to " + mcpServerConfigs.size() + " MCP server(s)...");
            this.mcpManager = new McpManager(mcpServerConfigs);
            var mcpResult = mcpManager.connectAll();
            for (var t : mcpResult.tools()) toolRegistry.register(t);
            this.mcpServers.addAll(mcpResult.servers());
            this.mcpErrors.addAll(mcpResult.errors());
            // 统计每个 server 注册的工具数（工具名前缀 mcp__<sanitize(server)>__），供状态面板显示
            for (var server : mcpResult.servers()) {
                String prefix = "mcp__" + McpManager.sanitizeName(server.name()) + "__";
                this.mcpToolCounts.put(server.name(),
                        (int) mcpResult.tools().stream().filter(t -> t.name().startsWith(prefix)).count());
            }
        }
        // 步骤 7：system prompt — 由 prompt 包（PromptBuilder + PromptSections）按优先级组装：
        // 身份/系统/任务/执行/工具/语气/输出/环境 8 个固定段落
        // + 已连接 MCP server 上报的 instructions
        // （自定义指令与记忆改由 Agent 的 injectLongTermMemory 以 system-reminder 注入，避免重复）
        this.systemPrompt = buildSystemPrompt();
        // LlmClient.create — 根据 provider 的 protocol（anthropic/openai）创建对应客户端
        this.client = LlmClient.create(provider, systemPrompt);
        // 步骤 8：权限裁决器 — 多层规则（Plan模式 → 安全命令 → 危险命令 → 路径沙箱 → YAML 规则 → 模式矩阵）
        this.permissionChecker = new PermissionChecker(
                PermissionMode.DEFAULT, Path.of(workDir));
        // Hook 引擎 — 生命周期钩子（默认无 hook，可通过 loadHooks 注入）
        this.hookEngine = new HookEngine();
        // 组装 Agent — 后端事件驱动的 agent 循环，UI 只消费 AgentEvent
        this.agent = new Agent(client, toolRegistry, provider);
        agent.setChecker(permissionChecker);
        agent.setHookEngine(hookEngine);
        agent.setFileHistory(fileHistory);
        agent.setWorkDir(workDir);
        agent.setMaxIterations(30);
        agent.setSessionId(sessionId);
        // ── 记忆系统接入（memory 包）──
        // 指令注入：InstructionLoader 全量发现（用户级/项目级/@include），CLAUDE.md 兼容回退
        String instructions = MemoryManager.loadInstructions(workDir);
        if (instructions == null || instructions.isEmpty()) {
            String legacy = loadCustomInstructions();
            instructions = legacy == null ? "" : legacy;
        }
        agent.setInstructions(instructions);
        this.loadedInstructions = instructions;
        // 记忆索引注入：MEMORY.md（用户级 + 项目级）作为 autoMemory 常驻上下文
        this.memoryManager = new MemoryManager(workDir);
        this.loadedMemoryReminder = memoryManager.buildSystemReminder();
        agent.setMemoryContent(loadedMemoryReminder);
        // ── 命令系统接入（command 包）──
        // 默认命令（/help /status /memory /plan …）+ .devecode/commands/ 自定义 .md 命令
        this.commandRegistry = new CommandRegistry();
        CommandLoader.registerUserCommands(commandRegistry, workDir);
        // TUI 生命周期命令：/exit 退出、/prompt 查看系统提示词
        commandRegistry.register(
                new Command("exit", "Quit DeveCode", new String[]{"quit"},
                        Command.CommandType.LOCAL_UI, false), null);
        commandRegistry.register(
                new Command("prompt", "Show current system prompt summary",
                        new String[0], Command.CommandType.LOCAL, false),
                ctx -> "System prompt (" + systemPrompt.length() + " chars · "
                        + mcpServers.size() + " MCP server(s))\n\n"
                        + (systemPrompt.length() > 800 ? systemPrompt.substring(0, 800) + "\n…" : systemPrompt));
        // ── Skill 系统接入（skill 包）──
        // 三层目录（builtin → ~/.devecode/skills → .devecode/skills），安装后可 /skill reload 热加载
        this.skillCatalog = SkillCatalog.loadCatalog(workDir);
        syncSkillCommands();
        // Skill 工具：模型经 Agent Loop 感知 skill 清单（name+description），调用激活后
        // 按模式分发——inline 正文作为工具结果返回；fork 在隔离子 Agent 执行后只回摘要
        toolRegistry.register(new SkillTool(skillCatalog, toolRegistry, this));
        agent.setSkillCatalog(skillCatalog);
        commandRegistry.register(
                new Command("skill", "Manage skills: list · reload · install <url> [--project]",
                        new String[0], Command.CommandType.LOCAL_UI, false,
                        Command.subcommands(
                                "list", "List installed skills",
                                "reload", "Hot-reload skills from disk",
                                "install", "Install a skill from GitHub <url>")),
                null);
    }

    // ═══════════════════════════════════════════════════════════════
    //  System prompt 组装（prompt 包接入）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 组装完整 system prompt：
     *   1. {@link PromptBuilder#buildSystemPrompt} 的 8 个固定段落（按优先级排序拼接）
     *   2. 已连接 MCP server 上报的 instructions（追加 "# MCP Servers" 段）
     *
     * 自定义指令（DEVECODE.md/AGENTS.md）与记忆不再拼进 system prompt：
     * 由 Agent 的 injectLongTermMemory 以 system-reminder 形式注入对话开头，
     * 每轮刷新且支持 @include 展开（见构造函数中 agent.setInstructions/setMemoryContent）。
     */
    private String buildSystemPrompt() {
        var env = PromptBuilder.detectEnvironment(provider.getModel());
        var options = new PromptBuilder.BuildOptions(null, null, null);
        String prompt = PromptBuilder.buildSystemPrompt(env, options);

        if (!mcpServers.isEmpty()) {
            var sb = new StringBuilder(prompt);
            sb.append("\n\n# MCP Servers\n\n");
            sb.append("The following MCP servers are connected. Their tools follow the naming scheme ")
              .append("mcp__<server>__<tool> and are deferred: use ToolSearch to load a tool's schema ")
              .append("before calling it.\n");
            for (var s : mcpServers) {
                sb.append("\n## ").append(s.name()).append('\n');
                if (s.instructions() != null && !s.instructions().isBlank()) {
                    sb.append(s.instructions().strip()).append('\n');
                }
            }
            prompt = sb.toString();
        }
        return prompt;
    }

    /** 加载用户自定义指令：优先工作目录下 DEVECODE.md，其次 CLAUDE.md；都不存在返回 null。 */
    private static String loadCustomInstructions() {
        for (String name : new String[]{"DEVECODE.md", "CLAUDE.md"}) {
            try {
                Path p = Path.of(System.getProperty("user.dir"), name);
                if (Files.exists(p)) {
                    String content = Files.readString(p).strip();
                    if (!content.isEmpty()) return content;
                }
            } catch (Exception ignored) {
                // 读取失败视为无自定义指令
            }
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════
    //  主循环
    // ═══════════════════════════════════════════════════════════════

    private void run() {
        terminal.enterRawMode();
        readTerminalSize();
        // 不开启 trackMouse：保留终端原生 QuickEdit / 文本选区 / I-beam 光标。
        // 滚动改用键盘（PageUp/PageDown/↑/↓）。

        // 首屏消息：横幅 + MCP 连接状态
        synchronized (messages) {
            messages.add(UIMessage.banner());
            for (var s : mcpServers) {
                messages.add(UIMessage.system(
                        GREEN + "●" + RESET + " MCP " + CYAN + s.name() + RESET
                        + GRAY + " — " + mcpToolCounts.getOrDefault(s.name(), 0) + " tool(s)" + RESET));
            }
            for (var err : mcpErrors) {
                messages.add(UIMessage.system(RED + "○ " + err + RESET));
            }
        }

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

                // 渲染（事件驱动 + 至少每秒一次的周期性刷新）
                // 周期刷新保证右侧状态面板（CPU 占用、Context 用量、API usage）实时更新，
                // 即使空闲无任何输入/流式事件也不会冻结
                long now = System.currentTimeMillis();
                if (needsRedraw || terminalResized || now - lastRenderMs >= PANEL_REFRESH_MS) {
                    terminalResized = false;
                    render();
                    needsRedraw = false;
                    lastRenderMs = now;
                }

                // 流式模式下定时刷新计时器
                if (streaming && !firstTokenReceived && pendingPermission == null) {
                    if (now - lastRenderMs >= 500) {
                        long elapsed = (System.currentTimeMillis() - streamStartMs) / 1000;
                        synchronized (messages) {
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
        // 关闭 MCP 连接（graceful shutdown stdio 子进程 / HTTP 客户端）
        if (mcpManager != null) {
            try { mcpManager.shutdown(); } catch (Exception ignored) {}
        }
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
                if (!streaming && activePicker == null) handleKeyTyped(kt.ch());
            }
            case UIEvent.PickerConfirm pc -> confirmPicker();
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
     * 提交用户消息并启动后端 Agent。
     *
     * UI 只负责：添加用户消息 → 调用 {@link Agent#run} 拿到 AgentEvent 队列 →
     * 在虚拟线程中消费事件并更新界面。LLM 调用、工具执行（权限检查/hook/并发分批）、
     * 上下文压缩、错误恢复全部由后端 Agent 完成。
     *
     * 斜杠命令（/ 开头）先经命令系统（command 包）派发：
     * LOCAL 输出结果、LOCAL_UI 触发界面动作、PROMPT 展开为提示词后继续正常提交。
     *
     * @param text 用户输入的文本
     */
    private void submitMessage(String text) {
        if (text == null || text.isBlank()) return;
        if (compacting) {
            appendMessage(UIMessage.system(YELLOW + "Compacting in progress, please wait…" + RESET));
            needsRedraw = true;
            return;
        }

        // 斜杠命令：命令系统统一派发
        if (text.trim().startsWith("/")) {
            text = text.trim();
            String prompt = handleCommand(text);
            if (prompt == null) return;   // LOCAL / LOCAL_UI / 未知命令：已处理完毕
            text = prompt;                // PROMPT 命令：展开后的提示词继续走提交流程
        }

        // 添加用户消息到 UI 列表 + ConversationManager
        synchronized (messages) { messages.add(UIMessage.user(text)); }
        conversation.addUserMessage(text);
        // session 包：持久化用户消息到 .devecode/sessions/<sessionId>.jsonl
        SessionManager.saveMessage(workDir, sessionId, "user", text);
        inputBuffer.setLength(0);
        cursorCol = 0;
        cursorRow = 0;
        scrollToBottom();

        // memory 包：记忆召回 prefetch —— 与主 LLM 调用并行，
        // Agent 在首轮工具执行后非阻塞检查 future 并把召回内容注入为 system-reminder
        CompletableFuture<String> recallFuture = new CompletableFuture<>();
        final String query = text;
        Thread.startVirtualThread(() -> {
            try {
                recallFuture.complete(recallMemories(query));
            } catch (Exception e) {
                recallFuture.complete("");  // 召回失败 → 无记忆注入，不影响主流程
            }
        });
        agent.setMemoryRecallFuture(recallFuture);

        // 启动流式状态
        streaming = true;
        streamAccum.setLength(0);
        thinkingAccum.setLength(0);
        firstTokenReceived = false;
        streamStartMs = System.currentTimeMillis();
        synchronized (messages) { messages.add(UIMessage.streaming("Imagining… (0s)")); }
        needsRedraw = true;

        Thread.startVirtualThread(() -> {
            try {
                BlockingQueue<AgentEvent> events = agent.run(conversation);
                consumeAgentEvents(events);
            } catch (InterruptedException e) {
                appendMessage(UIMessage.error("Request interrupted."));
            } catch (Exception e) {
                appendMessage(UIMessage.error("UI error: " + e.getMessage()));
            } finally {
                streaming = false;
                pendingPermission = null;
                needsRedraw = true;
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════
    //  命令提示（输入 / 时：实时过滤 + ↑↓ 选择 + Tab 补全 + Enter 执行）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 当前输入对应的命令候选列表，三种激活形态：
     * <ol>
     *   <li>命令名阶段（"/<token>"，无空格）：{@link CommandRegistry#search} 前缀过滤
     *       （匹配命令名与别名，忽略大小写）。裸 "/" 时 cacheKey 为空串——候选照常
     *       展示，但 Enter 不会自动执行（见 handleEnter）；</li>
     *   <li>子命令阶段（"/<cmd> <partial>"，partial 不含空格）：命令已确定且有注册
     *       subcommands 时，按前缀过滤子命令，合成为 "/<cmd> <sub>" 形式的候选；</li>
     *   <li>深层参数阶段（如 "/skill install <url>"）：无候选，Enter 原样提交整行，
     *       保证参数不被候选展开逻辑覆盖。</li>
     * </ol>
     *
     * 候选 key 变化时惰性重置选中索引为 0（渲染与键盘线程都会经过这里，
     * 无需在每个输入修改点埋点重置）。
     */
    private List<Command> currentCommandCandidates() {
        String text = inputBuffer.toString();
        String cacheKey;
        List<Command> result;
        if (!text.startsWith("/") || text.contains("\n") || text.contains("\t")) {
            cacheKey = null;
            result = List.of();
        } else {
            int sp = text.indexOf(' ');
            if (sp < 0) {
                // 命令名阶段：取 "/" 后的 token 做前缀过滤
                cacheKey = text.substring(1);
                result = commandRegistry.search(cacheKey);
            } else {
                String cmdName = text.substring(1, sp);
                String partial = text.substring(sp + 1);
                if (partial.contains(" ")) {
                    // 深层参数阶段：不提示也不重写，Enter 原样提交
                    cacheKey = null;
                    result = List.of();
                } else {
                    // 子命令阶段："/skill " 空尾也展示全部子命令
                    cacheKey = cmdName + " " + partial;
                    result = subcommandCandidates(cmdName, partial);
                }
            }
        }
        if (!Objects.equals(cacheKey, hintTokenCache)) {
            hintTokenCache = cacheKey;
            commandHintIndex = 0;
        }
        return result;
    }

    /**
     * 子命令阶段候选：命令已确定时，把注册的 subcommands 按前缀过滤，
     * 合成为 "/<cmd> <sub>" 形式的候选条目（复用命令提示面板的渲染与导航）。
     */
    private List<Command> subcommandCandidates(String cmdName, String partial) {
        Optional<Command> cmdOpt = commandRegistry.find(cmdName);
        if (cmdOpt.isEmpty() || cmdOpt.get().subcommands().isEmpty()) {
            return List.of();
        }
        Command parent = cmdOpt.get();
        String lower = partial.toLowerCase(Locale.ROOT);
        List<Command> result = new ArrayList<>();
        for (var entry : parent.subcommands().entrySet()) {
            if (entry.getKey().toLowerCase(Locale.ROOT).startsWith(lower)) {
                result.add(new Command(parent.name() + " " + entry.getKey(), entry.getValue(),
                        new String[0], parent.type(), false, parent.skill(), Map.of()));
            }
        }
        return result;
    }

    /** 命令提示导航：循环移动（与全屏选择器一致，到顶再按上跳到最后一条）。 */
    private void moveCommandHint(int delta) {
        var cands = currentCommandCandidates();
        if (cands.isEmpty()) return;
        int n = cands.size();
        commandHintIndex = (commandHintIndex + delta + n) % n;
        needsRedraw = true;
    }

    /** Tab：把选中候选补全到输入框（命令名后置一个空格，便于继续输入参数）。 */
    private void completeCommandHint() {
        var cands = currentCommandCandidates();
        if (cands.isEmpty()) return;
        int idx = Math.min(Math.max(commandHintIndex, 0), cands.size() - 1);
        replaceCommandToken(cands.get(idx).name() + " ");
        needsRedraw = true;
    }

    /**
     * 用完整命令名替换输入中的命令 token。
     * 提示只在单行、无空格时激活，输入内容就是 "/<token>"，可安全整体重建。
     */
    private void replaceCommandToken(String replacement) {
        inputBuffer.setLength(0);
        inputBuffer.append('/').append(replacement);
        cursorCol = replacement.length() + 1;
        cursorRow = 0;
    }

    // ═══════════════════════════════════════════════════════════════
    //  斜杠命令（command 包接入）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 斜杠命令统一入口：解析 name/args → registry 查找 → 按命令类型派发。
     *
     * @return PROMPT 命令展开后的提示词（继续提交给 Agent）；其余情况返回 null
     */
    private String handleCommand(String input) {
        String body = input.substring(1);
        String[] parts = body.split("\\s+", 2);
        String name = parts[0];
        String args = parts.length > 1 ? parts[1] : "";

        // 输入框统一清空（旧实现对 /mcp 等命令存在残留 bug）
        inputBuffer.setLength(0);
        cursorCol = 0;
        cursorRow = 0;

        Optional<Command> found = commandRegistry.find(name);
        if (found.isEmpty()) {
            appendMessage(UIMessage.system(
                    RED + "Unknown command: /" + name + RESET + GRAY
                            + " — type /help for available commands" + RESET));
            scrollToBottom();
            needsRedraw = true;
            return null;
        }
        Command cmd = found.get();
        CommandContext ctx = buildCommandContext(args);

        switch (cmd.type()) {
            case LOCAL -> {
                String output = commandRegistry.execute(name, ctx);
                String echo = CYAN + "/" + cmd.name() + RESET
                        + (args.isBlank() ? "" : GRAY + " " + args + RESET);
                appendMessage(UIMessage.system(echo));
                if (output != null && !output.isBlank()) {
                    appendMessage(UIMessage.system(UIMessage.grayLines(output.strip())));
                }
                scrollToBottom();
                needsRedraw = true;
                return null;
            }
            case LOCAL_UI -> {
                dispatchUiCommand(cmd.name(), args);
                return null;
            }
            case PROMPT -> {
                String prompt = commandRegistry.execute(name, ctx);
                if (prompt == null || prompt.isBlank()) {
                    appendMessage(UIMessage.system(YELLOW
                            + "Command /" + cmd.name() + " produced an empty prompt." + RESET));
                    needsRedraw = true;
                    return null;
                }
                // UI 显示命令回显（真实展开内容过长，不重复展示）
                appendMessage(UIMessage.system(CYAN + "/" + cmd.name() + RESET
                        + (args.isBlank() ? "" : GRAY + " " + args + RESET)
                        + GRAY + " → prompt sent" + RESET));
                needsRedraw = true;
                return prompt;
            }
        }
        return null;
    }

    /** LOCAL_UI 命令派发：命令层与 UI 层职责分离，界面动作在此实现。 */
    private void dispatchUiCommand(String name, String args) {
        switch (name) {
            case "exit" -> running = false;
            case "clear" -> doClear();
            case "compact" -> doCompact();
            case "plan" -> doPlan();
            case "resume" -> doResume(args);
            case "rewind" -> doRewind();
            case "skill" -> doSkill(args);
            default -> appendMessage(UIMessage.system(GRAY
                    + "Command /" + name + " is not available in this UI yet." + RESET));
        }
        needsRedraw = true;
    }

    /**
     * 装配命令运行时上下文：全部用 Supplier/IntSupplier/Runnable 惰性求值，
     * handler 执行时才读取最新状态（命令层因此不依赖 TUI 具体类）。
     */
    private CommandContext buildCommandContext(String args) {
        return new CommandContext(
                args,
                workDir,
                provider.getModel(),
                () -> permissionChecker.getMode().name().toLowerCase(Locale.ROOT),
                () -> toolRegistry.getAllSchemas(provider.getProtocol()).size(),
                () -> new int[]{usageInTokens, usageOutTokens},
                () -> memoryManager.getMemories(),
                () -> memoryManager.clear(),
                () -> sessionId + " · " + conversation.size() + " message(s)",
                this::buildMcpInfo,
                () -> permissionChecker.isSandboxEnabled() ? "enabled" : "disabled",
                this::switchSandbox
        );
    }

    /** /mcp 与状态展示共用的 MCP 连接摘要 */
    private String buildMcpInfo() {
        if (mcpServers.isEmpty() && mcpErrors.isEmpty()) return "";
        var sb = new StringBuilder();
        for (var s : mcpServers) {
            sb.append(s.name()).append(": ")
              .append(mcpToolCounts.getOrDefault(s.name(), 0)).append(" tool(s)\n");
        }
        for (var err : mcpErrors) {
            sb.append("error: ").append(err).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /** /sandbox 模式切换：1=沙箱+自动放行 2=沙箱+常规权限 3=关闭 */
    private void switchSandbox(Integer mode) {
        if (mode == null) return;
        switch (mode) {
            case 1 -> {
                permissionChecker.setSandboxEnabled(true);
                permissionChecker.setMode(PermissionMode.ACCEPT_EDITS);
            }
            case 2 -> {
                permissionChecker.setSandboxEnabled(true);
                permissionChecker.setMode(PermissionMode.DEFAULT);
            }
            case 3 -> permissionChecker.setSandboxEnabled(false);
            default -> { /* 无效选项由命令层提示 */ }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  记忆召回（memory 包：查询时按需注入相关记忆）
    // ═══════════════════════════════════════════════════════════════

    /**
     * memory 包：查询时记忆召回 —— 扫描两级记忆目录，selector LLM 挑选
     * 最多 5 条相关记忆，返回渲染后的 system-reminder 文本（空串 = 无相关记忆）。
     * 已召回过的记忆（surfacedMemories）不再重复注入。
     */
    private String recallMemories(String query) {
        List<MemoryRecall.RelevantMemory> selected = MemoryRecall.findRelevantMemories(
                query,
                memoryManager.userMemDir(),
                memoryManager.projectMemDir(),
                null,                       // recentTools：TUI 暂不跟踪近期工具
                surfacedMemories,
                this::selectMemories);
        for (var m : selected) {
            surfacedMemories.add(m.path());
        }
        return MemoryRecall.renderReminder(selected);
    }

    /**
     * MemoryRecall.SelectorFn 实现：专用侧查询 —— 把 selector 提示词与
     * 候选清单拼成一次性对话，经主 client 流式调用取回原始回复。
     */
    private String selectMemories(String systemPrompt, String userMessage) {
        ConversationManager selConv = new ConversationManager();
        selConv.addUserMessage(systemPrompt + "\n\n" + userMessage);
        BlockingQueue<StreamEvent> events = client.stream(selConv, null);
        var sb = new StringBuilder();
        try {
            while (true) {
                StreamEvent event = events.take();
                if (event instanceof StreamEvent.TextDelta td) {
                    sb.append(td.text());
                } else if (event instanceof StreamEvent.StreamEnd
                        || event instanceof StreamEvent.Error) {
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return sb.toString();
    }

    /** Markdown 渲染的安全包装（渲染失败回退原文，用于会话恢复时的历史消息展示）。 */
    private static String renderMarkdownSafe(String text) {
        if (text == null || text.isEmpty()) return "";
        try {
            return MarkdownRenderer.render(text);
        } catch (Exception e) {
            return text;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  LOCAL_UI 命令实现（/clear /compact /plan /resume /rewind）
    // ═══════════════════════════════════════════════════════════════

    /** /clear — 清空对话并开启新会话（session 包）。 */
    private void doClear() {
        startNewSession();
        appendMessage(UIMessage.system(CYAN + "✦ New session started" + RESET
                + GRAY + " — conversation cleared · " + sessionId + RESET));
        scrollToBottom();
    }

    /** 开启新会话：新 sessionId + 空对话 + 独立的文件快照历史。 */
    private void startNewSession() {
        sessionId = SessionManager.newId();
        agent.setSessionId(sessionId);
        conversation.truncateTo(0);
        synchronized (messages) {
            messages.clear();
            messages.add(UIMessage.banner());
        }
        usageInTokens = 0;
        usageOutTokens = 0;
        surfacedMemories.clear();
        // 新会话使用独立的 .devecode/file-history/<sessionId>/ 快照目录
        this.fileHistory = new FileHistory(workDir, sessionId);
        attachFileHistoryToTools();
        agent.setFileHistory(fileHistory);
        scrollToBottom();
        needsRedraw = true;
    }

    /** 向编辑类工具注入最新的 FileHistory / FileStateCache（会话切换后重绑）。 */
    private void attachFileHistoryToTools() {
        ((com.agent.tool.impl.ReadFileTool) toolRegistry.getTool("ReadFile")).setFileStateCache(fileStateCache);
        ((com.agent.tool.impl.EditFileTool) toolRegistry.getTool("EditFile")).setFileHistory(fileHistory);
        ((com.agent.tool.impl.EditFileTool) toolRegistry.getTool("EditFile")).setFileStateCache(fileStateCache);
        ((com.agent.tool.impl.WriteFileTool) toolRegistry.getTool("WriteFile")).setFileHistory(fileHistory);
        ((com.agent.tool.impl.WriteFileTool) toolRegistry.getTool("WriteFile")).setFileStateCache(fileStateCache);
    }

    /** /compact — 手动压缩上下文（后台执行，避免阻塞渲染循环）。 */
    private void doCompact() {
        if (streaming) {
            appendMessage(UIMessage.system(YELLOW
                    + "Cannot compact while streaming — wait or press Esc to interrupt." + RESET));
            return;
        }
        if (conversation.size() == 0) {
            appendMessage(UIMessage.system(GRAY + "Nothing to compact — conversation is empty." + RESET));
            return;
        }
        compacting = true;
        appendMessage(UIMessage.system(CYAN + "⤾ Compacting…" + RESET));
        scrollToBottom();
        needsRedraw = true;
        Thread.startVirtualThread(() -> {
            String report;
            try {
                report = ContextCompactor.forceCompact(
                        conversation, client, provider.resolvedContextWindow(),
                        workDir, sessionId, agent.getRecoveryState(),
                        toolRegistry.getAllSchemas(provider.getProtocol()), null);
            } catch (Exception e) {
                report = "";
                appendMessage(UIMessage.error("Compact failed: " + e.getMessage()));
            } finally {
                compacting = false;
            }
            if (report != null && !report.isEmpty()) {
                // session 包：写入压缩边界书签，resume 时据此跳过边界之前的旧记录
                saveCompactBoundaryFromConversation();
                appendMessage(UIMessage.system(
                        CYAN + "⤾ Context compacted" + RESET + GRAY + ": " + report + RESET));
            } else {
                appendMessage(UIMessage.system(GRAY
                        + "Nothing to compact — conversation too short." + RESET));
            }
            needsRedraw = true;
        });
    }

    /**
     * 把当前对话状态写入 CompactBoundary 书签：
     * 跳过开头的 system-reminder（压缩后 Agent 会重新注入），首条普通消息即摘要，
     * 其余为保留消息。rebuildConversation 重放时据此还原压缩后的状态。
     */
    private void saveCompactBoundaryFromConversation() {
        List<com.agent.llm.Message> msgs = conversation.getMessages();
        int i = 0;
        while (i < msgs.size() && msgs.get(i).getContent() != null
                && msgs.get(i).getContent().startsWith("<system-reminder>")) {
            i++;
        }
        if (i >= msgs.size()) return;
        String summary = msgs.get(i).getContent();
        List<SessionManager.KeepMessage> keep = new ArrayList<>();
        for (int j = i + 1; j < msgs.size(); j++) {
            keep.add(new SessionManager.KeepMessage(msgs.get(j).getRole(), msgs.get(j).getContent()));
        }
        SessionManager.saveCompactBoundary(workDir, sessionId, summary, keep);
    }

    /** /plan — 切换计划模式（只读，AI 只能调研和写计划文件）。 */
    private void doPlan() {
        if (permissionChecker.getMode() == PermissionMode.PLAN) {
            permissionChecker.setMode(PermissionMode.DEFAULT);
            appendMessage(UIMessage.system(YELLOW + "⌥ Plan mode off" + RESET
                    + GRAY + " — back to default permissions" + RESET));
        } else {
            permissionChecker.setMode(PermissionMode.PLAN);
            appendMessage(UIMessage.system(GREEN + "⌥ Plan mode on" + RESET + GRAY
                    + " — read-only, AI drafts its plan into .devecode/plans/plan.md" + RESET));
        }
        scrollToBottom();
    }

    /** /resume — 恢复历史会话（session 包）：带搜索参数直接匹配，多结果弹选择器。 */
    private void doResume(String args) {
        if (streaming) {
            appendMessage(UIMessage.system(YELLOW
                    + "Cannot resume while streaming." + RESET));
            return;
        }
        List<SessionManager.SessionInfo> sessions = SessionManager.listSessions(workDir);
        if (args != null && !args.isBlank()) {
            sessions = sessions.stream()
                    .filter(s -> SessionManager.matchesSearch(s, args))
                    .toList();
        }
        if (sessions.isEmpty()) {
            appendMessage(UIMessage.system(GRAY
                    + "No saved sessions found in .devecode/sessions/" + RESET));
            scrollToBottom();
            return;
        }
        if (sessions.size() == 1 && args != null && !args.isBlank()) {
            // 搜索词唯一命中：直接恢复
            resumeSession(sessions.getFirst());
            return;
        }
        // 多个会话：打开全屏选择器
        List<PickerItem> items = new ArrayList<>();
        for (var s : sessions) {
            String first = s.firstMessage().isEmpty() ? "(no messages)" : s.firstMessage();
            String meta = "%d msg · %s · %s".formatted(
                    s.messageCount(), SessionManager.formatFileSize(s.fileSize()),
                    SessionManager.formatRelativeTime(s.modTime()));
            items.add(new PickerItem(s.id(), first, meta, s));
        }
        openPicker("session", "Resume Session", items);
    }

    /** 恢复指定会话：重建对话历史 + 重建 UI 消息 + 重绑文件快照目录。 */
    private void resumeSession(SessionManager.SessionInfo info) {
        List<SessionManager.SessionMessage> msgs = SessionManager.loadSession(workDir, info.id());
        if (msgs.isEmpty()) {
            appendMessage(UIMessage.system(RED + "Session has no messages: " + info.id() + RESET));
            return;
        }
        // session 包：rebuildConversation 处理 CompactBoundary（有书签 → 摘要+保留消息，无 → 全量重放）
        ConversationManager rebuilt = SessionManager.rebuildConversation(msgs);
        conversation.truncateTo(0);
        conversation.getMessagesMutable().addAll(rebuilt.getMessages());

        sessionId = info.id();
        agent.setSessionId(sessionId);
        this.fileHistory = new FileHistory(workDir, sessionId);
        attachFileHistoryToTools();
        agent.setFileHistory(fileHistory);

        // 重建 UI 消息列表
        synchronized (messages) {
            messages.clear();
            messages.add(UIMessage.banner());
            messages.add(UIMessage.system(CYAN + "⤺ Resumed session " + RESET
                    + GRAY + info.id() + " · " + msgs.size() + " record(s)" + RESET));
            for (var m : msgs) {
                if (m.isCompactBoundary()) continue;
                if ("assistant".equals(m.role())) {
                    messages.add(UIMessage.assistant(renderMarkdownSafe(m.content()), null));
                } else if ("user".equals(m.role())) {
                    messages.add(UIMessage.user(m.content()));
                }
                // system 角色记录（压缩边界等）不展示
            }
        }
        scrollToBottom();
        needsRedraw = true;
    }

    /** /rewind — 回退到某一轮对话结束时的文件检查点（FileHistory 快照选择器）。 */
    private void doRewind() {
        if (streaming) {
            appendMessage(UIMessage.system(YELLOW
                    + "Cannot rewind while streaming." + RESET));
            return;
        }
        var snaps = fileHistory.getSnapshots();
        if (snaps.isEmpty()) {
            appendMessage(UIMessage.system(GRAY
                    + "No checkpoints yet — snapshots are taken after each AI turn that edits files." + RESET));
            scrollToBottom();
            return;
        }
        // 最新快照在前（回退通常想去最近的检查点）
        List<PickerItem> items = new ArrayList<>();
        for (int i = snaps.size() - 1; i >= 0; i--) {
            var snap = snaps.get(i);
            String title = (snap.userText() == null || snap.userText().isBlank())
                    ? "(turn checkpoint)" : snap.userText();
            String meta = "%d file(s) · %s".formatted(
                    snap.backups().size(),
                    snap.timestamp().atZone(ZoneId.systemDefault())
                            .format(DateTimeFormatter.ofPattern("MM-dd HH:mm")));
            items.add(new PickerItem(String.valueOf(i), title, meta, i));
        }
        openPicker("snapshot", "Rewind to Checkpoint", items);
    }

    /** 回退到指定快照：还原文件 + 截断对话到检查点。 */
    private void rewindTo(int snapshotIndex) {
        var snaps = fileHistory.getSnapshots();
        if (snapshotIndex < 0 || snapshotIndex >= snaps.size()) return;
        var snap = snaps.get(snapshotIndex);
        List<String> changed = fileHistory.rewind(snapshotIndex);
        // 对话回退到检查点对应的消息位置
        if (snap.messageIndex() >= 0 && snap.messageIndex() <= conversation.size()) {
            conversation.truncateTo(snap.messageIndex());
        }
        appendMessage(UIMessage.system(YELLOW + "⏪ Rewound" + RESET + GRAY + " — "
                + changed.size() + " file(s) restored, conversation truncated to checkpoint" + RESET));
        scrollToBottom();
        needsRedraw = true;
    }

    /** /skill — skill 管理（skill 包）：/skill list 列出 · /skill reload 热加载 · /skill install 安装。 */
    private void doSkill(String args) {
        String[] parts = (args == null ? "" : args.trim()).split("\\s+");
        String sub = parts[0].isEmpty() ? "" : parts[0];
        switch (sub) {
            case "list" -> doSkillList();
            case "reload" -> doSkillReload();
            case "install" -> doSkillInstall(parts);
            default -> printSkillUsage();
        }
    }

    /** /skill list — 列出所有已安装 skill 名（含来源层级：builtin / user / project）。 */
    private void doSkillList() {
        var skills = skillCatalog.getSkills();
        String out;
        if (skills.isEmpty()) {
            out = "No skills installed.\n\n"
                    + "Add skills to .devecode/skills/<skill-name>/SKILL.md\n"
                    + "or install one from GitHub: /skill install <url>";
        } else {
            var sb = new StringBuilder("Installed skills (%d):\n".formatted(skills.size()));
            for (var name : skills.keySet()) {
                sb.append("  • ").append(name);
                String src = skillCatalog.source(name);
                if (!src.isEmpty()) sb.append(" (").append(src).append(")");
                sb.append('\n');
            }
            out = sb.toString();
        }
        appendMessage(UIMessage.system(UIMessage.grayLines(out.strip())));
        scrollToBottom();
        needsRedraw = true;
    }

    /** /skill reload — 从磁盘热重载 skill 目录并同步命令注册。 */
    private void doSkillReload() {
        skillCatalog.reload(workDir);
        syncSkillCommands();
        appendMessage(UIMessage.system(UIMessage.grayLines(
                "Skills reloaded. " + skillCatalog.list().size() + " skill(s) available.")));
        scrollToBottom();
        needsRedraw = true;
    }

    private void printSkillUsage() {
        appendMessage(UIMessage.system(GRAY
                + "Usage: /skill list | reload | install <url> [--project]\n"
                + "  list        show installed skill names\n"
                + "  reload      hot-reload skills from disk\n"
                + "  install <url> [--project]\n"
                + "    url     github.com/<owner>/<repo>[.git] · github.com/…/tree/<ref>/<subpath> · skills.sh/<owner>/<repo>/<name>\n"
                + "    default installs to ~/.devecode/skills (user level); --project installs to .devecode/skills" + RESET));
        scrollToBottom();
        needsRedraw = true;
    }

    /** /skill install — 从 GitHub 安装 skill，默认装到用户级 ~/.devecode/skills。 */
    private void doSkillInstall(String[] parts) {
        if (parts.length < 2) {
            printSkillUsage();
            return;
        }
        String url = parts[1];
        boolean projectScope = false;
        for (int i = 2; i < parts.length; i++) {
            if ("--project".equals(parts[i])) projectScope = true;
        }
        if (skillInstalling) {
            appendMessage(UIMessage.system(YELLOW
                    + "A skill install is already in progress, please wait…" + RESET));
            return;
        }

        // 先解析 URL（同步，本地操作）：格式错误立即反馈，不进后台线程
        SkillSource src;
        try {
            src = SkillInstaller.parseSkillURL(url);
        } catch (IllegalArgumentException e) {
            appendMessage(UIMessage.error("Invalid skill URL: " + e.getMessage()));
            scrollToBottom();
            return;
        }

        skillInstalling = true;
        appendMessage(UIMessage.system(CYAN + "⤓ Installing skill " + RESET
                + GRAY + src.owner() + "/" + src.repo()
                + (src.subpath().isEmpty() ? "" : "/" + src.subpath()) + RESET));
        scrollToBottom();
        needsRedraw = true;

        final boolean toProject = projectScope;
        Thread.startVirtualThread(() -> {
            try {
                String root = toProject
                        ? Path.of(workDir, ".devecode", "skills").toString()
                        : SkillInstaller.userSkillsRoot();
                SkillInstallReport report = new SkillInstaller().install(src, root);
                skillCatalog.reload(workDir);  // 立即可用，无需重启
                syncSkillCommands();           // 新 skill 同步为可调用的命令
                appendMessage(UIMessage.system(
                        GREEN + "✔ Skill installed: " + RESET + WHITE + report.skillName() + RESET
                        + GRAY + " · " + report.fileCount() + " file(s)"
                        + (report.skippedFiles() > 0
                                ? " · " + report.skippedFiles() + " large file(s) skipped" : "")
                        + " · " + formatTokens((int) report.totalBytes()) + "B → " + report.targetDir()
                        + (toProject ? " (project)" : " (user)") + RESET));
            } catch (Exception e) {
                appendMessage(UIMessage.error("Skill install failed: "
                        + (e.getMessage() == null ? e.toString() : e.getMessage())));
            } finally {
                skillInstalling = false;
                needsRedraw = true;
            }
            scrollToBottom();
        });
    }

    /**
     * 将 skillCatalog 中全部 skill 同步注册为 PROMPT 命令：
     * 出现在命令提示面板与 /help 列表中（带 [skill] 标识），可直接 /<skill-name> 调用。
     * 与静态命令重名的 skill 会被跳过（静态命令优先）。
     * catalog 变更（reload / install）后需重新调用。
     * fork 模式的 skill 在后台隔离执行（返回摘要进主对话），inline 模式经
     * executeInline 激活（allowedTools 过滤生效）后作为 prompt 提交。
     */
    private void syncSkillCommands() {
        commandRegistry.clearSkillCommands();
        for (var meta : skillCatalog.list()) {
            String skillName = meta.name();
            Command cmd = new Command(skillName, firstLine(meta.description()),
                    new String[0], Command.CommandType.PROMPT, false, true);
            commandRegistry.registerSkill(cmd, ctx -> {
                var opt = skillCatalog.getFull(skillName);
                if (opt.isEmpty()) return "Skill not found: " + skillName;
                if ("fork".equals(opt.get().meta().mode())) {
                    // fork：后台隔离执行，摘要返回主对话；返回 null 表示命令已处理完毕
                    runForkSkillFromCommand(opt.get(), ctx.args());
                    return null;
                }
                // inline：激活（含 allowedTools 过滤设置）后作为 prompt 注入当前对话
                return SkillExecutor.executeInline(opt.get(), ctx.args(), this);
            });
        }
    }

    /**
     * 用户经 /skillname 直接调用 fork 模式 skill：后台隔离子 Agent 执行，
     * 结果摘要以 assistant 消息形式返回主对话并持久化到 session。
     */
    private void runForkSkillFromCommand(SkillCatalog.Skill skill, String args) {
        Thread.startVirtualThread(() -> {
            String skillName = skill.meta().name();
            // streaming=true：fork 执行期间锁定输入 + 启用权限询问的 y/n 应答
            streaming = true;
            try {
                String summary = SkillExecutor.dispatch(skill, args, this, toolRegistry);
                // 摘要返回主对话（fork 结果不进父上下文经子 Agent 隔离，
                // 但用户主动调用的结果对主对话可见）
                synchronized (messages) {
                    messages.add(UIMessage.assistant(renderMarkdownSafe(summary), null));
                }
                conversation.addAssistantMessage(summary);
                SessionManager.saveMessage(workDir, sessionId, "assistant", summary);
            } catch (Exception e) {
                appendMessage(UIMessage.error("Skill fork failed: "
                        + (e.getMessage() == null ? e.toString() : e.getMessage())));
            } finally {
                streaming = false;
                pendingPermission = null;
                scrollToBottom();
                needsRedraw = true;
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════
    //  SkillForkHost：fork 模式宿主（inline 部分委托给主 Agent）
    // ═══════════════════════════════════════════════════════════════

    /** inline skill 激活通知（SkillHost）——委托主 Agent。 */
    @Override
    public void activateSkill(String name, String body) {
        agent.activateSkill(name, body);
    }

    /** inline skill 的 allowedTools 过滤（SkillHost）——委托主 Agent。 */
    @Override
    public void setToolFilter(Predicate<String> filter) {
        agent.setToolFilter(filter);
    }

    /** 父对话消息快照（不是引用）：子 Agent 启动那一刻的冻结版本。 */
    @Override
    public List<Message> snapshotParentMessages() {
        return new ArrayList<>(conversation.getMessages());
    }

    /**
     * fork 模式核心：跑一个隔离子 Agent（独立 ConversationManager + 过滤后的工具 registry），
     * 只返回最终文本摘要。子 Agent 的事件以 fork 前缀（│）渲染进当前界面，
     * 权限询问复用主输入线程的 y/n 应答。
     */
    @Override
    public String runSubAgent(String skillName, String body, List<Message> seed,
                               String model, ToolRegistry tools) {
        if (forkDepth.get() >= 2) {
            return "Error: fork depth limit reached (max 2) — nested fork skills are not allowed";
        }
        forkDepth.incrementAndGet();
        appendMessage(UIMessage.system(
                CYAN + "⑂" + RESET + " fork skill '" + skillName + "' started in isolated context"
                + GRAY + " (parent conversation untouched)" + RESET));
        scrollToBottom();
        needsRedraw = true;
        var finalText = new StringBuilder();
        try {
            // 1. 隔离会话：seed 为父对话快照副本（fork_context: none/recent/full 控制继承量）
            var subConv = new ConversationManager();
            subConv.getMessagesMutable().addAll(seed);
            subConv.addUserMessage(body);
            // 2. 子 Agent：共享 client/checker/hook/文件历史，独立 conv + registry
            var forkCfg = forkProviderConfig(model);
            var subClient = (forkCfg == provider) ? client : LlmClient.create(forkCfg, systemPrompt);
            var sub = new Agent(subClient, tools, forkCfg);
            sub.setChecker(permissionChecker);
            sub.setHookEngine(hookEngine);
            sub.setFileHistory(fileHistory);
            sub.setWorkDir(workDir);
            sub.setMaxIterations(30);
            sub.setInstructions(loadedInstructions);
            sub.setMemoryContent(loadedMemoryReminder);
            sub.setSkillCatalog(skillCatalog);
            // 3. 消费子 Agent 事件流：进度渲染 + 最终文本收集
            BlockingQueue<AgentEvent> queue = sub.run(subConv);
            while (true) {
                AgentEvent ev = queue.take();
                if (ev instanceof AgentEvent.StreamText st) {
                    finalText.append(st.text());
                } else if (ev instanceof AgentEvent.ToolUseEvent tu) {
                    appendMessage(UIMessage.system(GRAY + "│ ⚙ " + tu.toolName() + RESET));
                    needsRedraw = true;
                } else if (ev instanceof AgentEvent.ToolResultEvent tr) {
                    appendMessage(UIMessage.system(GRAY + "│ " + (tr.isError() ? "✗" : "✔") + " "
                            + tr.toolName() + " (" + String.format("%.1f", tr.elapsed()) + "s)" + RESET));
                    needsRedraw = true;
                } else if (ev instanceof AgentEvent.PermissionRequestEvent pr) {
                    // 权限询问复用主输入线程的 y/n 应答（inputLoop 在 streaming 期间生效）
                    handlePermissionRequest(pr);
                    try {
                        pr.future().get(5, TimeUnit.MINUTES);
                    } catch (Exception e) {
                        pr.future().complete(PermissionResponse.DENY);
                    }
                } else if (ev instanceof AgentEvent.ErrorEvent e) {
                    appendMessage(UIMessage.error("│ " + e.message()));
                    needsRedraw = true;
                } else if (ev instanceof AgentEvent.LoopComplete lc) {
                    appendMessage(UIMessage.system(GRAY + "⑂ fork '" + skillName
                            + "' done in " + lc.totalTurns() + " turn(s)" + RESET));
                    scrollToBottom();
                    needsRedraw = true;
                    return finalText.toString();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return finalText.toString();
        } catch (Exception e) {
            return "Error: fork sub-agent failed: "
                    + (e.getMessage() == null ? e.toString() : e.getMessage());
        } finally {
            forkDepth.decrementAndGet();
        }
    }

    /** fork 子 Agent 的 provider 配置：model 为空 = 复用主 provider。 */
    private ProviderConfig forkProviderConfig(String model) {
        if (model == null || model.isBlank() || model.equals(provider.getModel())) {
            return provider;
        }
        var cfg = new ProviderConfig(provider.getName(), provider.getProtocol(),
                provider.getBaseUrl(), model, provider.getApiKey(), provider.isThinking());
        cfg.setContextWindow(provider.getContextWindow());
        cfg.setMaxOutputTokens(provider.getMaxOutputTokens());
        return cfg;
    }

    /** 取多行文本的第一个非空行（skill 描述常为多行 YAML，命令面板需单行）。 */
    private static String firstLine(String s) {
        if (s == null) return "";
        for (String line : s.split("\n")) {
            String t = line.strip();
            if (!t.isEmpty()) return t;
        }
        return "";
    }

    // ═══════════════════════════════════════════════════════════════
    //  全屏选择器（/resume 会话列表、/rewind 快照列表）
    // ═══════════════════════════════════════════════════════════════

    /** 打开全屏选择器（覆盖正常对话界面，Esc/q 取消，Enter 确认）。 */
    private void openPicker(String kind, String title, List<PickerItem> items) {
        activePicker = new PickerState(kind, title, List.copyOf(items));
        pickerIndex = 0;
        needsRedraw = true;
    }

    /** 选择器导航：循环移动（到顶再按上 → 跳到最后一条，反之亦然）。 */
    private void movePicker(int delta) {
        PickerState p = activePicker;
        if (p == null || p.items().isEmpty()) return;
        int n = p.items().size();
        pickerIndex = (pickerIndex + delta + n) % n;
        needsRedraw = true;
    }

    /** 确认选择器当前选中项（主线程执行，避免与渲染循环竞争）。 */
    private void confirmPicker() {
        PickerState p = activePicker;
        activePicker = null;
        if (p == null || p.items().isEmpty()) return;
        int idx = Math.min(Math.max(pickerIndex, 0), p.items().size() - 1);
        PickerItem item = p.items().get(idx);
        switch (p.kind()) {
            case "session" -> {
                if (item.payload() instanceof SessionManager.SessionInfo info) {
                    resumeSession(info);
                }
            }
            case "snapshot" -> {
                if (item.payload() instanceof Integer snapIdx) {
                    rewindTo(snapIdx);
                }
            }
            default -> { }
        }
        needsRedraw = true;
    }

    // ═══════════════════════════════════════════════════════════════
    //  AgentEvent 消费（后端 → 呈现端）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 消费后端 Agent 抛出的 AgentEvent 流，直到 LoopComplete。
     * 运行在 submitMessage 创建的虚拟线程上。
     */
    private void consumeAgentEvents(BlockingQueue<AgentEvent> events) throws InterruptedException {
        // 本轮已展示过"调用中"的工具 ID（ToolUseEvent 会发两次：
        // ToolCallStart 携带空参数，ToolCallComplete 携带完整参数）
        Set<String> startedCalls = new HashSet<>();

        while (true) {
            AgentEvent event = events.take();
            switch (event) {
                case AgentEvent.StreamText st -> {
                    ensureStreamingPlaceholder();
                    if (!firstTokenReceived) {
                        firstTokenReceived = true;
                        firstTokenMs = System.currentTimeMillis();
                    }
                    streamAccum.append(st.text());
                    synchronized (messages) { updateStreamingMessage(); }
                    needsRedraw = true;
                }
                case AgentEvent.ThinkingText tt -> {
                    ensureStreamingPlaceholder();
                    thinkingAccum.append(tt.text());
                    synchronized (messages) { updateStreamingMessage(); }
                    needsRedraw = true;
                }
                case AgentEvent.ThinkingComplete tc -> {
                    synchronized (messages) { updateStreamingMessage(); }
                    needsRedraw = true;
                }
                case AgentEvent.ToolUseEvent tu -> handleToolUse(tu, startedCalls);
                case AgentEvent.ToolResultEvent tr -> {
                    appendMessage(UIMessage.toolResult(
                            tr.toolName(), tr.output(), tr.isError(), tr.elapsed()));
                    scrollToBottom();
                    needsRedraw = true;
                }
                case AgentEvent.UsageEvent u -> {
                    usageInTokens = u.inputTokens();
                    usageOutTokens = u.outputTokens();
                    needsRedraw = true;
                }
                case AgentEvent.ErrorEvent e -> {
                    appendMessage(UIMessage.error(e.message()));
                    needsRedraw = true;
                }
                case AgentEvent.CompactEvent c -> {
                    // session 包：自动压缩已重写对话 → 写入边界书签，
                    // resume 时 rebuildConversation 据此跳过边界之前的旧记录
                    saveCompactBoundaryFromConversation();
                    appendMessage(UIMessage.system(
                            CYAN + "⤾ Context compacted" + RESET + GRAY +
                            (c.message() == null || c.message().isEmpty() ? "" : ": " + c.message()) + RESET));
                    needsRedraw = true;
                }
                case AgentEvent.RetryEvent r -> {
                    appendMessage(UIMessage.system(
                            YELLOW + "↻ Retrying: " + r.reason() + RESET + GRAY +
                            (r.waitMs() > 0 ? " (waiting " + (r.waitMs() / 1000) + "s)" : "") + RESET));
                    needsRedraw = true;
                }
                case AgentEvent.PermissionRequestEvent pr -> handlePermissionRequest(pr);
                case AgentEvent.TurnComplete t -> { /* 后端当前未发送，预留 */ }
                case AgentEvent.LoopComplete lc -> {
                    finishLoop(lc.totalTurns());
                    return;
                }
            }
        }
    }

    /**
     * 展示工具调用事件。
     * ToolCallStart（空参数）→ 显示 "⚙ Name(…)"；
     * ToolCallComplete（完整参数）→ 替换为完整参数显示。
     */
    private void handleToolUse(AgentEvent.ToolUseEvent tu, Set<String> startedCalls) {
        String toolName = tu.toolName();
        Map<String, Object> args = tu.args();
        boolean isStart = (args == null || args.isEmpty()) && startedCalls.add(tu.toolId());

        if (isStart) {
            // 若流式消息已有文本/思考内容，先定稿为助手消息，再添加工具调用
            // 这样助手正文和工具调用各自独立显示，不会互相覆盖
            synchronized (messages) {
                if (!messages.isEmpty() && messages.getLast().streaming()) {
                    if (!streamAccum.isEmpty() || !thinkingAccum.isEmpty()) {
                        finalizeStreamingAssistant();
                        messages.add(UIMessage.streamingToolCall(toolName, "…"));
                    } else {
                        replaceLastMessage(UIMessage.streamingToolCall(toolName, "…"));
                    }
                } else {
                    messages.add(UIMessage.streamingToolCall(toolName, "…"));
                }
            }
        } else {
            // ToolCallComplete（完整参数）：替换"调用中"的显示为完整参数
            synchronized (messages) {
                replaceLastMessage(UIMessage.toolCall(toolName, formatToolArgs(args)));
            }
        }
        scrollToBottom();
        needsRedraw = true;
    }

    /** 收到权限询问：在对话区渲染问题并挂起等待用户按键（y/a/n）。 */
    private void handlePermissionRequest(AgentEvent.PermissionRequestEvent pr) {
        pendingPermission = pr;
        synchronized (messages) {
            // 移除空的流式占位，让权限问题紧跟工具调用显示
            if (!messages.isEmpty() && messages.getLast().streaming()
                    && streamAccum.isEmpty() && thinkingAccum.isEmpty()) {
                messages.remove(messages.size() - 1);
            }
            messages.add(UIMessage.permissionRequest(pr.toolName(), pr.description()));
        }
        scrollToBottom();
        needsRedraw = true;
    }

    /** 整个 agent 循环结束：定稿流式消息，输出汇总 footer。 */
    private void finishLoop(int totalTurns) {
        synchronized (messages) {
            if (!messages.isEmpty() && messages.getLast().streaming()) {
                if (!streamAccum.isEmpty() || !thinkingAccum.isEmpty()) {
                    finalizeStreamingAssistant();
                } else {
                    messages.remove(messages.size() - 1);  // 空占位直接移除
                }
            }
            if (totalTurns > 0) {
                messages.add(UIMessage.system(
                        GREEN + "✔" + RESET + " Completed in " + totalTurns + " turn(s)" +
                        GRAY + " · ↑" + formatTokens(usageInTokens) +
                        " ↓" + formatTokens(usageOutTokens) + " tokens" + RESET));
            }
        }
        scrollToBottom();
        needsRedraw = true;

        // memory 包：每 EXTRACTION_INTERVAL 轮自动从对话提取记忆（后台执行，不阻塞 UI）
        if (totalTurns > 0 && memoryManager.shouldExtract()) {
            Thread.startVirtualThread(() -> {
                try {
                    memoryManager.extract(client, conversation);
                } catch (Exception ignored) {
                    // 提取失败不影响主流程
                }
            });
        }
    }

    /** 回答挂起的权限询问（由 inputLoop 的按键直接调用）。 */
    private void answerPermission(PermissionResponse response, String label) {
        AgentEvent.PermissionRequestEvent pr = pendingPermission;
        pendingPermission = null;
        if (pr == null) return;
        pr.future().complete(response);
        String verdict = response == PermissionResponse.DENY
                ? RED + "denied" + RESET
                : GREEN + label + RESET;
        appendMessage(UIMessage.system(GRAY + "  ↳ " + RESET + verdict));
        needsRedraw = true;
    }

    /** 若当前末尾不是流式占位消息，则添加一个（新一轮 LLM 输出开始时调用）。 */
    private void ensureStreamingPlaceholder() {
        synchronized (messages) {
            if (messages.isEmpty() || !messages.getLast().streaming()) {
                streamAccum.setLength(0);
                thinkingAccum.setLength(0);
                firstTokenReceived = false;
                streamStartMs = System.currentTimeMillis();
                messages.add(UIMessage.streaming("Imagining… (0s)"));
            }
        }
        scrollToBottom();
    }

    /** 线程安全地追加消息（消费线程/输入线程/主线程均可能调用）。 */
    private void appendMessage(UIMessage msg) {
        synchronized (messages) { messages.add(msg); }
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

    /** 更新最后一条流式消息的显示内容（根据当前 thinking 和 text 状态）。调用方需持有 messages 锁。 */
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
     * 在工具调用开始 / 循环结束时调用，把已收到的文本"封存"为独立消息。
     * 调用方需持有 messages 锁。
     */
    private void finalizeStreamingAssistant() {
        if (messages.isEmpty()) return;
        if (!messages.getLast().streaming()) return;

        String finalText = streamAccum.toString();
        String thinkText = thinkingAccum.toString();
        // session 包：持久化助手消息（纯文本，resume 时按 role 重放）
        if (!finalText.isBlank()) {
            SessionManager.saveMessage(workDir, sessionId, "assistant", finalText);
        }
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
        // 总耗时 = 定稿时刻 - 本轮流式开始时刻（原来误用了首 token 时间）
        double secs = Math.max(System.currentTimeMillis() - streamStartMs, 0) / 1000.0;
        replaceLastMessage(UIMessage.assistant(rendered, String.format("DeveCode  (%.1fs)", secs)));
    }

    /** 替换最后一条消息（不管是否流式）。调用方需持有 messages 锁。 */
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
     *   2. 若为 ESC (0x1B)：带超时读下一个字节——超时说明是独立的 Esc 键
     *      （流式期间触发中断 agent），否则解析 CSI / SS3 转义序列
     *   3. 若为控制字符（CR/LF/Backspace/Ctrl+C/Ctrl+P）：直接处理
     *   4. 流式期间若有挂起的权限询问：y/a/n（或 1/2/3）直接应答
     *   5. 若为可打印字符（>= 32 或 Tab）：封装为 KeyTyped 事件推入队列
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

                // --- ESC sequences (function keys) / lone Esc (interrupt) ---
                if (ch == 0x1B) {
                    // 30ms 超时区分"独立 Esc 键"和"转义序列首字节"
                    int c2 = reader.read(30L);
                    if (c2 == NonBlockingReader.READ_EXPIRED) {
                        if (activePicker != null) {
                            // 选择器激活：Esc 取消选择，返回对话界面
                            activePicker = null;
                            needsRedraw = true;
                        } else if (streaming) {
                            // 独立 Esc：流式期间中断当前 agent 循环
                            interruptAgent();
                        }
                        continue;
                    }
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
                            case "A" -> {
                                if (activePicker != null) movePicker(-1);
                                else if (!currentCommandCandidates().isEmpty()) moveCommandHint(-1);
                                else if (!streaming) { scrollOffset++; needsRedraw = true; }
                            }
                            case "B" -> {
                                if (activePicker != null) movePicker(1);
                                else if (!currentCommandCandidates().isEmpty()) moveCommandHint(1);
                                else if (!streaming) { scrollOffset = Math.max(0, scrollOffset - 1); needsRedraw = true; }
                            }
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
                            case 'A' -> {
                                if (activePicker != null) movePicker(-1);
                                else if (!currentCommandCandidates().isEmpty()) moveCommandHint(-1);
                                else if (!streaming) { scrollOffset++; needsRedraw = true; }
                            }
                            case 'B' -> {
                                if (activePicker != null) movePicker(1);
                                else if (!currentCommandCandidates().isEmpty()) moveCommandHint(1);
                                else if (!streaming) { scrollOffset = Math.max(0, scrollOffset - 1); needsRedraw = true; }
                            }
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

                // --- Permission prompt keys (权限询问期间直接应答) ---
                if (streaming && pendingPermission != null) {
                    switch (ch) {
                        case 'y', 'Y', '1' -> answerPermission(PermissionResponse.ALLOW, "allowed");
                        case 'a', 'A', '2' -> answerPermission(PermissionResponse.ALLOW_ALWAYS, "always allowed");
                        case 'n', 'N', '3', 'q', 'Q' -> answerPermission(PermissionResponse.DENY, "denied");
                        default -> { /* 权限等待期间忽略其他按键 */ }
                    }
                    continue;
                }

                // --- Printable characters (incl. CJK) ---
                if (ch >= 32 || ch == '\t') {
                    if (activePicker != null) {
                        // 选择器激活：q 取消，其余按键不进入输入框
                        if (ch == 'q' || ch == 'Q') {
                            activePicker = null;
                            needsRedraw = true;
                        }
                        continue;
                    }
                    // 命令提示激活：Tab 补全选中候选（不作为输入字符进入输入框）
                    if (ch == '\t' && !currentCommandCandidates().isEmpty()) {
                        completeCommandHint();
                        continue;
                    }
                    eventQueue.add(new UIEvent.KeyTyped(ch));
                    needsRedraw = true;
                }
            }
        } catch (Exception e) {
            eventQueue.add(new UIEvent.Exit());
        }
    }

    /** 中断当前正在运行的 agent 循环（Esc 键）。 */
    private void interruptAgent() {
        agent.stop();
        appendMessage(UIMessage.system(YELLOW + "⏹ Interrupting…" + RESET));
        needsRedraw = true;
    }

    /**
     * 处理 Enter 键提交：将输入缓冲区内容封装为 Submit 事件推入队列。
     * 由 inputLoop 直接调用（不经过 KeyTyped 事件，避免 Windows CRLF 双触发）。
     * 选择器激活时 Enter 转为 PickerConfirm 事件（动作由主线程执行）。
     */
    private void handleEnter() {
        if (activePicker != null) {
            eventQueue.add(new UIEvent.PickerConfirm());
            return;
        }
        if (!streaming) {
            // 命令提示激活（命令名阶段或子命令阶段）：Enter = 展开选中候选后提交执行。
            // 仅输入 "/" 时（cacheKey 为空串）不自动执行，避免误触发第一条候选命令；
            // 深层参数阶段无候选，整行原样提交——参数不会被候选展开覆盖。
            var cands = currentCommandCandidates();
            String token = hintTokenCache;
            if (!cands.isEmpty() && token != null && !token.isEmpty()) {
                int idx = Math.min(Math.max(commandHintIndex, 0), cands.size() - 1);
                replaceCommandToken(cands.get(idx).name());
            }
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

        // 全屏选择器激活时：覆盖正常界面，只渲染选择器
        if (activePicker != null) {
            buf.append(CLEAR).append(HOME);
            renderPicker(buf, termWidth, termHeight);
            writer.print(buf.toString());
            writer.flush();
            return;
        }

        buf.append(HOME);

        int rows = termHeight;
        int cols = termWidth;

        // 右侧状态面板：终端足够宽时显示
        boolean showPanel = cols >= PANEL_MIN_COLS && panelVisible;
        int panelW = showPanel ? PANEL_WIDTH : 0;
        int leftCols = showPanel ? cols - panelW - 1 : cols;  // -1 给竖线分隔
        int panelX = showPanel ? cols - panelW : 0;            // 面板起始列

        // 布局：状态行(1) | 分隔(1) | {对话区 | 命令提示 | 分隔(1) | 输入区} + 状态面板 | 状态栏(1)
        int statusRow = 0;
        int sep1Row = 1;
        int convStart = 2;
        int inputHeight = Math.max(countInputLines() + 1, 3); // +1 边框
        int sep2Row = rows - inputHeight - 2;
        int inputTop = sep2Row + 1;
        int statusBarRow = rows - 1;

        // 命令提示面板（输入 / 时）：占据对话区底部，对话区至少保留 3 行
        List<Command> hintCmds = currentCommandCandidates();
        int hintHeight = 0;
        if (!hintCmds.isEmpty()) {
            int maxByList = Math.min(hintCmds.size(), MAX_COMMAND_HINTS) + 2;  // 顶线 + 条目 + 操作提示行
            int maxBySpace = Math.max(0, sep2Row - convStart - 3);             // 对话区至少留 3 行
            hintHeight = Math.min(maxByList, maxBySpace);
            if (hintHeight < 3) hintHeight = 0;   // 空间太小放不下完整面板就不显示
        }

        // 确保对话区至少有 3 行
        int convEnd = sep2Row - 1 - hintHeight;
        if (convEnd - convStart < 3) {
            // 空间不足：优先放弃命令提示面板
            if (hintHeight > 0) {
                hintHeight = 0;
                convEnd = sep2Row - 1;
            }
            if (convEnd - convStart < 3) {
                convEnd = convStart + 3;
                sep2Row = convEnd;
                inputTop = sep2Row + 1;
                if (inputTop + inputHeight >= rows) {
                    inputHeight = rows - inputTop - 1;
                    if (inputHeight < 1) inputHeight = 1;
                }
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

        // ── 命令提示面板（对话区与分隔线2之间，输入 / 时出现）──
        if (hintHeight > 0) {
            renderCommandHints(buf, sep2Row - hintHeight, leftCols, hintCmds, hintHeight - 2);
        }

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
        List<UIMessage> snapshot;
        synchronized (messages) { snapshot = new ArrayList<>(messages); }
        for (UIMessage msg : snapshot) { allLines.addAll(msg.toRenderLines(textWidth)); }
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
            } else if (inputBuffer.isEmpty() && streaming && pendingPermission != null) {
                buf.append(YELLOW).append("Permission required: [y] allow  [a] always  [n] deny").append(RESET);
                moveTo(buf, topRow + 1, 3);
                buf.append(CURSOR_HIDE);
            } else if (inputBuffer.isEmpty() && streaming) {
                buf.append(DIM).append("Working… press Esc to interrupt, ctrl + c to quit").append(RESET);
                moveTo(buf, topRow + 1, 3);
                buf.append(CURSOR_HIDE);
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

    /**
     * 渲染命令提示面板（输入 / 时出现在对话区底部）。
     *
     * 结构：亮天蓝顶线（带 commands 标题）+ 候选条目列表 + 操作提示行。
     * ● 实心白点标记选中项（↑↓ 循环导航），○ 空心灰点标记未选中项，
     * 与全屏选择器的视觉惯例保持一致。
     *
     * @param startRow   面板首行（顶线）所在行
     * @param width      左侧区域宽度
     * @param cands      候选命令列表（已按名称排序）
     * @param maxVisible 空间允许显示的最大条目数
     */
    private void renderCommandHints(StringBuilder buf, int startRow, int width,
                                     List<Command> cands, int maxVisible) {
        int n = cands.size();
        int visible = Math.min(n, Math.min(maxVisible, MAX_COMMAND_HINTS));
        if (visible <= 0) return;
        int idx = Math.min(Math.max(commandHintIndex, 0), n - 1);

        // 滚动窗口跟随选中项（尽量居中）
        int winStart;
        if (n <= visible) {
            winStart = 0;
        } else {
            winStart = idx - visible / 2;
            if (winStart < 0) winStart = 0;
            if (winStart > n - visible) winStart = n - visible;
        }

        // ── 顶线：╾─ commands ──────（亮天蓝，区别于灰色分隔线）──
        moveTo(buf, startRow, 0);
        buf.append("\033[K");
        String header = " commands ";
        int fill = Math.max(0, width - header.length() - 2);
        buf.append(BORDER).append('╾').append(header).append(repeat('─', fill)).append(RESET);

        // ── 条目列表（命令列对齐，描述跟随其后）──
        // 命令列可见宽度 = max("/name (aliases)")，用于各条目描述列对齐
        int cmdColW = 0;
        for (int i = winStart; i < winStart + visible; i++) {
            cmdColW = Math.max(cmdColW, commandColumnWidth(cands.get(i)));
        }

        int y = startRow + 1;
        for (int i = winStart; i < winStart + visible; i++) {
            Command c = cands.get(i);
            boolean sel = (i == idx);
            moveTo(buf, y, 0);
            buf.append("\033[K");

            // 标记 + 命令名（选中：白粗体；未选中：白色）；skill 命令附带青色 [skill] 标识
            String marker = sel ? BOLD + WHITE + "● " + RESET : GRAY + "○ " + RESET;
            String nameColored = (sel ? BOLD + WHITE : WHITE) + "/" + c.name() + RESET;
            String aliases = "";
            if (c.aliases().length > 0) {
                aliases = GRAY + " (" + String.join(", ", c.aliases()) + ")" + RESET;
            }
            String skillTag = c.skill() ? CYAN + " [skill]" + RESET : "";
            buf.append(marker).append(nameColored).append(aliases).append(skillTag);

            // 描述（灰色，对齐到统一列；空间不足时截断）
            int used = 2 + commandColumnWidth(c);
            int descCol = 2 + cmdColW + 2;
            if (descCol < width - 4) {
                buf.append(repeat(' ', descCol - used));
                buf.append(GRAY).append(truncate(c.description(), width - descCol - 1)).append(RESET);
            }
            y++;
        }

        // ── 操作提示行（条目超出窗口时附带位置指示）──
        moveTo(buf, y, 0);
        buf.append("\033[K");
        String hint = GRAY + "  ↑↓ select  ·  Tab complete  ·  Enter run"
                + (n > visible ? "  ·  " + (idx + 1) + "/" + n : "") + RESET;
        buf.append(truncate(hint, width));
    }

    /** 命令条目中命令列（"/name (aliases) [skill]"）的可见宽度。 */
    private static int commandColumnWidth(Command c) {
        int w = 1 + c.name().length();
        if (c.aliases().length > 0) {
            w += 3 + String.join(", ", c.aliases()).length();  // " (" + join + ")"
        }
        if (c.skill()) {
            w += 7;  // " [skill]"
        }
        return w;
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
        y = panelKV(buf, y, padX, maxW, "Mode:", permissionChecker.getMode().name().toLowerCase());
        y = panelKV(buf, y, padX, maxW, "Tools:", String.valueOf(
                toolRegistry.getAllSchemas(provider.getProtocol()).size()));
        y = panelKV(buf, y, padX, maxW, "API usage:", "↑" + formatTokens(usageInTokens) + " ↓" + formatTokens(usageOutTokens));
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
        y = panelKV(buf, y, padX, maxW, "Session:", sessionId);
        y = panelKV(buf, y, padX, maxW, "Status:",
                permissionChecker.isSandboxEnabled()
                        ? GREEN + "●" + RESET + WHITE + " active" + RESET
                        : GRAY + "○" + RESET + WHITE + " off" + RESET);
        y++;

        // 区块五：MCP（真实连接状态：server 数 / 各 server 工具数 / 失败数）
        y = panelHeader(buf, y, padX, maxW, "MCP");
        if (mcpServers.isEmpty() && mcpErrors.isEmpty()) {
            y = panelKV(buf, y, padX, maxW, "servers:", "none");
        } else {
            y = panelKV(buf, y, padX, maxW, "servers:", String.valueOf(mcpServers.size()));
            int shown = 0;
            for (var s : mcpServers) {
                if (shown++ >= 4) {
                    // 超过 4 个折叠显示
                    y = panelLine(buf, y, padX, maxW, GRAY + "… +" + (mcpServers.size() - 4) + " more" + RESET);
                    break;
                }
                y = panelKV(buf, y, padX, maxW,
                        McpManager.sanitizeName(s.name()) + ":",
                        mcpToolCounts.getOrDefault(s.name(), 0) + " tools");
            }
            if (!mcpErrors.isEmpty()) {
                y = panelKV(buf, y, padX, maxW, "errors:", String.valueOf(mcpErrors.size()));
            }
        }
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
    //  全屏选择器渲染
    // ═══════════════════════════════════════════════════════════════

    /**
     * 渲染全屏选择器（与欢迎屏同一视觉语言：75 号天蓝边框、●/○ 选中标记、
     * ↑↓ 循环导航、居中盒子布局）。
     *
     * 条目超过可视高度时以选中项为中心滚动窗口。
     */
    private void renderPicker(StringBuilder buf, int w, int h) {
        PickerState p = activePicker;
        if (p == null) return;
        int n = p.items().size();

        // ── 计算盒子尺寸 ──
        int maxItemW = 0;
        for (var item : p.items()) {
            int len = Math.max(visibleLength(item.id()), Math.max(
                    item.title().length(), item.subtitle().length()));
            if (len > maxItemW) maxItemW = len;
        }
        int hintLen = 44;  // "↑↓ navigate · Enter select · Esc/q cancel"
        int innerW = Math.min(Math.max(Math.max(maxItemW + 6, p.title().length() + 4), hintLen), Math.max(40, w - 4));
        int boxW = innerW + 2;
        if (boxW > w) { boxW = w; innerW = boxW - 2; }

        int visible = Math.min(n, Math.max(3, h - 10));   // 可视条目窗口
        int boxH = Math.min(h, 2 /*边框*/ + 2 /*标题+空行*/ + visible * 2 + 1 /*空行*/ + 1 /*提示*/ + 2 /*留白*/);

        int boxX = Math.max(0, (w - boxW) / 2);
        int boxY = Math.max(0, (h - boxH) / 2);
        int left = boxX;
        int right = boxX + boxW - 1;

        // ── 滚动窗口：保持选中项可见（尽量居中） ──
        int winStart;
        if (n <= visible) {
            winStart = 0;
        } else {
            winStart = pickerIndex - visible / 2;
            if (winStart < 0) winStart = 0;
            if (winStart > n - visible) winStart = n - visible;
        }

        // ── 边框 ──
        moveTo(buf, boxY, left);
        buf.append(BORDER).append('╭').append(repeat('─', innerW)).append('╮').append(RESET);

        int y = boxY + 1;
        // 标题（青色粗体，左对齐带缩进）
        moveTo(buf, y, left);
        buf.append(BORDER).append('│').append(RESET);
        moveTo(buf, y, right);
        buf.append(BORDER).append('│').append(RESET);
        moveTo(buf, y, left + 2);
        buf.append(BOLD).append(CYAN).append(truncate(p.title(), innerW - 2)).append(RESET);
        y++;

        // 空行
        y = pickerBlankRow(buf, y, left, right);
        y = pickerBlankRow(buf, y, left, right);

        // ── 条目列表：● 实心白点选中 / ○ 空心灰点未选中 ──
        for (int i = winStart; i < winStart + visible && i < n; i++) {
            var item = p.items().get(i);
            boolean sel = (i == pickerIndex);

            // 第一行：标记 + 标题
            moveTo(buf, y, left);
            buf.append(BORDER).append('│').append(RESET);
            moveTo(buf, y, right);
            buf.append(BORDER).append('│').append(RESET);
            moveTo(buf, y, left + 1);
            String prefix = sel ? BOLD + WHITE + "● " + RESET : GRAY + "○ " + RESET;
            String titleColored = sel
                    ? BOLD + WHITE + truncate(item.title(), innerW - 4) + RESET
                    : WHITE + truncate(item.title(), innerW - 4) + RESET;
            buf.append(prefix).append(titleColored);
            y++;

            // 第二行：id + 元信息（灰色）
            moveTo(buf, y, left);
            buf.append(BORDER).append('│').append(RESET);
            moveTo(buf, y, right);
            buf.append(BORDER).append('│').append(RESET);
            moveTo(buf, y, left + 3);
            String meta = GRAY + truncate(item.id() + " · " + item.subtitle(), innerW - 4) + RESET;
            buf.append(meta);
            y++;
        }

        // ── 空行 + 操作提示 ──
        y = pickerBlankRow(buf, y, left, right);
        moveTo(buf, y, left);
        buf.append(BORDER).append('│').append(RESET);
        moveTo(buf, y, right);
        buf.append(BORDER).append('│').append(RESET);
        String hint = GRAY + "↑↓ navigate  ·  Enter select  ·  Esc/q cancel" + RESET;
        int hintX = left + 1 + Math.max(0, (innerW - visibleLength(hint)) / 2);
        moveTo(buf, y, hintX);
        buf.append(hint);
        y++;

        // 滚动指示（条目超出窗口时显示）
        if (n > visible) {
            y = pickerBlankRow(buf, y, left, right);
            moveTo(buf, y, left);
            buf.append(BORDER).append('│').append(RESET);
            moveTo(buf, y, right);
            buf.append(BORDER).append('│').append(RESET);
            String pos = DIM + (pickerIndex + 1) + " / " + n + RESET;
            moveTo(buf, y, left + 1 + Math.max(0, (innerW - visibleLength(pos)) / 2));
            buf.append(pos);
            y++;
        }

        // ── 底边框（固定在内容行之后）──
        moveTo(buf, y, left);
        buf.append(BORDER).append('╰').append(repeat('─', innerW)).append('╯').append(RESET);
    }

    /** 选择器盒子内的空行（只画左右边框）。 */
    private static int pickerBlankRow(StringBuilder buf, int y, int left, int right) {
        moveTo(buf, y, left);
        buf.append(BORDER).append('│').append(RESET);
        moveTo(buf, y, right);
        buf.append(BORDER).append('│').append(RESET);
        return y + 1;
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

        /** 工具执行结果（超长输出截断为前 500 字符，附执行耗时） */
        static UIMessage toolResult(String toolName, String output, boolean isError, double elapsed) {
            String color = isError ? RED : GRAY;
            String display = output;
            if (display != null && display.length() > 500) {
                display = display.substring(0, 500) + "\n…";
            }
            String time = elapsed > 0
                    ? GRAY + " [" + String.format("%.1fs", elapsed) + "]" + RESET
                    : "";
            // 输出可能含换行，逐行包裹颜色防止 \n 分割后丢失颜色
            String body = display != null ? display : "";
            String[] lines = body.split("\n", -1);
            var sb = new StringBuilder();
            for (int i = 0; i < lines.length; i++) {
                if (i > 0) sb.append("\n");
                sb.append(color).append(i == 0 ? "↳ " : "  ").append(lines[i]);
                if (i == lines.length - 1) sb.append(time);
                sb.append(RESET);
            }
            return new UIMessage("tool", sb.toString(), null, false, false);
        }

        /** 系统提示消息（压缩/重试/中断/轮次汇总等） */
        static UIMessage system(String text) {
            return new UIMessage("system", text, null, false, false);
        }

        /** 权限询问：显示待执行操作和 y/a/n 选项 */
        static UIMessage permissionRequest(String toolName, String description) {
            return new UIMessage("system",
                    BOLD + YELLOW + "⚠ Permission required" + RESET + "\n" +
                    BOLD + CYAN + toolName + RESET + GRAY + " — " + description + RESET + "\n" +
                    BOLD + "[y]" + RESET + " allow   " +
                    BOLD + "[a]" + RESET + " always allow   " +
                    BOLD + "[n]" + RESET + " deny",
                    LocalTime.now().format(TIME_FMT), false, false);
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
        record PickerConfirm() implements UIEvent {}
        record TerminalResize(int cols, int rows) implements UIEvent {}
        record Exit() implements UIEvent {}
    }

    // ── 全屏选择器数据类型 ──────────────────────────────────────────

    /** 选择器条目：id（会话 ID / 序号）、title（主标题）、subtitle（元信息行）、payload（原始数据） */
    private record PickerItem(String id, String title, String subtitle, Object payload) {}

    /** 选择器状态：kind（"session" / "snapshot"，决定确认后的动作）、标题、条目列表 */
    private record PickerState(String kind, String title, List<PickerItem> items) {}
}
