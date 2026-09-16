
package com.agent.teams;



import com.agent.agent.AgentEvent;
import com.agent.history.ConversationManager;
import com.agent.permission.PermissionResponse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Main loop for in-process teammates.
 */
public final class TeammateRunner {

    public static final String LEAD_NAME = "lead";
    public static final String SHUTDOWN_PREFIX = "[shutdown]";

    public static final long IDLE_POLL_MS = 500;

    private static final Logger log = Logger.getLogger(TeammateRunner.class.getName());

    private TeammateRunner() {}

    /**
     * 队友的"权限裁决出口"。
     *
     * <p>为什么必须有它：队友的工具调用同样要过 PermissionChecker 的多层裁决，而写文件 /
     * 发消息会命中 ASK。lead 的 ASK 会被 StreamingExecutor 包成 PermissionRequestEvent
     * 交给 TUI 弹窗（用户按 y/a/n），但队友的事件流以前没有消费者——drainAgentEvents 把事件
     * 塞进一个没人读的本地队列，请求就这么消失了，队友只能干等到 StreamingExecutor 的
     * 5 分钟超时被默认 DENY。表现就是"队友去写文件了，然后什么都没发生"，用户永远拿不到
     * 它的汇报。
     *
     * <p>所以把出口参数化：in-process 队友接 TUI 的弹窗（问真实用户）；没有 UI 的场景
     * （后台 / 测试）传 null，按"拒绝"处理并留下可见的理由。
     */
    @FunctionalInterface
    public interface PermissionAsker {
        /**
         * @param toolName    工具名
         * @param description 人类可读的操作描述（如 "Write: /path/to/x"）
         * @return 用户裁决；null 视作 DENY
         */
        PermissionResponse ask(String toolName, String description);
    }

    /** 结构化问卷（AskUserQuestion 工具）的出口，语义同 {@link PermissionAsker}。 */
    @FunctionalInterface
    public interface QuestionAsker {
        Map<String, String> ask(List<AgentEvent.AskUserRequestEvent.Question> questions);
    }

    /**
     * 把Agent（天生只能干一轮）包装成一个常驻的员工：干完一轮 → 报告"我空了" → 等新指令 → 用同一份记忆接着干 → 直到收到下班指令。
     */
    public static void runInProcessTeammate(
            TeamManager.Team team,
            TeamManager.Member member,
            String initialPrompt,
            String addendum,
            PermissionAsker permissionAsker,
            QuestionAsker questionAsker
    ) {
        // Create progress tracker and attach to member
        var progress = new TeammateProgress(member.getName(), team.getName(), "");
        member.progress = progress;

        if (addendum != null && !addendum.isEmpty()) {
            member.conv.addSystemReminder(addendum);
        }

        // First turn: use initial prompt
        member.conv.addUserMessage(initialPrompt);

        // Run agent
        var agentQueue = member.agent.run(member.conv);
        drainAgentEvents(agentQueue, progress, permissionAsker, questionAsker);

        // Send idle notification
        notifyLead(team, member.getName(), "completed initial task");

        // Subsequent turns: wait for mailbox messages,lead发[shutdown] 消息或team.stopMember()退出
        while (!Thread.currentThread().isInterrupted()) {
            var result = waitForNextPromptOrShutdown(team, member.getName());
            if (result.shutdown || result.prompt == null) break;

            member.conv.addUserMessage(result.prompt);
            agentQueue = member.agent.run(member.conv);
            drainAgentEvents(agentQueue, progress, permissionAsker, questionAsker);

            notifyLead(team, member.getName(), "completed follow-up");
        }

        member.active = false;
        progress.setStatus("completed");

        // 队友退出时持久化对话记录，用于调试
        try {
            Transcript.saveTranscript(team.getName(), member.getName(), member.conv);
        } catch (Exception ignored) {
            // best-effort：持久化失败不影响正常退出
        }
    }
    /**
     * 构建队员的系统提示词
     */
    public static String buildTeammateAddendum(String teamName, String memberName, List<String> otherMembers) {
        var sb = new StringBuilder();
        sb.append("You are a member of team \"").append(teamName).append("\". ");
        sb.append("Your name is \"").append(memberName).append("\".\n\n");
        if (otherMembers != null && !otherMembers.isEmpty()) {
            sb.append("Other team members: ").append(String.join(", ", otherMembers)).append("\n\n");
        }
        sb.append("You can communicate with teammates using the SendMessage tool.\n");
        sb.append("Messages from teammates arrive as system reminders at the start of each turn.\n");
        sb.append("When you finish your current task, simply stop calling tools — ");
        sb.append("an idle notification will be sent to the lead automatically.");
        return sb.toString();
    }
    /**
     * lead周期性检查邮箱,发现队员是否完成任务
     */
    public static List<String> drainLeadMailbox(TeamManager teamMgr) {
        var raw = drainLeadInbox(teamMgr);
        if (raw.isEmpty()) return List.of();
        // 按团队分组渲染成给模型看的 team-notification 块
        var order = new ArrayList<String>();
        var byTeam = new LinkedHashMap<String, List<FileMailBox.MailMessage>>();
        for (var m : raw) {
            if (!byTeam.containsKey(m.teamName())) order.add(m.teamName());
            byTeam.computeIfAbsent(m.teamName(), k -> new ArrayList<>()).add(m.message());
        }
        var result = new ArrayList<String>(order.size());
        for (String teamName : order) {
            var sb = new StringBuilder();
            sb.append("<team-notification team=\"").append(teamName).append("\">\n");
            for (var msg : byTeam.get(teamName)) {
                sb.append("from=").append(msg.from()).append(": ").append(msg.text()).append("\n");
            }
            sb.append("</team-notification>");
            result.add(sb.toString());
        }
        return result;
    }

