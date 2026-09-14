
package com.agent.teams;


import com.agent.tool.Tool;
import com.agent.tool.ToolCategory;
import com.agent.tool.result.ToolResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 暴露给模型的工具:SendMessageTool、TeamCreateTool、TeamDeleteTool,派队员执行在 @AgentTool中
 */
public final class TeamTools {

    private TeamTools() {}

    // ── SendMessage ────────────────────────────────────────────────────

    public static class SendMessageTool implements Tool {
        //senderName是构造时固定的,安全设计：如果让模型在参数里传 from，模型就能伪造发件人（比如冒充 lead 给所有队友下指令）
        private final TeamManager teamMgr;
        private final String senderName;

        public SendMessageTool(TeamManager teamMgr, String senderName) {
            this.teamMgr = teamMgr;
            this.senderName = senderName;
        }

        @Override public String name() { return "SendMessage"; }
        @Override public ToolCategory category() { return ToolCategory.COMMAND; }

        @Override
        public String description() {
            return "Send a message to another named agent in the team. The recipient will see it on their next turn. "
                    + "Use SendMessage to communicate with teammates by name. Messages arrive as system reminders.";
        }

        @Override
        public Map<String, Object> schema() {
            var props = new LinkedHashMap<String, Object>();
            props.put("to", Map.of("type", "string", "description", "Name of the recipient agent"));
            props.put("content", Map.of("type", "string", "description", "Message content to send"));

            return Map.of(
                    "name", name(),
                    "description", description(),
                    "input_schema", Map.of(
                            "type", "object",
                            "properties", props,
                            "required", List.of("to", "content")
                    )
            );
        }

        @Override
        public ToolResult execute(Map<String, Object> args) {
            String to = (String) args.get("to");
            String content = (String) args.get("content");
            if (to == null || to.isEmpty() || content == null || content.isEmpty()) {
                return ToolResult.error("Error: 'to' and 'content' are required");
            }

            // 发给“发件人”的lead，队友汇报工作。队友不需要知道队名，所以用"我所在的团队"来路由
            if ("lead".equals(to)) {
                for (String teamName : teamMgr.listTeams()) {
                    TeamManager.Team team = teamMgr.getTeam(teamName);
                    if (team != null && team.hasMember(senderName)) {
                        return deliver(team, senderName, "lead", content);
                    }
                }
                return ToolResult.error("Error: cannot find team for sender '" + senderName + "'");
            }

            for (String teamName : teamMgr.listTeams()) {
                TeamManager.Team team = teamMgr.getTeam(teamName);
                if (team == null) continue;
                if (team.hasMember(to)) {
                    return deliver(team, senderName, to, content);
                }
                // tmux/iTerm 模式：每个进程的花名册里只有自己，收件人根本不在本地表里。但消息仍然应该写进磁盘邮箱——因为收件人在另一个进程里
                if (team.hasMember(senderName)) {
                    return deliver(team, senderName, to, content);
                }
            }

            return ToolResult.error("Error: recipient '" + to + "' not found in any team");
        }

        /**
         * 写邮箱可能因为抢不到锁/写盘失败而抛异常，必须转成对模型可见的工具错误，
         * 不能让调用方以为"发送成功"。
         */
        private ToolResult deliver(TeamManager.Team team, String from, String to, String content) {
            try {
                team.sendMessage(from, to, content);
                return ToolResult.success("Message sent to " + to + ".");
            } catch (FileMailBox.MailboxBusyException e) {
                return ToolResult.error("Error: message to '" + to + "' was NOT delivered: " + e.getMessage());
            }
        }
    }

    // ── TeamCreate ─────────────────────────────────────────────────────

    public static class TeamCreateTool implements Tool {

        private final TeamManager teamMgr;

        public TeamCreateTool(TeamManager teamMgr) {
            this.teamMgr = teamMgr;
        }

        @Override public String name() { return "TeamCreate"; }
        @Override public ToolCategory category() { return ToolCategory.COMMAND; }

