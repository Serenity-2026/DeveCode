package com.agent.tui;

/**
 * TUI 共享样式与终端文本工具。
 *
 * 集中存放 ANSI 转义常量、应用名/版本，以及渲染必需的通用函数
 * （字符显示宽度、去 ANSI 可见长度、截断、换行、光标定位、字符重复）。
 * 各渲染/UI 类通过 {@code import static TuiStyle.*} 直接使用，避免
 * 在每个类里重复维护同一份转义串。
 */
final class TuiStyle {

    private TuiStyle() {}

    static final String APP_NAME    = "DeveCode";
    static final String APP_VERSION = "v1.0.0";

    // ── ANSI ──
    static final String ESC = "\033";
    static final String CLEAR   = ESC + "[2J";
    static final String HOME    = ESC + "[H";
    static final String CURSOR_HIDE = ESC + "[?25l";
    static final String CURSOR_SHOW = ESC + "[?25h";
    // 括号粘贴（bracketed paste）：打开后，终端会把粘贴内容用 \033[200~ ... \033[201~ 包起来。
    // 有了这对标记，才能区分"用户按了 Enter"和"粘贴内容里本来就带的换行"——
    // 否则粘贴一份多行脚本，会在第一个换行处就被当成提交发出去。
    static final String BRACKET_PASTE_ON  = ESC + "[?2004h";
    static final String BRACKET_PASTE_OFF = ESC + "[?2004l";
    static final String RESET   = ESC + "[0m";
    static final String BOLD    = ESC + "[1m";
    static final String DIM     = ESC + "[2m";
    static final String ITALIC  = ESC + "[3m";
    static final String RED     = ESC + "[31m";
    static final String GREEN   = ESC + "[32m";
    static final String YELLOW  = ESC + "[33m";
    static final String CYAN    = ESC + "[36m";
    static final String GRAY    = ESC + "[90m";
    static final String WHITE   = ESC + "[97m";
    static final String REVERSE = ESC + "[7m";

    // ── 边框专用色：256 色亮天蓝 (75)，与欢迎屏一致 ──
    static final String BORDER  = ESC + "[38;5;75m";

    // ── 右侧状态面板 ──
    static final int PANEL_WIDTH = 36;
    static final int PANEL_MIN_COLS = 100;  // 终端宽度 >= 此值才显示面板
    static final long PANEL_REFRESH_MS = 1000; // 面板周期刷新间隔
    static final int MAX_COMMAND_HINTS = 8;   // 命令提示面板最多显示的候选条数
    static final int MAX_INPUT_ROWS = 12;     // 输入框最多可见行数（含边框），再长就由输入框内部滚动

    static String repeat(char c, int n) {
        if (n <= 0) return "";
        return String.valueOf(c).repeat(n);
    }

    static String truncate(String s, int maxLen) {
        if (s == null) return "";
        // 移除 ANSI 序列再计算长度
        String stripped = s.replaceAll("\u001b\\[[0-9;]*[a-zA-Z]", "");
        if (stripped.length() <= maxLen) return s;
        return s.substring(0, Math.min(s.length(), maxLen));
    }

    /** 移动光标到 (row, col)，0-based。 */
    static void moveTo(StringBuilder buf, int row, int col) {
        buf.append(ESC).append('[').append(row + 1).append(';').append(col + 1).append('H');
    }

    /** 单个字符的终端显示宽度：ASCII 为 1，CJK/全角为 2。 */
    static int displayCharWidth(int codePoint) {
        if (codePoint < 0x80) return 1;
        if (codePoint >= 0x1100 && codePoint <= 0x115F) return 2;
        if (codePoint >= 0x2E80 && codePoint <= 0xA4CF) return 2;
        if (codePoint >= 0xAC00 && codePoint <= 0xD7A3) return 2;
        if (codePoint >= 0xF900 && codePoint <= 0xFAFF) return 2;
        if (codePoint >= 0xFE10 && codePoint <= 0xFE19) return 2;
        if (codePoint >= 0xFE30 && codePoint <= 0xFE6F) return 2;
        if (codePoint >= 0xFF01 && codePoint <= 0xFF60) return 2;
        if (codePoint >= 0xFFE0 && codePoint <= 0xFFE6) return 2;
        if (codePoint >= 0x1F000 && codePoint <= 0x1F9FF) return 2;
        if (codePoint >= 0x20000) return 2;
        return 1;
    }

    /** 字符串去掉 ANSI 转义后的显示宽度（含 CJK 双宽）。 */
    static int visibleLength(String s) {
        if (s == null) return 0;
        String stripped = s.replaceAll("\u001b\\[[0-9;]*[a-zA-Z]", "");
        int len = 0;
        for (int i = 0; i < stripped.length(); i++) len += displayCharWidth(stripped.charAt(i));
        return len;
    }

    /** 格式化 token 数为紧凑形式：1234 → "1.2K"，1000000 → "1.0M"。 */
    static String formatTokens(int tokens) {
        if (tokens >= 1_000_000) return String.format("%.1fM", tokens / 1_000_000.0);
        if (tokens >= 1_000) return String.format("%.1fK", tokens / 1_000.0);
        return String.valueOf(tokens);
    }
}
