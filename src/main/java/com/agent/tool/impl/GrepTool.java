package com.agent.tool.impl;

import com.agent.tool.Tool;
import com.agent.tool.ToolCategory;
import com.agent.tool.ToolRegistry;
import com.agent.tool.ToolResult;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
//Agent的文件内容搜索引擎——用正则表达式在项目文件中搜索匹配的行，返回文件路径:行号:内容 格式的结果。
public class GrepTool implements Tool {

    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".venv", "node_modules", "__pycache__", ".tox", ".mypy_cache"
    );

    private static final String DESCRIPTION = """
            Search file contents using a regex pattern, returning file:line:content matches.

            Usage notes:
            - Supports full regex syntax (e.g., "log.*Error", "func\\s+\\w+").
            - Filter files with the include parameter (e.g., "*.py", "*.go").
            - Search from "." or a specific path, never from "/".
            - Use this instead of grep or rg commands via Bash.
            - Automatically skips .git, node_modules, __pycache__, and similar directories.""";

    @Override
    public String name() {
        return "Grep";
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
        var properties = new LinkedHashMap<String, Object>();
        properties.put("pattern", Map.of("type", "string", "description", "Regex pattern to search for"));
        properties.put("path", Map.of("type", "string", "description", "Base directory to search from", "default", "."));
        properties.put("include", Map.of("type", "string", "description", "Glob filter for filenames (e.g. '*.py')"));

        return Map.of(
                "name", name(),
                "description", description(),
                "input_schema", Map.of(
                        "type", "object",
                        "properties", properties,
                        "required", List.of("pattern")
                )
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        //解析参数,搜索正则表达式 搜索起始目录 过滤的文件名
        String pattern = stringArg(args, "pattern", "");
        String basePath = stringArg(args, "path", ".");
        String include = stringArg(args, "include", "");
        if (basePath.isEmpty()) {
            basePath = ".";
        }
        //参数校验：合法正则 目录存在
        if (pattern.isEmpty()) {
            return ToolResult.error("Error: pattern is required");
        }

        Path root = Path.of(basePath);
        if (!Files.exists(root) || !Files.isDirectory(root)) {
            return ToolResult.error("Error: path not found: " + basePath);
        }
        //提前编译正则,Pattern.compile 耗时，编译一次后复用 regex.matcher(line) 每行匹配，避免反复编译。
        Pattern regex;
        try {
            regex = Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            return ToolResult.error("Error: invalid regex: " + e.getMessage());
        }
        //把 *.py 这样的 glob 模式转成 PathMatcher 对象。
        PathMatcher includeMatcher = include.isEmpty()
                ? null
                : FileSystems.getDefault().getPathMatcher("glob:" + include);

        // 先收集再搜索,得到确定性输出:
        //同一项目在不同OS上搜索，结果顺序不同:
        //边遍历边搜时截断:截断发生在"文件系统遍历顺序靠后"的文件中,不可预测.同一项目两次搜索,截断位置可能不同。
        var files = new ArrayList<Path>();
        try {
            //先收集所有满足要求的文件
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                //检查是否在 SKIP_DIRS ，是则 SKIP_SUBTREE 跳过整个子树
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (SKIP_DIRS.contains(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (includeMatcher != null && !includeMatcher.matches(file.getFileName())) {
                        return FileVisitResult.CONTINUE;
                    }
                    files.add(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            return ToolResult.error("Error: " + e.getMessage());
        }

        Collections.sort(files);

        var results = new ArrayList<String>();
        int totalChars = 0;

        for (Path file : files) {
            //跳过二进制文本
            if (isBinaryFile(file)) {
                continue;
            }

            try (BufferedReader reader = Files.newBufferedReader(file)) {
                String line;
                int lineNum = 0;
                while ((line = reader.readLine()) != null) {
                    lineNum++;
                    if (regex.matcher(line).find()) {
                        //转化为相对路径:
                        //- 绝对路径太长，浪费 token
                        //- 相对路径更简洁，AI 和用户都能理解
                        //- root.relativize(file) 把 file 转成相对于 root 的路径
                        String rel = root.relativize(file).toString();
                        //相对路径:行号:内容
                        String entry = rel + ":" + lineNum + ":" + line;
                        totalChars += entry.length() + 1; // +1 for newline
                        if (totalChars > ToolRegistry.MAX_OUTPUT_CHARS) {
                            results.add("... output truncated (max " + ToolRegistry.MAX_OUTPUT_CHARS + " chars)");
                            return ToolResult.success(String.join("\n", results));
                        }
                        results.add(entry);
                    }
                }
            } catch (IOException e) {
                // Skip files that can't be read
            }
        }

        if (results.isEmpty()) {
            return ToolResult.success("No matches found.");
        }
        return ToolResult.success(String.join("\n", results));
    }

    /**
     * 算法 ：读前 512 字节，检查是否含 null 字节（ \0 ）。
     * 为什么用null字节判断:
     * -文本文件不会含 \0 （文本编码 UTF-8/ASCII 都不用 \0 ）
     * -二进制文件（图片、编译产物、压缩文件）常含 \0
     * -这是git判断二进制文件的同样方法
     * 为什么只读512字节:
     * - 二进制文件的文件头通常就有 \0
     * - 读太多浪费 IO，512 字节足够判断
     * 异常处理:读取失败时返回 true （视为二进制），跳过该文件——"尽力而为"哲学。
     */
    private static boolean isBinaryFile(Path file) {
        try (InputStream is = Files.newInputStream(file)) {
            byte[] buf = new byte[512];
            int bytesRead = is.read(buf);
            if (bytesRead <= 0) {
                return false;
            }
            for (int i = 0; i < bytesRead; i++) {
                if (buf[i] == 0) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            return true; // Treat unreadable files as binary
        }
    }

    private static String stringArg(Map<String, Object> args, String key, String def) {
        var v = args.get(key);
        return v instanceof String s ? s : def;
    }
}