    /** 一条来自 lead 邮箱的消息，附带它所属的团队名（渲染 team-notification 时要用）。 */
    public record LeadInboxMessage(String teamName, FileMailBox.MailMessage message) {}

    /**
     * 把每个团队 lead 邮箱里的未读消息取出来（取出即标记已读），按团队顺序打平。
     *
     * <p>与 {@link #drainLeadMailbox} 的区别只是"不渲染"：等待队友汇报的轮询
     * （{@link com.agent.subAgent.PendingTeammates}）需要按成员名记账，渲染成
     * team-notification 文本反而会把发件人信息埋进字符串里。
     */
    public static List<LeadInboxMessage> drainLeadInbox(TeamManager teamMgr) {
        if (teamMgr == null) return List.of();
        var out = new ArrayList<LeadInboxMessage>();
        for (String teamName : teamMgr.listTeams()) {
            var team = teamMgr.getTeam(teamName);
            if (team == null) continue;
            List<FileMailBox.MailMessage> messages;
            try {
                // 取出 lead 所有未读消息
                messages = team.getMailBox().drainUnread(LEAD_NAME);
            } catch (FileMailBox.MailboxBusyException e) {
                // 邮箱暂时不可用：跳过这个团队，下一次再收
                continue;
            }
            for (var m : messages) {
                out.add(new LeadInboxMessage(teamName, m));
            }
        }
        return out;
    }


    public static boolean isShutdownRequest(String message) {
        return message != null && message.strip().startsWith(SHUTDOWN_PREFIX);
    }

    /**
     * 这条消息是不是 idle 通知（队友"这一轮干完了、又闲下来了"）。
     *
     * <p>为什么需要区分它和普通汇报：汇报（内容）只是"我干了什么"，idle 才是"这一轮到此为止"。
     * lead 的等待逻辑必须拿 idle 当结束信号——如果拿任意消息当信号，队友最后一条正式汇报
     * 一到达就会让等待提前结束，那条汇报反而来不及注入对话。
     */
    public static boolean isIdleNotification(String message) {
        return message != null && message.strip().startsWith("[idle]");
    }

