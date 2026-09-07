package com.agent.compact;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RecoveryState 是 压缩前的快照记录器 ——在压缩把旧消息清空之前，记录"AI 最近读过哪些文件"、
 * "激活了哪些 skill"，压缩后把这些信息附加到摘要消息中，避免 AI 失忆。
 * 每个 Agent 持有一个独立实例，记自己跑过的 ReadFile 与 Skill。与ContextCompactor两个文件互补，一个无状态，一个有状态，分工很清晰。
 */
public final class RecoveryState {

    /** 文件路径 + 内容快照 + 时间,压缩后 AI 不记得读过什么文件，快照让它能继续引用 */
    public record FileReadRecord(String path, String content, Instant timestamp) {}

    /** skill 名称 + SOP 正文 + 时间 压缩后 AI 忘记了 skill 的操作规范，需要重新附加*/
    public record SkillInvocationRecord(String name, String body, Instant timestamp) {}

    private final ConcurrentHashMap<String, FileReadRecord> files = new ConcurrentHashMap<>();

    // recordSkillInvocation 在工具执行线程（虚拟线程并行批次）被调用，须与 files 一致用并发容器
    private final Map<String, SkillInvocationRecord> skills = new ConcurrentHashMap<>();

    /** 记录文件读取 */
    public void recordFileRead(String path, String content) {
        if (path == null || path.isEmpty()) return;
        files.put(path, new FileReadRecord(path, content, Instant.now()));

    }

    /** 记录 skill 调用 */
    public void recordSkillInvocation(String name, String body) {
        if (name == null || name.isEmpty()) return;
        skills.put(name, new SkillInvocationRecord(name, body, Instant.now()));
    }

    /**
     * 获取文件快照
     * @param limit 需要获取的最近的文件快照数目
     * @return 最近读过的文件
     */
    public List<FileReadRecord> snapshotFiles(int limit) {
        List<FileReadRecord> out;
        out = new ArrayList<>(files.values());
        out.sort(Comparator.comparing(FileReadRecord::timestamp).reversed());
        if (limit > 0 && out.size() > limit) {
            return out.subList(0, limit);
        }
        return out;
    }

    /**
     * 获取skill快照
     * @return 最近读过的skill
     */
    public List<SkillInvocationRecord> snapshotSkills() {
        List<SkillInvocationRecord> out;
        out = new ArrayList<>(skills.values());
        out.sort(Comparator.comparing(SkillInvocationRecord::timestamp).reversed());
        return out;
    }
}