package com.agent;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class AnthropicCodeClient implements LlmClient{

    private final String model;
    private final boolean thinking;
    private volatile int maxOutputTokens;
    private volatile String systemPrompt;
    private static final ObjectMapper MAPPER=new ObjectMapper();
    private final AnthropicClient sdkClient;


    public AnthropicCodeClient(ProviderConfig cfg, String systemPrompt) {
        String apiKey = cfg.resolvedApiKey();
        //fail-fast
        if (apiKey.isEmpty()) {
            throw new LlmException.AuthenticationException(
                    "Anthropic API key not found.");
        }
        this.sdkClient= AnthropicOkHttpClient.builder()
                        .apiKey(apiKey)
                        .baseUrl(cfg.getBaseUrl())
                .build();
        this.model = ModelResolver.resolve(cfg.getModel());
        this.thinking = cfg.isThinking();
        this.maxOutputTokens = cfg.resolvedMaxOutputTokens();
        this.systemPrompt = systemPrompt;
    }

    @Override
    public BlockingQueue<StreamEvent> stream(ConversationManager conv, List<Map<String, Object>> tools) {
        var streamEvents = new LinkedBlockingQueue<StreamEvent>(64);
        //virtual thread + BlockingQueue 实现异步流式处理
        //创建的线程非常轻量，可以创建成千上万个而不会有性能问题。用它而不是平台线程，是因为 SSE 解析涉及大量阻塞 IO（等待服务器推送）,
        // 平台线程会绑定OS级线程，频繁阻塞就绪态切换会有很大的性能问题
        Thread.startVirtualThread(() -> {
            try {
                doStream(conv, tools, streamEvents);
            }
            catch (Exception e){
                streamEvents.add(new StreamEvent.Error(classifyError(e).getMessage()));
            }
        });
        return streamEvents;

    }
    //统一为LlmException异常
    private LlmException classifyError(Exception e) {
        if (e instanceof LlmException le) return le;
        if (e instanceof UnauthorizedException ue) {
            return new LlmException.AuthenticationException("Invalid API key: " + ue.getMessage());
        }
        if (e instanceof RateLimitException) {
            return new LlmException.RateLimitException("Rate limited. Please wait.", "");
        }
        if (e instanceof BadRequestException bre) {
            String msg = bre.getMessage() != null ? bre.getMessage().toLowerCase() : "";
            if (msg.contains("prompt is too long") || msg.contains("too many tokens")) {
                return new LlmException.ContextTooLongException("Context too long: " + bre.getMessage());
            }
            return new LlmException("Bad request.md: " + bre.getMessage(), bre);
        }
        if (e instanceof AnthropicServiceException se) {
            if (se.statusCode() == 413) {
                return new LlmException.ContextTooLongException("Context too long: " + se.getMessage());
            }
            return new LlmException("API error (" + se.statusCode() + "): " + se.getMessage(), se);
        }
        if (e instanceof AnthropicIoException) {
            return new LlmException.NetworkException("Network error: " + e.getMessage(), e);
        }
        //序列化、IO异常等意料之外的错误,包装成LlmException
        return new LlmException("Unexpected error: " + e.getMessage(), e);
    }
    //在虚拟线程中进行,需注意线程安全
    //将原始类型强制转换为泛型类型。Java 编译器无法在编译期验证这种转换的安全性，所以报了"unchecked"。运行时泛型被擦除，实际类型是正确的，程序不受影响，纯粹是编译器告警。
    //加了 @SuppressWarnings("unchecked") 注解告诉编译器："我知道这里无法静态证明类型安全，但运行时没问题，别再警告了。" 这是处理 JSON 反序列化的标准做法。
    @SuppressWarnings("unchecked")
    private void doStream(ConversationManager conv, List<Map<String, Object>> tools, LinkedBlockingQueue<StreamEvent> streamQueue) throws IOException, InterruptedException {
        //拼接Anthropic需要的JSON请求体
        var body = new LinkedHashMap<String, Object>();
        body.put("model", model);
        body.put("max_tokens", maxOutputTokens);
        body.put("stream", true);
        body.put("system", List.of(Map.of("type", "text", "text", systemPrompt)));
        //新模型（4.6 系列）支持 adaptive 类型的思考，budget 可以设满；旧模型只支持 enabled ，budget 必须比 max_tokens 少 1，否则 API 会报错。这个减 1 的细节是 Anthropic API 的硬性要求。
        if (thinking) {
            if (ModelResolver.supportsAdaptiveThinking(model)) {
                body.put("thinking", Map.of("type", "adaptive", "budget_tokens", maxOutputTokens));
            } else {
                body.put("thinking", Map.of("type", "enabled", "budget_tokens", maxOutputTokens - 1));
            }
        }
        body.put("messages",conv.serializeAnthropic());
        var httpClient = HttpClient.newHttpClient();
        var request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.deepseek.com/anthropic/v1/messages"))
                .header("Content-Type", "application/json")
                .header("x-api-key", "sk-c39ba4ff31ac42ae8fa5d6a20451c66f")
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build();
        //send会在响应头到达时就返回,把响应体留作InputStream供后续逐行读取,而不是一次性读进内存
        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        //是否在thinking块内
        boolean inThinking = false;
        StringBuilder thinkingAccum = new StringBuilder();
        String thinkingSignature = "";
        String currentToolName = "";
        String currentToolId = "";
        //StringBuilder.append() 的性能在这里很重要。一个工具调用的参数可能被拆成几十个 JSON 片段推过来，每次 append() 的复杂度是 O(1) 均摊。如果改成字符串拼接（ str += fragment ），每次都要创建新的 String 对象，性能会差很多。
        StringBuilder jsonAccum = new StringBuilder();
        String stopReason = "";
        int inputTokens=0;
        int outputTokens=0;
        //流式响应,try-with-resource,确保 reader 和底层的 InputStream 在结束时自动关闭
        try (var reader = new BufferedReader(new InputStreamReader(response.body()))) {
            String line;
            String eventType = null;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("event: ")) {
                    eventType = line.substring(7).trim();
                    continue;
                }
                if (!line.startsWith("data: ")) continue;
                String data = line.substring(6).trim();
                if (data.equals("[DONE]")) break;
                //Anthropic 用标准 SSE（Server-Sent Events）。每个事件由两行组成:一行 event: <类型>，一行 data: <JSON>，事件之间用空行分隔。
                /*
                * event: message_start
                data: {"type":"message_start","message":{"id":"msg_01...","role":"assistant","content":[],"model":"claude-...","usage":{"input_tokens":25,"output_tokens":1}}}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"先分析一下"}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"EuYBC..."}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":0}

                event: content_block_start
                data: {"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"你好"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":1}

                event: content_block_start
                data: {"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"toolu_01...","name":"get_weather","input":{}}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\"city\":"}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"\"北京\"}"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":2}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":42}}

                event: message_stop
                data: {"type":"message_stop"}*/
                Map<String, Object> event = MAPPER.readValue(data, Map.class);
                switch (eventType){
                case "content_block_start" -> {
                    var block = (Map<String, Object>) event.get("content_block");
                    //block!=null则get("type"),block为null则type为空
                    String type = block != null ? (String) block.get("type") : "";
                    if ("thinking".equals(type)) {
                        inThinking = true;
                        thinkingAccum.setLength(0);
                    }
                    else if ("tool_use".equals(type)) {
                        currentToolName = (String) block.getOrDefault("name", "");
                        currentToolId = (String) block.getOrDefault("id", "");
                        jsonAccum.setLength(0);
                        streamQueue.add(new StreamEvent.ToolCallStart(currentToolId, currentToolName));
                    }
                }
                case "content_block_delta"->{
                    var delta = (Map<String, Object>) event.get("delta");
                    if (delta == null) continue;
                    String deltaType = (String) delta.getOrDefault("type", "");
                    switch (deltaType){
                        case "thinking_delta" -> {
                            //thinking_delta 既推事件（给 UI 实时展示）又累积（block 结束时要拼成完整的思考文本）
                            String t = (String) delta.getOrDefault("thinking", "");
                            thinkingAccum.append(t);
                            streamQueue.add(new StreamEvent.ThinkingDelta(t));
                        }
                        case "signature_delta" ->
                            thinkingSignature = (String) delta.getOrDefault("signature", "");
                        case "text_delta" ->
                            streamQueue.add(new StreamEvent.TextDelta((String) delta.getOrDefault("text", "")));
                        case "input_json_delta" -> {
                            //input_json_delta 也是双重处理，因为工具参数需要等全部 JSON 片段到齐后才能反序列化成 Map。
                            String pj = (String) delta.getOrDefault("partial_json", "");
                            jsonAccum.append(pj);
                            streamQueue.add(new StreamEvent.ToolCallDelta(pj));
                        }
                    }
                }
                case "content_block_stop" -> {
                    if (inThinking) {
                        streamQueue.add(new StreamEvent.ThinkingComplete(thinkingAccum.toString(), thinkingSignature));
                        inThinking = false;
                    }
                    else if (!currentToolName.isEmpty()) {
                        Map<String, Object> args;
                        try { args = MAPPER.readValue(jsonAccum.toString(), Map.class); }
                        catch (Exception e) { args = new HashMap<>(); }
                        streamQueue.add(new StreamEvent.ToolCallComplete(currentToolId, currentToolName, args));
                        currentToolName = "";
                        currentToolId = "";
                        jsonAccum.setLength(0);
                    }
                }
                //SSE 流的末尾会推 message_start 和 message_delta 事件，携带 token 消耗信息：
                case "message_delta" ->{
                    var delta = (Map<String, Object>) event.get("delta");
                    if (delta != null && delta.containsKey("stop_reason"))
                        //end_turn 表示 LLM 自然结束， tool_use 表示 LLM 想调用工具。Agent Loop 根据这个字段决定是否进入下一轮迭代。
                        stopReason = (String) delta.get("stop_reason");
                    var usage = (Map<String, Object>) event.get("usage");
                    if (usage != null) {
                        int di = ((Number) usage.getOrDefault("input_tokens", 0)).intValue();
                        int do_ = ((Number) usage.getOrDefault("output_tokens", 0)).intValue();
                        if (di > 0) inputTokens = di;
                        if (do_ > 0) outputTokens = do_;
                    }
                }
                case "message_stop" ->
                    streamQueue.add(new StreamEvent.StreamEnd(stopReason,inputTokens,outputTokens));
            }
        }
    }

    }
    @Override
    public void setSystemPrompt(String prompt) {
           this.systemPrompt=prompt;
    }
}
