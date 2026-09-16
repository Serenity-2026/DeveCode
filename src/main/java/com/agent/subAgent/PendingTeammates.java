package com.agent.subAgent;

import com.agent.history.ConversationManager;
import com.agent.teams.FileMailBox;
import com.agent.teams.TeamManager;
import com.agent.teams.TeammateRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * "派出去但还没回话的队友"台账。
 *
 * <p>为什么需要它：{@code Agent(team_name=...)} 是异步派发——队友是长驻员工，干完一轮仍会留在
 * 自己的线程上等新指令，所以工具在毫秒级就返回"队友已在干活"。而 lead 随后因为没有工具调用
 * 就收尾（LoopComplete），队友的汇报却只会在 lead **下一轮迭代开头**才从邮箱被收走，
 * 那一轮在这一回合里根本不会到来。
 *
 * <p>有了这份台账，lead 在收尾前会先确认"还有没有人欠我一份汇报"：有就留在循环里，
 * 轮询各队邮箱（到达的消息先进缓冲区）直到每个派出去的队友都汇报过，或者等待预算耗尽。
 * 长驻语义没有被破坏——队友依然活在自己的线程里、依然能被后续消息唤醒，变的只是
 * lead 不再抢在汇报到达之前把这一轮结束掉。
 *
 * <p>为什么轮询放在这里而不是写进 Agent：收邮箱必须认识"团队"，而 agent 包刻意不依赖
 * teams 包（teams 依赖 agent）。所以本类只交给 Agent 一个 {@code isPending()} 谓词，
 * Agent 需要知道的仅仅是"还有人欠一条回复，再等一会儿"。
 */
public final class PendingTeammates {

    private static final Logger log = Logger.getLogger(PendingTeammates.class.getName());

    /** 等待队友汇报的总预算；超过就照常收尾，绝不让 lead 永久挂住。 */
    public static final long DEFAULT_WAIT_TIMEOUT_MS = 180_000L;

    /** 邮箱轮询步长。 */
    private static final long POLL_INTERVAL_MS = 500L;

    /** 队友名 → 已登记（value 恒为 TRUE，用 Map 是为了方便按名摘除）。 */
    private final Map<String, Boolean> pending = new ConcurrentHashMap<>();

    /**
     * 已经从邮箱取下来、但还没注入对话的汇报正文。
     *
     * <p>必须缓冲：取消息发生在等待阶段，而注入发生在下一轮迭代开头（Agent 的
     * notificationSource）。没有这块缓冲区，这些消息就永久丢了——drainUnread 是"取出即标记已读"。
     */
    private final List<String> buffered = new ArrayList<>();

    /** 登记一个"已派出、等它回话"的队友。 */
    public void register(String memberName) {
        if (memberName == null || memberName.isBlank()) return;
        pending.put(memberName, Boolean.TRUE);
    }

    /** 摘除一个队友的登记（它已汇报，或它已被停掉）。 */
    public void unregister(String memberName) {
        if (memberName == null || memberName.isBlank()) return;
        pending.remove(memberName);
    }

    /** 还有队友没回话吗？——Agent 用它决定"能不能收尾"。 */
    public boolean isPending() {
        return !pending.isEmpty();
    }

    /** 当前正在等的队友名（UI / 日志用）。 */
    public List<String> pendingNames() {
        return List.copyOf(pending.keySet());
    }

