package com.agent.tui;

import static com.agent.tui.TuiStyle.*;

import com.agent.command.Command;
import com.agent.mcp.McpManager;
import com.agent.teams.TeammateProgress;

import java.util.ArrayList;
import java.util.List;

/**
 * 主界面全屏渲染器：对话区、输入区、命令提示、右侧状态面板、状态栏、选择器。
 * 只做“绘制”，不持有会话/权限/流式状态；所需状态从 TerminalUI 读取（同包可见字段）。
 */
final class ScreenRenderer {

    private final TerminalUI ui;

    ScreenRenderer(TerminalUI ui) {
        this.ui = ui;
    }

    void render(StringBuilder buf) {
        // 全屏选择器激活时：覆盖正常界面，只渲染选择器
        if (ui.activePicker != null) {
            buf.append(CLEAR).append(HOME);
            renderPicker(buf, ui.termWidth, ui.termHeight);
            return;
        }
        // 结构化问卷激活时：覆盖正常界面，只渲染问卷对话框
        if (ui.pendingAsk != null) {
            buf.append(CLEAR).append(HOME);
            ui.renderAskDialog(buf, ui.termWidth, ui.termHeight);
            return;
        }
        buf.append(HOME);
        int rows = ui.termHeight;
        int cols = ui.termWidth;
        // 右侧状态面板：终端足够宽时显示
        boolean showPanel = cols >= PANEL_MIN_COLS && ui.panelVisible;
        int panelW = showPanel ? PANEL_WIDTH : 0;
        int leftCols = showPanel ? cols - panelW - 1 : cols;  // -1 给竖线分隔
        int panelX = showPanel ? cols - panelW : 0;            // 面板起始列
        // 布局：状态行(1) | 分隔(1) | {对话区 | 命令提示 | 分隔(1) | 输入区} + 状态面板 | 状态栏(1)
        int statusRow = 0;
        int sep1Row = 1;
        int convStart = 2;
        // 输入框高度随内容增长（长行折行后同样计入行数），但最多 MAX_INPUT_ROWS / rows-8，
        // 再长就由输入框内部滚动：否则一次长粘贴就能把对话区挤没
        int inputHeight = computeInputHeight(rows);
        int sep2Row = rows - inputHeight - 2;
        int inputTop = sep2Row + 1;
        int statusBarRow = rows - 1;
        // 命令提示面板（输入 / 时）：占据对话区底部，对话区至少保留 3 行
        List<Command> hintCmds = ui.currentCommandCandidates();
        int hintHeight = 0;
        if (!hintCmds.isEmpty()) {
            int maxByList = Math.min(hintCmds.size(), MAX_COMMAND_HINTS) + 2;  // 顶线 + 条目 + 操作提示行
            int maxBySpace = Math.max(0, sep2Row - convStart - 3);             // 对话区至少留 3 行
            hintHeight = Math.min(maxByList, maxBySpace);
            if (hintHeight < 3) hintHeight = 0;   // 空间太小放不下完整面板就不显示
        }
        // 确保对话区至少有 3 行
        int convEnd = sep2Row - 1 - hintHeight;
        if (convEnd - convStart < 3) {
            // 空间不足：优先放弃命令提示面板
            if (hintHeight > 0) {
                hintHeight = 0;
                convEnd = sep2Row - 1;
            }
            if (convEnd - convStart < 3) {
                convEnd = convStart + 3;
                sep2Row = convEnd;
                inputTop = sep2Row + 1;
                if (inputTop + inputHeight >= rows) {
                    inputHeight = rows - inputTop - 1;
                    if (inputHeight < 1) inputHeight = 1;
                }
            }
        }
        // ── 状态行（全宽）──
        moveTo(buf, statusRow, 0);
        String statusLine;
        if (ui.streaming) {
            if (!ui.firstTokenReceived) {
                long elapsed = (System.currentTimeMillis() - ui.streamStartMs) / 1000;
                statusLine = BOLD + YELLOW + "DeveCode: Imagining\u2026  (" + elapsed + "s)" + RESET;
            } else {
                statusLine = BOLD + GREEN + "DeveCode: Streaming\u2026" + RESET;
            }
        } else {
            statusLine = BOLD + "DeveCode: " + GREEN + "Ready" + RESET;
        }
        buf.append("\033[K");
        buf.append(truncate(statusLine, cols));
        // ── 分隔线1（全宽）──
        moveTo(buf, sep1Row, 0);
        buf.append(GRAY).append(repeat('-', cols)).append(RESET);
        // ── 对话区（左侧）──
        int convWidth = leftCols - 1;
        renderConversation(buf, convStart, convEnd, convWidth, leftCols);
        // ── 命令提示面板（对话区与分隔线2之间，输入 / 时出现）──
        if (hintHeight > 0) {
            renderCommandHints(buf, sep2Row - hintHeight, leftCols, hintCmds, hintHeight - 2);
        }
        // ── 分隔线2（仅左侧）──
        moveTo(buf, sep2Row, 0);
        buf.append("\033[K");
        buf.append(GRAY).append(repeat('-', leftCols)).append(RESET);
        // ── 右侧状态面板 ──
        if (showPanel) {
            // 竖线分隔（从 convStart 到 statusBarRow-1）
            for (int y = convStart; y < statusBarRow; y++) {
                moveTo(buf, y, leftCols);
                buf.append(GRAY).append('│').append(RESET);
            }
            renderStatusPanel(buf, convStart, statusBarRow - 1, leftCols + 1, panelW);
        }
        // ── 状态栏（全宽）──
        renderStatusBar(buf, statusBarRow, cols);
        // ── 输入区（仅左侧）── 最后渲染，确保光标定位在输入框
        renderInputArea(buf, inputTop, inputHeight, leftCols);
        // 写入终端
    }

