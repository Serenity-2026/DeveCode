package com.agent.tool.result;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * ToolResultBudget的决策状态记录
 */
public final class ContentReplacementState {
    //所有经过 apply() 至少一次的 toolUseId，无论最终是替换还是保持原文
    private final Set<String> seenIds = new HashSet<>();
    //被判“替换”的 id → 占位符的精确字节,两集合的差集就是“查过了、不超限、原文保留”的那批：替换过的必然见过，见过的未必替换。
    //value存的是 路径 + 大小 + 前 2KB 预览
    private final Map<String, String> replacements = new HashMap<>();

    public Set<String> seenIds() {
        return seenIds;
    }

    public Map<String, String> replacements() {
        return replacements;
    }

    /**
        子 Agent fork 时——子继承父的全部冻结决策：父历史上已被替换/保持的结果，子在重放同一份历史时做出完全相同的决策→父子发往 LLM的历史前缀一致→各自的 prompt cache都不断；
        但写入隔离：子之后产生的新决策（新的替换记录）写进自己的副本，不会污染父的集合——否则父下一轮重放时会突然发现多出几条自己没做过的决策。
     */
    public ContentReplacementState copy() {
        ContentReplacementState out = new ContentReplacementState();
        out.seenIds.addAll(this.seenIds);
        out.replacements.putAll(this.replacements);
        return out;
    }
}