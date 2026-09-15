package com.agent.teams;

import com.agent.tool.Tool;
import com.agent.tool.ToolCategory;
import com.agent.tool.result.ToolResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 共享任务板工具：TaskCreate / TaskList / TaskGet / TaskUpdate。
 *
 * 底层是 {@link SharedTaskStore}（每个团队一份 tasks.json）。它的价值是让"分工"这件事
 * 对模型可见：lead 建任务 → 队友 TaskList 看到有哪些活 → TaskUpdate 认领并推进状态，
 * 顺带用 blocks / blockedBy 表达依赖关系。
 */
public final class TaskTools {

    private TaskTools() {}

    /**
     * 四个工具共用的"我在哪个队"解析：显式传team_name>我所属的队>只有一个队时就用它。
     * 之所以要兜底"只有一个队"：lead 不在任何团队的 members 里，拿不到"我所属的队"。
     */
    private static TeamManager.Team resolveTeam(TeamManager teamMgr, String agentName, Map<String, Object> args) {
        Object explicit = args.get("team_name");
        if (explicit instanceof String s && !s.isBlank()) {
            return teamMgr.getTeam(s);
        }
        for (String teamName : teamMgr.listTeams()) {
            TeamManager.Team t = teamMgr.getTeam(teamName);
            if (t != null && t.hasMember(agentName)) return t;
        }
        List<String> all = teamMgr.listTeams();
        return all.size() == 1 ? teamMgr.getTeam(all.get(0)) : null;
    }

    private static Map<String, Object> teamNameProp() {
        return Map.of("type", "string",
                "description", "Team name. Optional: defaults to the team you belong to");
    }

    //把任务渲染成给模型看的紧凑文本
    private static String format(SharedTaskStore.SharedTask t) {
        var sb = new StringBuilder();
        sb.append("#").append(t.id()).append(" [").append(t.status()).append("] ").append(t.title());
        if (t.assignee() != null && !t.assignee().isEmpty()) {
            sb.append("  (assignee: ").append(t.assignee()).append(")");
        }
        if (t.description() != null && !t.description().isEmpty()) {
            sb.append("\n    ").append(t.description());
        }
        if (t.blockedBy() != null && !t.blockedBy().isEmpty()) {
            sb.append("\n    blockedBy: ").append(t.blockedBy());
        }
        if (t.blocks() != null && !t.blocks().isEmpty()) {
            sb.append("\n    blocks: ").append(t.blocks());
        }
        sb.append("\n    createdBy: ").append(t.createdBy());
        return sb.toString();
    }

    /** 解析形如 [1,2] 或 ["1","2"] 的 id 列表（模型给的 JSON 数组类型不保证）。 */
    private static List<Integer> intList(Object v) {
        var out = new ArrayList<Integer>();
        if (v instanceof List<?> raw) {
            for (Object o : raw) {
                if (o instanceof Number n) {
                    out.add(n.intValue());
                } else if (o instanceof String s) {
                    try { out.add(Integer.parseInt(s.trim())); } catch (NumberFormatException ignored) {}
                }
            }
        }
        return out;
    }

