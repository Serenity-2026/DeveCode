package com.agent.skill;

import java.util.List;

/**
   skill以inline模式运行时需要的能力
 */
public interface SkillHost {

    /**
     * 激活 skill 的工具白名单贡献：并入宿主的激活集（多 skill 叠加取并集），
     * 跨 Agent Loop 持久生效，直到 deactivateSkill 退出该 skill。
     * 空 allowedTools 也登记（无贡献、不限制），保证退出时生命周期统一。
     */
    void addSkillTools(String skillName, List<String> allowedTools);



    /**
     * 记录这个skill跑过，这样Layer 2压缩把转录擦掉后，SOP正文可以被重新挂回来”。
     * 做成 default no-op，是因为不支持恢复状态的宿主（测试桩、简单宿主）可以实现接口但跳过这个能力，不强制。
     */
    default void recordSkillInvocation(String name, String body) {}

    /**
     * 退出 skill（与 addSkillTools 对称的生命周期收尾）：工具白名单贡献移除 +
     * 压缩恢复记录清除 + 停用提示注入当前对话。未声明 allowedTools 的 skill
     * 只清恢复记录与提示（本来就无工具限制）。
     * 做成 default（返回不支持提示），模式同 recordSkillInvocation——不管理
     * 激活态的简单宿主（测试桩等）可跳过，真实宿主（TUI）override 提供完整清理。
     * @return 给模型/用户看的退出结果文本
     */
    default String deactivateSkill(String name) {
        return "Skill deactivation is not supported by this host.";
    }
}
