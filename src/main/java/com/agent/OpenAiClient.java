package com.agent;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * OpenAI 协议客户端实现
 *
 * 支持标准 OpenAI Chat Completions API 以及 OpenAI 兼容的第三方 API（如 DeepSeek）。
 * 处理 SSE 流式响应，解析三类增量：
 *   1. content — 普通文本回复
 *   2. tool_calls — 函数调用（按 index 聚合，参数分多个 chunk 推送）
 *   3. reasoning_content — 思考内容（DeepSeek reasoner 等模型，映射为 ThinkingDelta）
 *
 * 与 AnthropicCodeClient 的架构完全对称：虚拟线程 + BlockingQueue 实现异步流式。
 */
public class OpenAiClient implements LlmClient {

    private final String model;
    private final String apiKey;
    private final String endpointUrl;
    private volatile int maxOutputTokens;
    private volatile String systemPrompt;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public OpenAiClient(ProviderConfig cfg, String systemPrompt) {
        //fail-fast：API key 缺失时立即报错，避免等到 HTTP 401 才发现
        String apiKey = cfg.resolvedApiKey();
        if (apiKey.isEmpty()) {
            throw new LlmException.AuthenticationException("OpenAI API key not found.");
        }
        this.model = cfg.getModel();
        this.apiKey = apiKey;
        this.maxOutputTokens = cfg.resolvedMaxOutputTokens();
        this.systemPrompt = systemPrompt;
        //baseUrl 处理：null/空则回退到 OpenAI 官方地址；末尾拼接 /chat/completions
        //DeepSeek 等兼容 API 的 base_url 形如 "https://api.deepseek.com/v1"
        String base = cfg.getBaseUrl();
        if (base == null || base.isBlank()) {
            base = "https://api.openai.com/v1";
        }
        //去掉末尾斜杠避免双斜杠
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        this.endpointUrl = base + "/chat/completions";
    }

    @Override
    public BlockingQueue<StreamEvent> stream(ConversationManager conv, List<Map<String, Object>> tools) {
        var streamEvents = new LinkedBlockingQueue<StreamEvent>(64);
        //虚拟线程 + BlockingQueue 实现异步流式处理
        //SSE 解析涉及大量阻塞 IO（等待服务器推送），虚拟线程不绑定 OS 线程，频繁阻塞切换没有性能问题
        Thread.startVirtualThread(() -> {
            try {
                doStream(conv, tools, streamEvents);
            } catch (Exception e) {
                streamEvents.add(new StreamEvent.Error(classifyError(e).getMessage()));
            }
        });
        return streamEvents;
    }

    //统一为 LlmException 异常
    private LlmException classifyError(Exception e) {
        if (e instanceof LlmException le) return le;
        if (e instanceof IOException) {
            return new LlmException.NetworkException("Network error: " + e.getMessage(), e);
        }
        return new LlmException("Unexpected error: " + e.getMessage(), e);
    }