    /** 左侧可用宽度（扣除右侧面板和竖线） */
    private int leftContentWidth() {
        int cols = ui.termWidth;
        boolean showPanel = cols >= PANEL_MIN_COLS && ui.panelVisible;
        return showPanel ? cols - PANEL_WIDTH - 1 - 1 : cols - 1;
    }

    private List<RenderLine> buildAllRenderLines() {
        List<RenderLine> allLines = new ArrayList<>();
        int textWidth = Math.max(1, leftContentWidth());
        List<UIMessage> snapshot;
        synchronized (ui.messages) { snapshot = new ArrayList<>(ui.messages); }
        for (UIMessage msg : snapshot) { allLines.addAll(msg.toRenderLines(textWidth)); }
        return allLines;
    }
    /** PageUp/PageDown 每次滚动的行数（对话区可见行数 - 1，至少 1）。 */
    int pageScrollAmount() {
        int n = estimateConvAvailRows() - 1;
        return Math.max(1, n);
    }

    private int estimateConvAvailRows() {
        int rows = ui.termHeight;
        int inputHeight = computeInputHeight(rows);
        int sep2Row = rows - inputHeight - 2;
        int convStart = 2, convEnd = sep2Row - 1;
        if (convEnd - convStart < 3) convEnd = convStart + 3;
        return convEnd - convStart + 1;
    }
    private void renderConversation(StringBuilder buf, int startRow, int endRow, int textWidth, int termCols) {
        int availRows = endRow - startRow + 1;
        if (availRows <= 0) return;
        List<RenderLine> allLines = buildAllRenderLines();
        int totalLines = allLines.size();
        int maxScroll = Math.max(0, totalLines - availRows);
        if (ui.scrollOffset > maxScroll) ui.scrollOffset = maxScroll;
        int visibleStart = Math.max(0, totalLines - availRows - ui.scrollOffset);
        if (visibleStart < 0) visibleStart = 0;
        int y = startRow;
        for (int i = visibleStart; i < totalLines && y <= endRow; i++) {
            moveTo(buf, y, 0);
            buf.append("\033[K");
            if (i < allLines.size()) {
                RenderLine rl = allLines.get(i);
                String text = truncate(rl.text(), textWidth);
                buf.append(rl.style()).append(text).append(RESET);
                int vl = visibleLength(text);
                if (vl < textWidth) buf.append(repeat(' ', textWidth - vl));
                drawScrollbarCell(buf, y, termCols, i, visibleStart, totalLines, availRows);
            }
            y++;
        }
        for (; y <= endRow; y++) { moveTo(buf, y, 0); buf.append("\033[K"); }
    }
    private void drawScrollbarCell(StringBuilder buf, int row, int termCols, int lineIdx, int visibleStart, int totalLines, int availRows) {
        if (totalLines <= availRows) return;
        int maxScroll = totalLines - availRows;
        int thumbHeight = Math.max(1, availRows * availRows / totalLines);
        int thumbTop = maxScroll > 0 ? (maxScroll - ui.scrollOffset) * (availRows - thumbHeight) / maxScroll : availRows - thumbHeight;
        int thumbBottom = thumbTop + thumbHeight - 1;
        int rowInTrack = lineIdx - visibleStart;
        moveTo(buf, row, termCols - 1);
        if (rowInTrack >= thumbTop && rowInTrack <= thumbBottom) {
            buf.append(REVERSE).append(' ').append(RESET);
        } else {
            buf.append(GRAY).append('│').append(RESET);
        }
    }


