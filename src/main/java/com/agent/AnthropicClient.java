package com.agent;

import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

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
        return null;
    }

    @Override
    public void setSystemPrompt(String prompt) {

    }
}
