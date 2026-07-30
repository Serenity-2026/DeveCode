package com.agent.tui;

import com.agent.ProviderConfig;

import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.InfoCmp.Capability;
import org.jline.keymap.BindingReader;
import org.jline.keymap.KeyMap;
import static org.jline.keymap.KeyMap.key;
import static org.jline.keymap.KeyMap.ctrl;

import java.io.PrintWriter;
import java.util.List;

/**
 * 应用入口：加载配置 → provider 选择 → 启动终端 UI。
 *
 * provider 选择：
 *   单 provider    → 直接进入对话
 *   多 provider    → 方向键选择界面（带极客风欢迎屏，16:9 横向布局）
 */
public class DeveCodeApp {

    private static final String ESC = "\033";
    private static final String RESET   = ESC + "[0m";
    private static final String BOLD    = ESC + "[1m";
    private static final String DIM     = ESC + "[2m";
    private static final String CYAN    = ESC + "[36m";
    private static final String GREEN   = ESC + "[32m";
    private static final String YELLOW  = ESC + "[33m";
    private static final String GRAY    = ESC + "[90m";

    // ── 边框专用色：256 色亮天蓝 (75) ──
    private static final String BORDER  = ESC + "[38;5;75m";

    private static final String APP_NAME    = "DeveCode";
    private static final String APP_VERSION = "v0.1.0";

    // ── ASCII Logo (figlet "standard" 字体) ──
    private static final String[] LOGO = {
        " ____                  ____          _",
        "|  _ \\  _____   _____ / ___|___   __| | ___",
        "| | | |/ _ \\ \\ / / _ \\ |   / _ \\ / _` |/ _ \\",
        "| |_| |  __/\\ V /  __/ |__| (_) | (_| |  __/",
        "|____/ \\___| \\_/ \\___|\\____\\___/ \\__,_|\\___|"
    };

    // ── 小狗 ASCII 图案 ──
    private static final String[] DOG = {
        "   __      _",
        " o'')}____//",
        "  `_/      )",
        "  (_(_/-(_/"
    };

    public static void main(String[] args) {
        AppConfig config = AppConfig.load();
        List<ProviderConfig> providers = config.getProviders();

        ProviderConfig selected;
        if (providers.size() == 1) {
            // 单 provider 直接进入
            selected = providers.get(0);
            System.out.println("Starting DeveCode with " + selected.getName() + "...");
        } else {
            // 多 provider 显示选择界面
            selected = showProviderSelector(providers);
        }

        TerminalUI.launch(selected);
    }

