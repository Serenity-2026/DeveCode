package com.agent.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
/*
FileHistory是项目的文件历史快照管理器，
实现了类似"时光机/检查点"的功能——让用户能把代码和对话回退到之前某个时间点的状态。它是 AI Agent 编辑文件时的安全网。
用户发起对话轮次
   │
   ├── AI 调用 EditFileTool/WriteFileTool 修改文件
   │      └── 修改前 → fileHistory.trackEdit(path)
   │                  [备份旧内容 + trackedFiles 版本号+1]
   │
   ├── AI 不再调用工具（轮次结束）
   │      └── Agent → fileHistory.makeSnapshot(msgIndex, userText)
   │                  [整理所有跟踪文件的最新备份 + 创建 Snapshot + 加入列表]
   │
   │  ... 多轮对话累积多个 Snapshot ...
   │
   └── 用户选择回退到第 N 个检查点
          └── fileHistory.rewind(N)
                 ├── 还原每个文件到目标快照时的内容
                 ├── 删除目标快照时刻不存在的文件
                 ├── 截断后续快照
                 └── 重置版本号
          │
          └── 调用方 conversation.truncateTo(msgIndex)  ← 截断对话
* */
public class FileHistory {
    //单个文件某次编辑前的备份副本信息（备份文件路径 + 版本号 + 时间），描述单个文件某次备份的元信息
    public record Backup(String backupPath, int version, Instant time) {}
    //描述一次对话检查点——某一轮对话结束时所有被跟踪文件的整体状态快照。
    public record Snapshot(
            int messageIndex,
            String userText,
            Map<String, Backup> backups,
            Instant timestamp
    ) {}

    private static final int MAX_SNAPSHOTS = 100;

    private final Path sessionDir;
    //记录每个被跟踪文件的 当前版本号 （键=文件绝对路径，值=版本号，从 1 递增）
    private final Map<String, Integer> trackedFiles = new LinkedHashMap<>();
    //按时间顺序存储所有对话检查点
    private final List<Snapshot> snapshots = new CopyOnWriteArrayList<>();

    /**
     *
     * @param baseDir 项目工作目录的根路径
     * @param sessionId 当前会话唯一标识
     */
    public FileHistory(String baseDir, String sessionId) {
        this.sessionDir = Path.of(baseDir, ".devecode", "file-history", sessionId);
        try {
            Files.createDirectories(sessionDir);
        } catch (IOException ignored) {}
    }

