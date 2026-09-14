
package com.agent.teams;



import com.agent.agent.AgentEvent;
import com.agent.history.ConversationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
     * 把Agent（天生只能干一轮）包装成一个常驻的员工：干完一轮 → 报告"我空了" → 等新指令 → 用同一份记忆接着干 → 直到收到下班指令。
     */
    public static void runInProcessTeammate(
            TeamManager.Team team,
            TeamManager.Member member,
            String initialPrompt,
            String addendum
    ) {
        BlockingQueue<AgentEvent> eventOut = new LinkedBlockingQueue<>(32);

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
        drainAgentEvents(agentQueue, eventOut, progress);

        // Send idle notification
        notifyLead(team, member.getName(), "completed initial task");

        // Subsequent turns: wait for mailbox messages,lead发[shutdown] 消息或team.stopMember()退出
        while (!Thread.currentThread().isInterrupted()) {
            var result = waitForNextPromptOrShutdown(team, member.getName());
            if (result.shutdown || result.prompt == null) break;

            member.conv.addUserMessage(result.prompt);
            agentQueue = member.agent.run(member.conv);
            drainAgentEvents(agentQueue, eventOut, progress);

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
        if (teamMgr == null) return List.of();
        var result = new ArrayList<String>();
        for (String teamName : teamMgr.listTeams()) {
            var team = teamMgr.getTeam(teamName);
            if (team == null) continue;

            List<FileMailBox.MailMessage> messages;
            try {
                // 取出lead所有消息
                messages = team.getMailBox().drainUnread(LEAD_NAME);
            } catch (FileMailBox.MailboxBusyException e) {
                // 邮箱暂时不可用：跳过这个团队，下一轮再收
                continue;
            }
            if (messages.isEmpty()) continue;

            var sb = new StringBuilder();
            sb.append("<team-notification team=\"").append(teamName).append("\">\n");
            for (var msg : messages) {
                sb.append("from=").append(msg.from()).append(": ").append(msg.text()).append("\n");
            }
            sb.append("</team-notification>");
            result.add(sb.toString());
        }
        return result;
    }


    public static boolean isShutdownRequest(String message) {
        return message != null && message.strip().startsWith(SHUTDOWN_PREFIX);
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
     * @param sink 中转站
     * @param progress 供回调的进度条
     */
    private static void drainAgentEvents(BlockingQueue<AgentEvent> source, BlockingQueue<AgentEvent> sink,
                                         TeammateProgress progress) {
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
            sink.offer(event);

            // Record progress from agent events
            if (event instanceof AgentEvent.ToolUseEvent tue) {
                progress.recordToolUse(tue.toolName(), tue.args());
            } else if (event instanceof AgentEvent.UsageEvent ue) {
                progress.recordTokens(ue.inputTokens(), ue.outputTokens());
            } else if (event instanceof AgentEvent.ErrorEvent) {
                progress.setStatus("failed");
                return;
            } else if (event instanceof AgentEvent.LoopComplete) {
                return;
            }
        }
    }
}
