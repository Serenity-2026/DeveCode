package com.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;


/**
 * 对话状态的核心管理器
 */
public class ConversationManager {

    private final List<Message> history = new ArrayList<>();

    private boolean ltmInjected = false;

    private static final ObjectMapper MAPPER=new ObjectMapper();

    public void addUserMessage(String content) {
        history.add(new Message("user", content));
    }

    public void addAssistantMessage(String content) {
        history.add(new Message("assistant", content));
    }

    public void addAssistantFull(String text, List<ThinkingBlock> thinking, List<ToolUseBlock> toolUses) {
        var msg = new Message("assistant", text);
        msg.setThinkingBlocks(thinking);
        msg.setToolUses(toolUses);
        history.add(msg);
    }

    public void addAssistantMessageWithTools(String text, List<ToolUseBlock> toolUses) {
        var msg = new Message("assistant", text);
        msg.setToolUses(toolUses);
        history.add(msg);
    }

    public void addToolResultsMessage(List<ToolResultBlock> results) {
        var msg = new Message("user", "");
        //容易踩坑的 API 约定--工具执行结果在 Anthropic 协议里是用 user 角色发送的，而不是某种 tool 角色（OpenAI 的 function calling 用 tool 角色，
        // Anthropic 用 user 角色 + tool_result content block）。所以这里 new Message("user", "")，文本为空，
        // 真正的数据放在 toolResults 字段里。
        msg.setToolResults(results);
        history.add(msg);
    }

    /**、
     * 把项目说明（CLAUDE.md/AGENTS.md 风格）和自动记忆注入到对话开头，作为给 LLM 的背景上下文。
     */
    public void injectLongTermMemory(String instructions, String memories) {
        if (ltmInjected) return;
        var sections = new ArrayList<String>();
        if (instructions != null && !instructions.isEmpty()) {
            sections.add("# devecodeMd\nCodebase and user instructions are shown below. Be sure to adhere to these instructions. IMPORTANT: These instructions OVERRIDE any default behavior and you MUST follow them exactly as written.\n\n" + instructions);
        }
        if (memories != null && !memories.isEmpty()) {
            sections.add("# autoMemory\n" + memories);
        }
        if (sections.isEmpty()) return;
        //注入当前日期，让 LLM 知道"今天是几号"。这对回答"最近"、"上周"这类相对时间问题很关键--LLM 的训练数据有截止日期，不告诉它今天几号它会瞎猜。
        sections.add("# currentDate\nToday's date is " + java.time.LocalDate.now() + ".");
        String body = String.join("\n\n", sections);
        //外层包裹 <system-reminder> 标签，结尾加一句"this context may or may not be relevant"的免责声明。这套措辞直接复刻自 Claude 官方推荐的 system-reminder 模式，
        // 目的是让 LLM 知道有上下文但不强行响应--比如你注入了项目说明，但用户只是问"你好"，LLM 不应该开始背诵项目规范。
        String wrapped = "<system-reminder>\nAs you answer the user's questions, you can use the following context:\n" +
                body +
                "\n\n      IMPORTANT: this context may or may not be relevant to your tasks. You should not respond to this context unless it is highly relevant to your task.\n</system-reminder>";
        //Anthropic 的 system prompt 是请求级的独立参数（在 LlmClient.stream() 里通过 systemPrompt 传入），而 history 里的消息只能是 user/assistant。
        history.add(0, new Message("user", wrapped));
        ltmInjected = true;
    }

    public void resetLtmInjected() {
        ltmInjected = false;
    }

    public void addSystemReminder(String content) {
        history.add(new Message("user", "<system-reminder>\n" + content + "\n</system-reminder>"));
    }

    public List<Message> getMessages() {
        return List.copyOf(history);
    }

    public List<Message> getMessagesMutable() {
        return history;
    }

    public int size() {
        return history.size();
    }

