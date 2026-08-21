package com.agent.compact;

import com.agent.history.ConversationManager;
import com.agent.llm.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;



import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

/**
 二层上下文压缩：
 1.通过大型语言模型（LLM）调用，总结对话中较旧的前缀，同时保留最近的完整尾部。
 2.恢复快照（文件读取+技能标准操作流程）被附加，以确保模型在压缩后不会丢失工作上下文。
 */
public final class ContextCompactor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── PTL retry (summary request itself exceeds context window) ──────
    private static final int MAX_PTL_RETRIES = 3;
    private static final String PTL_RETRY_MARKER = "[earlier conversation truncated for compaction retry]";

    // ── messagesToKeep window ──────────────────────────────────────────
    private static final int KEEP_RECENT_TOKENS = 10_000;
    private static final int MIN_KEEP_MESSAGES = 5;
    private static final int KEEP_MAX_TOKENS = 40_000;

    // ── Recovery attachment budget ─────────────────────────────────────
    public static final int RECOVERY_FILE_LIMIT = 5;
    public static final int RECOVERY_TOKENS_PER_FILE = 5_000;
    public static final int RECOVERY_SKILLS_BUDGET = 25_000;
    public static final int RECOVERY_TOKENS_PER_SKILL = 5_000;
    private static final double RECOVERY_CHARS_PER_TOKEN = 3.5;
    private static final DateTimeFormatter RECOVERY_TS = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);
    public record UsageAnchor(int baselineTokens, int anchorCount) {}

    private static final String SUMMARY_SYSTEM_PROMPT = """
            Your task is to create a detailed summary of the conversation so far, paying close attention to the user's explicit requests and your previous actions.
            This summary should be thorough in capturing technical details, code patterns, and architectural decisions that would be essential for continuing development work without losing context.

            Before providing your final summary, wrap your analysis in <analysis> tags to organize your thoughts and ensure you've covered all necessary points. In your analysis process:

            1. Chronologically analyze each message and section of the conversation. For each section thoroughly identify:
               - The user's explicit requests and intents
               - Your approach to addressing the user's requests
               - Key decisions, technical concepts and code patterns
               - Specific details like:
                 - file names
                 - full code snippets
                 - function signatures
                 - file edits
               - Errors that you ran into and how you fixed them
               - Pay special attention to specific user feedback that you received, especially if the user told you to do something differently.
            2. Double-check for technical accuracy and completeness, addressing each required element thoroughly.

            After your analysis, output your final summary wrapped in <summary> tags. Your summary should include the following sections:

            1. Primary Request and Intent: Capture all of the user's explicit requests and intents in detail
            2. Key Technical Concepts: List all important technical concepts, technologies, and frameworks discussed.
            3. Files and Code Sections: Enumerate specific files and code sections examined, modified, or created. Pay special attention to the most recent messages and include full code snippets where applicable and include a summary of why this file read or edit was important.
            4. Errors and fixes: List all errors that you ran into, and how you fixed them. Pay special attention to specific user feedback that you received, especially if the user told you to do something differently.
            5. Problem Solving: Document problems solved and any ongoing troubleshooting efforts.
            6. All user messages: List ALL user messages that are not tool results. These are critical for understanding the users' feedback and changing intent.
            7. Pending Tasks: Outline any pending tasks that you have explicitly been asked to work on.
            8. Current Work: Describe in detail precisely what was being worked on immediately before this summary request, paying special attention to the most recent messages from both user and assistant. Include file names and code snippets where applicable.
            9. Optional Next Step: List the next step that you will take that is related to the most recent work you were doing. IMPORTANT: ensure that this step is DIRECTLY in line with the user's most recent explicit requests. If there is a next step, include direct quotes from the most recent conversation showing exactly what task you were working on and where you left off.

            Output structure:

            <analysis>
            [Your thought process]
            </analysis>

            <summary>
            1. Primary Request and Intent:
               [Detailed description]

            2. Key Technical Concepts:
               - [Concept 1]

            3. Files and Code Sections:
               - [File and code snippet]

            4. Errors and fixes:
               - [Error and fix]

            5. Problem Solving:
               [Description]

            6. All user messages:
               - [User message 1]

            7. Pending Tasks:
               - [Task 1]

            8. Current Work:
               [Precise description]

            9. Optional Next Step:
               [Next step if applicable]
            </summary>""";

    private ContextCompactor() {}

    // ── Public API ──────────────────────────────────────────────────────

    /** Force a full auto-compact regardless of current token usage. */
    public static String forceCompact(ConversationManager conv, LlmClient client, int contextWindow,
                                      RecoveryState recovery, List<Map<String, Object>> toolSchemas) {
        return autoCompact(conv, client, contextWindow, recovery, toolSchemas);
    }

    // ── Token estimation ────────────────────────────────────────────────

    /**遍历每条消息，对文本、tool_use、tool_result、thinking 分别按 字符数 / 3.5 + 固定开销 估算。
     * 3.5 是经验值——1 token 大约 3.5 个字符。*/
    public static int estimateTokens(List<Message> messages) {
        int total = 0;
        for (Message m : messages) {
            total += (int) (safeLength(m.getContent()) / 3.5) + 4;

            if (m.getToolUses() != null) {
                for (ToolUseBlock tu : m.getToolUses()) {
                    String argsJson;
                    try {
                        argsJson = MAPPER.writeValueAsString(tu.arguments());
                    } catch (JsonProcessingException e) {
                        argsJson = "{}";
                    }
                    total += 50 + (int) (argsJson.length() / 3.5);
                }
            }

            if (m.getToolResults() != null) {
                for (ToolResultBlock tr : m.getToolResults()) {
                    total += (int) (safeLength(tr.content()) / 3.5) + 10;
                }
            }

            if (m.getThinkingBlocks() != null) {
                for (ThinkingBlock tb : m.getThinkingBlocks()) {
                    total += (int) (safeLength(tb.thinking()) / 3.5);
                }
            }
        }
        return total;
    }

    // ── Layer 2: Auto-compact ──────────────────────────────────────────

    /**
     这一步决定"从哪里切"——前面是旧消息（要摘要），后面是近期消息（原样保留）。
     从末尾往前逐条累积 token，满足任一条件就停：
     */
    static int computeKeepStartIndex(List<Message> messages) {
        int n = messages.size();
        if (n == 0) return 0;
        // 已累积的 token
        int accumulated = 0;
        // 已累积的消息数
        int kept = 0;
        // 保留窗口起点（初始在末尾外，表示"什么都不保留"）
        int keepStart = n;
        for (int i = n - 1; i >= 0; i--) {
            int msgTokens = estimateTokens(List.of(messages.get(i)));
            //至少保留了1条就停止,上限
            if (accumulated + msgTokens > KEEP_MAX_TOKENS && kept > 0) {
                break;
            }
            accumulated += msgTokens;
            kept++;
            keepStart = i;
            //如果分割点落在只含tool_result的user消息上,往前退一条,把对应的assistant tool_use 也包进来
            //累积token达到10K或消息数达到5条就停止,下限
            if (accumulated >= KEEP_RECENT_TOKENS || kept >= MIN_KEEP_MESSAGES) {
                break;
            }
        }
        //LLM API要求tool_result必须和tool_use配对,拆散会报错。
        while (keepStart > 0 && isToolResultMessage(messages.get(keepStart))) {
            keepStart--;
        }
        return keepStart;
    }

    private static boolean isToolResultMessage(Message m) {
        return "user".equals(m.getRole())
                && m.getToolResults() != null
                && !m.getToolResults().isEmpty();
    }

    /**
     * 裁剪历史消息
     * @param conv 上下文管理器
     * @param client llmClient用于压缩
     * @param contextWindow 上下文大小
     * @param recovery 恢复文件及skills
     * @param toolSchemas 本地可调用工具
     * @return “”表示不需要裁剪或者“Compacted: %d -> %d estimated tokens"
     */
    private static String autoCompact(ConversationManager conv, LlmClient client, int contextWindow,
                                      RecoveryState recovery, List<Map<String, Object>> toolSchemas) {
        List<Message> messages = conv.getMessages();
        //1.计算已占用token
        int beforeTokens = estimateTokens(messages);
        //2.找到分割点,keepStartIndex之后的保留,前面的摘要处理
        int keepStartIndex = computeKeepStartIndex(messages);
        //保留窗口覆盖了全部消息,没有旧消息可摘要||要摘要的消息太少，不值得调 LLM=>返回空字符串,调用方知道"没压缩"
        if (keepStartIndex < MIN_KEEP_MESSAGES) {
            return "";
        }
        //摘要消息,subList左闭右开
        List<Message> toSummarize = messages.subList(0, keepStartIndex);
        //保留消息
        List<Message> toKeep = messages.subList(keepStartIndex, messages.size());
        //调用llm生成摘要文本
        String summaryText = requestSummaryWithPTLRetry(client, toSummarize, toolSchemas);

        String content = "本次会话延续自之前的对话，因上下文空间不足进行了压缩。以下是早期对话的摘要：\n\n" + summaryText;
        if (!toKeep.isEmpty()) {
            content += "\n\n近期消息已原样保留。";
        }
        //提取文件及skill快照,附加到摘要消息里
        String attachment = buildRecoveryAttachment(recovery, toolSchemas);
        if (!attachment.isEmpty()) {
            content += "\n\n---\n\n" + attachment;
        }

        ConversationManager compacted = new ConversationManager();
        compacted.addUserMessage(content);
        //ConversationManager:compacted + 保留的message
        for (Message m : toKeep) {
            appendMessage(compacted, m);
        }
        //将conv中的messages设置为裁剪后的messages
        replaceConversation(conv, compacted);

        int afterTokens = estimateTokens(conv.getMessages());
        return String.format("Compacted: %d -> %d estimated tokens", beforeTokens, afterTokens);
    }

    // ── Post-compact recovery attachment ───────────────────────────────

    /**
     * 提取文件及skill快照,附加到摘要消息里。
     */
    public static String buildRecoveryAttachment(RecoveryState state,
                                                 List<Map<String, Object>> toolSchemas) {
        var sb = new StringBuilder();

        if (state != null) {
            var files = state.snapshotFiles(RECOVERY_FILE_LIMIT);
            if (!files.isEmpty()) {
                sb.append("## Recently read files\n\n")
                  .append("These snapshots are what the file-reading tool last returned. ")
                  .append("Re-open with the tool if you need the current bytes.\n\n");
                for (var f : files) {
                    String body = truncateByTokens(f.content(), RECOVERY_TOKENS_PER_FILE);
                    sb.append("### ").append(f.path())
                      .append("  (read ").append(RECOVERY_TS.format(f.timestamp())).append(")\n\n")
                      .append("```\n").append(body);
                    if (!body.endsWith("\n")) sb.append('\n');
                    sb.append("```\n\n");
                }
            }
        }

        if (state != null) {
            var skills = state.snapshotSkills();
            if (!skills.isEmpty()) {
                var section = new StringBuilder();
                section.append("## Active skills\n\n")
                       .append("These skills were invoked earlier in the session. ")
                       .append("Continue to follow each SOP when its triggering condition applies.\n\n");
                int used = 0;
                boolean emitted = false;
                for (var sk : skills) {
                    String body = truncateByTokens(sk.body(), RECOVERY_TOKENS_PER_SKILL);
                    int tokens = approxTokens(body) + approxTokens(sk.name()) + 8;
                    if (used + tokens > RECOVERY_SKILLS_BUDGET) break;
                    used += tokens;
                    section.append("### ").append(sk.name()).append("\n\n")
                           .append(body).append("\n\n");
                    emitted = true;
                }
                if (emitted) sb.append(section);
            }
        }

        if (toolSchemas != null && !toolSchemas.isEmpty()) {
            sb.append("## Available tools\n\n")
              .append("You still have access to the following tools — call them directly when the task needs one:\n\n");
            for (var t : toolSchemas) {
                if (t == null) continue;
                Object nameObj = t.get("name");
                if (nameObj == null) continue;
                String name = nameObj.toString();
                if (name.isEmpty()) continue;
                Object descObj = t.get("description");
                String desc = descObj == null ? "" : firstLine(descObj.toString());
                if (!desc.isEmpty()) {
                    sb.append("- ").append(name).append(" — ").append(desc).append('\n');
                } else {
                    sb.append("- ").append(name).append('\n');
                }
            }
            sb.append('\n');
        }

        if (sb.length() == 0) return "";

        sb.append("## Note\n\nEverything above the divider is reconstructed context. ")
          .append("For exact code, error strings, or user-typed text, re-read the source rather than ")
          .append("guess from the summary.\n");
        return sb.toString();
    }

    // ── PTL retry ──────────────────────────────────────────────────────

    /**
     * 按API轮次分组:每个系统新回复开始一个新组，tool_use/tool_result 对保持在同一组。
     * tool_use和它的tool_result 必须在一起,拆散会导致LLM API报错
     */
    private static List<List<Message>> groupMessagesByAPIRound(List<Message> messages) {
        List<List<Message>> groups = new ArrayList<>();
        List<Message> current = new ArrayList<>();
        boolean prevHaveToolResult = false;

        for (Message m : messages) {
            //遇到assistant消息且前一条有tool_result → 新组开始
            if ("assistant".equals(m.getRole()) && prevHaveToolResult && !current.isEmpty()) {
                groups.add(current);
                current = new ArrayList<>();
            }
            current.add(m);
            prevHaveToolResult = m.getToolResults() != null && !m.getToolResults().isEmpty();
        }
        if (!current.isEmpty()) {
            groups.add(current);
        }
        return groups;
    }

    /**
     * 从最老的 API 轮次组开始丢弃，直到腾出足够 token。至少保留一组用于摘要。
     */
    private static List<Message> truncateHeadForPTL(List<Message> prefix, int tokenGap) {
        List<List<Message>> groups = groupMessagesByAPIRound(prefix);
        //只有一组时无法截断——截断后什么都没有
        if (groups.size() < 2) return null;

        int dropCount;

        if (tokenGap > 0) {
            int acc = 0;
            dropCount = 0;
            for (List<Message> g : groups) {
                acc += estimateTokens(g);
                dropCount++;
                if (acc >= tokenGap) break;
            }
        } else {
            //无token丢弃信息默认丢弃1/5
            dropCount = Math.max(1, groups.size() / 5);
        }
        //至少保留最新一组用于摘要
        dropCount = Math.min(dropCount, groups.size() - 1);
        if (dropCount < 1) return null;

        List<Message> result = new ArrayList<>();
        for (int i = dropCount; i < groups.size(); i++) {
            result.addAll(groups.get(i));
        }
        //丢弃最老的组后,新列表的第一条可能是assistant消息.LLM API要求对话必须以user消息开头。
        //告诉LLM "前面的内容被截断了",避免它困惑"为什么对话从这里开始".
        if (!result.isEmpty() && !"user".equals(result.get(0).getRole())) {
            result.add(0, new Message("user", PTL_RETRY_MARKER));
        }
        return result;
    }

    /**
     * 带 PTL 重试的摘要生成：捕获 ContextTooLongException，丢弃最老轮次后重试，最多重试 MAX_PTL_RETRIES 次。
     * @param client llm
     * @param source messages
     * @param toolSchemas tools
     * @return summary
     */
    private static String requestSummaryWithPTLRetry(LlmClient client, List<Message> source,
                                                     List<Map<String, Object>> toolSchemas) {
        List<Message> currentSource = new ArrayList<>(source);
        for (int attempt = 0; ; attempt++) {
            //得到消息文本
            String serialized = serializeForSummary(currentSource, 500);
            try {
                String raw = requestSummary(client, SUMMARY_SYSTEM_PROMPT + "\n\n" + serialized, toolSchemas);
                return formatCompactSummary(raw);
            } catch (RuntimeException e) {
                String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
                //Agent要把旧消息摘要掉,但旧消息太多连同摘要系统提示一起发给LLM时,摘要请求本身就超出了上下文窗口.报prompt too Long错误
                boolean isPTL = msg.contains("prompt") && msg.contains("long")
                        || msg.contains("too many") || msg.contains("context_length");
                if (!isPTL || attempt >= MAX_PTL_RETRIES) {
                    throw e;
                }
                //每次砍掉约20%
                int tokenGap = estimateTokens(currentSource) / 5;
                //从最老的API轮次组开始丢弃，直到腾出足够 token。至少保留一组用于摘要。
                List<Message> truncated = truncateHeadForPTL(currentSource, tokenGap);
                if (truncated == null) {
                    throw e;
                }
                currentSource = truncated;
            }
        }
    }

    /**
     *
     * @param client llm
     * @param userMessage 要压缩的对话
     * @param toolSchemas 工具列表
     * @return summary 消息摘要
     */
    private static String requestSummary(LlmClient client, String userMessage,
                                         List<Map<String, Object>> toolSchemas) {
        ConversationManager summaryConv = new ConversationManager();
        summaryConv.addUserMessage(userMessage);

        BlockingQueue<StreamEvent> events = client.stream(summaryConv, toolSchemas);
        var summary = new StringBuilder();

        try {
            while (true) {
                StreamEvent ev = events.take();
                if (ev instanceof StreamEvent.TextDelta td) {
                    summary.append(td.text());
                } else if (ev instanceof StreamEvent.Error err) {
                    throw new RuntimeException("LLM summary failed: " + err.message());
                } else if (ev instanceof StreamEvent.StreamEnd) {
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Summary interrupted", e);
        }

        return summary.toString();
    }

    /**
     * 将消息列表转为纯文本
     * @param messages 消息列表
     * @param toolResultCap
     * @return 消息文本
     */
    private static String serializeForSummary(List<Message> messages, int toolResultCap) {
        var sb = new StringBuilder();
        for (Message m : messages) {
            sb.append(String.format("[%s]: %s\n", m.getRole(), nullSafe(m.getContent())));
            //消息只带name和id
            if (m.getToolUses() != null) {
                for (ToolUseBlock tu : m.getToolUses()) {
                    sb.append(String.format("[tool_use %s]: %s\n", tu.toolName(), tu.toolId()));
                }
            }
            //result最多保留500字符
            if (m.getToolResults() != null) {
                for (ToolResultBlock tr : m.getToolResults()) {
                    String content = nullSafe(tr.content());
                    if (content.length() > toolResultCap) {
                        content = content.substring(0, toolResultCap) + "...";
                    }
                    sb.append(String.format("[tool_result]: %s\n", content));
                }
            }
        }
        return sb.toString();
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /**
     * LLM 在 <summary> 标签内输出结果，这里只提取标签内容。
     * @param raw 原文
     * @return 去除空白字符后的原文
     */
    static String formatCompactSummary(String raw) {
        int start = raw.indexOf("<summary>");
        int end = raw.indexOf("</summary>");
        if (start >= 0 && end > start) {
            return raw.substring(start + "<summary>".length(), end).strip();
        }
        return raw.strip();
    }

    /**
     * 字符串长度转token
     * @param s string
     * @return token number
     */
    private static int approxTokens(String s) {
        if (s == null || s.isEmpty()) return 0;
        return (int) (s.length() / RECOVERY_CHARS_PER_TOKEN);
    }

    /**
     * 根据tokenBudget截断字符串
     * @param s string
     * @param tokenBudget 字符串上限
     * @return s or s.substring+… (content truncated)
     */
    private static String truncateByTokens(String s, int tokenBudget) {
        if (s == null || s.isEmpty() || tokenBudget <= 0) return s == null ? "" : s;
        if (approxTokens(s) <= tokenBudget) return s;
        int maxChars = (int) (tokenBudget * RECOVERY_CHARS_PER_TOKEN);
        if (maxChars <= 0 || maxChars >= s.length()) return s;
        return s.substring(0, maxChars) + "\n… (content truncated)";
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        for (String line : s.split("\n")) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty()) return trimmed;
        }
        return "";
    }

    /**
     * 在ConversationManager中添加历史消息记录
     * @param conv 上下文管理器
     * @param m 待添加的message
     */
    private static void appendMessage(ConversationManager conv, Message m) {
        if (m.getToolUses() != null && !m.getToolUses().isEmpty()) {
            conv.addAssistantFull(m.getContent(), m.getThinkingBlocks(), m.getToolUses());
        } else if (m.getToolResults() != null && !m.getToolResults().isEmpty()) {
            conv.addToolResultsMessage(m.getToolResults());
        } else if ("user".equals(m.getRole())) {
            conv.addUserMessage(m.getContent());
        } else if ("assistant".equals(m.getRole())) {
            conv.addAssistantFull(m.getContent(), m.getThinkingBlocks(), null);
        }
    }

    private static void replaceConversation(ConversationManager target, ConversationManager source) {
        List<Message> targetList = target.getMessagesMutable();
        targetList.clear();
        targetList.addAll(source.getMessages());
    }

    private static int safeLength(String s) {
        return s == null ? 0 : s.length();
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}