        @Override
        public String description() {
            return "Create a new team for coordinating multiple agents.\n\n"
                    + "## When to Use\n\n"
                    + "Use this tool proactively whenever:\n"
                    + "- The user explicitly asks to use a team, swarm, or group of agents\n"
                    + "- The user mentions wanting agents to work together, coordinate, or collaborate\n"
                    + "- A task requires sequential or parallel collaboration between multiple agents\n\n"
                    + "When in doubt about whether a task warrants a team, prefer spawning a team.\n\n"
                    + "## Team Workflow\n\n"
                    + "1. **Create a team** with TeamCreate\n"
                    + "2. **Spawn teammates** using the Agent tool with team_name and name parameters — "
                    + "this is REQUIRED to create long-running team members\n"
                    + "3. Teammates work independently and communicate via **SendMessage**\n"
                    + "4. When a teammate finishes, it sends its result to \"lead\" via SendMessage, then goes idle\n"
                    + "5. The lead collects and synthesizes all teammate results\n\n"
                    + "## CRITICAL: Spawning Teammates\n\n"
                    + "To add a member to a team, you MUST pass both team_name and name to the Agent tool:\n"
                    + "```\nAgent({\n"
                    + "  \"team_name\": \"<team name from step 1>\",\n"
                    + "  \"name\": \"<member name, e.g. reviewer>\",\n"
                    + "  \"prompt\": \"...\",\n"
                    + "  \"description\": \"...\"\n"
                    + "})\n```\n"
                    + "Without team_name, the agent runs as a one-shot sub-agent that blocks and returns inline — "
                    + "it will NOT be a team member.\n\n"
                    + "## Teammate Idle State\n\n"
                    + "Teammates go idle after every turn — this is completely normal. "
                    + "Sending a message to an idle teammate wakes them up.\n\n"
                    + "## Communication\n\n"
                    + "- Use SendMessage to talk to teammates by name\n"
                    + "- Messages from teammates arrive as system reminders at the start of each turn\n"
                    + "- Messages are delivered automatically — you do NOT need to manually check your inbox";
        }

        @Override
        public Map<String, Object> schema() {
            var props = new LinkedHashMap<String, Object>();
            props.put("team_name", Map.of("type", "string", "description", "Name for the team"));
            props.put("description", Map.of("type", "string", "description", "What this team will work on"));

            return Map.of(
                    "name", name(),
                    "description", description(),
                    "input_schema", Map.of(
                            "type", "object",
                            "properties", props,
                            "required", List.of("team_name")
                    )
            );
        }

        @Override
        public ToolResult execute(Map<String, Object> args) {
            String name = (String) args.get("team_name");
            if (name == null || name.isEmpty()) {
                return ToolResult.error("Error: team_name is required");
            }

            String baseName = name;
            //自动改名
            for (int i = 2; teamMgr.getTeam(name) != null; i++) {
                name = baseName + "-" + i;
            }

            TeamManager.TeamMode mode = TeamManager.detectBackend();
            TeamManager.Team team = teamMgr.createTeam(name, mode);

            String desc = args.get("description") instanceof String s ? s : "";
            return ToolResult.success(
                    "Team \"%s\" created (mode: %s). Use Agent tool with team_name=\"%s\" to add teammates.\nDescription: %s"
                            .formatted(team.getName(), team.getMode(), team.getName(), desc));
        }
    }

    // ── TeamDelete ─────────────────────────────────────────────────────

    public static class TeamDeleteTool implements Tool {
        private final TeamManager teamMgr;

        public TeamDeleteTool(TeamManager teamMgr) {
            this.teamMgr = teamMgr;
        }

        @Override public String name() { return "TeamDelete"; }
        @Override public ToolCategory category() { return ToolCategory.COMMAND; }

        @Override
        public String description() {
            return "Delete a team, stopping all its members.";
        }

        @Override
        public Map<String, Object> schema() {
            var props = new LinkedHashMap<String, Object>();
            props.put("team_name", Map.of("type", "string", "description", "Name of the team to delete"));

            return Map.of(
                    "name", name(),
                    "description", description(),
                    "input_schema", Map.of(
                            "type", "object",
                            "properties", props,
                            "required", List.of("team_name")
                    )
            );
        }

        @Override
        public ToolResult execute(Map<String, Object> args) {
            String name = (String) args.get("team_name");
            if (name == null || name.isEmpty()) {
                return ToolResult.error("Error: team_name is required");
            }

            TeamManager.Team team = teamMgr.getTeam(name);
            if (team == null) {
                return ToolResult.error("Error: team '%s' not found".formatted(name));
            }

            List<String> memberNames = team.memberNames();
            teamMgr.deleteTeam(name);
            return ToolResult.success(
                    "Team \"%s\" deleted. Stopped %d member(s): %s"
                            .formatted(name, memberNames.size(), String.join(", ", memberNames)));
        }
    }
}