    private void renderInputArea(StringBuilder buf, int topRow, int height, int cols) {
        // 只清空左侧区域，不擦掉右侧竖线和面板
        for (int y = topRow; y < topRow + height; y++) {
            moveTo(buf, y, 0);
            buf.append(repeat(' ', cols));
        }

        // ASCII border
        moveTo(buf, topRow, 0);
        buf.append(GRAY).append("+").append(repeat('-', cols - 2)).append("+").append(RESET);
        for (int y = topRow + 1; y < topRow + height - 1; y++) {
            moveTo(buf, y, 0);
            buf.append(GRAY).append("|").append(RESET);
            moveTo(buf, y, cols - 1);
            buf.append(GRAY).append("|").append(RESET);
        }
        moveTo(buf, topRow + height - 1, 0);
        buf.append(GRAY).append("+").append(repeat('-', cols - 2)).append("+").append(RESET);

        // prompt + input content
        if (height >= 2) {
            moveTo(buf, topRow + 1, 1);
            buf.append(BOLD).append("> ").append(RESET);

            if (ui.inputBuffer.isEmpty() && !ui.streaming) {
                buf.append(DIM).append("Send a message · ↑↓ history/scroll · Ctrl+C quit · Ctrl+P panel").append(RESET);
                moveTo(buf, topRow + 1, 3);
                buf.append(CURSOR_SHOW);
            } else if (ui.inputBuffer.isEmpty() && ui.streaming && ui.pendingPermission != null) {
                buf.append(YELLOW).append("Permission required: [y] allow  [a] always  [n] deny").append(RESET);
                moveTo(buf, topRow + 1, 3);

            } else if (ui.inputBuffer.isEmpty() && ui.streaming) {
                buf.append(DIM).append("Working… press Esc to interrupt, ctrl + c to quit").append(RESET);
                moveTo(buf, topRow + 1, 3);

            } else {
                // 长行先折成"屏幕行"，再决定窗口和光标位置。
                // 曾经的做法是把逻辑行原样丢给终端，让终端自己在行尾折行：续行落在输入框
                // 之外的屏幕行上，下一帧又被 moveTo 覆盖 —— 用户看到的现象就是"打/贴到一定
                // 长度之后中间一段文字凭空消失"，进而以为输入框有长度上限。
                // 缓冲区其实一个字符都没丢，丢的是画面。
                String[] lines = inputLogicalLines();
                List<InputLine> wrapped = wrapInputLines(lines);
                int visibleRows = Math.max(1, height - 2);
                int startIdx = Math.max(0, wrapped.size() - visibleRows);
                int cursorRowClamped = Math.min(Math.max(ui.cursorRow, 0), lines.length - 1);
                int cursorColClamped = Math.min(Math.max(ui.cursorCol, 0),
                        lines[cursorRowClamped].length());
                int cursorIdx = findInputLine(wrapped, cursorRowClamped, cursorColClamped);
                if (!ui.streaming && cursorIdx >= 0) {
                    // 窗口跟随光标：光标跑到可视区上方/下方时把窗口拉回来
                    if (cursorIdx < startIdx) startIdx = cursorIdx;
                    if (cursorIdx >= startIdx + visibleRows) startIdx = cursorIdx - visibleRows + 1;
                }
                startIdx = Math.max(0, Math.min(startIdx,
                        Math.max(0, wrapped.size() - visibleRows)));

                for (int i = startIdx; i < wrapped.size() && i - startIdx < visibleRows; i++) {
                    moveTo(buf, topRow + 1 + (i - startIdx), 3);
                    buf.append(wrapped.get(i).text());
                }

                if (!ui.streaming && cursorIdx >= startIdx && cursorIdx < startIdx + visibleRows) {
                    InputLine cur = wrapped.get(cursorIdx);
                    // 逻辑列 → 显示列（CJK / 全角按 2 列算）
                    moveTo(buf, topRow + 1 + (cursorIdx - startIdx),
                            3 + displayWidthOf(cur.text(), cursorColClamped - cur.startCol()));
                    buf.append(CURSOR_SHOW);
                }

                // 上方还有内容没显示：在顶边框上标一下，避免再次产生"我打的东西不见了"的错觉
                if (startIdx > 0) {
                    String more = " " + startIdx + " more ↑ ";
                    moveTo(buf, topRow, Math.max(1, cols - 2 - more.length()));
                    buf.append(GRAY).append(more).append(RESET);
                }
            }
        }
    }

