
package com.agent.teams;


import com.agent.agent.AgentDeps;
import com.agent.config.ProviderConfig;
import com.agent.llm.LlmClient;
import com.agent.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把"派生一个队友"这件事按后端模式归一化。上层只需构造一个 SpawnConfig，不用关心底层是虚拟线程还是 tmux 还是 iTerm。
 */
public final class SpawnDispatcher {

    private static final Logger log = LoggerFactory.getLogger(SpawnDispatcher.class);

    public record SpawnConfig(
            TeamManager.Team team,
            String memberName,
            String task,
            //附加的系统提示
            String addendum,
            LlmClient client,
            ToolRegistry registry,
            ProviderConfig providerConfig,
            String workdir,
            // 队友 Agent 的依赖套装（权限裁决 / hook / 文件历史 / 指令 / 记忆 / skill / 迭代上限）。
            // 传 null 的队友是"裸 Agent"：没有权限检查、没有记忆，通常不是你想要的
            AgentDeps deps
    ) {}

    public record SpawnResult(
            TeamManager.TeamMode mode,
            //tmux窗口名/iTerm标签名,进程内模式为null
            String paneId
    ) {}

    private SpawnDispatcher() {}

    public static SpawnResult spawnTeammate(SpawnConfig config) throws Exception {
        var team = config.team();
        var mode = team.getMode();

        switch (mode) {
            case IN_PROCESS -> {
                var member = team.addMember(config.memberName(), config.client(),
                        config.registry(), config.providerConfig());
                if (config.workdir() != null) {
                    member.agent.setWorkDir(config.workdir());
                }
                // 注入依赖套装：缺了 checker，队友的工具调用会完全跳过权限裁决
                if (config.deps() != null) {
                    config.deps().applyTo(member.agent);
                }
                member.active = true;
                member.thread = Thread.startVirtualThread(() ->
                        TeammateRunner.runInProcessTeammate(team, member, config.task(), config.addendum()));
                return new SpawnResult(mode, null);
            }
            //
            case TMUX -> {
                return null;
            }
            case ITERM -> {
                return null;
            }
            default -> throw new IllegalStateException("Unsupported team mode: " + mode);
        }
    }
}
