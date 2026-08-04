package com.agent.infra;

import java.util.Map;
//配置解析类
public class ProviderConfig {

    private static final Map<String, String> ENV_KEY_MAP = Map.of(
            "anthropic", "ANTHROPIC_API_KEY",
            "openai", "OPENAI_API_KEY",
            "openai-compat", "OPENAI_API_KEY"
    );

    private String name;
    private String protocol;
    private String baseUrl;
    private String model;
    private String apiKey;
    private boolean thinking;

    private int contextWindow;
    private int maxOutputTokens;

    public ProviderConfig(String name, String protocol, String baseUrl, String model, String apiKey, boolean thinking) {
        this.name = name;
        this.protocol = protocol;
        this.baseUrl = baseUrl;
        this.model = model;
        this.apiKey = apiKey;
        this.thinking = thinking;
    }

    /*
        从模型 API 动态查询到的上下文窗口（二级缓存）,用Integer（包装类型）而非 int，因为需要用 null 表示"还没查询过/查询失败"。
        volatile 关键字保证多线程可见性--这个值由客户端构造线程写入，可能被其他线程读取，需要内存屏障防止读到过期值。
         */
    private volatile Integer fetchedContextWindow;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getProtocol() { return protocol; }

    public void setProtocol(String protocol) { this.protocol = protocol; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public boolean isThinking() { return thinking; }
    public void setThinking(boolean thinking) { this.thinking = thinking; }

    public int getContextWindow() { return contextWindow; }

    public void setContextWindow(int contextWindow) { this.contextWindow = contextWindow; }

    public int getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(int maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }
    public void setFetchedContextWindow(int window) {
        if (window > 0) this.fetchedContextWindow = window;
    }

    /**
     * 优先级从高到低的四层回退设计：
     * 用户配置最权威（手动 > 自动），其次是模型 API 自己上报的真实值（动态 > 静态），再次是代码里维护的经验值（兜底）
     */
    public int resolvedContextWindow() {
        // Layer 1: explicit config override.
        if (contextWindow > 0) return contextWindow;
        // Layer 2: auto-fetched from the provider (cached at client creation).
        Integer fetched = fetchedContextWindow;
        if (fetched != null && fetched > 0) return fetched;
        // Layers 3 + 4: built-in table, then conservative default.
        return windowForModel(model);
    }

    public static int windowForModel(String model) {
        String m = model == null ? "" : model.toLowerCase();
        // Most specific first.
        if (m.contains("1m") || m.contains("-1m")) return 1_000_000; // explicit 1M-context variants
        if (m.contains("gpt-4.1")) return 1_000_000;
        if (m.contains("gpt-4o")) return 128_000;
        if (m.contains("gpt-4-turbo")) return 128_000;
        if (m.contains("o1") || m.contains("o3") || m.contains("o4")) return 200_000; // OpenAI reasoning models
        if (m.contains("gpt-3.5")) return 16_385;
        if (m.contains("claude")) return 200_000;
        return 128_000; // conservative default
    }

    public int resolvedMaxOutputTokens() {
        if (maxOutputTokens > 0) return maxOutputTokens;
        return thinking ? 64_000 : 8192;
    }
    //先拿代码中配置好的api_key,如果没有再去环境变量中取
    public String resolvedApiKey() {
        if (apiKey != null && !apiKey.isEmpty()) return apiKey;
        String envVar = ENV_KEY_MAP.get(protocol);
        if (envVar == null) return "";
        String val = System.getenv(envVar);
        return val != null ? val : "";
    }
}

