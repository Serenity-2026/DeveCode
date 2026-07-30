/*
 * @Author: lv jiang er hao devedmc@163.com
 * @Date: 2026-07-29 11:13:21
 * @LastEditors: lv jiang er hao devedmc@163.com
 * @LastEditTime: 2026-07-30 20:48:58
 * @FilePath: \tui\MarkdownRenderer.java
 * @Description: 这是默认设置,请设置`customMade`, 打开koroFileHeader查看配置 进行设置: https://github.com/OBKoro1/koro1FileHeader/wiki/%E9%85%8D%E7%BD%AE
 */
package com.agent.tui;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将 Markdown 文本转换为带 ANSI 颜色与样式的终端字符串。
 *
 * 配色参考 rich / glow / marked-terminal 等优秀终端渲染器：
 *   - 标题：橘色加粗（H1 附带下划线）
 *   - 链接：蓝色 + 下划线
 *   - 行内代码：青色
 *   - 代码块：带边框（┌ │ └），灰色文字，顶部显示语言标识
 *   - 列表：青色项目符号 / 绿色序号
 *   - 引用块：灰色竖线 + 暗淡斜体
 *   - 分割线：灰色横线
 *
 * 支持语法：
 *   # h1 ~ ###### h6  |  **bold**  |  *italic*  |  ~~strike~~  |  `code`
 *   ```lang ... ```    |  [text](url)  |  <auto url>  |  > quote
 *   - / * / + 列表     |  1. 有序列表   |  --- / *** 分割线
 */
public class MarkdownRenderer {

    // ── 基础 ANSI 样式 ──
    private static final String RESET   = "\u001b[0m";
    private static final String BOLD    = "\u001b[1m";
    private static final String DIM     = "\u001b[2m";
    private static final String ITALIC  = "\u001b[3m";
    private static final String UNDER   = "\u001b[4m";
    private static final String REVERSE = "\u001b[7m";

    // ── 256 色调色板 ──
    private static final String ORANGE = "\u001b[38;5;208m";  // 标题主色（橘色）
    private static final String AMBER  = "\u001b[38;5;214m";  // H3
    private static final String GOLD   = "\u001b[38;5;221m";  // H4-H6
    private static final String BLUE   = "\u001b[38;5;75m";   // 链接
    private static final String CYAN   = "\u001b[38;5;51m";   // 行内代码 / 列表符号
    private static final String GREEN  = "\u001b[38;5;114m";  // 有序列号
    private static final String GRAY   = "\u001b[90m";
    private static final String BAR    = "\u001b[38;5;240m";  // 引用块 / 代码块边框

