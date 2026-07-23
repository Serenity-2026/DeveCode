package com.agent;

import com.anthropic.errors.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class AnthropicClient implements LlmClient{

    private final String model;
    private final boolean thinking;
    private final int maxOutputTokens;
    private volatile String systemPrompt;

    public AnthropicClient(ProviderConfig cfg, String systemPrompt) {
        String apiKey = cfg.resolvedApiKey();
        //fail-fast
        if (apiKey.isEmpty()) {
            throw new LlmException.AuthenticationException(
                    "Anthropic API key not found.");
        }
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
            return new LlmException("Bad request: " + bre.getMessage(), bre);
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

    private void doStream(ConversationManager conv, List<Map<String, Object>> tools, LinkedBlockingQueue<StreamEvent> streamEvents) {
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
    }

    @Override
    public void setSystemPrompt(String prompt) {

    }
}