    /**
     * 渲染命令提示面板（输入 / 时出现在对话区底部）。
     *
     * 结构：亮天蓝顶线（带 commands 标题）+ 候选条目列表 + 操作提示行。
     * ● 实心白点标记选中项（↑↓ 循环导航），○ 空心灰点标记未选中项，
     * 与全屏选择器的视觉惯例保持一致。
     *
     * @param startRow   面板首行（顶线）所在行
     * @param width      左侧区域宽度
     * @param cands      候选命令列表（已按名称排序）
     * @param maxVisible 空间允许显示的最大条目数
     */
    private void renderCommandHints(StringBuilder buf, int startRow, int width,
                                     List<Command> cands, int maxVisible) {
        int n = cands.size();
        int visible = Math.min(n, Math.min(maxVisible, MAX_COMMAND_HINTS));
        if (visible <= 0) return;
        int idx = Math.min(Math.max(ui.commandHintIndex, 0), n - 1);

        // 滚动窗口跟随选中项（尽量居中）
        int winStart;
        if (n <= visible) {
            winStart = 0;
        } else {
            winStart = idx - visible / 2;
            if (winStart < 0) winStart = 0;
            if (winStart > n - visible) winStart = n - visible;
        }

        // ── 顶线：╾─ commands ──────（亮天蓝，区别于灰色分隔线）──
        moveTo(buf, startRow, 0);
        buf.append("\033[K");
        String header = " commands ";
        int fill = Math.max(0, width - header.length() - 2);
        buf.append(BORDER).append('╾').append(header).append(repeat('─', fill)).append(RESET);

        // ── 条目列表（命令列对齐，描述跟随其后）──
        // 命令列可见宽度 = max("/name (aliases)")，用于各条目描述列对齐
        int cmdColW = 0;
        for (int i = winStart; i < winStart + visible; i++) {
            cmdColW = Math.max(cmdColW, commandColumnWidth(cands.get(i)));
        }

        int y = startRow + 1;
        for (int i = winStart; i < winStart + visible; i++) {
            Command c = cands.get(i);
            boolean sel = (i == idx);
            moveTo(buf, y, 0);
            buf.append("\033[K");

            // 标记 + 命令名（选中：白粗体；未选中：白色）；skill 命令附带青色 [skill] 标识
            String marker = sel ? BOLD + WHITE + "● " + RESET : GRAY + "○ " + RESET;
            String nameColored = (sel ? BOLD + WHITE : WHITE) + "/" + c.name() + RESET;
            String aliases = "";
            if (c.aliases().length > 0) {
                aliases = GRAY + " (" + String.join(", ", c.aliases()) + ")" + RESET;
            }
            String skillTag = c.skill() ? CYAN + " [skill]" + RESET : "";
            buf.append(marker).append(nameColored).append(aliases).append(skillTag);

            // 描述（灰色，对齐到统一列；空间不足时截断）
            int used = 2 + commandColumnWidth(c);
            int descCol = 2 + cmdColW + 2;
            if (descCol < width - 4) {
                buf.append(repeat(' ', descCol - used));
                buf.append(GRAY).append(truncate(c.description(), width - descCol - 1)).append(RESET);
            }
            y++;
        }

        // ── 操作提示行（条目超出窗口时附带位置指示）──
        moveTo(buf, y, 0);
        buf.append("\033[K");
        String hint = GRAY + "  ↑↓ select  ·  Tab complete  ·  Enter run"
                + (n > visible ? "  ·  " + (idx + 1) + "/" + n : "") + RESET;
        buf.append(truncate(hint, width));
    }

    /** 命令条目中命令列（"/name (aliases) [skill]"）的可见宽度。 */
    private static int commandColumnWidth(Command c) {
        int w = 1 + c.name().length();
        if (c.aliases().length > 0) {
            w += 3 + String.join(", ", c.aliases()).length();  // " (" + join + ")"
        }
        if (c.skill()) {
            w += 7;  // " [skill]"
        }
        return w;
    }

    // ═══════════════════════════════════════════════════════════════
    //  右侧状态面板
    // ═══════════════════════════════════════════════════════════════