    private static Integer intArg(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private static String strArg(Map<String, Object> args, String key) {
        return args.get(key) instanceof String s ? s : null;
    }

    // ── TaskCreate ─────────────────────────────────────────────────────

    public static class TaskCreateTool implements Tool {
        private final TeamManager teamMgr;
        private final String agentName;

        public TaskCreateTool(TeamManager teamMgr, String agentName) {
            this.teamMgr = teamMgr;
            this.agentName = agentName;
        }

        @Override public String name() { return "TaskCreate"; }
        @Override public ToolCategory category() { return ToolCategory.COMMAND; }

        @Override
        public String description() {
            return "Create a task on the team's shared task board. Use it to break work into steps that "
                    + "teammates can see, claim (TaskUpdate assignee) and complete (TaskUpdate status). "
                    + "Returns the new task id, which TaskGet / TaskUpdate take as input.";
        }

        @Override
        public Map<String, Object> schema() {
            var props = new LinkedHashMap<String, Object>();
            props.put("title", Map.of("type", "string", "description", "Short task title"));
            props.put("description", Map.of("type", "string", "description", "What must be done (optional)"));
            props.put("team_name", teamNameProp());
            return Map.of("name", name(), "description", description(),
                    "input_schema", Map.of("type", "object", "properties", props, "required", List.of("title")));
        }

        @Override
        public ToolResult execute(Map<String, Object> args) {
            String title = strArg(args, "title");
            if (title == null || title.isBlank()) {
                return ToolResult.error("Error: 'title' is required");
            }
            TeamManager.Team team = resolveTeam(teamMgr, agentName, args);
            if (team == null) {
                return ToolResult.error("Error: no team found. Create one with TeamCreate first.");
            }
            String desc = strArg(args, "description");
            var task = team.getTasks().create(title, desc == null ? "" : desc, agentName);
            return ToolResult.success("Created task:\n" + format(task));
        }
    }

    // ── TaskList ───────────────────────────────────────────────────────

    public static class TaskListTool implements Tool {
        private final TeamManager teamMgr;
        private final String agentName;

        public TaskListTool(TeamManager teamMgr, String agentName) {
            this.teamMgr = teamMgr;
            this.agentName = agentName;
        }

        @Override public String name() { return "TaskList"; }
        @Override public ToolCategory category() { return ToolCategory.READ; }

        @Override
        public String description() {
            return "List tasks on the team's shared task board, optionally filtered by status or assignee. "
                    + "Use it to see what work exists and what is still unclaimed.";
        }

        @Override
        public Map<String, Object> schema() {
            var props = new LinkedHashMap<String, Object>();
            props.put("status", Map.of("type", "string",
                    "description", "Filter by status, e.g. todo / in_progress / done (optional)"));
            props.put("assignee", Map.of("type", "string", "description", "Filter by assignee name (optional)"));
            props.put("team_name", teamNameProp());
            return Map.of("name", name(), "description", description(),
                    "input_schema", Map.of("type", "object", "properties", props, "required", List.of()));
        }

        @Override
        public ToolResult execute(Map<String, Object> args) {
            TeamManager.Team team = resolveTeam(teamMgr, agentName, args);
            if (team == null) {
                return ToolResult.error("Error: no team found. Create one with TeamCreate first.");
            }
            var tasks = team.getTasks().listTasks(strArg(args, "status"), strArg(args, "assignee"));
            if (tasks.isEmpty()) return ToolResult.success("(no tasks)");
            var sb = new StringBuilder();
            for (var t : tasks) sb.append(format(t)).append("\n\n");
            return ToolResult.success(sb.toString().stripTrailing());
        }
    }

    // ── TaskGet ────────────────────────────────────────────────────────

    public static class TaskGetTool implements Tool {
        private final TeamManager teamMgr;
        private final String agentName;

        public TaskGetTool(TeamManager teamMgr, String agentName) {
            this.teamMgr = teamMgr;
            this.agentName = agentName;
        }

        @Override public String name() { return "TaskGet"; }
        @Override public ToolCategory category() { return ToolCategory.READ; }

        @Override
        public String description() {
            return "Get one task from the shared task board by its id, including description and dependencies.";
        }

        @Override
        public Map<String, Object> schema() {
            var props = new LinkedHashMap<String, Object>();
            props.put("id", Map.of("type", "integer", "description", "Task id returned by TaskCreate / TaskList"));
            props.put("team_name", teamNameProp());
            return Map.of("name", name(), "description", description(),
                    "input_schema", Map.of("type", "object", "properties", props, "required", List.of("id")));
        }

        @Override
        public ToolResult execute(Map<String, Object> args) {
            Integer id = intArg(args, "id");
            if (id == null) return ToolResult.error("Error: 'id' is required");
            TeamManager.Team team = resolveTeam(teamMgr, agentName, args);
            if (team == null) return ToolResult.error("Error: no team found. Create one with TeamCreate first.");
            var task = team.getTasks().get(id);
            if (task == null) return ToolResult.error("Error: task " + id + " not found");
            return ToolResult.success(format(task));
        }
    }

    // ── TaskUpdate ─────────────────────────────────────────────────────

    public static class TaskUpdateTool implements Tool {
        private final TeamManager teamMgr;
        private final String agentName;

        public TaskUpdateTool(TeamManager teamMgr, String agentName) {
            this.teamMgr = teamMgr;
            this.agentName = agentName;
        }

        @Override public String name() { return "TaskUpdate"; }
        @Override public ToolCategory category() { return ToolCategory.COMMAND; }

        @Override
        public String description() {
            return "Update a task on the shared task board. Claim work by setting assignee to your own name, "
                    + "advance it with status (todo / in_progress / done), and record dependencies with "
                    + "add_blocks (tasks this one blocks) / add_blocked_by (tasks blocking this one).\n"
                    + "Semantics: status only changes when a non-empty value is given; assignee replaces the "
                    + "current one (pass \"\" to unassign); blocks / blocked_by are appended, not replaced.";
        }

        @Override
        public Map<String, Object> schema() {
            var props = new LinkedHashMap<String, Object>();
            props.put("id", Map.of("type", "integer", "description", "Task id to update"));
            props.put("status", Map.of("type", "string", "description", "New status, e.g. in_progress / done"));
            props.put("assignee", Map.of("type", "string",
                    "description", "New assignee name; pass \"\" to unassign"));
            props.put("add_blocks", Map.of("type", "array", "items", Map.of("type", "integer"),
                    "description", "Task ids that this task blocks (appended)"));
            props.put("add_blocked_by", Map.of("type", "array", "items", Map.of("type", "integer"),
                    "description", "Task ids that block this task (appended)"));
            props.put("team_name", teamNameProp());
            return Map.of("name", name(), "description", description(),
                    "input_schema", Map.of("type", "object", "properties", props, "required", List.of("id")));
        }

        @Override
        public ToolResult execute(Map<String, Object> args) {
            Integer id = intArg(args, "id");
            if (id == null) return ToolResult.error("Error: 'id' is required");
            TeamManager.Team team = resolveTeam(teamMgr, agentName, args);
            if (team == null) return ToolResult.error("Error: no team found. Create one with TeamCreate first.");

            var updated = team.getTasks().update(id,
                    strArg(args, "status"),
                    strArg(args, "assignee"),
                    intList(args.get("add_blocks")),
                    intList(args.get("add_blocked_by")));
            if (updated == null) return ToolResult.error("Error: task " + id + " not found");
            return ToolResult.success("Updated task:\n" + format(updated));
        }
    }
}
