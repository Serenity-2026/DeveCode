package com.agent.tui;

import static com.agent.tui.TuiStyle.*;

import com.agent.agent.AgentEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AskUserQuestion 全屏问卷：交互状态机 + 渲染。
 *
 * <p>职责边界：
 * <ul>
 *   <li>持有单份问卷的全部交互状态（当前题、已答列表、选项选中、自定义输入、输入/选择模式）；</li>
 *   <li>接收按键：数字选选项、Tab 切换模式、任意字符自定义输入、退格、Enter 确认、Esc 取消；</li>
 *   <li>答完/取消时通过 {@code Host} 通知外层（写对话消息、滚动、重绘），并完成事件的 future；</li>
 *   <li>{@link #render} 只做纯绘制，不触碰对话消息。</li>
 * </ul>
 *
 * <p>跨线程访问约定：按键线程（inputLoop）通过 confirm/cancel/编辑方法修改状态，
 * 主线程 render() 在 synchronized(this) 下做快照，避免数据竞争。
 */
public final class AskUserDialog {

    /** 外层宿主：TerminalUI 实现，负责问卷完成/取消后的对话消息、滚动与重绘。 */
    public interface Host {
        void appendMessage(UIMessage message);
        void scrollToBottom();
        void requestRedraw();
    }

    private final AgentEvent.AskUserRequestEvent event;
    private final Host host;
    private final List<String> answers = new ArrayList<>(); // 与 questions 平行；未答为 ""

    private int current;             // 当前题号（0-based）
    private int selectedOption = -1; // 当前选项题选中的下标；-1=未选
    private String textAnswer = "";  // 当前题的自定义文本
    private boolean customInput;     // true = 自定义输入模式（数字也进文本框）
    private boolean finished;        // 已全部答完或已取消（外层据此清空 pendingAsk）

    public AskUserDialog(AgentEvent.AskUserRequestEvent event, Host host) {
        this.event = event;
        this.host = host;
        for (int i = 0; i < event.questions().size(); i++) {
            answers.add("");
        }
        this.customInput = !event.questions().isEmpty()
                && event.questions().getFirst().options().isEmpty();
    }

    public boolean isFinished() {
        return finished;
    }

    // ─────────────────────────────────────────────────────────────
    //  按键处理
    // ─────────────────────────────────────────────────────────────

    /**
     * 可打印按键：
     * - Tab：在「选选项」与「自定义输入」间切换（自定义模式下数字也进文本框）；
     * - 选项模式数字 1-6：选择选项；
     * - 自定义输入/纯文本题：任意可打印字符都进入文本框；
     * - 选项模式非数字字符：自动切到自定义输入并输入该字符。
     */
    public void onPrintable(int ch) {
        synchronized (this) {
            if (finished) return;
            var qs = event.questions();
            if (current < 0 || current >= qs.size()) return;
            var q = qs.get(current);
            boolean hasOptions = !q.options().isEmpty();
            if (ch == '\t' && hasOptions) {
                customInput = !customInput;
                if (customInput) selectedOption = -1;
            } else if (!hasOptions || customInput) {
                if (ch == '\t') ch = ' ';
                if (ch >= 32) {
                    if (textAnswer.length() < 1000) {
                        textAnswer += (char) ch;
                        if (hasOptions) selectedOption = -1;
                    }
                }
            } else if (hasOptions && ch >= '1' && ch <= '9') {
                int idx = ch - '1';
                if (idx < q.options().size()) {
                    selectedOption = idx;
                    textAnswer = "";
                    if (isFreeTextOptionLabel(q.options().get(idx).label())) {
                        selectedOption = -1;
                        customInput = true;
                    }
                }
            } else if (ch >= 32) {
                customInput = true;
                if (textAnswer.length() < 1000) {
                    textAnswer += (char) ch;
                    selectedOption = -1;
                }
            }
        }
        host.requestRedraw();
    }

    /** 退格：编辑自定义文本，并进入/保持自定义输入模式。 */
    public void onBackspace() {
        synchronized (this) {
            if (finished) return;
            if (current >= 0 && current < event.questions().size() && !textAnswer.isEmpty()) {
                textAnswer = textAnswer.substring(0, textAnswer.length() - 1);
                if (!event.questions().get(current).options().isEmpty()) {
                    customInput = true;
                }
            }
        }
        host.requestRedraw();
    }

    /**
     * 确认当前题：自定义文本优先，其次选中选项。全部答完后完成 future
     * 并把 Q&A 写入对话；未选也未输入时 Enter 无效果。
     */
    public void confirm() {
        String transcript = null;
        synchronized (this) {
            if (finished) return;
            var qs = event.questions();
            if (current < 0 || current >= qs.size()) return;
            var q = qs.get(current);
            String answer;
            String typed = textAnswer.strip();
            if (!typed.isEmpty()) {
                answer = typed;
            } else if (!q.options().isEmpty()
                    && selectedOption >= 0 && selectedOption < q.options().size()) {
                answer = q.options().get(selectedOption).label();
            } else {
                return; // 既没选中选项也没输入内容
            }
            answers.set(current, answer);
            if (current + 1 < qs.size()) {
                current++;
                selectedOption = -1;
                textAnswer = "";
                customInput = qs.get(current).options().isEmpty();
            } else {
                finished = true;
                var result = new LinkedHashMap<String, String>();
                var sb = new StringBuilder();
                for (int i = 0; i < qs.size(); i++) {
                    result.put(String.valueOf(i + 1), answers.get(i));
                    if (i > 0) sb.append('\n');
                    sb.append("  ").append(i + 1).append(". ").append(qs.get(i).question())
                      .append('\n').append("     ").append(GREEN).append("→ ").append(RESET)
                      .append(WHITE).append(answers.get(i)).append(RESET);
                }
                transcript = sb.toString();
                event.future().complete(result);
            }
        }
        if (transcript != null) {
            host.appendMessage(UIMessage.system(GRAY + "❓ answers recorded" + RESET + "\n" + transcript));
            host.scrollToBottom();
        }
        host.requestRedraw();
    }

    /** 取消整份问卷：以空 Map 完成 future（执行端按“拒绝回答”处理）。 */
    public void cancel() {
        synchronized (this) {
            if (finished) return;
            finished = true;
            event.future().complete(Map.of());
        }
        host.appendMessage(UIMessage.system(YELLOW + "✋ Question dialog cancelled" + RESET));
        host.scrollToBottom();
        host.requestRedraw();
    }

    // ─────────────────────────────────────────────────────────────
    //  渲染
    // ─────────────────────────────────────────────────────────────

    /**
     * 渲染全屏问卷：标题 → 已答摘要 → 当前问题（选项/自定义输入）→ 底部操作提示。
     * 状态在 synchronized(this) 下一次性快照，避免与按键线程竞争。
     */
    public void render(StringBuilder buf, int w, int h) {
        List<AgentEvent.AskUserRequestEvent.Question> qs;
        List<String> answersCopy;
        int currentCopy;
        int selectedCopy;
        String textAnswerCopy;
        boolean customInputCopy;
        synchronized (this) {
            if (finished) return;
            qs = event.questions();
            answersCopy = List.copyOf(answers);
            currentCopy = current;
            selectedCopy = selectedOption;
            textAnswerCopy = textAnswer;
            customInputCopy = customInput;
        }

        int n = qs.size();
        int y = 0;
        int safeW = Math.max(24, w);

        moveTo(buf, y, 0);
        buf.append("\033[K");
        buf.append(BOLD).append(CYAN).append("❓ AskUserQuestion").append(RESET)
           .append(GRAY).append("   ·   ").append(n).append(n == 1 ? " question" : " questions")
           .append("   ·   Esc cancel").append(RESET);
        y++;
        moveTo(buf, y, 0);
        buf.append("\033[K");
        buf.append(GRAY).append(repeat('─', Math.min(safeW - 2, 72))).append(RESET);
        y++;
        y++;

        int bottom = h - 3; // 底部保留 2 行提示 + 1 行边距

        for (int i = 0; i < Math.min(currentCopy, n); i++) {
            if (y >= bottom) break;
            String ans = i < answersCopy.size() ? answersCopy.get(i) : "";
            moveTo(buf, y, 0);
            buf.append("\033[K");
            buf.append("  ").append(GRAY).append("[").append(i + 1).append("/").append(n).append("]").append(RESET)
               .append(GREEN).append(" ✓ ").append(RESET)
               .append(GRAY).append("answer: ").append(RESET)
               .append(truncate(ans, Math.max(10, safeW - 22)));
            y++;
        }
        if (y > 4) y++;

        if (currentCopy >= 0 && currentCopy < n && y < bottom) {
            var q = qs.get(currentCopy);
            moveTo(buf, y, 0);
            buf.append("\033[K");
            buf.append("  ").append(YELLOW).append("● ").append(RESET)
               .append(GRAY).append("Question ").append(currentCopy + 1).append("/").append(n).append(RESET);
            y++;

            String header = q.header() == null ? "" : q.header().strip();
            if (!header.isEmpty() && y < bottom) {
                moveTo(buf, y, 0);
                buf.append("\033[K");
                buf.append("     ").append(BOLD).append(CYAN).append(truncate(header, safeW - 10)).append(RESET);
                y++;
            }

            String question = q.question() == null ? "" : q.question().strip();
            int rows = 0;
            for (String line : UIMessage.wrapText(question, Math.max(16, safeW - 8))) {
                if (y >= bottom || rows >= 3) break;
                moveTo(buf, y, 0);
                buf.append("\033[K");
                buf.append("     ").append(BOLD).append(truncate(line, safeW - 6)).append(RESET);
                y++;
                rows++;
            }
            y++;

            if (!q.options().isEmpty() && y < bottom) {
                int shown = Math.min(q.options().size(), Math.min(6, Math.max(1, bottom - y - 2)));
                for (int oi = 0; oi < shown && y < bottom; oi++) {
                    var opt = q.options().get(oi);
                    boolean sel = oi == selectedCopy;
                    moveTo(buf, y, 0);
                    buf.append("\033[K");
                    buf.append("     ").append(sel ? BOLD + WHITE + "● " + RESET : GRAY + "○ " + RESET);
                    buf.append(GRAY).append(oi + 1).append(". ").append(RESET);
                    buf.append(sel ? BOLD + WHITE : WHITE)
                       .append(truncate(opt.label(), Math.max(10, safeW - 28))).append(RESET);
                    y++;
                    String desc = opt.description() == null ? "" : opt.description().strip();
                    if (!desc.isEmpty() && y < bottom) {
                        moveTo(buf, y, 0);
                        buf.append("\033[K");
                        buf.append("           ").append(DIM).append(truncate(desc, Math.max(10, safeW - 24))).append(RESET);
                        y++;
                    }
                }
                if (q.options().size() > shown && y < bottom) {
                    moveTo(buf, y, 0);
                    buf.append("\033[K");
                    buf.append("     ").append(DIM)
                       .append("… +").append(q.options().size() - shown).append(" more").append(RESET);
                    y++;
                }
            }
            if (y < bottom) {
                boolean optionQuestion = currentCopy >= 0 && currentCopy < n
                        && !qs.get(currentCopy).options().isEmpty();
                boolean typing = !optionQuestion || customInputCopy
                        || (textAnswerCopy != null && !textAnswerCopy.isEmpty());
                moveTo(buf, y, 0);
                buf.append("\033[K");
                if (!typing) {
                    buf.append("     ").append(DIM)
                       .append("[Tab] type your own answer").append(RESET);
                } else {
                    buf.append("     ").append(DIM).append("custom answer: ").append(RESET)
                       .append(GREEN).append("> ").append(RESET)
                       .append(truncate(textAnswerCopy == null ? "" : textAnswerCopy,
                               Math.max(10, safeW - 24)))
                       .append(REVERSE).append(' ').append(RESET);
                }
                y++;
            }
        }

        int hintRow = Math.max(0, h - 2);
        moveTo(buf, hintRow, 0);
        buf.append("\033[K");
        boolean hasOptions = currentCopy >= 0 && currentCopy < n
                && !qs.get(currentCopy).options().isEmpty();
        String hint = hasOptions
                ? (customInputCopy
                    ? "Typing custom answer (digits ok) · Enter confirm · Tab to choose · Esc cancel"
                    : "Press 1-" + qs.get(currentCopy).options().size()
                            + " to choose · Tab to type custom · Enter confirm · Esc cancel")
                : "Type your answer · Enter to confirm · Esc to cancel";
        buf.append(DIM).append(truncate(hint, safeW)).append(RESET);

        int footRow = Math.max(0, h - 1);
        moveTo(buf, footRow, 0);
        buf.append("\033[K");
        buf.append(DIM).append("DeveCode · AskUserQuestion").append(RESET);
    }

    /** 选项是否表达了"让我自己输入"（命中即自动进入自定义输入模式）。 */
    private static boolean isFreeTextOptionLabel(String label) {
        if (label == null) return false;
        String l = label.toLowerCase(Locale.ROOT);
        return l.contains("自由输入") || l.contains("自由填写") || l.contains("自定义")
                || l.contains("其它") || l.contains("其他") || l.contains("请输入")
                || l.contains("other") || l.contains("custom") || l.contains("free text")
                || l.contains("free input");
    }
}
