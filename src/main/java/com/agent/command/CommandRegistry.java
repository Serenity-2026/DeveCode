

package com.agent.command;


import com.agent.command.Command.CommandType;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 命令注册中心
 */
public class CommandRegistry {

    private final List<Command> commands = new ArrayList<>();
    // 由 skill catalog 动态注册的命令（PROMPT 类型）：CopyOnWriteArrayList 保证
    // 后台 install/reload 线程同步时，渲染线程的 search() 快照遍历不会抛 CME
    private final List<Command> skillCommands = new CopyOnWriteArrayList<>();
    //key包含name与alias,值为对应的handler

    private final Map<String, Function<CommandContext, String>> handlers = new HashMap<>();
    // 用于冲突检测：记录已注册的命令名和别名的归属关系
    private final Set<String> nameIndex = new HashSet<>();   // name → ownerName
    private final Map<String, String> aliasIndex = new HashMap<>();  // alias → ownerName

    /** Creates a registry pre-populated with the default DeveCode commands. */
    public CommandRegistry() {
        registerDefaults();
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Registers a command with an optional handler.
     * 检测名称/别名冲突：与已有命令名或别名重复时抛出 IllegalArgumentException。
     *
     * @param cmd     command definition
     * @param handler handler function (args -> output); may be {@code null} for UI-only commands
     */
    public void register(Command cmd, Function<CommandContext, String> handler) {
        // 命令名不能与已注册的命令名重复
        if (nameIndex.contains(cmd.name())) {
            throw new IllegalArgumentException(
                    "commands: duplicate command name '%s'".formatted(cmd.name()));
        }
        // 命令名不能与已注册的别名冲突
        if (aliasIndex.containsKey(cmd.name())) {
            throw new IllegalArgumentException(
                    "commands: command name '%s' collides with alias of '%s'"
                            .formatted(cmd.name(), aliasIndex.get(cmd.name())));
        }
        // 每个别名不能与已注册的命令名或别名冲突
        for (var alias : cmd.aliases()) {
            if (nameIndex.contains(alias)) {
                throw new IllegalArgumentException(
                        "commands: alias '%s' for '%s' collides with existing command name"
                                .formatted(alias, cmd.name()));
            }
            if (aliasIndex.containsKey(alias)) {
                throw new IllegalArgumentException(
                        "commands: alias '%s' for '%s' already registered by '%s'"
                                .formatted(alias, cmd.name(), aliasIndex.get(alias)));
            }
        }

        // 注册到索引
        nameIndex.add(cmd.name());
        for (var alias : cmd.aliases()) {
            aliasIndex.put(alias, cmd.name());
        }

        commands.add(cmd);
        if (handler != null) {
            handlers.put(cmd.name(), handler);
            for (var alias : cmd.aliases()) {
                handlers.put(alias, handler);
            }
        }
    }

    /**
     * 检查命令的名称或别名是否与已注册条目冲突。
     * 动态加载器（如从文件加载的命令）应在 register 前调用此方法，
     * 避免触发 register 的异常。
     */
    public boolean hasConflict(Command cmd) {
        if (find(cmd.name()).isPresent()) {
            return true;
        }
        for (var alias : cmd.aliases()) {
            if (find(alias).isPresent()) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 动态 skill 命令
    // ------------------------------------------------------------------

    /**
     * 注册一个由 skill catalog 提供的动态命令（通常为 PROMPT 类型）。
     * 与静态命令或已注册 skill 命令重名时静默跳过（静态命令优先），
     * 避免动态加载触发 register 的冲突异常。
     */
    public void registerSkill(Command cmd, Function<CommandContext, String> handler) {
        if (find(cmd.name()).isPresent()) {
            return;
        }
        skillCommands.add(cmd);
        if (handler != null) {
            handlers.put(cmd.name(), handler);
            for (var alias : cmd.aliases()) {
                handlers.put(alias, handler);
            }
        }
    }

    /** 清除全部动态 skill 命令及其 handler（catalog reload 前调用）。 */
    public void clearSkillCommands() {
        for (Command c : skillCommands) {
            handlers.remove(c.name());
            for (var alias : c.aliases()) {
                handlers.remove(alias);
            }
        }
        skillCommands.clear();
    }

    /**
     * 按前缀过滤候选命令：静态命令在前、skill 命令在后，各自按名称排序
     * （命令提示面板的显示顺序约定）。
     */
    public List<Command> search(String prefix) {
        String lower = prefix.toLowerCase();
        List<Command> result = new ArrayList<>(matchByPrefix(commands, lower));
        result.addAll(matchByPrefix(skillCommands, lower));
        return result;
    }

    private static List<Command> matchByPrefix(List<Command> source, String lowerPrefix) {
        return source.stream()
                .filter(c -> !c.hidden())
                .filter(c -> {
                    if (c.name().toLowerCase().startsWith(lowerPrefix)) {
                        return true;
                    }
                    for (var alias : c.aliases()) {
                        if (alias.toLowerCase().startsWith(lowerPrefix)) {
                            return true;
                        }
                    }
                    return false;
                })
                .sorted(Comparator.comparing(Command::name))
                .collect(Collectors.toList());
    }

    /** Finds a command by exact name or alias match（静态命令优先于 skill 命令）。 */
    public Optional<Command> find(String name) {
        Optional<Command> cmd = commands.stream()
                .filter(c -> c.matches(name))
                .findFirst();
        if (cmd.isPresent()) {
            return cmd;
        }
        return skillCommands.stream()
                .filter(c -> c.matches(name))
                .findFirst();
    }

    /**
     * Executes a LOCAL command handler and returns its output.
     *
     * @param name command name or alias
     * @param ctx arguments passed after the command name
     * @return handler output, or an error message if not found / no handler
     */
    public String execute(String name, CommandContext ctx) {
        Function<CommandContext, String> handler = handlers.get(name);
        if (handler != null) {
            return handler.apply(ctx);
        }
        return "No handler registered for /" + name;
    }

    /** Returns an unmodifiable view of all registered commands. */
    public List<Command> listAll() {
        return Collections.unmodifiableList(commands);
    }

    /** Returns all non-hidden commands：静态命令在前、skill 命令在后，各自按名称排序。 */
    public List<Command> listVisible() {
        List<Command> result = new ArrayList<>(
                commands.stream()
                        .filter(c -> !c.hidden())
                        .sorted(Comparator.comparing(Command::name))
                        .collect(Collectors.toList()));
        result.addAll(skillCommands.stream()
                .filter(c -> !c.hidden())
                .sorted(Comparator.comparing(Command::name))
                .collect(Collectors.toList()));
        return result;
    }

    // ------------------------------------------------------------------
    // Default command registration
    // ------------------------------------------------------------------

    private void registerDefaults() {
        // /help (LOCAL, aliases: h, ?)
        register(
                new Command("help", "Show available commands",
                        new String[]{"h", "?"}, CommandType.LOCAL, false),
                ctx -> {
                    String args = ctx.args();
                    if (args != null && !args.isBlank()) {
                        // 模式一:/help <cmd> 查详情
                        Optional<Command> target = find(args.strip());
                        if (target.isEmpty()) {
                            return "Unknown command: " + args.strip();
                        }
                        Command c = target.get();
                        var sb = new StringBuilder();
                        sb.append("/").append(c.name()).append(" — ").append(c.description()).append("\n");
                        if (c.aliases().length > 0) {
                            sb.append("  Aliases: ").append(String.join(", ", c.aliases())).append("\n");
                        }
                        return sb.toString();
                    }
                    //模式二:无参 → 全量列表（静态命令在前、skill 命令在后，均按名称排序）
                    var sb = new StringBuilder();
                    sb.append("Available commands:\n\n");
                    for (var cmd : listVisible()) {
                        String aliases = "";
                        if (cmd.aliases().length > 0) {
                            aliases = ", /" + String.join(", /", cmd.aliases());
                        }
                        sb.append("  /").append(cmd.name()).append(aliases);
                        if (cmd.skill()) {
                            sb.append("  [skill]");
                        }
                        sb.append('\n');
                        sb.append("    ").append(cmd.description()).append("\n");
                    }
                    sb.append("\nType /help <command> for details.");
                    return sb.toString();
                }
        );

        // /mcp (LOCAL)
        register(
                new Command("mcp", "Show MCP server status",
                        new String[]{}, CommandType.LOCAL, false),
                ctx -> {
                    if (ctx.mcpInfo() == null) return "No MCP servers configured";
                    String info = ctx.mcpInfo().get();
                    return info.isEmpty() ? "No MCP servers connected" : info;
                }
        );

        // /clear (LOCAL_UI)
        register(
                new Command("clear", "Clear conversation and start fresh",
                        new String[]{}, CommandType.LOCAL_UI, false),
                null
        );

        // /compact (LOCAL_UI, alias: c)
        register(
                new Command("compact", "Compress conversation context",
                        new String[]{"c"}, CommandType.LOCAL_UI, false),
                null
        );

        // /status (LOCAL, alias: s)
        register(
                new Command("status", "Show current status",
                        new String[]{"s"}, CommandType.LOCAL, false),
                ctx -> {
                    var sb = new StringBuilder();
                    sb.append("DeveCode Status\n");
                    sb.append("──────────────\n");
                    sb.append("  Mode:      ").append(ctx.permissionMode().get()).append("\n");
                    int[] tokens = ctx.tokenCount().get();
                    sb.append("  Tokens:    ").append(tokens[0]).append(" in / ").append(tokens[1]).append(" out\n");
                    sb.append("  Tools:     ").append(ctx.toolCount().getAsInt()).append(" enabled\n");
                    var memories = ctx.memoryList().get();
                    sb.append("  Memories:  ").append(memories.size()).append(" entries\n");
                    sb.append("  Model:     ").append(ctx.model()).append("\n");
                    sb.append("  Directory: ").append(ctx.workDir()).append("\n");
                    return sb.toString();
                }
        );

        // /memory (LOCAL)
        register(
                new Command("memory", "Manage auto-memories",
                        new String[]{}, CommandType.LOCAL, false,
                        Command.subcommands(
                                "list", "Show stored memories",
                                "clear", "Clear all memories")),
                ctx -> {
                    String args = ctx.args();
                    String sub = (args == null || args.isBlank()) ? "list" : args.strip().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
                    return switch (sub) {
                        case "list" -> {
                            var memories = ctx.memoryList().get();
                            if (memories.isEmpty()) yield "No memories stored yet.";
                            var sb = new StringBuilder("Auto-memories (%d):\n".formatted(memories.size()));
                            for (var m : memories) sb.append("  • ").append(m).append("\n");
                            yield sb.toString();
                        }
                        case "clear" -> { ctx.memoryClear().run(); yield "All auto-memories cleared."; }
                        default -> "Usage: /memory [list|clear]";
                    };
                }
        );

        // /plan (LOCAL_UI, alias: p)
        register(
                new Command("plan", "Switch to plan mode (read-only)",
                        new String[]{"p"}, CommandType.LOCAL_UI, false),
                null
        );


        // /permission (LOCAL, alias: perm)
        register(
                new Command("permission", "Permission management",
                        new String[]{"perm"}, CommandType.LOCAL, false,
                        Command.subcommands(
                                "info", "Show current permission mode",
                                "mode", "Usage: /permission mode <mode>")),
                ctx -> {
                    String args = ctx.args();
                    String sub = (args == null || args.isBlank()) ? "info" : args.strip().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
                    return switch (sub) {
                        case "info" -> "Current permission mode: " + ctx.permissionMode().get();
                        case "mode" -> "Usage: /permission mode <default|acceptEdits|plan|bypassPermissions>";

                        default -> "Usage: /permission [info|mode <mode>|rules]";
                    };
                }
        );

        // /resume (LOCAL_UI, alias: r)
        register(
                new Command("resume", "Resume a previous session",
                        new String[]{"r"}, CommandType.LOCAL_UI, false),
                null
        );

        // /rewind (LOCAL_UI)
        register(
                new Command("rewind", "Rewind to a previous checkpoint",
                        new String[]{}, CommandType.LOCAL_UI, false),
                null
        );

        // /review (PROMPT)
        register(
                new Command("review", "Review current code changes",
                        new String[]{}, CommandType.PROMPT, false),
                ctx -> {
                    String args = ctx.args();
                    String prompt = "Please review the current git diff for code changes. Focus on:\n"
                            + "1. Logic errors\n2. Security issues\n3. Performance problems\n4. Code style";
                    if (args != null && !args.isBlank()) {
                        prompt += "\n\nAdditional focus: " + args.strip();
                    }
                    return prompt;
                }
        );

        // /sandbox (LOCAL) — 沙箱模式管理
        register(
                new Command("sandbox", "Manage OS-level sandbox for Bash commands",
                        new String[]{}, CommandType.LOCAL, false),
                ctx -> {
                    String args = ctx.args();
                    if (args == null || args.isBlank()) {
                        // 显示当前状态和可选模式
                        String status = ctx.sandboxStatus() != null ? ctx.sandboxStatus().get() : "unavailable";
                        var sb = new StringBuilder();
                        sb.append("沙箱状态: ").append(status).append("\n\n");
                        sb.append("可选模式:\n");
                        sb.append("  /sandbox 1  — 开启沙箱 + 自动放行（推荐）\n");
                        sb.append("  /sandbox 2  — 开启沙箱 + 常规权限\n");
                        sb.append("  /sandbox 3  — 关闭沙箱\n");
                        return sb.toString();
                    }

                    String sub = args.strip();
                    if (ctx.sandboxSwitch() == null) {
                        return "沙箱功能不可用（当前平台不支持或 bwrap/sandbox-exec 未安装）";
                    }
                    return switch (sub) {
                        case "1" -> {
                            ctx.sandboxSwitch().accept(1);
                            yield "已开启沙箱 + 自动放行模式。命令在 OS 级沙箱中执行，无需逐条确认。";
                        }
                        case "2" -> {
                            ctx.sandboxSwitch().accept(2);
                            yield "已开启沙箱 + 常规权限模式。命令在沙箱中执行，但仍需按权限规则确认。";
                        }
                        case "3" -> {
                            ctx.sandboxSwitch().accept(3);
                            yield "已关闭沙箱。命令将直接执行。";
                        }
                        default -> "无效选项。使用 /sandbox 1|2|3 选择模式。";
                    };
                }
        );
    }
}
