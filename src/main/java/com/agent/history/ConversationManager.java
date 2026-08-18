package com.agent.history;

import com.agent.llm.Message;
import com.agent.llm.ThinkingBlock;
import com.agent.llm.ToolResultBlock;
import com.agent.llm.ToolUseBlock;
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
        Message msg = new Message("user", content);
        history.add(msg);
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
    //动态状态 -> 包成 system-reminder，贴在最新 user 消息末尾，每轮刷新
    public void addSystemReminder(String content) {
        //需要合并system-reminder和前面一条消息
        if (!history.isEmpty()) {
            var prev = history.getLast();
            var prevRole = (String) prev.getRole();
            //如果是相同角色
            if (prevRole != null && prevRole.equals("user")) {
                var prevContent = prev.getContent();
                if (prevContent instanceof String s) {
                    var merged = new Message(prev.getRole(),s + "\n\n" + prev.getContent(),prev.getThinkingBlocks(),prev.getToolUses(),prev.getToolResults());
                    history.set(history.size() - 1, merged);
                }
            }
        }
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
    //将历史消息history转为OpenAI Chat Completions标准格式
    //OpenAI 的消息格式与 Anthropic 有三大区别：
    //  1. 每条消息必须有顶层 role 字段（user/assistant/tool/system）
    //  2. 工具调用放在 assistant 消息的 tool_calls 数组里，结构为 {id, type:"function", function:{name, arguments}}
    //     其中 arguments 是 JSON 字符串而非嵌套对象
    //  3. 工具执行结果用独立的 role="tool" 消息发送，携带 tool_call_id 关联到对应的工具调用
    //  4. OpenAI 标准 API 不支持 thinking 块（reasoning 内容只在响应中出现，不需要回传），故不序列化思考内容
    public List<Map<String, Object>> serializeOpenAI() {
        var messages = new ArrayList<Map<String, Object>>();
        for (Message msg : history) {
            //优先处理工具结果消息：OpenAI 用 role="tool" + tool_call_id + content
            //一条 user 消息可能携带多个 ToolResultBlock（对应多个并发工具调用），每个都拆成独立的 tool 消息
            if (msg.getToolResults() != null && !msg.getToolResults().isEmpty()) {
                for (var tr : msg.getToolResults()) {
                    var toolMsg = new LinkedHashMap<String, Object>();
                    toolMsg.put("role", "tool");
                    toolMsg.put("tool_call_id", tr.toolUseId());
                    toolMsg.put("content", tr.content() != null ? tr.content() : "");
                    messages.add(toolMsg);
                }
                //如果同一条消息还附带文本内容，作为额外的 user 消息发送
                if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                    messages.add(Map.of("role", "user", "content", msg.getContent()));
                }
                continue;
            }
            //普通消息（user/assistant）
            var message = new LinkedHashMap<String, Object>();
            message.put("role", msg.getRole() != null ? msg.getRole() : "user");
            message.put("content", msg.getContent() != null ? msg.getContent() : "");
            //工具调用：OpenAI 的 tool_calls 结构，arguments 需序列化成 JSON 字符串
            if (msg.hasToolUses()) {
                var toolCalls = new ArrayList<Map<String, Object>>();
                for (var tu : msg.getToolUses()) {
                    String argsJson;
                    try { argsJson = MAPPER.writeValueAsString(tu.arguments()); }
                    catch (JsonProcessingException e) { argsJson = "{}"; }
                    var tc = new LinkedHashMap<String, Object>();
                    tc.put("id", tu.toolUseId());
                    tc.put("type", "function");
                    var fn = new LinkedHashMap<String, Object>();
                    fn.put("name", tu.toolName());
                    fn.put("arguments", argsJson);
                    tc.put("function", fn);
                    toolCalls.add(tc);
                }
                message.put("tool_calls", toolCalls);
            }
            messages.add(message);
        }
        return messages;
    }
    //将历史消息history转为Anthropic标准,按顺序放 thinking、text、tool_use 块。
    /*
    *  messages=[
        {
            "role": "user",
            "content": [
                {
                    "type": "text",
                    "text": "Hi, how are you?"
                }
            ]
        }
    ]
    * */
    public List<Map<String, Object>> serializeAnthropic() {
        var messages = new ArrayList<Map<String, Object>>();
        for (Message msg : history) {
            var message = new LinkedHashMap<String, Object>();
            message.put("role", msg.getRole());
            var content = new ArrayList<Map<String, Object>>();
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
            //4.添加tool_result块（工具执行结果）
            //Anthropic 协议中工具结果用 user 角色 + tool_result content block 发送
            if (msg.getToolResults() != null && !msg.getToolResults().isEmpty()) {
                for (var tr : msg.getToolResults()) {
                    var block = new LinkedHashMap<String, Object>();
                    block.put("type", "tool_result");
                    block.put("tool_use_id", tr.toolUseId());
                    block.put("content", tr.content() != null ? tr.content() : "");
                    if (tr.isError()) {
                        block.put("is_error", true);
                    }
                    content.add(block);
                }
            }
            message.put("content", content);
            messages.add(message);

        }
        return messages;
    }
}

