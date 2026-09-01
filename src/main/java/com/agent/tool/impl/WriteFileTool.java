package com.agent.tool.impl;

import com.agent.tool.*;
import com.agent.tool.result.ToolResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class WriteFileTool implements Tool {

    private FileHistory fileHistory;
    private FileStateCache fileStateCache;

    public void setFileHistory(FileHistory fh) { this.fileHistory = fh; }
    public void setFileStateCache(FileStateCache c) { this.fileStateCache = c; }

    private static final String DESCRIPTION = """
            Write content to a file, creating parent directories if needed. Overwrites existing files.

            Usage notes:
            - If modifying an existing file, prefer EditFile over WriteFile — it only sends the diff.
            - Use this tool only to create new files or for complete rewrites.
            - You MUST read existing files with ReadFile before overwriting them.
            - NEVER create documentation files (*.md) or README files unless explicitly requested.""";

    @Override
    public String name() {
        return "WriteFile";
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.WRITE;
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of(
                "name", name(),
                "description", description(),
                "input_schema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "file_path", Map.of("type", "string", "description", "Path to the file to write"),
                                "content", Map.of("type", "string", "description", "Content to write to the file")
                        ),
                        "required", List.of("file_path", "content")
                )
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String filePath = stringArg(args, "file_path", "");
        String content = stringArg(args, "content", "");

        if (filePath.isEmpty()) {
            return ToolResult.error("Error: file_path is required");
        }
        // 写入前先备份
        if (fileHistory != null) fileHistory.trackEdit(filePath);

        Path path = Path.of(filePath);

        // 强制写前读,判断是否读过、是否被修改,新创建文件会跳过
        if (fileStateCache != null && Files.exists(path)) {
            String absPath = path.toAbsolutePath().toString();
            String err = fileStateCache.validate(absPath);
            if (err != null) return ToolResult.error(err);
        }
        //判断是否支持POSIX权限模型
        boolean posix = path.getFileSystem().supportedFileAttributeViews().contains("posix");

        try {
            Path parent = path.getParent();
            //创建父目录,在支持POSIX权限模型的系统上还会设置POSIX权限模型
            if (parent != null) {
                if (posix) {
                    //设置权限,所有者可读写执行、其他人可读和执行，rwx rx rx
                    Set<PosixFilePermission> dirPerms = PosixFilePermissions.fromString("rwxr-xr-x");
                    Files.createDirectories(parent, PosixFilePermissions.asFileAttribute(dirPerms));
                } else {
                    Files.createDirectories(parent);
                }
            }
        } catch (IOException e) {
            return ToolResult.error("Error creating directories: " + e.getMessage());
        }
        //写文件并设置权限,Files.writeString 默认 CREATE, WRITE, TRUNCATE_EXISTING- 即：文件不存在则创建，存在则清空后写入，与函数描述一致
        //可通过StandardOpenOption修改
        try {
            Files.writeString(path, content);
            if (posix) {
                Set<PosixFilePermission> filePerms = PosixFilePermissions.fromString("rw-r--r--");
                Files.setPosixFilePermissions(path, filePerms);
            }
        } catch (IOException e) {
            return ToolResult.error("Error writing file: " + e.getMessage());
        }

        // Update cache with new content + mtime
        if (fileStateCache != null) {
            fileStateCache.update(path.toAbsolutePath().toString(), content);
        }

        return ToolResult.success("Successfully wrote to " + filePath);
    }


    private static String stringArg(Map<String, Object> args, String key, String def) {
        var v = args.get(key);
        return v instanceof String s ? s : def;
    }
}