    //在虚拟线程中执行，需注意线程安全
    //JSON 反序列化会产生 unchecked 警告（泛型擦除），运行时类型是正确的，加注解抑制
    @SuppressWarnings("unchecked")
    private void doStream(ConversationManager conv, List<Map<String, Object>> tools,
                          LinkedBlockingQueue<StreamEvent> streamQueue) throws IOException, InterruptedException {
        //Step 1：构建请求消息——先放 system 消息，再放历史消息
        var messages = new ArrayList<Map<String, Object>>();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
        messages.addAll(conv.serializeOpenAI());

        //Step 2：拼接 OpenAI Chat Completions 请求体
        var body = new LinkedHashMap<String, Object>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("stream", true);
        //stream_options.include_usage=true 让最后一个 chunk 携带 token 用量
        body.put("stream_options", Map.of("include_usage", true));
        if (maxOutputTokens > 0) {
            body.put("max_tokens", maxOutputTokens);
        }

        //Step 3：发送 HTTP POST 请求
        var httpClient = HttpClient.newHttpClient();
        var request = HttpRequest.newBuilder()
                .uri(URI.create(endpointUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build();
        //send 在响应头到达时就返回，响应体留作 InputStream 供逐行读取
        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

        //Step 4：检查 HTTP 状态码，非 200 则读取错误体并抛出分类异常
        int statusCode = response.statusCode();
        if (statusCode != 200) {
            String errBody;
            try (var is = response.body()) {
                errBody = new String(is.readAllBytes());
            }
            throw mapHttpError(statusCode, errBody);
        }

        //Step 5：解析 SSE 流
        /*
         * OpenAI SSE 格式（每行一个 data: 前缀，末尾是 [DONE]）：
         *
         * data: {"choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}
         *
         * data: {"choices":[{"index":0,"delta":{"content":"你好"},"finish_reason":null}]}
         *
         * //工具调用——首个 chunk 携带 id 和 name，后续 chunk 只推送 arguments 片段
         * data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_abc","type":"function","function":{"name":"get_weather","arguments":""}}]},"finish_reason":null}]}
         *
         * data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"city"}}]}},"finish_reason":null}]}
         *
         * data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\":\"北京\"}"}}]}},"finish_reason":null}]}
         *
         * //finish_reason=tool_calls 表示本轮以工具调用结束
         * data: {"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}
         *
         * //usage（启用 stream_options 后，最后一个非 [DONE] 块携带）
         * data: {"choices":[],"usage":{"prompt_tokens":25,"completion_tokens":42,"total_tokens":67}}
         *
         * data: [DONE]
         *
         * //DeepSeek reasoner 的思考内容（非标准扩展，reasoning_content 字段）
         * data: {"choices":[{"index":0,"delta":{"reasoning_content":"先分析一下"}}]}
         */

        //工具调用状态：按 index 聚合，因为同一个工具调用的参数会被拆成多个 JSON 片段推送
        var toolCallNames = new HashMap<Integer, String>();
        var toolCallIds = new HashMap<Integer, String>();
        var toolCallArgs = new HashMap<Integer, StringBuilder>();
        var finishedToolIndices = new HashMap<Integer, Boolean>();

        //思考内容累积（reasoning_content），用于在收到 content 时补发 ThinkingComplete
        StringBuilder reasoningAccum = new StringBuilder();
        boolean inReasoning = false;

        String stopReason = "";
        int inputTokens = 0;
        int outputTokens = 0;

        //try-with-resources 确保 reader 和底层 InputStream 自动关闭
        try (var reader = new BufferedReader(new InputStreamReader(response.body()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                if (!line.startsWith("data: ")) continue;
                String data = line.substring(6).trim();
                if (data.equals("[DONE]")) break;

                Map<String, Object> event = MAPPER.readValue(data, Map.class);

                //提取 usage（stream_options 启用后，最后一个非 [DONE] 块携带）
                var usage = (Map<String, Object>) event.get("usage");
                if (usage != null) {
                    inputTokens = ((Number) usage.getOrDefault("prompt_tokens", 0)).intValue();
                    outputTokens = ((Number) usage.getOrDefault("completion_tokens", 0)).intValue();
                }

                var choices = (List<Map<String, Object>>) event.get("choices");
                if (choices == null || choices.isEmpty()) continue;

                for (var choice : choices) {
                    var delta = (Map<String, Object>) choice.get("delta");
                    if (delta == null) continue;

                    //1. reasoning_content — DeepSeek reasoner 等模型的思考内容，映射为 ThinkingDelta
                    String reasoning = (String) delta.get("reasoning_content");
                    if (reasoning != null && !reasoning.isEmpty()) {
                        if (!inReasoning) {
                            inReasoning = true;
                            reasoningAccum.setLength(0);
                        }
                        reasoningAccum.append(reasoning);
                        streamQueue.add(new StreamEvent.ThinkingDelta(reasoning));
                    }

                    //2. content — 普通文本回复
                    String text = (String) delta.get("content");
                    if (text != null && !text.isEmpty()) {
                        //如果之前在 reasoning 中，现在收到 content，说明思考结束，补发 ThinkingComplete
                        //OpenAI 没有 signature 概念，传空字符串即可
                        if (inReasoning) {
                            streamQueue.add(new StreamEvent.ThinkingComplete(reasoningAccum.toString(), ""));
                            inReasoning = false;
                        }
                        streamQueue.add(new StreamEvent.TextDelta(text));
                    }

                    //3. tool_calls — 函数调用增量
                    var toolCalls = (List<Map<String, Object>>) delta.get("tool_calls");
                    if (toolCalls != null) {
                        for (var tc : toolCalls) {
                            int idx = ((Number) tc.getOrDefault("index", 0)).intValue();
                            var function = (Map<String, Object>) tc.get("function");
                            if (function == null) continue;

                            //首次出现 id 和 name 时，标记工具调用开始（发 ToolCallStart）
                            String id = (String) tc.get("id");
                            String name = (String) function.get("name");
                            if (id != null && !toolCallIds.containsKey(idx)) {
                                toolCallIds.put(idx, id);
                                toolCallNames.put(idx, name != null ? name : "");
                                toolCallArgs.put(idx, new StringBuilder());
                                streamQueue.add(new StreamEvent.ToolCallStart(id, name != null ? name : ""));
                            }

                            //累积 arguments 片段（发 ToolCallDelta，给 UI 实时展示）
                            String argsFragment = (String) function.get("arguments");
                            if (argsFragment != null && !argsFragment.isEmpty()) {
                                toolCallArgs.get(idx).append(argsFragment);
                                streamQueue.add(new StreamEvent.ToolCallDelta(argsFragment));
                            }
                        }
                    }

                    //4. finish_reason — 本轮结束原因
                    String finishReason = (String) choice.get("finish_reason");
                    if (finishReason != null && !"null".equals(finishReason)) {
                        stopReason = finishReason;
                        //finish_reason=tool_calls 时，完成所有未完成的工具调用（发 ToolCallComplete）
                        //参数需要等全部 JSON 片段到齐后才能反序列化成 Map
                        if ("tool_calls".equals(finishReason)) {
                            for (var idx : toolCallIds.keySet()) {
                                if (!finishedToolIndices.getOrDefault(idx, false)) {
                                    String tcId = toolCallIds.get(idx);
                                    String tcName = toolCallNames.get(idx);
                                    Map<String, Object> args;
                                    try {
                                        args = MAPPER.readValue(toolCallArgs.get(idx).toString(), Map.class);
                                    } catch (Exception e) {
                                        args = new HashMap<>();
                                    }
                                    streamQueue.add(new StreamEvent.ToolCallComplete(tcId, tcName, args));
                                    finishedToolIndices.put(idx, true);
                                }
                            }
                        }
                    }
                }
            }
        }

        //流结束时仍在 reasoning（没有收到 content），补发 ThinkingComplete
        if (inReasoning) {
            streamQueue.add(new StreamEvent.ThinkingComplete(reasoningAccum.toString(), ""));
        }

        //映射 stop_reason 到与 Anthropic 一致的语义，方便 Agent Loop 统一处理
        //OpenAI: "stop" → "end_turn"；"tool_calls" → "tool_use"；其余保持原值
        String mappedStopReason = "tool_calls".equals(stopReason) ? "tool_use"
                : "stop".equals(stopReason) ? "end_turn"
                : stopReason;

        streamQueue.add(new StreamEvent.StreamEnd(mappedStopReason, inputTokens, outputTokens));
    }

    //HTTP 状态码到 LlmException 子类的映射
    private LlmException mapHttpError(int statusCode, String errBody) {
        String msg = errBody;
        if (msg == null || msg.isBlank()) msg = "HTTP " + statusCode;
        return switch (statusCode) {
            case 401 -> new LlmException.AuthenticationException("Invalid API key: " + msg);
            case 429 -> new LlmException.RateLimitException("Rate limited. Please wait.", "");
            case 413 -> new LlmException.ContextTooLongException("Context too long: " + msg);
            default -> new LlmException("API error (" + statusCode + "): " + msg);
        };
    }

    @Override
    public void setSystemPrompt(String prompt) {
        this.systemPrompt = prompt;
    }
}
