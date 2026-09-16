package com.agent.memory;

import com.agent.history.ConversationManager;
import com.agent.llm.LlmClient;
import com.agent.llm.Message;
import com.agent.llm.StreamEvent;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 记忆管理器，使用独立 .md 文件 + MEMORY.md 索引的统一存储格式。
 *
 * <p>存储结构：
 * <ul>
 *   <li>用户级 (~/.devecode/memory/)：存放 type=user / type=feedback 的记忆文件</li>
 *   <li>项目级 (.devecode/memory/)：存放 type=project / type=reference 的记忆文件</li>
 *   每个记忆文件 = YAML frontmatter（name/description/type 元数据）+ 正文：
 *   ---
 *      name: prefers-chinese-answers
 *      description: 用户偏好中文回复
 *      metadata:
 *      type: user
 *   ---
 * 用户所有对话均使用中文，回复应使用中文……
 * </ul>
 *
 * <p>每条记忆是一个独立的 .md 文件，包含 YAML frontmatter（name, description, type）。
 * 每个目录下有一个 MEMORY.md 索引文件，用一行指针格式 `- [Title](file.md) — description`
 * 汇总该目录下的所有记忆。
 */
public class MemoryManager {

    /** MEMORY.md 索引文件名 */
    private static final String ENTRYPOINT_NAME = "MEMORY.md";
    /** 提取频率 */
    private static final int EXTRACTION_INTERVAL = 2;
    private static final String MEMORY_DIR = ".devecode/memory";

    // user/feedback 跟随用户;project/reference 跟随项目
    private static final Set<String> USER_TYPES = Set.of("user", "feedback");
    private static final Set<String> PROJECT_TYPES = Set.of("project", "reference");

    private final Path userMemDirPath;
    private final Path projectMemDirPath;
    private int turnCount;

    public MemoryManager(String workDir) {
        this.projectMemDirPath = Path.of(workDir, MEMORY_DIR);
        this.userMemDirPath = Path.of(System.getProperty("user.home"), MEMORY_DIR);
        // 确保目录存在，让 Agent 的 Write 工具可以直接写入
        ensureDir(userMemDirPath);
        ensureDir(projectMemDirPath);
    }

    // ---- Directory accessors (for memory recall) ----

    /** 返回用户级记忆目录（~/.devecode/memory/） */
    public Path userMemDir() {
        return userMemDirPath;
    }

    /** 返回项目级记忆目录（.devecode/memory/） */
    public Path projectMemDir() {
        return projectMemDirPath;
    }

    /** 返回项目级 MEMORY.md 的路径 */
    public Path entrypointPath() {
        return projectMemDirPath.resolve(ENTRYPOINT_NAME);
    }

    /** 返回用户级 MEMORY.md 的路径 */
    public Path userEntrypointPath() {
        return userMemDirPath.resolve(ENTRYPOINT_NAME);
    }

    // ---- Accessors ----

    /**
     * 返回所有记忆的摘要行，格式为 "[type] name — description"。
     * 扫描两个目录下的 .md 文件（不含 MEMORY.md），按文件名排序。
     */
    public List<String> getMemories() {
        var files = loadAll();
        var out = new ArrayList<String>();
        for (var f : files) {
            String typeTag = f.type().isEmpty() ? "?" : f.type();
            String desc = f.description().isEmpty() ? f.filename() : f.description();
            out.add("[%s] %s — %s".formatted(typeTag, f.name(), desc));
        }
        return out;
    }
    /*每多少论触发一次自动记忆提取*/
    public boolean shouldExtract() {
        turnCount++;
        return turnCount % EXTRACTION_INTERVAL == 0;
    }

    /**
     * 清除两个目录下的所有 .md 文件（包括 MEMORY.md）。
     */
    public void clear() {
        clearDir(userMemDirPath);
        clearDir(projectMemDirPath);
    }

    // ---- Memory file record ----

    /** 一个记忆文件的元数据 */
    public record MemoryFile(String path, String filename, String name, String description, String type) {}

    /**
     * 扫描两个目录，加载所有记忆文件的 frontmatter 元数据。
     * 用户级在前，项目级在后。
     */
    List<MemoryFile> loadAll() {
        var out = new ArrayList<MemoryFile>();
        out.addAll(loadDir(userMemDirPath));
        out.addAll(loadDir(projectMemDirPath));
        return out;
    }