    /**
     * 渲染带极客风欢迎屏的 provider 选择界面（16:9 横向布局）。
     *
     * 布局：
     *   ╭──────────────────────────────────────────────╮
     *   │                                              │
     *   │   ASCII Logo         小狗图案                 │
     *   │   (5 行并排)                                   │
     *   │   v0.1.0 · tagline    "Woof! ..."            │
     *   │                                              │
     *   ├──────────────────────────────────────────────┤
     *   │                                              │
     *   │   ❯ provider1    meta                        │
     *   │     provider2    meta                        │
     *   │   ↑↓ · Enter · Ctrl+C                        │
     *   │                                              │
     *   ╰──────────────────────────────────────────────╯
     */
    private static ProviderConfig showProviderSelector(List<ProviderConfig> providers) {
        try {
            Terminal terminal = TerminalBuilder.builder()
                    .jna(true).system(true)
                    .signalHandler(Terminal.SignalHandler.SIG_IGN)
                    .build();
            terminal.enterRawMode();
            PrintWriter writer = terminal.writer();

            int selectedIdx = 0;

            BindingReader bindingReader = new BindingReader(terminal.reader());
            KeyMap<String> keys = new KeyMap<>();
            keys.bind("up",    key(terminal, Capability.key_up),    "\033[A", "\033OA");
            keys.bind("down",  key(terminal, Capability.key_down),  "\033[B", "\033OB");
            keys.bind("enter", "\r", "\n");
            keys.bind("quit",  ctrl('C'));

            while (true) {
                Integer hObj = terminal.getHeight();
                Integer wObj = terminal.getWidth();
                int h = (hObj != null && hObj > 0) ? hObj : 24;
                int w = (wObj != null && wObj > 0) ? wObj : 80;

                StringBuilder buf = new StringBuilder();
                buf.append(ESC + "[2J" + ESC + "[3J" + ESC + "[H" + ESC + "[?25l");

                renderWelcome(buf, w, h, providers, selectedIdx);

                writer.print(buf.toString());
                writer.flush();

                String op = bindingReader.readBinding(keys);
                if (op == null || op.equals("quit")) { System.exit(0); }

                if (op.equals("up") && selectedIdx > 0) {
                    selectedIdx--;
                } else if (op.equals("down") && selectedIdx < providers.size() - 1) {
                    selectedIdx++;
                } else if (op.equals("enter")) {
                    break;
                }
            }

            terminal.close();

            ProviderConfig chosen = providers.get(selectedIdx);
            System.out.println("Selected: " + chosen.getName() + " (" + chosen.getModel() + ")");
            return chosen;

        } catch (Exception e) {
            System.err.println("Provider selection failed: " + e.getMessage());
            System.exit(1);
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  欢迎屏渲染（16:9 横向布局）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 渲染完整的欢迎/选择界面。
     * Logo + 小狗左右并排，版本 + 标语同一行，整体偏横向。
     */
    private static void renderWelcome(StringBuilder buf, int w, int h,
                                      List<ProviderConfig> providers, int selectedIdx) {
        // ── 计算最大 provider 名长度（用于对齐）──
        int maxNameLen = 0;
        for (ProviderConfig p : providers) {
            maxNameLen = Math.max(maxNameLen, p.getName().length());
        }

        // ── 计算各区块宽度 ──
        int logoWidth = maxLineLen(LOGO);
        int dogWidth  = maxLineLen(DOG);
        int logoDogGap = 6;
        int combinedLogoDog = logoWidth + logoDogGap + dogWidth;

        // 版本 + 标语 并排
        String verText  = APP_VERSION + " · " + "Your AI pair programmer";
        String quoteText = "\"Woof! Ready to pair-program!\"";
        int verQuoteGap = 6;
        int combinedVerQuote = verText.length() + verQuoteGap + quoteText.length();

        // provider 行宽度
        int providerLineLen = 2 + maxNameLen + 4 + 10 + 3 + 20;

        int minContentW = Math.max(combinedLogoDog,
                          Math.max(combinedVerQuote,
                          Math.max(providerLineLen, 50)));
        int boxW = Math.min(w, minContentW + 10);
        if (boxW > w) boxW = w;
        if (boxW < 40) boxW = 40;

        int boxX = (w - boxW) / 2;
        if (boxX < 0) boxX = 0;

        boolean showLogo = (boxW >= combinedLogoDog + 4);

        // ── 计算盒子高度（16:9 横向：尽量少行）──
        int contentH = 1 /*顶留白*/
                + LOGO.length          /*logo+小狗并排*/
                + 1                   /*留白*/
                + 1                   /*版本+标语*/
                + 1                   /*留白*/
                + 1                   /*分隔线*/
                + 1                   /*留白*/
                + providers.size()   /*provider 列表*/
                + 1                   /*留白*/
                + 1                   /*提示行*/
                + 1;                  /*底留白*/
        int boxH = contentH + 2;

        int boxY = (h - boxH) / 2;
        if (boxY < 0) boxY = 0;

        int left = boxX;
        int right = boxX + boxW - 1;
        int innerW = boxW - 2;

        // ── 顶边框 ──
        moveTo(buf, boxY, left);
        buf.append(BORDER).append('╭').append(repeat('─', innerW)).append('╮').append(RESET);

        int y = boxY + 1;

        // ── 顶留白 ──
        y = drawBoxRow(buf, y, left, right, "");

        // ── Logo + 小狗 并排 ──
        if (showLogo) {
            int logoDogX = left + 1 + (innerW - combinedLogoDog) / 2;
            int dogX = logoDogX + logoWidth + logoDogGap;
            for (int i = 0; i < LOGO.length; i++) {
                // 左右边框
                moveTo(buf, y, left);
                buf.append(BORDER).append('│').append(RESET);
                moveTo(buf, y, right);
                buf.append(BORDER).append('│').append(RESET);
                // Logo
                moveTo(buf, y, logoDogX);
                buf.append(CYAN).append(BOLD).append(LOGO[i]).append(RESET);
                // 小狗（LOGO 比 DOG 多一行，最后一行不画狗）
                if (i < DOG.length) {
                    moveTo(buf, y, dogX);
                    buf.append(GREEN).append(DOG[i]).append(RESET);
                }
                y++;
            }
        } else {
            // 终端太窄，回退为纯文本
            y = drawBoxRow(buf, y, left, right,
                    BOLD + CYAN + APP_NAME + RESET + " " + DIM + APP_VERSION + RESET);
        }

        // ── 留白 ──
        y = drawBoxRow(buf, y, left, right, "");

        // ── 版本 + 标语 同一行 ──
        if (showLogo) {
            int verQuoteX = left + 1 + (innerW - combinedVerQuote) / 2;
            // 左右边框
            moveTo(buf, y, left);
            buf.append(BORDER).append('│').append(RESET);
            moveTo(buf, y, right);
            buf.append(BORDER).append('│').append(RESET);
            // 版本
            moveTo(buf, y, verQuoteX);
            buf.append(DIM).append(APP_VERSION).append(RESET)
               .append(" · ").append(GREEN).append("Your AI pair programmer").append(RESET);
            // 标语
            moveTo(buf, y, verQuoteX + verText.length() + verQuoteGap);
            buf.append(YELLOW).append(quoteText).append(RESET);
            y++;
        } else {
            y = drawBoxRow(buf, y, left, right,
                    DIM + APP_VERSION + RESET + "  ·  "
                    + GREEN + "Your AI pair programmer" + RESET);
            y = drawBoxRow(buf, y, left, right, "");
            y = drawDogRow(buf, y, left, right, innerW);
        }

        // ── 留白 ──
        y = drawBoxRow(buf, y, left, right, "");

        // ── 分隔线 ──
        moveTo(buf, y, left);
        buf.append(BORDER).append('├').append(repeat('─', innerW)).append('┤').append(RESET);
        y++;

        // ── 留白 ──
        y = drawBoxRow(buf, y, left, right, "");

        // ── Provider 列表（整块左对齐）──
        int maxProviderW = 0;
        for (ProviderConfig p : providers) {
            int len = 2 + maxNameLen + 4 + p.getProtocol().length() + 3 + p.getModel().length();
            if (len > maxProviderW) maxProviderW = len;
        }
        int providerX = left + 1 + Math.max(0, (innerW - maxProviderW) / 2);

        for (int i = 0; i < providers.size(); i++) {
            ProviderConfig p = providers.get(i);
            String prefix = (i == selectedIdx)
                    ? BOLD + YELLOW + "❯ " + RESET
                    : GRAY + "  " + RESET;
            String paddedName = padRight(p.getName(), maxNameLen);
            String nameColored = (i == selectedIdx)
                    ? BOLD + CYAN + paddedName + RESET
                    : CYAN + paddedName + RESET;
            String meta = GRAY + "    " + p.getProtocol() + " · " + p.getModel() + RESET;
            y = drawBoxRowLeft(buf, y, left, right, providerX, prefix + nameColored + meta);
        }

        // ── 留白 ──
        y = drawBoxRow(buf, y, left, right, "");

        // ── 操作提示 ──
        String hint = GRAY + "↑↓ navigate  ·  Enter select  ·  Ctrl+C quit" + RESET;
        y = drawBoxRow(buf, y, left, right, hint);

        // ── 底留白 ──
        y = drawBoxRow(buf, y, left, right, "");

        // ── 底边框 ──
        moveTo(buf, y, left);
        buf.append(BORDER).append('╰').append(repeat('─', innerW)).append('╯').append(RESET);
    }

    /**
     * 渲染小狗 + 标语行（小狗在左，标语在右）。
     * 仅在终端太窄、回退到纵向布局时使用。
     */
    private static int drawDogRow(StringBuilder buf, int startRow, int left, int right, int innerW) {
        String quote1 = YELLOW + "\"Woof! Ready to pair-program.\"" + RESET;
        String quote2 = GRAY + "Ask me anything about your code." + RESET;

        int dogW = DOG[0].length();
        int quoteW = stripAnsi(quote1).length();
        int combinedW = dogW + 4 + quoteW;
        int dogX = left + 1 + (innerW - combinedW) / 2;
        if (dogX < left + 1) dogX = left + 1;

        for (int i = 0; i < DOG.length; i++) {
            int y = startRow + i;
            moveTo(buf, y, left);
            buf.append(BORDER).append('│').append(RESET);
            moveTo(buf, y, right);
            buf.append(BORDER).append('│').append(RESET);
            moveTo(buf, y, dogX);
            buf.append(GREEN).append(DOG[i]).append(RESET);
            if (i == 0) {
                moveTo(buf, y, dogX + dogW + 4);
                buf.append(quote1);
            } else if (i == 1) {
                moveTo(buf, y, dogX + dogW + 4);
                buf.append(quote2);
            }
        }
        return startRow + DOG.length;
    }

    // ═══════════════════════════════════════════════════════════════
    //  工具方法
    // ═══════════════════════════════════════════════════════════════

    /**
     * 绘制盒子内的一行：左右边框 + 居中内容。
     * @return 下一行的行号
     */
    private static int drawBoxRow(StringBuilder buf, int y, int left, int right, String content) {
        moveTo(buf, y, left);
        buf.append(BORDER).append('│').append(RESET);
        moveTo(buf, y, right);
        buf.append(BORDER).append('│').append(RESET);
        if (content != null && !content.isEmpty()) {
            int innerW = right - left - 1;
            int contentW = stripAnsi(content).length();
            int cx = left + 1 + (innerW - contentW) / 2;
            if (cx < left + 1) cx = left + 1;
            moveTo(buf, y, cx);
            buf.append(content);
        }
        return y + 1;
    }

    /**
     * 绘制盒子内的一行：左右边框 + 左对齐内容（固定起始列）。
     * @return 下一行的行号
     */
    private static int drawBoxRowLeft(StringBuilder buf, int y, int left, int right,
                                      int contentX, String content) {
        moveTo(buf, y, left);
        buf.append(BORDER).append('│').append(RESET);
        moveTo(buf, y, right);
        buf.append(BORDER).append('│').append(RESET);
        if (content != null && !content.isEmpty()) {
            moveTo(buf, y, contentX);
            buf.append(content);
        }
        return y + 1;
    }

    /** 返回字符串数组中最长行的长度。 */
    private static int maxLineLen(String[] lines) {
        int max = 0;
        for (String l : lines) {
            if (l.length() > max) max = l.length();
        }
        return max;
    }

    /** 移动光标到 (row, col)，0-based。 */
    private static void moveTo(StringBuilder buf, int row, int col) {
        buf.append(ESC).append('[').append(row + 1).append(';').append(col + 1).append('H');
    }

    /** 移除 ANSI 转义序列，返回纯文本。 */
    private static String stripAnsi(String s) {
        return s.replaceAll("\u001b\\[[0-9;]*[a-zA-Z]", "");
    }

    /** 左对齐填充字符串到指定宽度。 */
    private static String padRight(String s, int width) {
        if (s.length() >= width) return s;
        return s + repeat(' ', width - s.length());
    }

    private static String repeat(char c, int n) {
        return n > 0 ? String.valueOf(c).repeat(n) : "";
    }
}
