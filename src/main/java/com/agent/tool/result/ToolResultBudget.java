package com.agent.tool.result;

import com.agent.history.ConversationManager;
import com.agent.llm.Message;
import com.agent.llm.ToolResultBlock;
import com.agent.llm.ToolUseBlock;
import com.agent.tool.PathContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
    决定哪些结果必须“挪到磁盘上”、历史里留什么，三条原则:
        1.就地修改——不复制 ConversationManager，直接改消息内部的 ToolResultBlock 列表，省内存；
        2.决策冻结——state.seenIds / replacements 记录决策，后续轮次重放相同决策，保证保prompt-cache prefix稳定
        3.byte-identical——占位符字符串逐字节不变，不触发 I/O，保 prompt-cache prefix 稳定（缓存命中直接换算成钱）。
 */
public final class ToolResultBudget {

    /** 单个工具调用大小限制. */
    public static final int SINGLE_RESULT_LIMIT = 50_000;

    /** 总消息大小限制. */
    public static final int MESSAGE_AGGREGATE_LIMIT = 200_000;

    /** Tool_Result溢写目录 */
    public static final String SPILL_SUBDIR = "tool_results";
    /** 识别“外部预标记内容”的前缀 */
    private static final String PERSISTED_TAG_PREFIX = "[Result of ";
    private ToolResultBudget() {}

    /**
     * 防止溢写机制告诉 LLM“内容在文件里”，LLM觉得预览不够用照着路径去读，读回来的东西又被溢写机制拦下,陷入死循环。
     * 该函数作用:识别“这条大结果是 LLM 读溢写文件读回来的”，对它网开一面——不溢写，原文保留。
     * @param tr 待判定的工具结果
     * @param toolUseIndex id → 工具调用块 的索引
     * @param absSpillDir 溢写目录的绝对规范路径
     * @return 是否在读溢写文件
     */
    private static boolean isSpillReadback(ToolResultBlock tr, Map<String, ToolUseBlock> toolUseIndex, String absSpillDir) {
        var tu = toolUseIndex.get(tr.toolId());
        if (tu == null || !"ReadFile".equals(tu.toolName()) || absSpillDir.isEmpty()) return false;
        Object raw = tu.arguments().get("file_path");
        if (!(raw instanceof String path) || path.isEmpty()) return false;
        try {
            String abs = PathContext.resolve(path).toAbsolutePath().normalize().toString();
            return abs.startsWith(absSpillDir);
        } catch (Exception e) {
            return false;
        }
    }
    //建立toolUseId → ToolUseBlock的索引表
    private static Map<String, ToolUseBlock> buildToolUseIndex(List<Message> messages) {
        Map<String, ToolUseBlock> idx = new HashMap<>();
        for (Message m : messages) {
            if (m.getToolUses() != null) {
                for (var tu : m.getToolUses()) {
                    idx.put(tu.toolId(), tu);
                }
            }
        }
        return idx;
    }

