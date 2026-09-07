
package com.agent.command;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 命令定义
 * @param name        canonical name without the leading slash (e.g. "help")
 * @param description one-line description shown in /help output
 * @param aliases     alternative names (e.g. {"h", "?"} for help)
 * @param type        how the command is dispatched
 * @param hidden      if true, omitted from /help listings
 * @param skill       true 表示该命令由 skill catalog 动态提供（列表/提示面板中带 [skill] 标识）
 * @param subcommands 子命令名 → 简短描述（保持声明顺序）；输入 "/<cmd> <partial>" 时
 *                    用于命令提示面板的子命令候选与 Tab 补全
 */
public record Command(
        String name,
        String description,
        String[] aliases,
        CommandType type,
        boolean hidden,
        boolean skill,
        Map<String, String> subcommands
) {

    /** 静态命令使用的便利构造器（skill = false，无子命令）。 */
    public Command(String name, String description, String[] aliases, CommandType type, boolean hidden) {
        this(name, description, aliases, type, hidden, false, Map.of());
    }

    /** skill 命令使用的便利构造器（无子命令）。 */
    public Command(String name, String description, String[] aliases, CommandType type, boolean hidden, boolean skill) {
        this(name, description, aliases, type, hidden, skill, Map.of());
    }

    /** 带子命令的静态命令使用的便利构造器（skill = false）。 */
    public Command(String name, String description, String[] aliases, CommandType type, boolean hidden,
                   Map<String, String> subcommands) {
        this(name, description, aliases, type, hidden, false, subcommands);
    }

    /** 按声明顺序构建子命令表（参数为 name, description 成对传入）。 */
    public static Map<String, String> subcommands(String... pairs) {
        var m = new LinkedHashMap<String, String>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            m.put(pairs[i], pairs[i + 1]);
        }
        return Collections.unmodifiableMap(m);
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

