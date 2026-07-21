package com.agent;

import java.util.List;
//用的是class而不是record。因为 Message 需要先创建再逐步填充，
// 比如 addAssistantFull() 先 new 一个 Message，再 setThinkingBlocks() ，
// 再 setToolUses() 。record 是不可变的，创建时就得把所有字段传全，不适合这种「先骨架后填肉」的模式。
public class Message {
    private String role;
    private String content;
    private List<ThinkingBlock> thinkingBlocks;
    private List<ToolUseBlock> toolUses;
    private List<ToolResultBlock> toolResults;

    public Message(String role, String content) {
        this.role = role;
        this.content = content;
    }
    //getter and setter
    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public List<ThinkingBlock> getThinkingBlocks() {
        return thinkingBlocks;
    }

    public void setThinkingBlocks(List<ThinkingBlock> thinkingBlocks) {
        this.thinkingBlocks = thinkingBlocks;
    }

    public List<ToolUseBlock> getToolUses() {
        return toolUses;
    }

    public void setToolUses(List<ToolUseBlock> toolUses) {
        this.toolUses = toolUses;
    }

    public List<ToolResultBlock> getToolResults() {
        return toolResults;
    }

    public void setToolResults(List<ToolResultBlock> toolResults) {
        this.toolResults = toolResults;
    }
}
