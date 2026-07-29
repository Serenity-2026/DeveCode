/*
 * @Author: lv jiang er hao devedmc@163.com
 * @Date: 2026-07-29 11:13:21
 * @LastEditors: lv jiang er hao devedmc@163.com
 * @LastEditTime: 2026-07-29 21:55:14
 * @FilePath: \tui\MarkdownRenderer.java
 * @Description: 这是默认设置,请设置`customMade`, 打开koroFileHeader查看配置 进行设置: https://github.com/OBKoro1/koro1FileHeader/wiki/%E9%85%8D%E7%BD%AE
 */
package com.agent.tui;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts Markdown text to ANSI-formatted terminal strings.
 *
 * Supported syntax:
 *   **bold**  |  *italic*  |  `code`  |  ```blocks```  |  # headings  |  - lists
 */
public class MarkdownRenderer {

    private static final String RESET   = "\u001b[0m";
    private static final String BOLD    = "\u001b[1m";
    private static final String DIM     = "\u001b[2m";
    private static final String GRAY    = "\u001b[90m";
    private static final String REVERSE = "\u001b[7m";

    public static String render(String markdown) {
        if (markdown == null || markdown.isEmpty()) return "";
        String[] rawLines = markdown.split("\n", -1);
        List<String> processedLines = new ArrayList<>();
        boolean inCodeBlock = false;
        StringBuilder codeBlock = new StringBuilder();
        for (String line : rawLines) {
            if (!inCodeBlock && line.trim().startsWith("```")) {
                inCodeBlock = true;
                codeBlock.setLength(0);
                continue;
            }
            if (inCodeBlock && line.trim().equals("```")) {
                inCodeBlock = false;
                String[] codeLines = codeBlock.toString().split("\n", -1);
                for (String cl : codeLines) {
                    processedLines.add(GRAY + "  " + cl + RESET);
                }
                continue;
            }
            if (inCodeBlock) {
                if (!codeBlock.isEmpty()) codeBlock.append("\n");
                codeBlock.append(line);
                continue;
            }
            processedLines.add(renderLine(line));
        }
        if (inCodeBlock) {
            String[] codeLines = codeBlock.toString().split("\n", -1);
            for (String cl : codeLines) {
                processedLines.add(GRAY + "  " + cl + RESET);
            }
        }
        return String.join("\n", processedLines);
    }

    private static String renderLine(String line) {
        // 标题：去掉 # 前缀，加粗显示
        if (line.matches("^#{1,6}\\s+.*")) {
            String text = line.replaceFirst("^#{1,6}\\s+", "");
            return BOLD + text + RESET;
        }
        String result = line;
        result = replaceAll(result, "`([^`]+)`", REVERSE + "$1" + RESET);
        result = replaceAll(result, "\\*\\*(.+?)\\*\\*", BOLD + "$1" + RESET);
        result = replaceAll(result, "(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)", DIM + "$1" + RESET);
        return result;
    }

    private static String replaceAll(String input, String regex, String replacement) {
        Matcher m = Pattern.compile(regex).matcher(input);
        StringBuilder sb = new StringBuilder();
        // 注意：不能使用 Matcher.quoteReplacement，否则 $1 反向引用会被转义成字面量
        while (m.find()) {
            m.appendReplacement(sb, replacement);
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