    /**
     * 渲染右侧系统状态监控面板。
     * 区块化垂直堆叠，每块左侧有灰色竖线，用 ▰ 图标和颜色区隔。
     */
    private void renderStatusPanel(StringBuilder buf, int startRow, int endRow, int x, int width) {
        // 先清空面板区域
        for (int y = startRow; y <= endRow; y++) {
            moveTo(buf, y, x);
            buf.append("\033[K");
        }

        // ── 收集数据 ──
        int usedTokens = estimateTokens();
        // ui.provider.resolvedContextWindow() — 从 ProviderConfig 获取上下文窗口大小（如 200000）
        int contextWindow = ui.provider.resolvedContextWindow();
        double pct = contextWindow > 0 ? usedTokens * 100.0 / contextWindow : 0;
        double freePct = 100.0 - pct;
        // ui.provider.getModel() — 从 ProviderConfig 获取模型名（如 "deepseek-v4-flash"）
        String modelName = ui.provider.getModel();
        if (modelName.length() > 18) modelName = modelName.substring(0, 17) + "…";

        int cpuThreads = ui.osBean.getAvailableProcessors();
        double cpuLoad = ui.osBean.getCpuLoad() * 100;
        if (cpuLoad < 0) cpuLoad = 0;

        // ── 逐行渲染 ──
        int y = startRow;
        int padX = x + 1;    // 竖线位置
        int maxW = width - 2; // 内容最大宽度

        // 区块一：Context
        y = panelHeader(buf, y, padX, maxW, "Context");
        y = panelLine(buf, y, padX, maxW,
                WHITE + BOLD + formatTokens(usedTokens) + RESET +
                YELLOW + " (" + String.format("%.1f%%", pct) + ")" + RESET);
        y++;

        // 区块二：Context Detail
        y = panelHeader(buf, y, padX, maxW, "Context Detail");
        y = panelKV(buf, y, padX, maxW, "Context window:", formatTokens(contextWindow) + " (" + String.format("%.1f%%", pct) + ")");
        y = panelKV(buf, y, padX, maxW, "Model:", modelName);
        y = panelKV(buf, y, padX, maxW, "Mode:", ui.permissionChecker.getMode().name().toLowerCase());
        y = panelKV(buf, y, padX, maxW, "Tools:", String.valueOf(
                ui.toolRegistry.getAllSchemas(ui.provider.getProtocol()).size()));
        y = panelKV(buf, y, padX, maxW, "API usage:", "↑" + formatTokens(ui.usageInTokens) + " ↓" + formatTokens(ui.usageOutTokens));
        y = panelKV(buf, y, padX, maxW, "Free Space:", String.format("%.1f%%", freePct));
        y++;

        // 区块三：Compact（柱状图）
        y = panelHeader(buf, y, padX, maxW, "Compact");
        y = panelBar(buf, y, padX, maxW, pct);
        y++;  // 柱状图和图例之间空一行
        // 图例
        String usageColor = pct > 80 ? RED : (pct > 50 ? YELLOW : GREEN);
        y = panelLine(buf, y, padX, maxW,
                usageColor + "█" + RESET + GRAY + " usage  " + RESET +
                GRAY + "░" + RESET + GRAY + " usable" + RESET);
        y++;

        // 区块四：Sandbox
        y = panelHeader(buf, y, padX, maxW, "Sandbox");
        y = panelKV(buf, y, padX, maxW, "Session:", ui.sessionId);
        y = panelKV(buf, y, padX, maxW, "Status:",
                ui.permissionChecker.isSandboxEnabled()
                        ? GREEN + "●" + RESET + WHITE + " active" + RESET
                        : GRAY + "○" + RESET + WHITE + " off" + RESET);
        y++;

        // 区块五：MCP（真实连接状态：server 数 / 各 server 工具数 / 失败数）
        y = panelHeader(buf, y, padX, maxW, "MCP");
        if (ui.mcpServers.isEmpty() && ui.mcpErrors.isEmpty()) {
            y = panelKV(buf, y, padX, maxW, "servers:", "none");
        } else {
            y = panelKV(buf, y, padX, maxW, "servers:", String.valueOf(ui.mcpServers.size()));
            int shown = 0;
            for (var s : ui.mcpServers) {
                if (shown++ >= 4) {
                    // 超过 4 个折叠显示
                    y = panelLine(buf, y, padX, maxW, GRAY + "… +" + (ui.mcpServers.size() - 4) + " more" + RESET);
                    break;
                }
                y = panelKV(buf, y, padX, maxW,
                        McpManager.sanitizeName(s.name()) + ":",
                        ui.mcpToolCounts.getOrDefault(s.name(), 0) + " tools");
            }
            if (!ui.mcpErrors.isEmpty()) {
                y = panelKV(buf, y, padX, maxW, "errors:", String.valueOf(ui.mcpErrors.size()));
            }
        }
        y++;

        // 区块六：Teammates（只在有队友时出现，避免常态占用面板高度）
        var mates = ui.teammateProgress();
        if (!mates.isEmpty() && y + 3 < endRow) {
            y = panelHeader(buf, y, padX, maxW, "Teammates");
            int shownMates = 0;
            for (var tp : mates) {
                if (shownMates++ >= 3) {
                    y = panelLine(buf, y, padX, maxW,
                            GRAY + "… +" + (mates.size() - 3) + " more" + RESET);
                    break;
                }
                String mark = switch (tp.getStatus()) {
                    case "completed" -> GREEN + "●" + RESET;
                    case "failed" -> RED + "●" + RESET;
                    default -> YELLOW + "●" + RESET;
                };
                y = panelLine(buf, y, padX, maxW,
                        mark + " " + WHITE + tp.getName() + RESET + GRAY + " · "
                                + TeammateProgress.formatTokens(tp.getTokenCount()) + " tok · "
                                + truncate(tp.getActivitySummary(), 16) + RESET);
            }
            y++;
        }

        // 区块七：CPU
        y = panelHeader(buf, y, padX, maxW, "CPU");
        y = panelKV(buf, y, padX, maxW, "Threads:", String.valueOf(cpuThreads));
        y = panelKV(buf, y, padX, maxW, "Usage:", String.format("%.1f%%", cpuLoad));

        // 页脚（固定在面板底部）
        panelFooter(buf, endRow, padX, maxW, DIM + APP_NAME.toLowerCase() + " " + APP_VERSION + RESET);
    }

