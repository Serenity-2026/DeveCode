
package com.agent.skill;



import java.util.ArrayList;
import java.util.List;

import com.agent.llm.Message;
import com.agent.skill.SkillCatalog;
/**
 * Executes skills in inline or fork mode.
 */
public final class SkillExecutor {

    private static final int FORK_RECENT_COUNT = 5;

    private SkillExecutor() {}

    /**
     * 以inline模式激活 skill——正文注入当前对话，返回渲染后的 prompt。
     */
    public static String executeInline(SkillCatalog.Skill skill, String args, SkillHost host) {
        String body = substituteArguments(skill.promptBody(), args);
        host.activateSkill(skill.meta().name(), body);
        host.recordSkillInvocation(skill.meta().name(), body);
        return body;
    }

    /**
     * Executes the skill in an isolated sub-agent and returns the
     * final assistant text.
     */
    public static String executeFork(SkillCatalog.Skill skill, String args, SkillForkHost host) {
        String body = substituteArguments(skill.promptBody(), args);
        host.recordSkillInvocation(skill.meta().name(), skill.promptBody());
        List<Message> seed = buildForkSeed(skill.meta().forkContext(), host.snapshotParentMessages());
        return host.runSubAgent(body, seed, skill.meta().model());
    }

    /**
     * 将skill body中的$ARGUMENTS替换为args
     * @param body
     * @param args
     * @return
     */
    static String substituteArguments(String body, String args) {
        if (args == null || args.isBlank()) {
            return body;
        }
        if (body.contains("$ARGUMENTS")) {
            return body.replace("$ARGUMENTS", args);
        }
        return body + "\n\n## User Request\n\n" + args;
    }

    /**
     * 根据mode返回子列表
     * @param mode full - 全量复制,recent - 复制FORK_RECENT_COUNT条
     * @param parent 父messages
     * @return submessage
     */
    static List<Message> buildForkSeed(String mode, List<Message> parent) {
        if (parent == null || parent.isEmpty()) {
            return List.of();
        }
        return switch (mode != null ? mode : "none") {
            case "full" -> new ArrayList<>(parent);
            case "recent" -> {
                if (parent.size() <= FORK_RECENT_COUNT) {
                    yield new ArrayList<>(parent);
                }
                yield new ArrayList<>(parent.subList(parent.size() - FORK_RECENT_COUNT, parent.size()));
            }
            default -> List.of();
        };
    }

}