    /**
     * 就地修改 conv 中超限的 ToolResultBlock content（Design A）。
     * 直接遍历 conv.getMessages()，对需要替换的 ToolResultBlock 创建新实例并
     * 通过 msg.setToolResults() 写回，不创建新的 ConversationManager。
     *
     * @return 本次新增的替换记录列表（供调用方写入 session transcript）
     */
    public static List<ContentReplacementRecord> apply(
            ConversationManager conv,
            Path sessionDir,
            ContentReplacementState state
    ) {
        List<Message> messages = conv.getMessages();
        if (messages.isEmpty()) {
            return List.of();
        }
        //拼接子目录,获取绝对路径
        Path spillDir = sessionDir.resolve(SPILL_SUBDIR);
        String absSpillDir = spillDir.toAbsolutePath().normalize().toString();
        //key:toolUseId,value:ToolUseBlock,判断溢写读回时必须知道这条结果对应的是哪次工具调用、参数是什么，结果块自己不带参数。
        Map<String, ToolUseBlock> toolUseIndex = buildToolUseIndex(messages);
        List<ContentReplacementRecord> records = new ArrayList<>();

        for (Message msg : messages) {
            List<ToolResultBlock> trs = msg.getToolResults();
            if (trs == null || trs.isEmpty()) {
                continue;
            }
            //本消息内所有结果的最终形态（不管来自重放、保持还是新判定），最后统一用它重建消息。
            Map<String, String> decisions = new HashMap<>();
            //等待真正的大小判定的result列表
            List<ToolResultBlock> fresh = new ArrayList<>();

            for (ToolResultBlock tr : trs) {
                String id = tr.toolId();
                String replacement = state.replacements().get(id);
                //1.已替换，把当年冻结的占位符字符串原样放进decisions，continue。注意这条路径零 I/O、零字符串构造
                if (replacement != null) {
                    decisions.put(id, replacement);
                    continue;
                }
                //2.已决策过该tool_use但未替换,保持原文
                if (state.seenIds().contains(id)) {
                    decisions.put(id, tr.content());
                    continue;
                }
                //3.外部预标记内容留口,上游（比如恢复的会话、移植的历史）已经带了占位标签。这种内容视为“已被处理过”
                if (isAlreadyReplaced(tr.content())) {
                    state.seenIds().add(id);
                    state.replacements().put(id, tr.content());
                    decisions.put(id, tr.content());
                    records.add(ContentReplacementRecord.toolResult(id, tr.content()));
                    continue;
                }
                fresh.add(tr);
            }

            // Pass 1: 单文件阈值审判
            Set<String> persistedByP1 = new HashSet<>();
            for (ToolResultBlock tr : fresh) {
                //小于阈值不处理
                if (tr.content().length() <= SINGLE_RESULT_LIMIT) continue;
                //溢写读回豁免——防死循环的闸门
                if (isSpillReadback(tr, toolUseIndex, absSpillDir)) {
                    persistedByP1.add(tr.toolId());
                    continue;
                }

                String preview = spillAndPreview(spillDir, tr);
                //溢写失败分支,冻结原始内容，不再重试。
                //防止第N轮写盘失败留原文、第N+1轮写盘成功变占位符，两轮历史不一致，缓存断裂。
                if (preview == null) {
                    state.seenIds().add(tr.toolId());
                    decisions.put(tr.toolId(), tr.content());
                    persistedByP1.add(tr.toolId());
                    continue;
                }
                //溢写成功分支
                state.seenIds().add(tr.toolId());
                decisions.put(tr.toolId(), preview);
                persistedByP1.add(tr.toolId());
                state.replacements().put(tr.toolId(), preview);
                records.add(ContentReplacementRecord.toolResult(tr.toolId(), preview));
            }

            // Pass 2: 聚合超限时，从最大的未处理结果开始 spill，直到 total ≤ MESSAGE_AGGREGATE_LIMIT。
            // 收集Pass 1没碰过的 fresh 结果
            List<ToolResultBlock> remaining = new ArrayList<>();
            for (ToolResultBlock tr : fresh) {
                if (!persistedByP1.contains(tr.toolId())) {
                    remaining.add(tr);
                }
            }
            //total=已定决策的总量 + 剩余原文的总量
            int total = 0;
            for (String content : decisions.values()) total += content.length();
            for (ToolResultBlock tr : remaining) total += tr.content().length();

            if (total > MESSAGE_AGGREGATE_LIMIT && !remaining.isEmpty()) {
                List<ToolResultBlock> sorted = new ArrayList<>(remaining);
                //从大到小排序
                sorted.sort(Comparator.comparingInt((ToolResultBlock t) -> t.content().length()).reversed());
                for (ToolResultBlock tr : sorted) {
                    if (total <= MESSAGE_AGGREGATE_LIMIT) break;
                    //保留读溢写文件的内容
                    if (isSpillReadback(tr, toolUseIndex, absSpillDir)) continue;
                    //不断溢写最大的文件,直到满足<= MESSAGE_AGGREGATE_LIMIT的要求
                    String preview = spillAndPreview(spillDir, tr);
                    if (preview == null) {
                        state.seenIds().add(tr.toolId());
                        decisions.put(tr.toolId(), tr.content());
                        continue;
                    }
                    state.seenIds().add(tr.toolId());
                    decisions.put(tr.toolId(), preview);
                    state.replacements().put(tr.toolId(), preview);
                    records.add(ContentReplacementRecord.toolResult(tr.toolId(), preview));
                    total -= tr.content().length() - preview.length();
                }
            }

            // 其余 fresh 结果冻结为"已见但未替换"。
            for (ToolResultBlock tr : fresh) {
                if (decisions.containsKey(tr.toolId())) continue;
                state.seenIds().add(tr.toolId());
                decisions.put(tr.toolId(), tr.content());
            }

            // 就地替换：按原始顺序重建 ToolResultBlock 列表，写回 msg。
            List<ToolResultBlock> newResults = new ArrayList<>(trs.size());
            for (ToolResultBlock tr : trs) {
                String decided = decisions.get(tr.toolId());
                if (decided != null && !decided.equals(tr.content())) {
                    //ToolResultBlock的content内容变了才用新实例
                    newResults.add(new ToolResultBlock(tr.toolId(), decided, tr.isError()));
                } else {
                    newResults.add(tr);
                }
            }
            msg.setToolResults(newResults);
        }

        return records;
    }

    private static final int PREVIEW_CHARS = 2_000;

    /**
     * 生成占位符
     * @param content 文件内容
     * @param path 溢写路径
     * @return 溢写路径
     */
    private static String buildSpillPreview(String content, Path path) {
        int sizeKB = content.length() / 1024;
        String preview = content.length() <= PREVIEW_CHARS
                ? content : content.substring(0, PREVIEW_CHARS);
        boolean hasMore = content.length() > PREVIEW_CHARS;
        StringBuilder sb = new StringBuilder();
        sb.append("<persisted-output>\n");
        sb.append("输出太大（").append(sizeKB).append("KB），完整内容已保存到：\n");
        sb.append(path).append("\n\n");
        sb.append("预览（前 2KB）：\n").append(preview);
        if (hasMore) sb.append("\n...");
        sb.append("\n</persisted-output>");
        return sb.toString();
    }

    /**
     * 溢写并生成占位符
     * @param spillDir 溢写目录
     * @param tr 要写的tr
     * @return 占位符
     */
    private static String spillAndPreview(Path spillDir, ToolResultBlock tr) {
        try {
            Files.createDirectories(spillDir);
            Path file = spillDir.resolve(tr.toolId());
            //已有重复文件,跳过写入直接build preview
            if (Files.exists(file) && Files.size(file) == tr.content().length()) {
                return buildSpillPreview(tr.content(), file);
            }
            Files.writeString(file, tr.content());
            return buildSpillPreview(tr.content(), file);
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean isAlreadyReplaced(String s) {
        return s != null && s.startsWith(PERSISTED_TAG_PREFIX);
    }

}