package com.agent.session;

import com.agent.history.ConversationManager;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;


import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.*;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

public class SessionManager {

    /**
     * 记录类型的判别字符串。配合 SessionMessage.type 字段形成“普通消息 / 边界记录”的二分
     */
    public static final String TYPE_COMPACT_BOUNDARY = "compact_boundary";

    /**
     * 会话消息记录
     * @param role user/assistant/system
     * @param type compact_boundary:文件里的“书签”,autoCompact把ConversationManager的旧前缀替换成“摘要+保留”消息,但是.jsonl历史记录文件里的记录没变，
     *             需要有标识表示在这之前的记录没用
     * @param content json
     * @param timestamp
     * @param toolUseId
     */
    public record SessionMessage(String role, String type, String content, long timestamp, String toolUseId) {
        public SessionMessage(String role, String content, long timestamp) {
            this(role, null, content, timestamp, null);
        }
        public SessionMessage(String role, String type, String content, long timestamp) {
            this(role, type, content, timestamp, null);
        }

        public boolean isCompactBoundary() {
            return TYPE_COMPACT_BOUNDARY.equals(type);
        }
    }

    /* autoCompact时保留的消息数组基本单元*/
    public record KeepMessage(String role, String content) {}

    /* 边界标记，文件里的“书签”,autoCompact把ConversationManager的旧前缀替换成“摘要+保留”消息,但是.jsonl历史记录文件里的记录没变，
     * 需要有标识表示在这之前的记录没用。
     * summary:摘要
     * keep:保留的消息
     * */
    public record CompactBoundary(String summary, List<KeepMessage> keep) {}

    /*
    boundary:边界本身
    after:边界之后的普通消息
    found:是否找到
    */
    public record BoundaryScan(CompactBoundary boundary, List<SessionMessage> after, boolean found) {}
    /*会话基本信息*/
    public record SessionInfo(String id, String firstMessage, int messageCount,
                              long fileSize, String gitBranch, Instant modTime) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /*生成传入workDir对应的session管理文件夹id*/
    private static Path sessionsDir(String workDir) {
        return Path.of(workDir, ".devecode", "sessions");
    }

    // ---- ID generation ----

    /**
     * 生成带随机后缀的 session ID，格式为 yyyyMMdd-HHmmss-xxxx。
     * 随机后缀使用 SecureRandom 生成 2 字节十六进制，防止同秒并发冲突。
     * id=时间戳-随机戳(防止同一秒生成多个文件)
     */
    public static String newId() {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        byte[] randomBytes = new byte[2];
        try {
            SecureRandom.getInstanceStrong().nextBytes(randomBytes);
        } catch (NoSuchAlgorithmException e) {
            // SecureRandom 极少失败；兜底用纳秒低16位
            int fallback = (int) (System.nanoTime() & 0xFFFF);
            return "%s-%04x".formatted(timestamp, fallback);
        }
        return "%s-%s".formatted(timestamp,
                HexFormat.of().formatHex(randomBytes));
    }

    // ---- Persistence ----

    public static void saveMessage(String workDir, String sessionId, String role, String content) {
        if (workDir == null || workDir.isBlank() || sessionId == null || sessionId.isBlank()) {
            return;
        }
        saveRecord(workDir, sessionId, role, null, content, null);
    }

    /**
     * 保存带 toolUseId 的消息，用于resume时的chain validation。
     */
    public static void saveMessageWithToolUseId(String workDir, String sessionId,
                                                 String role, String content, String toolUseId) {
        if (workDir == null || workDir.isBlank() || sessionId == null || sessionId.isBlank() || role == null || role.isBlank()) {
            return;
        }
        saveRecord(workDir, sessionId, role, null, content, toolUseId);
    }

