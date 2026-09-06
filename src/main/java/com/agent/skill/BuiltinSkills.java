package com.agent.skill;



import java.util.List;

/**
 * 内置 skill 加载器。
 * 当前版本不包含任何内置 skill，所有 skill 通过用户目录或项目目录加载。
 */
public final class BuiltinSkills {

    private BuiltinSkills() {}

    /**
     * 返回空列表——不再内置任何 skill。
     */
    public static List<SkillCatalog.Skill> load() {
        return List.of();
    }
}