    /** 区块标题：│ ▰ Title（竖线 + 青色粗体） */
    private int panelHeader(StringBuilder buf, int y, int x, int maxW, String title) {
        moveTo(buf, y, x);
        buf.append("\033[K");
        buf.append(truncate(GRAY + "│ " + RESET + CYAN + BOLD + "▰ " + title + RESET, maxW));
        return y + 1;
    }

    /** 水平柱状图：│ ████████░░░░░░ 22.2% */
    private int panelBar(StringBuilder buf, int y, int x, int maxW, double pct) {
        moveTo(buf, y, x);
        buf.append("\033[K");

        int barW = maxW - 10;  // 留给 │ + 空格 + 百分比
        if (barW < 8) barW = 8;
        int filled = (int) Math.round(pct * barW / 100.0);
        if (filled > barW) filled = barW;
        if (filled == 0 && pct > 0) filled = 1;  // 至少 1 格
        int empty = barW - filled;

        String usageColor = pct > 80 ? RED : (pct > 50 ? YELLOW : GREEN);
        StringBuilder bar = new StringBuilder();
        bar.append(GRAY).append("│ ").append(RESET);
        bar.append(usageColor);
        for (int i = 0; i < filled; i++) bar.append('█');
        bar.append(RESET);
        bar.append(GRAY);
        for (int i = 0; i < empty; i++) bar.append('░');
        bar.append(RESET);
        bar.append(" ").append(YELLOW).append(String.format("%.1f%%", pct)).append(RESET);

        buf.append(truncate(bar.toString(), maxW));
        return y + 1;
    }

    /** 渲染面板中一行纯文本（带竖线前缀） */
    private int panelLine(StringBuilder buf, int y, int x, int maxW, String content) {
        moveTo(buf, y, x);
        buf.append("\033[K");
        buf.append(truncate(GRAY + "│ " + RESET + content, maxW));
        return y + 1;
    }

    /** 渲染面板中一行键值对：│ · label: value */
    private int panelKV(StringBuilder buf, int y, int x, int maxW, String label, String value) {
        moveTo(buf, y, x);
        buf.append("\033[K");
        String line = GRAY + "│ · " + label + " " + RESET + WHITE + value + RESET;
        buf.append(truncate(line, maxW));
        return y + 1;
    }

    /** 渲染页脚（无竖线前缀，固定底部） */
    private void panelFooter(StringBuilder buf, int y, int x, int maxW, String content) {
        moveTo(buf, y, x);
        buf.append("\033[K");
        buf.append(truncate(content, maxW));
    }

    private void renderStatusBar(StringBuilder buf, int row, int cols) {
        moveTo(buf, row, 0);
        buf.append("\033[K");
        buf.append(REVERSE);

        String left = " " + ui.provider.getName() + " ";
        String right = " " + ui.provider.getModel() + " ";
        int padding = cols - left.length() - right.length();
        if (padding < 0) padding = 0;

        buf.append(left);
        buf.append(repeat(' ', padding));
        buf.append(right);
        buf.append(RESET);
    }

    // ═══════════════════════════════════════════════════════════════
    //  全屏选择器渲染
    // ═══════════════════════════════════════════════════════════════

