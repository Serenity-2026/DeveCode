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

    private static final ObjectMapper MAPPER=new ObjectMapper();


    public ConversationManager(){

    }
    /**
     * 深拷贝:history 列表、Message 本体、以及它携带的三个 block 列表容器全部重建。
     *
     * 为什么必须深到这一层:fork 出来的子 Agent 会和父 Agent 并发跑各自的 agentLoop,
     * 而 ToolResultBudget 写回是就地改消息(msg.setToolResults(...))。
     * 只复制 history 列表的话,父子两边的写回会落在同一个 Message 对象上——
     * 轻则互相覆盖(比如溢写预览里嵌了各自不同的路径,两边来回翻),重则两个线程无同步地改同一字段。
     * block 本体(ThinkingBlock/ToolUseBlock/ToolResultBlock)是 record,按只读约定使用,复制容器即可。
     */
    public ConversationManager(ConversationManager c){
        for(Message m : c.history){
            Message copy = new Message(m.getRole(), m.getContent());
            copy.setThinkingBlocks(m.getThinkingBlocks() == null ? null : List.copyOf(m.getThinkingBlocks()));
            copy.setToolUses(m.getToolUses() == null ? null : List.copyOf(m.getToolUses()));
            copy.setToolResults(m.getToolResults() == null ? null : List.copyOf(m.getToolResults()));
            this.history.add(copy);
        }
    }

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

    /**
     * 把项目说明（CLAUDE.md/AGENTS.md 风格）、自动记忆和 skill 清单注入到对话开头，作为给 LLM 的背景上下文。
     * @param instructions:预先写好的项目知识和编码规范，相当于员工的「入职文档」。
     * @param memories:Agent 在对话中自动积累的经验，比如你的编码偏好、项目的技术细节。
     * @param skills:skill 清单 section（name + description，由 Agent 构建）；压缩后重注入可保证不丢失。
     */
    public void injectLongTermMemory(String instructions, String memories, String skills) {
        var sections = new ArrayList<String>();
        if (instructions != null && !instructions.isEmpty()) {
            sections.add("# devecodeMd\nCodebase and user instructions are shown below. Be sure to adhere to these instructions. IMPORTANT: These instructions OVERRIDE any default behavior and you MUST follow them exactly as written.\n\n" + instructions);
        }
        if (memories != null && !memories.isEmpty()) {
            sections.add("# autoMemory\n" + memories);
        }
        if (skills != null && !skills.isEmpty()) {
            sections.add(skills);
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
        // 关键:先看 history[0] 是不是已经注入过的 system-reminder
        if (!history.isEmpty()) {
            var first = history.getFirst();
            if (first.getContent() instanceof String s && s.startsWith("<system-reminder>")) {
                history.set(0, new Message("user", wrapped));  // 原地替换
                return;
            }
        }
        // 否则首次注入,插入到开头
        history.addFirst(new Message("user", wrapped));
    }


    //动态状态 -> 包成 system-reminder，贴在最新 user 消息末尾，每轮刷新
    //system-reminder是一种特殊的消息标记。它放在messages字段里，但用XML标签包裹，告诉模型「这不是用户说的话，而是系统给你的补充指令」。
    //不影响 system 字段的缓存，又能让模型在对话过程中随时参考
    public void addSystemReminder(String content) {
        String wrapped = "<system-reminder>\n" + content + "\n</system-reminder>";
        if (!history.isEmpty()) {
            var prev = history.getLast();
            if ("user".equals(prev.getRole()) && prev.getContent() != null) {
                var merged = new Message(
                        prev.getRole(),
                        prev.getContent() + "\n\n" + wrapped,
                        prev.getThinkingBlocks(),
                        prev.getToolUses(),
                        prev.getToolResults());
                history.set(history.size() - 1, merged);
                return;
            }
        }
        history.add(new Message("user", wrapped));
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
                    toolMsg.put("tool_call_id", tr.toolId());
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
                    tc.put("id", tu.toolId());
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
        String lastRole=null;
        for (Message msg : history) {
            String role = msg.getRole() != null ? msg.getRole() : "user";
            //role 兜底：null 会导致 Anthropic API 400
            var content = new ArrayList<Map<String, Object>>();
            //1.添加tool_result块（工具执行结果），必须放在 content 最前：
            //Anthropic 要求 tool_use 的下一条消息以 tool_result 开头（"immediately after"），
            //前面插入 text 块（如 system-reminder 合并进来的文本）会触发 400
            //Anthropic 协议中工具结果用 user 角色 + tool_result content block 发送
            if (msg.getToolResults() != null && !msg.getToolResults().isEmpty()) {
                for (var tr : msg.getToolResults()) {
                    var block = new LinkedHashMap<String, Object>();
                    block.put("type", "tool_result");
                    block.put("tool_use_id", tr.toolId());
                    block.put("content", tr.content() != null ? tr.content() : "");
                    if (tr.isError()) {
                        block.put("is_error", true);
                    }
                    content.add(block);
                }
            }
            //2.添加thinking块
            if(msg.hasThinking()){
            for (var tb : msg.getThinkingBlocks()) {
                    content.add(Map.of(
                            "type", "thinking",
                            "thinking", tb.thinking(),
                            "signature", tb.signature()));
            }
            }
            //3.添加text
            if(msg.getContent()!=null&&!msg.getContent().isEmpty()){
                content.add(Map.of(
                        "type", "text",
                        "text", msg.getContent()));
            }
            //4.添加tool_use,有可能存在发起函数调用但无参数的情况，因此用LinkedHashMap单独构建
            if (msg.hasToolUses()) {
                for (var tu : msg.getToolUses()) {
                    var block = new LinkedHashMap<String, Object>();
                    block.put("type", "tool_use");
                    block.put("id", tu.toolId());
                    block.put("name", tu.toolName());
                    block.put("input", tu.arguments());
                    content.add(block);
                }
            }
            //空 content 会导致 Anthropic API 400（content 必须非空数组），
            //跳过既无 thinking/text 也无 tool_use/tool_result 的空消息
            if (content.isEmpty()) {
                continue;
            }
            if (role.equals(lastRole) && !messages.isEmpty()) {
                // 相邻同角色:把 content 块追加到上一条消息的 content 数组
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> prevContent =
                        (List<Map<String, Object>>) messages.getLast().get("content");
                prevContent.addAll(content);
            }else {
                var message = new LinkedHashMap<String, Object>();
                message.put("role", role);
                message.put("content", content);
                messages.add(message);
                lastRole = role;
            }

        }
        return messages;
    }
}

