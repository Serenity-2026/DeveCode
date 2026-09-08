
package com.agent.history;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 负责把用户输入的提示词以JSONL（每行一个 JSON 对象）形式持久化到磁盘，并在内存中维护一份“输入历史”列表供 UI 回看。
 *  每条记录形如 {"text":"用户输入","ts":Unix秒级时间戳}；
 *  连续重复输入会被抑制（去重）；
 *  容量上限 200，超出时丢弃最旧记录，行为类似环形缓冲。
 *  调用链:DeveCodeModel.java持有historyStore，在启动时load()，发送消息时append()，按↑/↓键时用getEntries()回看。
 */
public class HistoryStore {

    private static final int MAX_ENTRIES = 200;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path filePath;
    /* jsonl基本存储单元:用户message-timestamp*/
    private record Entry(String text, long ts) {}
    private final List<Entry> entries = new ArrayList<>();

    /** 默认使用/user.home/.devecode */
    public HistoryStore() {
        this(Path.of(System.getProperty("user.home"), ".devecode", "prompt_history.jsonl"));
    }

    /** Testable constructor that accepts an explicit file path. */
    public HistoryStore(Path filePath) {
        this.filePath = filePath;
    }

    // ------------------------------------------------------------------
    // Load
    // ------------------------------------------------------------------

    /**
     * Reads the JSONL file and populates the in-memory entry list.
     */
    public void load() {
        entries.clear();
        if (!Files.exists(filePath)) {
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(filePath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    //JSON字符串解析为JsonNode树形结构，方便按需访问节点数据，而无需一次性映射到Java对象，这里只取text
                    var node = MAPPER.readTree(line);
                    var textNode = node.get("text");
                    if (textNode == null || !textNode.isTextual()) {
                        continue;
                    }
                    String text = textNode.asText().trim();
                    if (text.isEmpty()) {
                        continue;
                    }
                    var tsNode = node.get("ts");
                    long ts = (tsNode != null && tsNode.isIntegralNumber())
                            ? tsNode.asLong()
                            : Instant.now().getEpochSecond();
                    entries.add(new Entry(text, ts));
                } catch (Exception ignored) {
                    // skip malformed lines
                }
            }
        } catch (IOException ignored) {
            // file unreadable — start with empty history
        }
    }

    // ------------------------------------------------------------------
    // Append
    // ------------------------------------------------------------------

    /**
     * Appends a new entry to the history.
     */
    public void append(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }

        // 去重:与最后一条相同则直接返回
        if (!entries.isEmpty() && entries.getLast().text().equals(text)) {
            return;
        }

        entries.add(new Entry(text,Instant.now().getEpochSecond()));

        // 裁剪,subList不复制，而是返回一个指向原列表区间的“视图”对象,clear后该区域为空即实现移除功能
        if (entries.size() > MAX_ENTRIES) {
            int excess = entries.size() - MAX_ENTRIES;
            entries.subList(0, excess).clear();
        }

        writeToDisk();
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    /** Returns an unmodifiable snapshot of the current entries. */
    public List<String> getEntries() {
        return entries.stream().map(Entry::text).toList();
    }

    /** Number of entries currently held. */
    public int size() {
        return entries.size();
    }

    /** Returns the entry at the given index. */
    public String get(int index) {
        return entries.get(index).text();
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /** Rewrites the full JSONL file from the in-memory list.
     * 先在同一目录创建 prompt-history*.tmp 临时文件并写入全部内容，再 Files.move(tmp, filePath, REPLACE_EXISTING, ATOMIC_MOVE)（history/HistoryStore.java:184）完成替换。
     * 同目录保证同文件系统，是 ATOMIC_MOVE 生效的前提；原子替换使读取方永远只能看到完整的旧文件或完整的新文件。
     * 文件系统不支持原子移动时降级为普通 REPLACE_EXISTING；finally 中清理残留临时文件；所有失败仍按 best-effort 吞掉。
     * */
    private void writeToDisk() {
        Path dir = filePath.getParent();
        if (dir == null) {
            return;
        }
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            return;
        }

        Path tmp = null;
        try {
            tmp = Files.createTempFile(dir, "prompt-history", ".tmp");
            try (BufferedWriter writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                for (Entry entry : entries) {
                    var node = MAPPER.createObjectNode();
                    node.put("text", entry.text());
                    node.put("ts", entry.ts());
                    writer.write(MAPPER.writeValueAsString(node));
                    writer.newLine();
                }
            }

            try {
                Files.move(tmp, filePath,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicNotSupported) {
                // 文件系统不支持原子移动时降级为普通 REPLACE_EXISTING
                Files.move(tmp, filePath, StandardCopyOption.REPLACE_EXISTING);
            }
            tmp = null; // moved successfully; nothing left to clean up
        } catch (IOException ignored) {
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {

                }
            }
        }
    }
}
