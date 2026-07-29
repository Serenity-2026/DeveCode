package com.agent.tui;

import com.agent.ProviderConfig;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 加载并校验 providers.yaml 配置。
 *
 * 查找顺序：
 *   1. 当前工作目录下的 providers.yaml
 *   2. classpath 下的 providers.yaml
 *   3. 都不存在则报错退出。
 *
 * 校验规则：
 *   - providers 列表不能为空
 *   - 每项必须包含 name、protocol、model
 *   - api_key 必须在 yaml 或对应环境变量中存在
 */
public class AppConfig {

    private final List<ProviderConfig> providers;

    private AppConfig(List<ProviderConfig> providers) {
        this.providers = providers;
    }

    public List<ProviderConfig> getProviders() {
        return providers;
    }

    public ProviderConfig getSingle() {
        if (providers.size() != 1) {
            throw new IllegalStateException("Expected exactly one provider, got " + providers.size());
        }
        return providers.get(0);
    }

    /**
     * 加载配置，校验失败时打印错误并调用 System.exit(1)。
     */
    public static AppConfig load() {
        String yamlContent = null;

        // 1. 尝试当前工作目录
        Path cwdPath = Paths.get("providers.yaml");
        if (Files.exists(cwdPath)) {
            try { yamlContent = Files.readString(cwdPath); }
            catch (IOException e) { /* fall through */ }
        }

        // 2. 尝试 classpath
        if (yamlContent == null) {
            try (InputStream in = AppConfig.class.getClassLoader().getResourceAsStream("providers.yaml")) {
                if (in != null) {
                    yamlContent = new String(in.readAllBytes());
                }
            } catch (IOException e) { /* fall through */ }
        }

        if (yamlContent == null || yamlContent.isBlank()) {
            System.err.println("Error: providers.yaml not found in current directory or classpath.");
            System.err.println("Create a providers.yaml file with at least one provider definition.");
            System.exit(1);
        }

        Yaml yaml = new Yaml();
        Map<String, Object> root;
        try {
            root = yaml.load(yamlContent);
        } catch (Exception e) {
            System.err.println("Error: Failed to parse providers.yaml: " + e.getMessage());
            System.exit(1);
            return null; // unreachable
        }

        if (root == null || !root.containsKey("providers")) {
            System.err.println("Error: providers.yaml must contain a top-level 'providers' key.");
            System.exit(1);
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawList = (List<Map<String, Object>>) root.get("providers");
        if (rawList == null || rawList.isEmpty()) {
            System.err.println("Error: providers list is empty.");
            System.exit(1);
        }

        List<ProviderConfig> parsed = new ArrayList<>();
        for (int i = 0; i < rawList.size(); i++) {
            Map<String, Object> item = rawList.get(i);
            try {
                parsed.add(parseProvider(i, item));
            } catch (ConfigException e) {
                System.err.println("Error in provider[" + i + "]: " + e.getMessage());
                System.exit(1);
            }
        }

        return new AppConfig(parsed);
    }

    private static ProviderConfig parseProvider(int index, Map<String, Object> item) {
        String name = getString(item, "name", true);
        String protocol = getString(item, "protocol", true);
        String model = getString(item, "model", true);
        String apiKey = getString(item, "api_key", false);
        String baseUrl = getString(item, "base_url", false);
        boolean thinking = getBool(item, "thinking", false);

        // 校验 protocol 值
        if (!"anthropic".equals(protocol) && !"openai".equals(protocol)) {
            throw new ConfigException("protocol must be 'anthropic' or 'openai', got: " + protocol);
        }

        ProviderConfig cfg = new ProviderConfig(name, protocol, baseUrl, model, apiKey, thinking);

        // 校验密钥存在
        if (cfg.resolvedApiKey().isEmpty()) {
            throw new ConfigException(
                "No API key found. Set api_key in yaml or via environment variable " +
                ("anthropic".equals(protocol) ? "ANTHROPIC_API_KEY" : "OPENAI_API_KEY"));
        }

        return cfg;
    }

    private static String getString(Map<String, Object> map, String key, boolean required) {
        Object val = map.get(key);
        if (val == null) {
            if (required) throw new ConfigException("missing required field: " + key);
            return null;
        }
        return val.toString().trim();
    }

    private static boolean getBool(Map<String, Object> map, String key, boolean defaultVal) {
        Object val = map.get(key);
        if (val == null) return defaultVal;
        if (val instanceof Boolean b) return b;
        return Boolean.parseBoolean(val.toString().trim());
    }

    private static class ConfigException extends RuntimeException {
        ConfigException(String msg) { super(msg); }
    }
}
