package com.agent.permission;

import com.agent.tool.ToolCategory;

public enum PermissionMode {
    //用户可运行时切换的全局权限档位:
    //默认严格模式 日常使用，每个写/命令操作都要询问
    DEFAULT,
    //自动接受编辑用户信任AI改代码，但仍要确认shell命令
    ACCEPT_EDITS,
    //计划模式AI只能调研和写计划文件，不能动项目代码
    PLAN,
    //绕过模式管理员/调试，全部放行（危险）
    BYPASS;

    /**
     * 模式 × 工具类别 的二维决策矩阵
     * @param category 工具分类,用于裁决出方法具体行为，READ/WRITE/COMMAND
     * @return 裁决结果
     */
    public Decision decide(ToolCategory category) {
        return switch (this) {
            case DEFAULT -> switch (category) {
                case READ -> Decision.ALLOW;
                case WRITE, COMMAND -> Decision.ASK;
            };
            case ACCEPT_EDITS -> switch (category) {
                case READ, WRITE -> Decision.ALLOW;
                case COMMAND -> Decision.ASK;
            };
            case PLAN -> DEFAULT.decide(category);
            case BYPASS -> Decision.ALLOW;
        };
    }

    /**
     * 单次工具调用的裁决结果：
     * - ALLOW ：放行
     * - DENY ：拒绝（不可逆，优先级最高的一类）
     * - ASK ：抛给用户决定（通过 UI 弹窗）
     */
    public enum Decision {
        ALLOW, DENY, ASK
    }
}
