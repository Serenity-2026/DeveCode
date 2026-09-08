package com.agent.tui;

import static com.agent.tui.TuiStyle.*;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * UI 消息记录。封装一条消息在终端中显示所需的全部信息。
 *
 * @param role      消息角色："user" / "assistant" / "error" / "banner" / "tool"
 * @param content   已格式化的内容（含 ANSI 颜色码），按 \n 分行
 * @param timeLabel 时间标签（如 "14:30"），显示在首行前；null 表示不显示
 * @param streaming 是否为流式进行中的消息（true 时不加空行分隔）
 * @param error     是否为错误消息
 */
public record UIMessage(String role, String content, String timeLabel, boolean streaming, boolean error) {
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    public static UIMessage banner() {
        String banner = BOLD + APP_NAME + " " + APP_VERSION + RESET;
        String cwd = System.getProperty("user.dir");
        return new UIMessage("banner", banner + "\n" + DIM + cwd + RESET, null, false, false);
    }

    public static UIMessage user(String text) {
        return new UIMessage("user",
                BOLD + GREEN + "You" + RESET + ": " + text,
                LocalTime.now().format(TIME_FMT), false, false);
    }

    public static UIMessage assistant(String text, String timeLabel) {
        return new UIMessage("assistant",
                BOLD + CYAN + "DeveCode" + RESET + "\n" + text,
                timeLabel, false, false);
    }

    public static UIMessage streaming(String text) {
        return new UIMessage("assistant",
                BOLD + CYAN + "DeveCode" + RESET + "\n" + text,
                null, true, false);
    }

    /** 流式显示思考过程（每行浅灰色，带计时器） */
    public static UIMessage streamingThinking(String thinkText, long elapsed) {
        return new UIMessage("assistant",
                BOLD + CYAN + "DeveCode" + RESET + "\n" +
                GRAY + "✻ Thinking… (" + elapsed + "s)" + RESET + "\n" +
                grayLines(thinkText),
                null, true, false);
    }

    /** 思考完成，显示结束标记 */
    public static UIMessage streamingThinkingDone(String thinkText) {
        return new UIMessage("assistant",
                BOLD + CYAN + "DeveCode" + RESET + "\n" +
                GRAY + "✻ Thinking…\n" + grayLines(thinkText) + "\n" +
                GRAY + "✻ Done" + RESET,
                null, true, false);
    }

    /** 流式显示思考过程（含结束标记）+ 正文 */
    public static UIMessage streamingWithThinking(String thinkText, String responseText) {
        return new UIMessage("assistant",
                BOLD + CYAN + "DeveCode" + RESET + "\n" +
                GRAY + "✻ Thinking…\n" + grayLines(thinkText) + "\n" +
                GRAY + "✻ Done" + RESET + "\n\n" +
                responseText,
                null, true, false);
    }

    // ── 工具调用相关的工厂方法 ──

    /** 工具调用流式中（参数正在推送） */
    public static UIMessage streamingToolCall(String toolName, String argsDisplay) {
        return new UIMessage("tool",
                YELLOW + "⚙ " + CYAN + toolName + RESET +
                GRAY + "(" + argsDisplay + ")" + RESET,
                null, true, false);
    }

    /** 工具调用完成（显示完整参数） */
    public static UIMessage toolCall(String toolName, String argsDisplay) {
        return new UIMessage("tool",
                YELLOW + "⚙ " + CYAN + toolName + RESET +
                GRAY + "(" + argsDisplay + ")" + RESET,
                LocalTime.now().format(TIME_FMT), false, false);
    }

    /** 工具执行中 */
    public static UIMessage toolExecuting(String toolName) {
        return new UIMessage("tool",
                YELLOW + "⚙ " + CYAN + toolName + RESET +
                GRAY + "  executing…" + RESET,
                null, false, false);
    }