    /**
     * 渲染全屏选择器（与欢迎屏同一视觉语言：75 号天蓝边框、●/○ 选中标记、
     * ↑↓ 循环导航、居中盒子布局）。
     *
     * 条目超过可视高度时以选中项为中心滚动窗口。
     */
    private void renderPicker(StringBuilder buf, int w, int h) {
        PickerState p = ui.activePicker;
        if (p == null) return;
        int n = p.items().size();

        // ── 计算盒子尺寸 ──
        int maxItemW = 0;
        for (var item : p.items()) {
            int len = Math.max(visibleLength(item.id()), Math.max(
                    item.title().length(), item.subtitle().length()));
            if (len > maxItemW) maxItemW = len;
        }
        int hintLen = 44;  // "↑↓ navigate · Enter select · Esc/q cancel"
        int innerW = Math.min(Math.max(Math.max(maxItemW + 6, p.title().length() + 4), hintLen), Math.max(40, w - 4));
        int boxW = innerW + 2;
        if (boxW > w) { boxW = w; innerW = boxW - 2; }

        int visible = Math.min(n, Math.max(3, h - 10));   // 可视条目窗口
        int boxH = Math.min(h, 2 /*边框*/ + 2 /*标题+空行*/ + visible * 2 + 1 /*空行*/ + 1 /*提示*/ + 2 /*留白*/);

        int boxX = Math.max(0, (w - boxW) / 2);
        int boxY = Math.max(0, (h - boxH) / 2);
        int left = boxX;
        int right = boxX + boxW - 1;

        // ── 滚动窗口：保持选中项可见（尽量居中） ──
        int winStart;
        if (n <= visible) {
            winStart = 0;
        } else {
            winStart = ui.pickerIndex - visible / 2;
            if (winStart < 0) winStart = 0;
            if (winStart > n - visible) winStart = n - visible;
        }

        // ── 边框 ──
        moveTo(buf, boxY, left);
        buf.append(BORDER).append('╭').append(repeat('─', innerW)).append('╮').append(RESET);

        int y = boxY + 1;
        // 标题（青色粗体，左对齐带缩进）
        moveTo(buf, y, left);
        buf.append(BORDER).append('│').append(RESET);
        moveTo(buf, y, right);
        buf.append(BORDER).append('│').append(RESET);
        moveTo(buf, y, left + 2);
        buf.append(BOLD).append(CYAN).append(truncate(p.title(), innerW - 2)).append(RESET);
        y++;

        // 空行
        y = pickerBlankRow(buf, y, left, right);
        y = pickerBlankRow(buf, y, left, right);

        // ── 条目列表：● 实心白点选中 / ○ 空心灰点未选中 ──
        for (int i = winStart; i < winStart + visible && i < n; i++) {
            var item = p.items().get(i);
            boolean sel = (i == ui.pickerIndex);

            // 第一行：标记 + 标题
            moveTo(buf, y, left);
            buf.append(BORDER).append('│').append(RESET);
            moveTo(buf, y, right);
            buf.append(BORDER).append('│').append(RESET);
            moveTo(buf, y, left + 1);
            String prefix = sel ? BOLD + WHITE + "● " + RESET : GRAY + "○ " + RESET;
            String titleColored = sel
                    ? BOLD + WHITE + truncate(item.title(), innerW - 4) + RESET
                    : WHITE + truncate(item.title(), innerW - 4) + RESET;
            buf.append(prefix).append(titleColored);
            y++;

            // 第二行：id + 元信息（灰色）
            moveTo(buf, y, left);
            buf.append(BORDER).append('│').append(RESET);
            moveTo(buf, y, right);
            buf.append(BORDER).append('│').append(RESET);
            moveTo(buf, y, left + 3);
            String meta = GRAY + truncate(item.id() + " · " + item.subtitle(), innerW - 4) + RESET;
            buf.append(meta);
            y++;
        }

        // ── 空行 + 操作提示 ──
        y = pickerBlankRow(buf, y, left, right);
        moveTo(buf, y, left);
        buf.append(BORDER).append('│').append(RESET);
        moveTo(buf, y, right);
        buf.append(BORDER).append('│').append(RESET);
        String hint = GRAY + "↑↓ navigate  ·  Enter select  ·  Esc/q cancel" + RESET;
        int hintX = left + 1 + Math.max(0, (innerW - visibleLength(hint)) / 2);
        moveTo(buf, y, hintX);
        buf.append(hint);
        y++;

        // 滚动指示（条目超出窗口时显示）
        if (n > visible) {
            y = pickerBlankRow(buf, y, left, right);
            moveTo(buf, y, left);
            buf.append(BORDER).append('│').append(RESET);
            moveTo(buf, y, right);
            buf.append(BORDER).append('│').append(RESET);
            String pos = DIM + (ui.pickerIndex + 1) + " / " + n + RESET;
            moveTo(buf, y, left + 1 + Math.max(0, (innerW - visibleLength(pos)) / 2));
            buf.append(pos);
            y++;
        }

        // ── 底边框（固定在内容行之后）──
        moveTo(buf, y, left);
        buf.append(BORDER).append('╰').append(repeat('─', innerW)).append('╯').append(RESET);
    }

    /** 选择器盒子内的空行（只画左右边框）。 */
    private static int pickerBlankRow(StringBuilder buf, int y, int left, int right) {
        moveTo(buf, y, left);
        buf.append(BORDER).append('│').append(RESET);
        moveTo(buf, y, right);
        buf.append(BORDER).append('│').append(RESET);
        return y + 1;
    }

    // ═══════════════════════════════════════════════════════════════
    //  工具方法
    // ═══════════════════════════════════════════════════════════════

    /** 左侧区域占用的列数（右侧状态面板开启时扣掉面板本身和分隔竖线）。 */
    private int leftColsForLayout() {
        int cols = ui.termWidth;
        boolean showPanel = cols >= PANEL_MIN_COLS && ui.panelVisible;
        return showPanel ? cols - PANEL_WIDTH - 1 : cols;
    }

