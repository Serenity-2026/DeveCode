package com.agent.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 编辑前读，强制缓存（Read-before-edit enforcement cache）。
 * 记录哪些文件已经被 ReadFile 工具读取过（包含完整内容和修改时间），
 * 这样 EditFile / WriteFile 工具就可以拒绝修改从未读取过的文件，
 * 强制执行 “先读后改” 的纪律，防止盲写覆盖。
 */
public class FileStateCache {

    //记录一个文件的状态：内容 + 修改时间（毫秒），使用 record 简化不可变数据类。
    public record FileState(String content, long mtimeMs) {}
    //多工具调用，保障线程安全
    private final ConcurrentHashMap<String, FileState> cache = new ConcurrentHashMap<>();

    public void record(String absPath, String content, long mtimeMs) {
        cache.put(absPath, new FileState(content, mtimeMs));
    }
    /**1.当 Agent 调用 EditFile 时，工具会从缓存中取出 content。
    *它会检查 Agent 提供的 old_content 是否真的存在于缓存记录的 content 中。
    *如果存在：说明 Agent 是基于它“看过”的准确内容进行修改的，允许执行。
    *如果不存在：说明 Agent 胡编乱造了一段不存在的代码来修改，工具会直接报错拒绝。
    *Update cache after a successful edit/write. Re-reads mtime from disk.
    */
    public void update(String absPath, String newContent) {
        long mtime;
        try {
            mtime = Files.getLastModifiedTime(Path.of(absPath)).toMillis();
        } catch (IOException e) {
            mtime = System.currentTimeMillis();
        }
        cache.put(absPath, new FileState(newContent, mtime));
    }

    /** Returns null if the file has never been read. */
    public FileState get(String absPath) {
        return cache.get(absPath);
    }

    /**
     * Validate that a file is safe to edit/write.
     * Returns null if OK, or an error message string if the edit should be blocked.
     */
    public String validate(String absPath) {
        FileState state = cache.get(absPath);
        if (state == null) {
            return "Error: file has not been read yet. Read it first before editing.";
        }
        long currentMtime;
        try {
            currentMtime = Files.getLastModifiedTime(Path.of(absPath)).toMillis();
        } catch (IOException e) {
            // File might have been deleted; let the caller's own exists-check handle it
            return null;
        }
        if (currentMtime > state.mtimeMs()) {
            return "Error: file has been modified since last read. Read it again before editing.";
        }
        return null;
    }
}