    public void truncateTo(int index) {
        if (index >= 0 && index < history.size()) {
            history.subList(index, history.size()).clear();
        }
    }
    public List<Map<String, Object>> serialize(String protocol) {
        return "anthropic".equals(protocol)
                ? serializeAnthropic()
                : serializeOpenAI();
    }
    //将历史消息history转为OpenAI标准
    private List<Map<String, Object>> serializeOpenAI() {
        var content=new ArrayList<Map<String,Object>>();
        for (Message msg : history) {
            if(msg.hasThinking()){
                //1.添加thinking块
                for (var tb : msg.getThinkingBlocks()) {
                    content.add(Map.of(
                            "type", "thinking",
                            "thinking", tb.thinking(),
                            "signature", tb.signature()));
                }
            }
            //2.添加text
            if(msg.getContent()!=null&&!msg.getContent().isEmpty()){
                content.add(Map.of(
                        "type", "text",
                        "text", msg.getContent()));
            }
            //3.添加tool_use,有可能存在发起函数调用但无参数的情况，因此用LinkedHashMap单独构建
            //和 Anthropic 最大的区别是:工具调用的参数需要序列化成JSON字符串,而不是直接嵌套Map对象.Anthropic的input字段接受嵌套JSON对象,
            // OpenAI 的 arguments 字段只接受字符串。所以这里多了一步 MAPPER.writeValueAsString() 。
            if (msg.hasToolUses()) {
                for (var tu : msg.getToolUses()) {
                    String argsJson;
                    try { argsJson = MAPPER.writeValueAsString(tu.arguments()); }
                    catch (JsonProcessingException e) { argsJson = "{}"; }
                    var item = new LinkedHashMap<String, Object>();
                    item.put("type", "function_call");
                    item.put("name", tu.toolName());
                    item.put("call_id", tu.toolUseId());
                    item.put("arguments", argsJson);
                    content.add(item);
                }


        }

    }
        return content;
    }
    //将历史消息history转为Anthropic标准,按顺序放 thinking、text、tool_use 块。
    private List<Map<String, Object>> serializeAnthropic() {
        var content=new ArrayList<Map<String,Object>>();
        for (Message msg : history) {
            if(msg.hasThinking()){
                //1.添加thinking块
            for (var tb : msg.getThinkingBlocks()) {
                    content.add(Map.of(
                            "type", "thinking",
                            "thinking", tb.thinking(),
                            "signature", tb.signature()));
            }
            }
            //2.添加text
            if(msg.getContent()!=null&&!msg.getContent().isEmpty()){
                content.add(Map.of(
                        "type", "text",
                        "text", msg.getContent()));
            }
            //3.添加tool_use,有可能存在发起函数调用但无参数的情况，因此用LinkedHashMap单独构建
            if (msg.hasToolUses()) {
                for (var tu : msg.getToolUses()) {
                    var block = new LinkedHashMap<String, Object>();
                    block.put("type", "tool_use");
                    block.put("id", tu.toolUseId());
                    block.put("name", tu.toolName());
                    block.put("input", tu.arguments());
                    content.add(block);
                }
            }


        }
        return content;
    }
    //添加消息时要注意：AnthropicAPI要求消息严格按user/assistant交替（user→assistant→user→…），连续两条同角色消息会直接报错。
    // 但在用户消息后追加的system-reminder也是 user 角色，于是出现连续两条 user。在把消息塞进结果列表前，先看上一条是不是同角色，是的话就并成一条。
    private void checkSingleRole(Message msg){
        if (!history.isEmpty()) {
            var prev = history.getLast();
            var prevRole = (String) prev.getRole();
            //如果是相同角色
            if (prevRole != null && prevRole.equals(msg.getRole())) {
                var prevContent = prev.getContent();
                if (prevContent instanceof String s) {
                    var merged = new Message(prev.getRole(),s + "\n\n" + prev.getContent(),prev.getThinkingBlocks(),prev.getToolUses(),prev.getToolResults());
                    history.set(history.size() - 1, merged);
                }
            }
        }
    }
}

