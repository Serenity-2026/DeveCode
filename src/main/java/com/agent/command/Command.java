
package com.agent.command;

/**
 * 命令定义
 * @param name        canonical name without the leading slash (e.g. "help")
 * @param description one-line description shown in /help output
 * @param aliases     alternative names (e.g. {"h", "?"} for help)
 * @param type        how the command is dispatched
 * @param hidden      if true, omitted from /help listings
 * @param skill       true 表示该命令由 skill catalog 动态提供（列表/提示面板中带 [skill] 标识）
 */
public record Command(
        String name,
        String description,
        String[] aliases,
        CommandType type,
        boolean hidden,
        boolean skill
) {

    /** 静态命令使用的便利构造器（skill = false）。 */
    public Command(String name, String description, String[] aliases, CommandType type, boolean hidden) {
        this(name, description, aliases, type, hidden, false);
    }

    /** Dispatch style for a command. */
    public enum CommandType {
        /** Synchronous handler that returns text output:/help /status /memory*/
        LOCAL,
        /** TUI action (clear screen, mode switch) -- no text output:/clear /plan /compact /resume /rewind
         * TUI 层发现命令类型是 LOCAL_UI 时，不调用 execute() ，而是直接根据命令名做对应的 UI 操作（清屏、切换模式等）。命令层和 UI 层的职责完全分离。
         * */
        LOCAL_UI,
        /** Generates a prompt string sent to the LLM agent.:/review、所有 Skill命令*/
        PROMPT
    }

    /**
     * Returns {@code true} when {@code input} matches the canonical name
     * or any alias (exact, case-sensitive comparison).
     */
    public boolean matches(String input) {
        if (name.equals(input)) {
            return true;
        }
        for (var alias : aliases) {
            if (alias.equals(input)) {
                return true;
            }
        }
        return false;
    }
}

