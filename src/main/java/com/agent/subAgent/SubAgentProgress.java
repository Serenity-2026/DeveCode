
package com.agent.subAgent;

/**
 * 报告子Agent的运行状态
 *
 * @param agentType   the spec name of the sub-agent (e.g. "plan", "explore")
 * @param description short description of the sub-agent task
 * @param toolName    name of the tool that just ran, or {@code null} on completion
 * @param toolOutput  summary of the tool output, or {@code null} on completion
 * @param toolError   whether the tool call returned an error
 * @param done        whether the sub-agent has finished
 * @param toolCount   total number of tool calls made so far
 * @param totalTime   wall-clock seconds elapsed since the sub-agent started
 */
public record SubAgentProgress(
        String agentType,
        String description,
        String toolName,
        String toolOutput,
        boolean toolError,
        boolean done,
        int toolCount,
        double totalTime
) {}

