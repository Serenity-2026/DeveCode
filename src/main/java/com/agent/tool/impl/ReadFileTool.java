package com.agent.tool.impl;

import com.agent.tool.FileStateCache;
import com.agent.tool.PathContext;
import com.agent.tool.Tool;
import com.agent.tool.ToolCategory;
import com.agent.tool.result.ToolResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
/**
 *  ReadFile 工具实现。
 *  功能：读取指定文件的内容，返回带有行号的分段文本。
 *  关键行为：
 *  - 支持 offset（起始行，0‑based）和 limit（最大行数）参数，便于读取大文件的部分内容。
 *  - 读取完成后，将文件路径、完整内容和当时的 mtime 记录到 FileStateCache 中。
 *  - 特别强调：不要为了验证编辑结果而重新读取刚编辑过的文件（因为 EditFile 本身会在失败时报错）。
 */
public class ReadFileTool implements Tool {
    //保证read write edit操纵的是同一块内存
    private FileStateCache fileStateCache;

    public void setFileStateCache(FileStateCache c) { this.fileStateCache = c; }

    private static final String DESCRIPTION = """
            Read a file and return its contents with line numbers.

            Usage notes:
            - The file_path parameter should be an absolute path when possible.
            - By default reads up to 2000 lines from the beginning of the file.
            - Use offset and limit to read specific parts of large files. Only read what you need.
            - Results are returned with line numbers (1-based) for easy reference.
            - This tool can only read files, not directories. Use Glob to list directory contents.
            - Do NOT re-read a file you just edited to verify — EditFile would have errored if the change failed.""";

    @Override
    public String name() {
        return "ReadFile";
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.READ;
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of(
                "name", name(),
                "description", description(),
                "input_schema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "file_path", Map.of("type", "string", "description", "Absolute or relative path to the file to read"),
                                "offset", Map.of("type", "integer", "description", "Line offset to start reading from (0-based)", "default", 0),
                                "limit", Map.of("type", "integer", "description", "Maximum number of lines to read", "default", 2000)
                        ),
                        "required", List.of("file_path")
                )
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String filePath = stringArg(args, "file_path", "");
        if (filePath.isEmpty()) {
            return ToolResult.error("Error: file_path is required");
        }

        int offset = intArg(args, "offset", 0);
        int limit = intArg(args, "limit", 2000);

        Path path = PathContext.resolve(filePath);

        if (!Files.exists(path)) {
            return ToolResult.error("Error: file not found: " + filePath);
        }
        if (Files.isDirectory(path)) {
            return ToolResult.error("Error: not a file: " + filePath);
        }

        String content;
        try {
            content = Files.readString(path);
        } catch (IOException e) {
            return ToolResult.error("Error reading file: " + e.getMessage());
        }
        //limit=-1表示尽可能多次分割，保留所有尾随的空字符串。
        String[] lines = content.split("\n", -1);
        //如果 offset 超出总行数，返回空字符串（表示无内容可读），offset:0 based
        if (offset >= lines.length) {
            return ToolResult.success("");
        }
        // 计算实际结束位置（不超出数组范围）
        int end = offset + limit;
        if (end > lines.length) {
            end = lines.length;
        }

        // Record in file-state cache so EditFile/WriteFile know the file has been read
        if (fileStateCache != null) {
            try {
                long mtime = Files.getLastModifiedTime(path).toMillis();
                fileStateCache.record(path.toAbsolutePath().toString(), content, mtime);
            } catch (IOException ignored) {
                // best-effort: don't fail the read because of mtime lookup
            }
        }

        var sb = new StringBuilder();
        for (int i = offset; i < end; i++) {
            if (i > offset) {
                sb.append('\n');
            }
            //添加行号,based 1
            sb.append(i + 1).append('\t').append(lines[i]);
        }

        return ToolResult.success(sb.toString());
    }

    /**
     * @param args params map
     * @param key param you want to get
     * @param def default value
     * @return if args.get(key) is String return s,else return def
     */
    private static String stringArg(Map<String, Object> args, String key, String def) {
        var v = args.get(key);
        return v instanceof String s ? s : def;
    }

    /**
     *
     * @param args params map
     * @param key  param you want to get
     * @param def  default value
     * @return if args.get(key) is Number return s,else return def
     */
    private static int intArg(Map<String, Object> args, String key, int def) {
        var v = args.get(key);
        return v instanceof Number n?n.intValue():def;
    }
}
