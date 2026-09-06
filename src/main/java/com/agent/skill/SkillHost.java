package com.agent.skill;

import com.agent.tool.ToolRegistry;


import java.util.function.Predicate;

/**
   skill以inline模式运行时需要的能力
 */
public interface SkillHost {

    void activateSkill(String name, String body);

    void setToolFilter(Predicate<String> filter);



    /**
     * 记录这个skill跑过，这样Layer 2压缩把转录擦掉后，SOP正文可以被重新挂回来”。
     * 做成 default no-op，是因为不支持恢复状态的宿主（测试桩、简单宿主）可以实现接口但跳过这个能力，不强制。
     */
    default void recordSkillInvocation(String name, String body) {}
}