    /**
     * 阻塞等待：直到所有登记过的队友都汇报过，或者超过 {@code timeoutMs}。
     *
     * @return true = 都汇报了；false = 超时（调用方应照常收尾，别让 lead 卡死）
     */
    public boolean waitForReports(TeamManager teamMgr, long timeoutMs) {
        if (teamMgr == null) return false;
        long deadline = System.nanoTime() + Math.max(0L, timeoutMs) * 1_000_000L;
        while (isPending()) {
            // 只用"看"的，不用"取"的：这里一旦把队友消息取走（drainUnread 取出即标记已读），
            // 就必须由本方法负责把它送进对话；而等待发生在"本轮已经没有下一次迭代"的时刻，
            // 没有下一轮 notificationSource 来接力 —— 消息就这么被吞了。
            // 所以这里只观察 idle 来解除等待，真正的读取与注入仍然由下一轮 notificationSource 完成。
            observeIdleAndUnregister(teamMgr);
            if (!isPending()) {
                return true;
            }
            if (System.nanoTime() >= deadline) {
                log.warning("teammate report wait timed out; still pending: " + pendingNames());
                return false;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /**
     * 收一次各队的 lead 邮箱，收到的东西进缓冲区（由 notificationSource 调用，
     * 这样普通邮箱往来不必绕道等待循环就能到达模型）。；
     */
    public void pumpOnce(TeamManager teamMgr) {
        drainOnce(teamMgr);
    }

    /**
     * 取走并清空缓冲区（由 lead 的 notificationSource 调用）；没有内容时返回空列表。
     */
    public synchronized List<String> drainBuffered() {
        if (buffered.isEmpty()) return List.of();
        var out = List.copyOf(buffered);
        buffered.clear();
        return out;
    }

    // ── 邮箱轮询 ────────────────────────────────────────────────────────

    /**
     * 对每个团队的 lead 邮箱做一次"取出式"扫描。
     *
     * <p>复用 {@link TeammateRunner#drainLeadInbox}，让"谁欠汇报"的记账和"消息怎么渲染"只存在于
     * 一处。注意"取出即标记已读"，所以只要走过这里，就必须负责把消息送进对话（缓冲区 → 注入）。
     */
    private void drainOnce(TeamManager teamMgr) {
        var drained = TeammateRunner.drainLeadInbox(teamMgr);
        for (var item : drained) {
            var msg = item.message();
            if (TeammateRunner.isShutdownRequest(msg.text())) continue;
            synchronized (this) {
                buffered.add("from=" + msg.from() + ": " + msg.text());
            }
            // 只有 idle 才算"这一轮结束了"。
            //
            // 以前是"收到任何消息就注销"，这会踩到一个丢消息的坑：队友的最后一条正式汇报
            // （内容消息）被 drain 进来后立刻注销，等待循环随即看到"没人 pending"而结束这一轮；
            // 可这条汇报还躺在缓冲区里，要下一次迭代开头才会被注入——而这一轮已经没有下一次了。
            // 结果就是邮箱里那条消息被标记已读（drainUnread 取出即标记）却从未出现在模型上下文里，
            // lead 只能自己去翻文件，用户看到的是"汇报到了但它说没到"。
            if (TeammateRunner.isIdleNotification(msg.text())) {
                unregister(msg.from());
            }
        }
    }

    /**
     * 非破坏性地看一眼各队 lead 邮箱：谁发来了 idle 通知，就把它从 pending 里摘掉。
     *
     * <p>用 {@code readUnread} 而不是 {@code drainUnread}——只看不取，邮件保持未读，
     * 留给下一轮 notificationSource 正常读取并注入对话。等待循环因此不会"吃掉"任何消息。
     *
     * <p>为什么只认 idle：idle 才是"这一轮干完了"的信号。若拿任意消息当信号，队友最后那条
     * 正式汇报一到达就会解除等待，而那条汇报还躺在邮箱/缓冲区里来不及注入——就是它曾经被吞掉的原因。
     */
    private void observeIdleAndUnregister(TeamManager teamMgr) {
        if (teamMgr == null) return;
        for (String teamName : teamMgr.listTeams()) {
            var team = teamMgr.getTeam(teamName);
            if (team == null) continue;
            List<FileMailBox.MailMessage> unread;
            try {
                unread = team.getMailBox().readUnread(TeammateRunner.LEAD_NAME);
            } catch (RuntimeException e) {
                continue;
            }
            for (var msg : unread) {
                if (TeammateRunner.isIdleNotification(msg.text()) && pending.containsKey(msg.from())) {
                    unregister(msg.from());
                }
            }
        }
    }

    /**
     * 把缓冲区里尚未注入的消息立刻灌进对话（作为 system-reminder）。
     *
     * <p>调用点是"等待结束时"：正常路径由宿主 notificationSource 在下一轮开头注入，
     * 但"没有再下一轮"（本轮到点收尾）或通知源没被调用时，这些消息就会静默消失。
     * 走这一步就保证了「只要 drain 过，就一定会进模型上下文」。
     *
     * @return 实际注入的条数
     */
    public int flushInto(ConversationManager conv) {
        if (conv == null) return 0;
        var pendingMessages = drainBuffered();
        if (pendingMessages.isEmpty()) return 0;
        var sb = new StringBuilder("<team-notification>\n");
        for (String m : pendingMessages) sb.append(m).append('\n');
        sb.append("</team-notification>");
        conv.addSystemReminder(sb.toString());
        return pendingMessages.size();
    }
}