    /** 输入框内文本可用的显示宽度：左右边框各占 1 列，"> " 提示占 2 列。 */
    private int inputTextWidth() {
        return Math.max(1, leftColsForLayout() - 4);
    }

    /**
     * 输入框高度（含上下边框）：随内容行数增长，取 MAX_INPUT_ROWS 与 rows-8 的较小值。
     * rows-8 是屏幕兜底：终端再矮，输入框也不会把屏幕吃光，对话区仍能剩几行。
     * 超出视高的内容不丢失，由输入框内部滚动（顶边框上会标 "N more"）。
     */
    private int computeInputHeight(int rows) {
        int desired = Math.max(countInputLines() + 1, 3);
        int cap = Math.max(3, Math.min(MAX_INPUT_ROWS, rows - 8));
        return Math.min(desired, cap);
    }

    /** 输入框里一个"屏幕行"：来自逻辑行 row、从第 startCol 个字符开始的那一段。 */
    record InputLine(int row, int startCol, String text) {}

    private String[] inputLogicalLines() {
        return ui.inputBuffer.toString().split("\n", -1);
    }

    /**
     * 按输入框宽度把逻辑行折成屏幕行。
     *
     * 折行不改变缓冲区内容，只决定"怎么画"；每个片段都记录自己在逻辑行里的起始字符下标，
     * 供光标定位（逻辑列 → 第几个片段 + 片段内第几列）使用。
     */
    private List<InputLine> wrapInputLines(String[] lines) {
        return wrapText(lines, inputTextWidth());
    }

    /** 折行的纯函数版本：width = 每行可用的显示列数。不依赖 TerminalUI，便于直接单测。 */
    static List<InputLine> wrapText(String[] lines, int width) {
        var out = new ArrayList<InputLine>();
        for (int r = 0; r < lines.length; r++) {
            String line = lines[r];
            if (line.isEmpty()) {
                out.add(new InputLine(r, 0, ""));
                continue;
            }
            int i = 0;
            while (i < line.length()) {
                int start = i;
                int w = 0;
                while (i < line.length() && w + displayCharWidth(line.charAt(i)) <= width) {
                    w += displayCharWidth(line.charAt(i));
                    i++;
                }
                // 单个字符就宽过整行（极窄终端）：至少吃掉一个字符，避免死循环
                if (i == start) i++;
                out.add(new InputLine(r, start, line.substring(start, i)));
            }
        }
        return out;
    }

    /** 逻辑位置 (row, col) 落在第几个屏幕行上；找不到就退回该逻辑行的最后一个片段。 */
    static int findInputLine(List<InputLine> wrapped, int row, int col) {
        int fallback = -1;
        for (int i = 0; i < wrapped.size(); i++) {
            InputLine il = wrapped.get(i);
            if (il.row() != row) continue;
            fallback = i;
            if (col < il.startCol() + il.text().length()) return i;
        }
        return fallback;
    }

    /** text 前 chars 个字符的终端显示宽度（ASCII 1 列，CJK / 全角 2 列）。 */
    static int displayWidthOf(String text, int chars) {
        int n = Math.min(Math.max(chars, 0), text.length());
        int w = 0;
        for (int i = 0; i < n; i++) w += displayCharWidth(text.charAt(i));
        return w;
    }

    /** 输入框内容占几行（按折行后算）——空输入框也算 1 行。 */
    private int countInputLines() {
        if (ui.inputBuffer.isEmpty()) return 1;
        return wrapInputLines(inputLogicalLines()).size();
    }

    // ── Token 估算 ──

    /**
     * 粗略估算当前对话已消耗的 token 数（~4 字符/token）。
     *
     * 数据来源：
     *   - ConversationManager.getMessages()：已完成的对话历史
     *   - ui.streamAccum：当前流式输出中尚未完成的文本
     *
     * 用于右侧状态面板的 Context 占用率显示。
     */
    private int estimateTokens() {
        int totalChars = 0;
        // ui.conversation.getMessages() 返回的是发给 LLM 的消息列表（Message 类型）
        for (var msg : ui.conversation.getMessages()) {
            String content = msg.getContent();
            if (content != null) totalChars += content.length();
        }
        // 加上流式累积中的文本（尚未存入 ConversationManager）
        if (ui.streaming) totalChars += ui.streamAccum.length();
        return totalChars / 4;
    }

    /** 格式化 token 数为紧凑形式：1234 → "1.2K"，1000000 → "1.0M" */
    private static String formatTokens(int tokens) {
        if (tokens >= 1_000_000) return String.format("%.1fM", tokens / 1_000_000.0);
        if (tokens >= 1_000) return String.format("%.1fK", tokens / 1_000.0);
        return String.valueOf(tokens);
    }
}
