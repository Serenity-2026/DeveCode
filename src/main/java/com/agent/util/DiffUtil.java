package com.agent.util;

import java.util.ArrayList;
import java.util.List;
/**
 * 对比编辑前后的文件内容，生成一段带行号的 diff。
 * 利用"编辑只改动中间一小段"的特点，从两端找公共前缀/后缀行，
 * 避免跑通用的 LCS/Myers diff 算法（对大文件更快，实现也更简单）。
 */
public final class DiffUtil {
    //diff 输出中，差异块前后各显示 3 行上下文
    private static final int CONTEXT_LINES = 3;
    // 防止超大文件产出天量 diff 文本拖垮 TUI 渲染和上下文占用
    private static final int MAX_DIFF_LINES = 200;

    private DiffUtil() {}

    /**
     *
     * @param text 格式化的 diff 文本，带行号和前缀符号
     * @param additions 新增行数（ + 开头的行数）
     * @param removals 删除行数（ - 开头的行数）
     */
    public record DiffResult(String text, int additions, int removals) {}

    /**
     * 对比新旧内容，生成带行号和上下文的 diff 文本。
     * @param oldContent 编辑前的文件内容
     * @param newContent 编辑后的文件内容
     * @return DiffResult ，包含 diff 文本、新增行数、删除行数
     * 通用 diff 算法（Myers、LCS）能处理任意复杂的差异，但代价是 O(n·m) 的复杂度。而 EditFileTool 的使用场景有一个 关键先验 ：
     *  AI 的编辑永远是"小范围替换"——用 old_string （通常 2-4 行）替换成 new_string
     * 这意味着新旧内容的关系是： 两端相同，中间一段不同 。就像在一根绳子上换掉一小段。
     * 基于这个先验， DiffUtil 采用了一个极简但高效的算法:两端扩展，一次扫描找前缀，一次扫描找后缀，差异自动浮现 ——不需要复杂的动态规划。
     */
    public static DiffResult buildDiff(String oldContent, String newContent) {
        //按行拆分
        String[] oldLines = oldContent.split("\n", -1);
        String[] newLines = newContent.split("\n", -1);
        //定位前缀
        int prefixLen = 0;
        int maxPrefix = Math.min(oldLines.length, newLines.length);
        while (prefixLen < maxPrefix && oldLines[prefixLen].equals(newLines[prefixLen])) {
            prefixLen++;
        }
        //定位后缀，前后缀之间闭区间就是差异文本
        int suffixLen = 0;
        int maxSuffix = maxPrefix - prefixLen;
        while (suffixLen < maxSuffix
                && oldLines[oldLines.length - 1 - suffixLen].equals(newLines[newLines.length - 1 - suffixLen])) {
            suffixLen++;
        }
        //提取改动
        String[] removedLines = slice(oldLines, prefixLen, oldLines.length - suffixLen);
        String[] addedLines = slice(newLines, prefixLen, newLines.length - suffixLen);
        //提取上下文
        int contextStart = Math.max(0, prefixLen - CONTEXT_LINES);
        String[] contextBefore = slice(oldLines, contextStart, prefixLen);
        int contextEnd = Math.min(oldLines.length, oldLines.length - suffixLen + CONTEXT_LINES);
        String[] contextAfter = slice(oldLines, oldLines.length - suffixLen, contextEnd);

        List<String> out = new ArrayList<>();
        int oldLineNo = contextStart + 1;
        int newLineNo = contextStart + 1;
        boolean[] truncated = {false};

        for (String l : contextBefore) {
            push(out, truncated, " ", oldLineNo, l);
            oldLineNo++;
            newLineNo++;
        }
        for (String l : removedLines) {
            push(out, truncated, "-", oldLineNo, l);
            oldLineNo++;
        }
        for (String l : addedLines) {
            push(out, truncated, "+", newLineNo, l);
            newLineNo++;
        }
        for (String l : contextAfter) {
            push(out, truncated, " ", oldLineNo, l);
            oldLineNo++;
            newLineNo++;
        }

        if (truncated[0]) {
            out.add("  … (diff truncated at %d lines)".formatted(MAX_DIFF_LINES));
        }

        return new DiffResult(String.join("\n", out), addedLines.length, removedLines.length);
    }

    /**
     * - 截断保护 ：检查输出行数是否已达上限（200 行），达到则停止添加并标记截断
     * - 格式化添加 ：把前缀（ / - / + ）、行号（右对齐宽度 4）、内容拼成一行，加入输出列表
     * @param out 已添加内容
     * @param truncated 标记阶段数组
     * @param prefix 前缀
     * @param lineNo 行号
     * @param content 内容
     */
    private static void push(List<String> out, boolean[] truncated, String prefix, int lineNo, String content) {
        if (out.size() >= MAX_DIFF_LINES) {
            truncated[0] = true;
            return;
        }
        out.add("%s %4d  %s".formatted(prefix, lineNo, content));
    }

    private static String[] slice(String[] arr, int from, int to) {
        if (to <= from) return new String[0];
        String[] out = new String[to - from];
        System.arraycopy(arr, from, out, 0, to - from);
        return out;
    }
}