    /** 工具执行结果（超长输出截断为前 500 字符，附执行耗时） */
    public static UIMessage toolResult(String toolName, String output, boolean isError, double elapsed) {
        String color = isError ? RED : GRAY;
        String display = output;
        if (display != null && display.length() > 500) {
            display = display.substring(0, 500) + "\n…";
        }
        String time = elapsed > 0
                ? GRAY + " [" + String.format("%.1fs", elapsed) + "]" + RESET
                : "";
        // 输出可能含换行，逐行包裹颜色防止 \n 分割后丢失颜色
        String body = display != null ? display : "";
        String[] lines = body.split("\n", -1);
        var sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append("\n");
            sb.append(color).append(i == 0 ? "↳ " : "  ").append(lines[i]);
            if (i == lines.length - 1) sb.append(time);
            sb.append(RESET);
        }
        return new UIMessage("tool", sb.toString(), null, false, false);
    }

    /** 系统提示消息（压缩/重试/中断/轮次汇总等） */
    public static UIMessage system(String text) {
        return new UIMessage("system", text, null, false, false);
    }

    /** 权限询问：显示待执行操作和 y/a/n 选项 */
    public static UIMessage permissionRequest(String toolName, String description) {
        return new UIMessage("system",
                BOLD + YELLOW + "⚠ Permission required" + RESET + "\n" +
                BOLD + CYAN + toolName + RESET + GRAY + " — " + description + RESET + "\n" +
                BOLD + "[y]" + RESET + " allow   " +
                BOLD + "[a]" + RESET + " always allow   " +
                BOLD + "[n]" + RESET + " deny",
                LocalTime.now().format(TIME_FMT), false, false);
    }

    /** 将多行文本逐行包裹 GRAY 颜色（防止 \n 分割后丢失颜色） */
    public static String grayLines(String text) {
        if (text == null || text.isEmpty()) return "";
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append("\n");
            sb.append(GRAY).append(lines[i]).append(RESET);
        }
        return sb.toString();
    }

    public static UIMessage error(String text) {
        return new UIMessage("error",
                BOLD + RED + "Error" + RESET + "\n" + RED + text + RESET,
                LocalTime.now().format(TIME_FMT), false, true);
    }

    /** 把消息切成渲染行（按终端宽度换行 + 首行时间标签 + 消息间空行）。 */
    public List<RenderLine> toRenderLines(int width) {
        List<RenderLine> result = new ArrayList<>();
        String[] parts = content.split("\n", -1);
        for (String part : parts) {
            List<String> wrapped = wrapText(part, width);
            for (String w : wrapped) {
                result.add(new RenderLine("  " + w, ""));
            }
        }
        if (!result.isEmpty() && timeLabel != null && !timeLabel.isEmpty()) {
            var first = result.getFirst();
            result.set(0, new RenderLine(
                    DIM + "[" + timeLabel + "]" + RESET + " " + first.text(),
                    first.style()));
        }
        // 消息间加空行
        if (!result.isEmpty() && !streaming) {
            result.addFirst(new RenderLine("", ""));
        }
        return result;
    }

    /** 按显示宽度换行，并在换行处传递活跃的 ANSI 颜色状态。 */
    public static List<String> wrapText(String text, int width) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isEmpty()) { result.add(""); return result; }
        if (width <= 0) { result.add(text); return result; }
        if (visibleLength(text) <= width) { result.add(text); return result; }

        StringBuilder currentLine = new StringBuilder();
        int currentLineWidth = 0;
        String plainText = text.replaceAll("\u001b\\[[0-9;]*[a-zA-Z]", "");
        int plainPos = 0, origPos = 0;
        while (plainPos < plainText.length()) {
            char c = plainText.charAt(plainPos);
            int cw = displayCharWidth(c);
            if (currentLineWidth + cw > width && !currentLine.isEmpty()) {
                String ansiState = extractAnsiState(currentLine.toString());
                if (!ansiState.isEmpty()) currentLine.append(RESET);
                result.add(currentLine.toString());
                currentLine.setLength(0);
                if (!ansiState.isEmpty()) currentLine.append(ansiState);
                currentLineWidth = 0;
            }
            while (origPos < text.length()) {
                char oc = text.charAt(origPos);
                if (oc == '\u001b') {
                    int seqEnd = origPos;
                    while (seqEnd < text.length() && !Character.isLetter(text.charAt(seqEnd))) seqEnd++;
                    if (seqEnd < text.length()) seqEnd++;
                    currentLine.append(text, origPos, seqEnd);
                    origPos = seqEnd;
                } else {
                    currentLine.append(oc);
                    origPos++;
                    break;
                }
            }
            currentLineWidth += cw;
            plainPos++;
        }
        if (!currentLine.isEmpty()) result.add(currentLine.toString());
        if (result.isEmpty()) result.add("");
        return result;
    }

    /** 提取文本末尾活跃的 ANSI 颜色码（遇 RESET 清空，遇设置码追加） */
    private static String extractAnsiState(String text) {
        StringBuilder state = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            if (text.charAt(i) == '\u001b') {
                int start = i;
                i++;
                while (i < text.length() && !Character.isLetter(text.charAt(i))) i++;
                if (i < text.length()) i++;
                String code = text.substring(start, i);
                if (code.equals(RESET)) {
                    state.setLength(0);
                } else {
                    state.append(code);
                }
            } else {
                i++;
            }
        }
        return state.toString();
    }
}

/** 渲染行：text 含 ANSI 颜色码，style 预留（目前未使用） */
record RenderLine(String text, String style) {}