    /**
     * 根据文件路径 + 版本号，生成磁盘上的备份文件名，相同文件路径及版本号得到的备份文件名相同
     * @param filePath 文件名
     * @param version 版本号
     * @return 备份文件名
     */
    private String backupName(String filePath, int version) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(filePath.getBytes());
            //格式化为2位16进制数,不足两位时补0
            return "%02x%02x%02x%02x%02x%02x%02x%02x@v%d".formatted(
                    hash[0], hash[1], hash[2], hash[3],
                    hash[4], hash[5], hash[6], hash[7], version);
        } catch (Exception e) {
            return "backup-" + filePath.hashCode() + "@v" + version;
        }
    }

    /**
     * 作用：在文件被修改之前，备份其当前内容，并递增版本号。
     * 调用时机：EditFileTool 和 WriteFileTool 在执行修改操作之前调用。
     * synchronized保证线程安全
     * @param path 相对路径/绝对路径
     */
    public synchronized void trackEdit(String path) {
        //统一路径
        Path absPath;
        try {
            absPath = Path.of(path).toAbsolutePath();
        } catch (Exception e) {
            absPath = Path.of(path);
        }
        String key = absPath.toString();

        int ver = trackedFiles.getOrDefault(key, 0);
        int newVer = ver + 1;
        //读当前内容，写到备份文件
        try {
            byte[] data = Files.readAllBytes(absPath);
            //path.resolve把相对路径转化为绝对路径
            Path bp = sessionDir.resolve(backupName(key, newVer));
            Files.write(bp, data);
        } catch (IOException ignored) {
            // File doesn't exist yet (new file) — no backup, but still track
        }

        trackedFiles.put(key, newVer);
    }

    /**
     * 作用 ：在对话轮次结束时创建一个检查点，记录所有被跟踪文件的当前状态。
     * 调用时机 ： Agent.java:389-392 ，当 AI 一轮对话结束（不再调用工具）时触发。
     * @param msgIndex:当前对话消息列表的长度，回退时调用方据此截断对话
     * @param userText:该轮用户输入的摘要文本，UI 展示用
     *为什么在"轮次结束"时打快照而非"每次编辑"时 ：
     * - 一次轮次内可能编辑多个文件，回退的粒度应该是"一轮对话"而非"一次编辑"
     * - 用户的心智模型是"回到我说那句话之前的状态"，而非"回到某次编辑之前"
     * - 这样回退粒度与对话粒度对齐，体验更直观
     * 使用sync保证线程安全:
     * - 遍历 trackedFiles 期间不能被 trackEdit 并发修改
     * - "整理 backups + add snapshot + removeFirst" 三步必须原子
     */
    public synchronized void makeSnapshot(int msgIndex, String userText) {
        var backups = new LinkedHashMap<String, Backup>();
        //整理所有的backup备份文件
        for (var entry : trackedFiles.entrySet()) {
            String path = entry.getKey();
            int ver = entry.getValue();
            String bpName = backupName(path, ver);
            Path bp = sessionDir.resolve(bpName);
            // AI 在本轮新建了一个文件：trackEdit 时文件不存在，没生成备份文件，但 trackedFiles 里版本号已 +1
            if (!Files.exists(bp)) {
                try {
                    byte[] data = Files.readAllBytes(Path.of(path));
                    Files.write(bp, data);
                } catch (IOException ignored) {}
            }

            backups.put(path, new Backup(bp.toString(), ver, Instant.now()));
        }

        snapshots.add(new Snapshot(msgIndex, userText, backups, Instant.now()));
        //FIFO，超过MAX_SNAPSHOTS时丢弃最旧的
        while (snapshots.size() > MAX_SNAPSHOTS) {
            snapshots.removeFirst();
        }
    }
    //List.copyOf浅拷贝,返回不可变的列表对象
    public List<Snapshot> getSnapshots() {
        return List.copyOf(snapshots);
    }

    public boolean hasSnapshots() {
        return !snapshots.isEmpty();
    }

    /**
     * 把所有被跟踪文件还原到指定检查点时的状态。
     * @param snapshotIndex 目标检查点在 snapshots 列表中的索引
     * @return 实际被还原（发生改动）的文件路径列表，UI 据此显示"已还原 N 个文件"
     */
    public synchronized List<String> rewind(int snapshotIndex) {
        if (snapshotIndex < 0 || snapshotIndex >= snapshots.size()) {
            return List.of();
        }

        Snapshot target = snapshots.get(snapshotIndex);
        var changed = new ArrayList<String>();
        var targetFiles = target.backups().keySet();
        //逐文件还原
        for (var entry : target.backups().entrySet()) {
            String filePath = entry.getKey();
            Backup backup = entry.getValue();

            Path path = Path.of(filePath);
            try {
                //读备份内容
                byte[] backupData = Files.readAllBytes(Path.of(backup.backupPath()));
                byte[] currentData = new byte[0];
                try {
                    //读当前内容,可能已被删除
                    currentData = Files.readAllBytes(path);
                } catch (IOException ignored) {}
                //只有不同才写回
                if (!Arrays.equals(currentData, backupData)) {
                    //若已被删除,重建父目录
                    Files.createDirectories(path.getParent());
                    Files.write(path, backupData);
                    changed.add(filePath);
                }
            } catch (IOException e) {
                // 备份文件不存在 = 该文件在目标快照时刻 根本不存在 ，所以回退时应 删除当前文件 。
                try {
                    if (Files.exists(path)) {
                        Files.delete(path);
                        changed.add(filePath);
                    }
                } catch (IOException ignored) {}
            }
        }
        // ===== 删除目标快照之后才被跟踪的文件 =====
        var toRemove = new ArrayList<String>();
        for (var entry : trackedFiles.entrySet()) {
            String filePath = entry.getKey();
            if (!targetFiles.contains(filePath)) {
                // 该文件在目标快照之后才被跟踪 → 应删除
                try {
                    Path path = Path.of(filePath);
                    if (Files.exists(path)) {
                        Files.delete(path);
                        changed.add(filePath);
                    }
                } catch (IOException ignored) {}
                toRemove.add(filePath);
            }
        }
        for (String path : toRemove) {
            trackedFiles.remove(path);
        }
        // 删除目标快照之后的所有快照。
        while (snapshots.size() > snapshotIndex + 1) {
            snapshots.removeLast();
        }

        // 终止版本号
        for (var entry : target.backups().entrySet()) {
            trackedFiles.put(entry.getKey(), entry.getValue().version());
        }

        return changed;
    }
}