    /**
     * 在.jsonl文件中保存CompactBoundary记录
     */
    public static void saveCompactBoundary(String workDir, String sessionId,
                                           String summary, List<KeepMessage> keep) {
        if (workDir == null || workDir.isBlank() || sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            String blob = MAPPER.writeValueAsString(
                    new CompactBoundary(summary, keep == null ? List.of() : keep));
            saveRecord(workDir, sessionId, "system", TYPE_COMPACT_BOUNDARY, blob, null);
        } catch (JsonProcessingException ignored) {
            //best-effort:best-effort:界写失败→下次resume全量重放，依然正确（向后兼容）
        }
    }
    /*
    * 将一行记录保存到。jsonl文件中。一行内容含:{"role","type","content","ts","tool_use_id"}
    * */
    private static void saveRecord(String workDir, String sessionId,
                                   String role, String type, String content, String toolUseId) {
        try {
            Path baseDir = sessionsDir(workDir);
            Files.createDirectories(baseDir);
            Path file = baseDir.resolve(sessionId + ".jsonl");
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("role", role);
            if (type != null && !type.isEmpty()) {
                line.put("type", type);
            }
            line.put("content", content);
            line.put("ts", Instant.now().getEpochSecond());
            if (toolUseId != null && !toolUseId.isEmpty()) {
                line.put("tool_use_id", toolUseId);
            }
            String json = MAPPER.writeValueAsString(line) + "\n";
            Files.writeString(file, json, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }
    /*
    * 每行内容:{"role","type","content","ts","tool_use_id"}
    *
    * */
    public static List<SessionMessage> loadSession(String workDir, String sessionId) {
        Path file = sessionsDir(workDir).resolve(sessionId + ".jsonl");
        if (!Files.exists(file)) {
            return List.of();
        }
        List<SessionMessage> messages = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    Map<String, Object> map = MAPPER.readValue(line, Map.class);
                    String role = (String) map.get("role");
                    String type = (String) map.get("type");
                    String content = (String) map.get("content");
                    long ts = map.get("ts") instanceof Number n ? n.longValue() : 0L;
                    String toolUseId = (String) map.get("tool_use_id");
                    if (content != null && !content.isEmpty()) {
                        messages.add(new SessionMessage(role, type, content, ts, toolUseId));
                    }
                } catch (IOException ignored) {
                    // skip malformed lines
                }
            }
        } catch (IOException ignored) {
            // return whatever we collected so far
        }
        return messages;
    }

    // ---- Compaction-boundary scanning ----

    /**
     * 一个会话可能压缩多次（长会话：第一次压缩后又涨满、再压一次，每次都追加一条边界）。只有最后一条边界代表当前状态，更早的边界和它们之间的原始消息全部作废。
     * 报错时 —— 宁可全量重放（正确但慢），不可带着坏书签重建（快但错）
     */
    public static BoundaryScan findLastCompactBoundary(List<SessionMessage> messages) {
        int last = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).isCompactBoundary()) {
                last = i;
            }
        }
        if (last < 0) {
            return new BoundaryScan(null, List.of(), false);
        }
        CompactBoundary boundary;
        try {
            boundary = MAPPER.readValue(messages.get(last).content(), CompactBoundary.class);
        } catch (IOException e) {
            return new BoundaryScan(null, List.of(), false);
        }
        List<SessionMessage> after = new ArrayList<>();
        for (int i = last + 1; i < messages.size(); i++) {
            SessionMessage m = messages.get(i);
            after.add(m);
        }
        return new BoundaryScan(boundary, after, true);
    }

    // ---- Conversation rebuild ----

    /**
     * 重建会话
     */
    public static ConversationManager rebuildConversation(List<SessionMessage> messages) {
        BoundaryScan scan = findLastCompactBoundary(messages);
        if (!scan.found()) {
            //老会话/无边界:全量重建
            return replay(messages);
        }
        List<SessionMessage> replay = new ArrayList<>();
        // 摘要转user消息
        String resumeSummary = "本次会话延续自之前的对话，因上下文空间不足进行了压缩。以下是早期对话的摘要：\n\n"
                + scan.boundary().summary();
        if (!scan.boundary().keep().isEmpty()) {
            resumeSummary += "\n\n近期消息已原样保留。";
        }
        replay.add(new SessionMessage("user", resumeSummary, 0L));
        //将分割点前保留的消息及分割点后的消息都加入message列表
        for (KeepMessage k : scan.boundary().keep()) {
            replay.add(new SessionMessage(k.role(), k.content(), 0L));
        }
        replay.addAll(scan.after());
        return replay(replay);
    }

    /**
     *根据消息列表重建对话,跳过CompactBoundary类型
     */
    private static ConversationManager replay(List<SessionMessage> messages) {
        ConversationManager conversation = new ConversationManager();
        for (SessionMessage msg : messages) {
            if (msg.isCompactBoundary()) continue; // never replay the raw boundary blob
            switch (msg.role()) {
                case "assistant" -> conversation.addAssistantMessage(msg.content());
                default -> conversation.addUserMessage(msg.content());
            }
        }
        return conversation;
    }

    // ---- Session expiry cleanup ----

    /** 过期阈值：30 天 */
    private static final long EXPIRY_DAYS = 30;

    /**
     * 自动清理超过 30 天的过期 session 文件。
     * 根据文件的最后修改时间判断是否过期。
     * 失败时静默忽略——清理是尽力而为，不应影响正常流程。
     */
    public static void cleanExpiredSessions(String workDir) {
        Path baseDir = sessionsDir(workDir);
        if (!Files.isDirectory(baseDir)) {
            return;
        }
        long cutoffMs = System.currentTimeMillis() - EXPIRY_DAYS * 24 * 60 * 60 * 1000L;
        try (Stream<Path> paths = Files.list(baseDir)) {
            paths.filter(p -> p.toString().endsWith(".jsonl"))
                 .filter(Files::isRegularFile)
                 .forEach(p -> {
                     try {
                         long mtime = Files.getLastModifiedTime(p).toMillis();
                         if (mtime < cutoffMs) {
                             Files.deleteIfExists(p);
                         }
                     } catch (IOException ignored) {
                         // 单个文件清理失败不影响其它
                     }
                 });
        } catch (IOException ignored) {
            // 目录不可读时静默忽略
        }
    }

    // ---- Listing ----

    public static List<SessionInfo> listSessions(String workDir) {
        Path baseDir = sessionsDir(workDir);
        if (!Files.isDirectory(baseDir)) {
            return List.of();
        }
        String branch = currentGitBranch(workDir);
        List<SessionInfo> sessions = new ArrayList<>();
        try (Stream<Path> paths = Files.list(baseDir)) {
            paths.filter(p -> p.toString().endsWith(".jsonl"))
                 .filter(Files::isRegularFile)
                 .forEach(p -> {
                     String fileName = p.getFileName().toString();
                     String id = fileName.substring(0, fileName.length() - ".jsonl".length());
                     try {
                         long fileSize = Files.size(p);
                         Instant modTime = Files.getLastModifiedTime(p).toInstant();
                         List<SessionMessage> msgs = loadSession(workDir, id);
                         //读文件全文,拿首条消息和消息数作展示
                         String first = msgs.stream()
                                 .filter(m -> "user".equals(m.role()))
                                 .map(SessionMessage::content)
                                 .findFirst()
                                 .orElse("");
                         sessions.add(new SessionInfo(id, first, msgs.size(),
                                 fileSize, branch, modTime));
                     } catch (IOException ignored) {
                         // skip this file
                     }
                 });
        } catch (IOException ignored) {
            // return empty
        }
        sessions.sort(Comparator.comparing(SessionInfo::modTime).reversed());
        return sessions;
    }

    // ---- Git branch ----

    public static String currentGitBranch(String workDir) {
        try {
            Process proc = new ProcessBuilder("git", "-C", workDir, "rev-parse", "--abbrev-ref", "HEAD")
                    .redirectErrorStream(true)
                    .start();
            String output = new String(proc.getInputStream().readAllBytes()).trim();
            int code = proc.waitFor();
            return code == 0 ? output : "";
        } catch (IOException | InterruptedException e) {
            return "";
        }
    }

    // ---- Formatting helpers ----

    public static String formatRelativeTime(Instant t) {
        Duration d = Duration.between(t, Instant.now());
        long seconds = d.getSeconds();
        if (seconds < 60) {
            return "just now";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes == 1 ? "1 minute ago" : minutes + " minutes ago";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours == 1 ? "1 hour ago" : hours + " hours ago";
        }
        long days = hours / 24;
        if (days < 7) {
            return days == 1 ? "1 day ago" : days + " days ago";
        }
        long weeks = days / 7;
        return weeks == 1 ? "1 week ago" : weeks + " weeks ago";
    }

    public static String formatFileSize(long bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        }
        if (bytes < 1024 * 1024) {
            double kb = bytes / 1024.0;
            return kb == (long) kb
                    ? String.format("%.0fKB", kb)
                    : String.format("%.1fKB", kb);
        }
        double mb = bytes / 1024.0 / 1024.0;
        return String.format("%.1fMB", mb);
    }

    // ---- Search ----

    public static boolean matchesSearch(SessionInfo s, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String q = query.toLowerCase();
        return s.firstMessage().toLowerCase().contains(q)
                || s.id().toLowerCase().contains(q);
    }
}