    public static String createIdleNotification(String memberName, String reason) {
        return "[idle] %s: %s (at %s)".formatted(memberName, reason,
                Instant.now().toString());
    }

    // ── Internal helpers ──────────────────────────────────────────────

    /** 向 lead 汇报 idle：邮箱暂时不可用不能拖垮队友，记一条日志即可 */
    private static void notifyLead(TeamManager.Team team, String memberName, String reason) {
        try {
            team.sendMessage(memberName, LEAD_NAME, createIdleNotification(memberName, reason));
        } catch (FileMailBox.MailboxBusyException e) {
            log.warning("cannot notify lead: " + e.getMessage());
        }
    }

    private record WaitResult(String prompt, boolean shutdown) {}

    private static WaitResult waitForNextPromptOrShutdown(TeamManager.Team team, String memberName) {
        //轮询等待新任务
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(IDLE_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new WaitResult(null, true);
            }

            List<FileMailBox.MailMessage> messages;
            try {
                messages = team.getMailBox().drainUnread(memberName);
            } catch (FileMailBox.MailboxBusyException e) {
                // 抢不到邮箱锁：下一轮轮询再试，不打断队友
                continue;
            }
            if (messages.isEmpty()) continue;

            for (var msg : messages) {
                if (isShutdownRequest(msg.text())) {
                    return new WaitResult(null, true);
                }
            }

            // Format as prompt
            var sb = new StringBuilder("You have new messages from your team:\n\n");
            for (var msg : messages) {
                sb.append("From ").append(msg.from()).append(": ").append(msg.text()).append("\n\n");
            }
            return new WaitResult(sb.toString(), false);
        }
        return new WaitResult(null, true);
    }

    /**
     * 消费Agent产生的输出
     * @param source 源agent产生的输出
     * @param progress 供回调的进度条
     * @param permissionAsker 权限询问出口（可为 null：无 UI 场景按拒绝处理）
     * @param questionAsker 结构化问卷出口（可为 null：无 UI 场景按"拒绝回答"处理）
     */
    private static void drainAgentEvents(BlockingQueue<AgentEvent> source,
                                         TeammateProgress progress,
                                         PermissionAsker permissionAsker,
                                         QuestionAsker questionAsker) {
        while (true) {
            AgentEvent event;
            try {
                event = source.poll(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                progress.setStatus("failed");
                return;
            }
            if (event == null) return;

            // Record progress from agent events
            if (event instanceof AgentEvent.ToolUseEvent tue) {
                progress.recordToolUse(tue.toolName(), tue.args());
            } else if (event instanceof AgentEvent.UsageEvent ue) {
                progress.recordTokens(ue.inputTokens(), ue.outputTokens());
            } else if (event instanceof AgentEvent.ErrorEvent) {
                progress.setStatus("failed");
                return;
            } else if (event instanceof AgentEvent.PermissionRequestEvent pr) {
                // 关键分支：队友的 ASK 必须有人答，否则工具线程会挂到 5 分钟超时。
                PermissionResponse response = PermissionResponse.DENY;
                if (permissionAsker != null) {
                    try {
                        PermissionResponse asked = permissionAsker.ask(pr.toolName(), pr.description());
                        if (asked != null) response = asked;
                    } catch (Exception e) {
                        log.warning("teammate permission ask failed: " + e.getMessage());
                    }
                } else {
                    log.warning("teammate '" + progress.getName() + "' requested permission for "
                            + pr.toolName() + " but no asker is wired; denying: " + pr.description());
                }
                pr.future().complete(response);
            } else if (event instanceof AgentEvent.AskUserRequestEvent aq) {
                Map<String, String> answers = Map.of();
                if (questionAsker != null) {
                    try {
                        Map<String, String> asked = questionAsker.ask(aq.questions());
                        if (asked != null) answers = asked;
                    } catch (Exception e) {
                        log.warning("teammate question ask failed: " + e.getMessage());
                    }
                }
                aq.future().complete(answers);
            } else if (event instanceof AgentEvent.LoopComplete) {
                return;
            }
        }
    }
}