    // ── 正则模式（预编译） ──
    private static final Pattern HEADING    = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern HR         = Pattern.compile("^(?:\\s*[-*_]){3,}\\s*$");
    private static final Pattern QUOTE      = Pattern.compile("^>\\s?(.*)$");
    private static final Pattern ULIST      = Pattern.compile("^(\\s*)([-*+])\\s+(.*)$");
    private static final Pattern OLIST      = Pattern.compile("^(\\s*)(\\d+)(\\.)(\\s+)(.*)$");
    private static final Pattern CODE_FENCE = Pattern.compile("^\\s*```(.*)$");
    private static final Pattern LINK       = Pattern.compile("\\[([^\\]]+)\\]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)");
    private static final Pattern AUTO_LINK  = Pattern.compile("(?<![\\w@])((?:https?|ftp)://[^\\s<)]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern INLINE_CODE= Pattern.compile("`([^`]+)`");
    private static final Pattern BOLD_MD    = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern ITALIC_MD  = Pattern.compile("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)");
    private static final Pattern STRIKE     = Pattern.compile("~~(.+?)~~");

    private MarkdownRenderer() {}

    public static String render(String markdown) {
        if (markdown == null || markdown.isEmpty()) return "";
        String[] rawLines = markdown.split("\n", -1);
        List<String> out = new ArrayList<>();
        boolean inCode = false;
        StringBuilder codeBuf = new StringBuilder();
        String codeLang = "";

        for (String line : rawLines) {
            Matcher fence = CODE_FENCE.matcher(line);
            if (!inCode && fence.matches()) {
                inCode = true;
                codeBuf.setLength(0);
                codeLang = fence.group(1).trim();
                continue;
            }
            if (inCode && line.trim().equals("```")) {
                inCode = false;
                renderCodeBlock(out, codeBuf.toString(), codeLang);
                continue;
            }
            if (inCode) {
                if (!codeBuf.isEmpty()) codeBuf.append("\n");
                codeBuf.append(line);
                continue;
            }
            out.add(renderLine(line));
        }
        // 未闭合的代码块
        if (inCode) renderCodeBlock(out, codeBuf.toString(), codeLang);
        return String.join("\n", out);
    }

    // ── 代码块：带边框 + 语言标识 ──
    private static void renderCodeBlock(List<String> out, String code, String lang) {
        if (lang.isEmpty()) {
            out.add("  " + BAR + "┌" + RESET);
        } else {
            out.add("  " + BAR + "┌─ " + RESET + GRAY + lang + RESET);
        }
        String[] codeLines = code.split("\n", -1);
        for (String cl : codeLines) {
            out.add("  " + BAR + "│ " + RESET + GRAY + cl + RESET);
        }
        out.add("  " + BAR + "└" + RESET);
    }

    // ── 行级渲染：识别块级元素，否则走行内 ──
    private static String renderLine(String line) {
        // 水平分割线
        if (HR.matcher(line).matches()) {
            return GRAY + "  ─────────────────────────────────────────────" + RESET;
        }
        // 标题
        Matcher h = HEADING.matcher(line);
        if (h.matches()) {
            int level = h.group(1).length();
            String text = renderInline(h.group(2));
            String color = switch (level) {
                case 1, 2 -> ORANGE;
                case 3 -> AMBER;
                default -> GOLD;
            };
            // H1 加下划线以示强调
            return (level == 1 ? BOLD + UNDER + color : BOLD + color) + text + RESET;
        }
        // 引用块
        Matcher q = QUOTE.matcher(line);
        if (q.matches()) {
            return BAR + "│ " + RESET + DIM + ITALIC + renderInline(q.group(1)) + RESET;
        }
        // 无序列表
        Matcher ul = ULIST.matcher(line);
        if (ul.matches()) {
            String indent = ul.group(1);
            String content = renderInline(ul.group(3));
            return indent + CYAN + "•" + RESET + " " + content;
        }
        // 有序列表
        Matcher ol = OLIST.matcher(line);
        if (ol.matches()) {
            String indent = ol.group(1);
            String num = ol.group(2);
            String dot = ol.group(3);
            String sep = ol.group(4);
            String content = renderInline(ol.group(5));
            return indent + GREEN + num + dot + RESET + sep + content;
        }
        return renderInline(line);
    }

    /**
     * 行内格式处理。应用顺序很重要：
     *   先处理显式链接 [text](url)（会消耗掉 url，避免被自动链接二次匹配），
     *   再处理自动链接、行内代码、粗体、斜体、删除线。
     */
    private static String renderInline(String text) {
        if (text == null || text.isEmpty()) return text;
        String r = text;
        r = replaceAll(r, LINK, BLUE + UNDER + "$1" + RESET);
        r = replaceAll(r, AUTO_LINK, BLUE + UNDER + "$1" + RESET);
        r = replaceAll(r, INLINE_CODE, CYAN + "$1" + RESET);
        r = replaceAll(r, BOLD_MD, BOLD + "$1" + RESET);
        r = replaceAll(r, ITALIC_MD, ITALIC + "$1" + RESET);
        r = replaceAll(r, STRIKE, DIM + "$1" + RESET);
        return r;
    }

    private static String replaceAll(String input, Pattern p, String replacement) {
        Matcher m = p.matcher(input);
        StringBuilder sb = new StringBuilder();
        // 注意：不能用 Matcher.quoteReplacement，否则 $1 反向引用会被当字面量
        while (m.find()) {
            m.appendReplacement(sb, replacement);
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
