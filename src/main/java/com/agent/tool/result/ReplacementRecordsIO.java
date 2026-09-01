package com.agent.tool.result;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 序列化器
 */
public final class ReplacementRecordsIO {

    public static final String RECORDS_FILENAME = "replacement_records.jsonl";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ReplacementRecordsIO() {}

    /**
     * 将record添加到sessionDir+replacement_records.jsonl文件中
     * @param sessionDir 目标目录
     * @param records 待写入的records
     */
    public static void append(Path sessionDir, List<ContentReplacementRecord> records) throws IOException {
        if (records.isEmpty()) return;
        Files.createDirectories(sessionDir);
        Path file = sessionDir.resolve(RECORDS_FILENAME);
        try (BufferedWriter w = Files.newBufferedWriter(
                file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND
        )) {
            for (ContentReplacementRecord r : records) {
                String kind = r.kind() == null || r.kind().isEmpty()
                        ? ContentReplacementRecord.KIND_TOOL_RESULT
                        : r.kind();
                ContentReplacementRecord normalized = new ContentReplacementRecord(
                        kind, r.toolUseId(), r.replacement());
                try {
                    w.write(MAPPER.writeValueAsString(normalized));
                } catch (JsonProcessingException e) {
                    throw new IOException(e);
                }
                w.write('\n');
            }
        }
    }

    /**
     * 从sessionDir+replacement_records.jsonl文件中读取records
     * @param sessionDir 目标目录
     * @return records
     */
    public static List<ContentReplacementRecord> load(Path sessionDir) throws IOException {
        Path file = sessionDir.resolve(RECORDS_FILENAME);
        if (!Files.exists(file)) return Collections.emptyList();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<ContentReplacementRecord> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            if (line.isEmpty()) continue;
            //jsonl格式的标准消费方式——每行一个独立 JSON 文档，
            out.add(MAPPER.readValue(line, ContentReplacementRecord.class));
        }
        return out;
    }
}