    /**
     * 返回所有记忆文件元数据内容
     */
    private static List<MemoryFile> loadDir(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        List<Path> mdFiles;
        try (Stream<Path> stream = Files.list(dir)) {
            mdFiles = stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        //排除ENTRYPOINT_NAME.md索引文件
                        return n.endsWith(".md") && !n.equals(ENTRYPOINT_NAME);
                    })
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }

        var out = new ArrayList<MemoryFile>();
        for (Path fp : mdFiles) {
            try {
                String content = Files.readString(fp);
                //记忆文件的元数据抽取器:给定一个.md文件的全文，取出顶部frontmatter里的name/description/type三个字段
                var fm = MemoryScanner.parseFrontmatter(content);
                String name = fm.name().isEmpty()
                        ? fp.getFileName().toString().replace(".md", "")
                        : fm.name();
                out.add(new MemoryFile(
                        fp.toAbsolutePath().toString(),
                        fp.getFileName().toString(),
                        name, fm.description(), fm.type()));
            } catch (IOException ignored) {
                // 跳过不可读的文件
            }
        }
        return out;
    }

    private static void clearDir(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                  .filter(p -> p.getFileName().toString().endsWith(".md"))
                  .forEach(p -> {
                      try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                  });
        } catch (IOException ignored) {}
    }

    // ---- Build system-reminder section ----

    /**
     * 构建记忆系统的system-reminder部分，包含MEMORY.md 索引内容。
     * 确保两个目录都存在后读取各自的 MEMORY.md。构建好的内容如下:
     * # auto memory
     * ## User-level MEMORY.md (`C:/Users/xxx/.devecode/memory/MEMORY.md`)
     * - [prefers-chinese-answers](prefers-chinese-answers.md) — 用户偏好中文回复
     * ## Project-level MEMORY.md (`c:/.../devecode/.devecode/memory/MEMORY.md`)
     * - [project-uses-mcp-sdk](project-uses-mcp-sdk.md) — 本项目使用 MCP SDK 0.18.4
     */
    public String buildSystemReminder() {
        ensureDir(userMemDirPath);
        ensureDir(projectMemDirPath);

        var sb = new StringBuilder();
        sb.append("# auto memory\n\n");

        // 用户级MEMORY.md
        appendEntrypoint(sb, "User-level", userMemDirPath);
        sb.append("\n\n");
        // 项目级MEMORY.md
        appendEntrypoint(sb, "Project-level", projectMemDirPath);
        return sb.toString();
    }
    /*拼接scopeLabel与文件内容*/
    private static void appendEntrypoint(StringBuilder sb, String scopeLabel, Path memDir) {
        Path ep = memDir.resolve(ENTRYPOINT_NAME);
        sb.append("## %s %s (`%s`)\n\n".formatted(scopeLabel, ENTRYPOINT_NAME, ep));
        try {
            String content = Files.readString(ep).strip();
            if (!content.isEmpty()) {
                sb.append(content);
            } else {
                sb.append("This %s is currently empty.".formatted(ENTRYPOINT_NAME));
            }
        } catch (IOException e) {
            sb.append("This %s is currently empty.".formatted(ENTRYPOINT_NAME));
        }
    }

    // ---- Extraction via LLM ----

    /**
     * 扫描已有记忆文件，生成已有记忆清单给LLM做去重。
     */
    private String scanExistingMemories() {
        var entries = new ArrayList<String>();
        for (Path dir : List.of(userMemDirPath, projectMemDirPath)) {
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(f -> f.toString().endsWith(".md") && !f.getFileName().toString().equals(ENTRYPOINT_NAME))
                     .sorted()
                     .forEach(f -> {
                         try {
                             String content = Files.readString(f);
                             // 简单解析 frontmatter
                             String type = extractField(content, "type");
                             String desc = extractField(content, "description");
                             if (type.isEmpty()) type = "?";
                             if (desc.isEmpty()) desc = f.getFileName().toString();
                             entries.add("- [%s] %s: %s".formatted(type, f.getFileName(), desc));
                         } catch (IOException ignored) {}
                     });
            } catch (IOException ignored) {}
        }
        return String.join("\n", entries);
    }

    /**
     * 在shouldExtract()=True时调用,通过 LLM从对话中提取记忆
     * 发送已有记忆manifest做去重，使用 MEMORY_NAME/TYPE/DESC/BODY 格式解析输出。
     */
    public void extract(LlmClient client, ConversationManager conv) {
        //对话信息不足直接放弃
        List<Message> messages = conv.getMessages();
        if (messages.size() < 4) return;

        // 只取最近40条消息，得到[user]:内容/[assistant]:内容的纯文本行
        int start = Math.max(0, messages.size() - 40);
        var sb = new StringBuilder();
        for (int i = start; i < messages.size(); i++) {
            var msg = messages.get(i);
            sb.append('[').append(msg.getRole()).append("]: ").append(msg.getContent()).append('\n');
        }

        // 扫描已有记忆做去重
        String manifest = scanExistingMemories();
        String manifestSection = manifest.isEmpty() ? "" :
                "\n\n## Existing memory files\n\n" + manifest +
                "\n\nCheck this list before creating — update an existing file rather than creating a duplicate.";

        ConversationManager extractConv = new ConversationManager();
        extractConv.addUserMessage(
                "Analyze the conversation below and extract memories worth saving.\n\n"
                + "For each memory, output in this exact format:\n"
                + "MEMORY_NAME: <kebab-case-name>\n"
                + "MEMORY_TYPE: <user|feedback|project|reference>\n"
                + "MEMORY_DESC: <one-line description>\n"
                + "MEMORY_BODY: <content>\n"
                + "---\n\n"
                + "Types:\n"
                + "- user/feedback → save to " + userMemDirPath + "\n"
                + "- project/reference → save to " + projectMemDirPath + "\n\n"
                + "What NOT to save:\n"
                + "- Code patterns derivable from reading the project\n"
                + "- Git history, debugging solutions\n"
                + "- Ephemeral task details\n\n"
                + "If nothing is worth saving, output NONE." + manifestSection + "\n\n"
                + "Conversation:\n" + sb
        );
        //组装流式响应
        BlockingQueue<StreamEvent> events = client.stream(extractConv, null);
        var result = new StringBuilder();
        try {
            while (true) {
                StreamEvent event = events.take();
                if (event instanceof StreamEvent.TextDelta td) {
                    result.append(td.text());
                } else if (event instanceof StreamEvent.StreamEnd || event instanceof StreamEvent.Error) {
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        String output = result.toString().trim();
        if (output.isEmpty() || output.equals("NONE") || !output.contains("MEMORY_NAME:")) return;

        // 解析 MEMORY_NAME/TYPE/DESC/BODY 格式
        for (String block : output.split("---")) {
            if (!block.contains("MEMORY_NAME:")) continue;
            String name = extractField(block, "MEMORY_NAME");
            String type = extractField(block, "MEMORY_TYPE");
            String desc = extractField(block, "MEMORY_DESC");
            String body = extractField(block, "MEMORY_BODY");
            if (name.isEmpty() || body.isEmpty()) continue;
            if (!USER_TYPES.contains(type) && !PROJECT_TYPES.contains(type)) type = "reference";

            Path targetDir = USER_TYPES.contains(type) ? userMemDirPath : projectMemDirPath;
            writeMemoryFile(targetDir, name, type, desc, body);
        }
    }
    /*在block文本里找“field:”开头的行，返回冒号后面到行尾的内容；找不到返回空串。*/
    private static String extractField(String block, String field) {
        var m = Pattern.compile(field + ":\\s*(.+?)(?:\\n|$)").matcher(block);
        return m.find() ? m.group(1).trim() : "";
    }

    /**
     * 将一条记忆写为独立的 .md 文件，并在 MEMORY.md 索引中追加指针。
     */
    private void writeMemoryFile(Path dir, String name, String type, String description, String body) {
        ensureDir(dir);
        String filename = name + ".md";
        Path filePath = dir.resolve(filename);

        String fileContent = "---\nname: %s\ndescription: %s\nmetadata:\n  type: %s\n---\n\n%s\n"
                .formatted(name, description, type, body);
        try {
            //同名重写覆盖
            Files.writeString(filePath, fileContent);
        } catch (IOException e) {
            return;
        }
        // 更新MEMORY.md 索引
        Path entrypoint = dir.resolve(ENTRYPOINT_NAME);
        String pointer = "- [%s](%s) — %s\n".formatted(name, filename, description);
        try {
            String existing = Files.exists(entrypoint) ? Files.readString(entrypoint) : "";
            if (!existing.contains(filename)) {
                //ENTRYPOINT_NAME.md不存在该种文件才添加
                Files.writeString(entrypoint, existing + pointer);
            }
        } catch (IOException ignored) {}
    }


    // ---- Injection ----

    /**
     * 向对话注入已有的记忆内容（MEMORY.md 索引）。
     */
    public void injectMemories(ConversationManager conv) {
        String reminder = buildSystemReminder();
        if (reminder.isBlank()) {
            return;
        }
        if (conv.getMessages().isEmpty()) {
            conv.addUserMessage(reminder);
            conv.addAssistantMessage("Understood, I'll keep this context in mind.");
        }
    }

    /**
     * 加载指令文件，实际发现规则见 {@link InstructionLoader}：
     * 用户级 ~/.devecode/DEVECODE.md、~/.devecode/AGENTS.md；
     * 项目级从 git root 沿目录树向下到 workDir，每层找 DEVECODE.md / AGENTS.md；
     * 兼容旧版 .devecode/INSTRUCTIONS.md 与私有覆盖 DEVECODE.local.md；支持 @include 递归展开。
     */
    public static String loadInstructions(String workDir) {
        return InstructionLoader.loadInstructions(workDir);
    }
    // ---- Helpers ----

    private static void ensureDir(Path dir) {
        if (dir == null) return;
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {}
    }
}