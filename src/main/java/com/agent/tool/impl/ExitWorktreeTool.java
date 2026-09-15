package com.agent.tool.impl;

import com.agent.tool.PathContext;
import com.agent.tool.Tool;
import com.agent.tool.ToolCategory;
import com.agent.tool.result.ToolResult;
import com.agent.worktree.WorktreeChanges;
import com.agent.worktree.WorktreeManager;
import com.agent.worktree.WorktreeSession;
import com.agent.worktree.WorktreeSessionStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 退出隔离工作树：把 {@link PathContext} 的根切回原目录，并按 action 决定保留还是删除。
 *
 * action=keep：目录/分支/改动全部保留（默认）。
 * action=remove：删除工作目录——若检测到未提交改动或新 commit，必须显式传 discard_changes=true，
 *                避免"以为只是离开、结果把活干没了"。
 */
public class ExitWorktreeTool implements Tool {

    private final WorktreeManager manager;
    private final Consumer<String> onRootChanged;

    public ExitWorktreeTool(WorktreeManager manager, Consumer<String> onRootChanged) {
        this.manager = manager;
        this.onRootChanged = onRootChanged;
    }

    @Override public String name() { return "ExitWorktree"; }
    @Override public ToolCategory category() { return ToolCategory.COMMAND; }

    @Override
    public String description() {
        return "Leave the current git worktree and switch relative paths back to the original working directory.\n"
                + "action=keep (default): keep the worktree directory, its branch and all changes.\n"
                + "action=remove: delete the worktree directory; if it has uncommitted changes or new commits you "
                + "must also pass discard_changes=true, otherwise the call is refused.";
    }

    @Override
    public Map<String, Object> schema() {
        var props = new LinkedHashMap<String, Object>();
        props.put("action", Map.of("type", "string", "enum", List.of("keep", "remove"),
                "description", "keep = preserve worktree (default), remove = delete it"));
        props.put("discard_changes", Map.of("type", "boolean",
                "description", "Required with action=remove when the worktree still has changes"));
        return Map.of("name", name(), "description", description(),
                "input_schema", Map.of("type", "object", "properties", props, "required", List.of()));
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        WorktreeSession session = WorktreeSessionStore.getCurrentSession();
        if (session == null) {
            return ToolResult.success("Not in a worktree session — nothing to do.");
        }

        String action = args.get("action") instanceof String s && !s.isBlank() ? s.trim() : "keep";
        //验证是否有对变化的处理逻辑
        if (!action.equals("keep") && !action.equals("remove")) {
            return ToolResult.error("Error: action must be 'keep' or 'remove'");
        }
        boolean discard = Boolean.TRUE.equals(args.get("discard_changes"));
        //remove模式下需要验证是否放弃更改
        if (action.equals("remove")) {
            var summary = WorktreeChanges.countChanges(session.worktreePath(), session.originalHeadCommit());
            if (summary == null) {
                if (!discard) {
                    return ToolResult.error("Error: cannot determine whether the worktree has changes "
                            + "(git failed). Pass discard_changes=true to remove it anyway, or use action=keep.");
                }
            } else if ((summary.changedFiles() > 0 || summary.commits() > 0) && !discard) {
                return ToolResult.error("Error: worktree still has %d changed file(s) and %d new commit(s). "
                        + "Re-run with action=remove AND discard_changes=true to delete them, or use action=keep."
                        .formatted(summary.changedFiles(), summary.commits()));
            }
        }

        // 先清会话（内存 + 磁盘）再切根：即使后面删除失败，也不会卡在"一棵不存在的树"里
        WorktreeSessionStore.restoreSession(null);
        try {
            WorktreeSessionStore.save(session.originalCwd(), null);
        } catch (Exception ignored) {}
        PathContext.setRoot(session.originalCwd());
        //一个consumer,改变Agent侧的workDir
        if (onRootChanged != null) onRootChanged.accept(session.originalCwd());
        //KEEP分支,不动文件系统
        if (!action.equals("remove")) {
            return ToolResult.success("Exited worktree. Your work is preserved at " + session.worktreePath()
                    + " (branch " + session.worktreeBranch() + "). Relative paths are back in "
                    + session.originalCwd() + ".");
        }
        //REMOVE分支,正常清除分支,有未提交改动的REMOVE已经返回
        try {
            manager.remove(session.worktreeName());
        } catch (Exception e) {
            // 进程重启后 WorktreeManager 的内存台账是空的 → 直接让 git 收拾
            String removed = EnterWorktreeTool.git(session.originalCwd(), "worktree", "remove",
                    session.worktreePath(), "--force");
            if (removed == null) {
                return ToolResult.error("Exited worktree but could not remove " + session.worktreePath()
                        + " (" + e.getMessage() + "). Remove it manually with git worktree remove.");
            }
            EnterWorktreeTool.git(session.originalCwd(), "branch", "-D", session.worktreeBranch());
        }
        return ToolResult.success("Exited and removed worktree at " + session.worktreePath()
                + ". Session is now back in " + session.originalCwd() + ".");
    }
}
