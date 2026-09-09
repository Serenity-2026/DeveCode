package com.agent.tui;

import com.agent.config.McpServerConfig;
import com.agent.config.ProviderConfig;
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
    private final List<McpServerConfig> mcpServers;

    private AppConfig(List<ProviderConfig> providers, List<McpServerConfig> mcpServers) {
        this.providers = providers;
        this.mcpServers = mcpServers;
    }

    public List<ProviderConfig> getProviders() {
        return providers;
    }

    /** providers.yaml 顶层 mcp_servers 段解析出的 MCP server 配置（无配置时为空列表）。 */
    public List<McpServerConfig> getMcpServers() {
        return mcpServers;
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

        // Step 1：定位 providers.yaml——优先当前工作目录，找不到再回退到 classpath
        Path cwdPath = Paths.get("providers.yaml");
        if (Files.exists(cwdPath)) {
            try { yamlContent = Files.readString(cwdPath); }
            catch (IOException e) { /* fall through */ }
        }

        // classpath 回退（打包成 jar 后从资源里读）
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

        // Step 2：用 SnakeYAML 把文本解析成 Map<String,Object> 根结构
        Yaml yaml = new Yaml();
        Map<String, Object> root;
        try {
            root = yaml.load(yamlContent);
        } catch (Exception e) {
            System.err.println("Error: Failed to parse providers.yaml: " + e.getMessage());
            System.exit(1);
            return null; // unreachable
        }

        // Step 3：校验顶层 "providers" 键存在且为非空列表
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

        // Step 4：逐条解析 provider，交由 parseProvider() 校验并构造 ProviderConfig
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

        return new AppConfig(parsed, parseMcpServers(root));
    }

    /**
     * 解析顶层 mcp_servers 段（Map 结构，key 为 server 名）：
     * <pre>
     * mcp_servers:
     *   GitHub:                     # stdio：有 command → 启动子进程
     *     command: "npx"
     *     args: ["-y", "@modelcontextprotocol/server-github"]
     *     env:
     *       GITHUB_TOKEN: "${GITHUB_TOKEN}"
     *   remote-tool:                # Streamable HTTP：有 url → 发 HTTP 请求
     *     url: "https://api.example.com/mcp"
     *     headers:
     *       Authorization: "Bearer ${API_TOKEN}"
     * </pre>
     * 校验规则：command 与 url 至少有一个，否则跳过并警告。
     */
    private static List<McpServerConfig> parseMcpServers(Map<String, Object> root) {
        var result = new ArrayList<McpServerConfig>();
        Object raw = root.get("mcp_servers");
        if (!(raw instanceof Map<?, ?> servers)) return result;

        for (var entry : servers.entrySet()) {
            String name = String.valueOf(entry.getKey()).trim();
            if (!(entry.getValue() instanceof Map<?, ?> cfg)) {
                System.err.println("Warning: mcp_servers." + name + " is not a mapping, skipping.");
                continue;
            }
            var c = new McpServerConfig();
            c.setName(name);
            c.setCommand(asString(cfg.get("command")));
            c.setArgs(asStringList(cfg.get("args")));
            c.setUrl(asString(cfg.get("url")));
            c.setHeaders(asStringMap(cfg.get("headers")));
            c.setEnv(asStringMap(cfg.get("env")));

            boolean hasCommand = c.getCommand() != null && !c.getCommand().isBlank();
            boolean hasUrl = c.getUrl() != null && !c.getUrl().isBlank();
            if (!hasCommand && !hasUrl) {
                System.err.println("Warning: mcp_servers." + name + " has neither command nor url, skipping.");
                continue;
            }
            result.add(c);
        }
        return result;
    }

    private static String asString(Object val) {
        return val == null ? null : val.toString().trim();
    }

    private static List<String> asStringList(Object val) {
        if (!(val instanceof List<?> list)) return null;
        var result = new ArrayList<String>();
        for (var item : list) result.add(String.valueOf(item));
        return result;
    }

    private static Map<String, String> asStringMap(Object val) {
        if (!(val instanceof Map<?, ?> map)) return null;
        var result = new java.util.LinkedHashMap<String, String>();
        for (var e : map.entrySet()) result.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
        return result;
    }

    /**
     * 解析单个 provider 条目并执行校验。
     * 校验规则：
     *   - name / protocol / model 必填（getString 第 3 参 required=true）
     *   - protocol 取值只能是 "anthropic" 或 "openai"
     *   - api_key 非必填于 yaml，但最终必须能解析到：来自 yaml 的 api_key，
     *     或对应环境变量（ANTHROPIC_API_KEY / OPENAI_API_KEY），否则报错
     *   - base_url / thinking 可选
     */
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

        // 构造 ProviderConfig：参数顺序 = (name, protocol, baseUrl, model, apiKey, thinking)；
        // ProviderConfig 内部只做存储，resolvedApiKey() 负责按优先级解析真实密钥
        ProviderConfig cfg = new ProviderConfig(name, protocol, baseUrl, model, apiKey, thinking);

        // 校验密钥存在：resolvedApiKey() 优先用 yaml 的 api_key，为空则回退到环境变量
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
