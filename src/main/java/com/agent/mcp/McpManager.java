package com.agent.mcp;

import com.agent.config.McpServerConfig;
import com.agent.tool.Tool;
import com.agent.tool.ToolCategory;
import com.agent.tool.ToolRegistry;
import com.agent.tool.ToolResult;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;

import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;

import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

public class McpManager {
    //正则 [^a-zA-Z0-9_]，用于把工具名里的非法字符替换成 _，保证生成的工具名是合法标识符
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-zA-Z0-9_]");
    //正则 \$\{([^}]+)}，匹配 ${VAR} 形式的环境变量占位符，用于配置值脱敏（如 headers 里写 ${API_KEY}）
    private static final Pattern ENV_VAR = Pattern.compile("\\$\\{([^}]+)}");
    //server名称+server自述的使用说明（MCP 握手时server可以上报instructions，会被拼进Agent的系统提示词）
    public record ServerInfo(String name, String instructions) {}
    //成功注册的工具、成功的server信息、失败的错误消息，三路结果一次带出
    public record ConnectResult(List<Tool> tools, List<ServerInfo> servers, List<String> errors) {}
    //配置表
    private final Map<String, McpServerConfig> configs = new LinkedHashMap<>();
    //已建立连接的客户端表
    private final Map<String, McpSyncClient> clients = new LinkedHashMap<>();

    public McpManager(List<McpServerConfig> configs) {
        if (configs != null) {
            for (var cfg : configs) this.configs.put(cfg.getName(), cfg);
        }
    }

    public ConnectResult connectAll() {
        var tools = new ArrayList<Tool>();
        var servers = new ArrayList<ServerInfo>();
        var errors = new ArrayList<String>();
        //遍历所有配置建立连接
        for (var entry : configs.entrySet()) {
            String name = entry.getKey();
            var cfg = entry.getValue();

            try {
                var client = createClient(cfg);
                //握手
                client.initialize();
                //存放的是成功连接的client
                clients.put(name, client);

                String instructions = client.getServerInstructions();
                servers.add(new ServerInfo(name, instructions != null ? instructions : ""));

                var result = client.listTools();
                if (result != null && result.tools() != null) {
                    for (var sdkTool : result.tools()) {
                        tools.add(new McpToolWrapper(name, sdkTool, client));
                    }
                }
            } catch (Exception e) {
                errors.add("MCP server '" + name + "': " + e.getMessage());
            }
        }

        return new ConnectResult(List.copyOf(tools), List.copyOf(servers), List.copyOf(errors));
    }

    public List<String> registerAllTools(ToolRegistry registry) {
        var result = connectAll();
        for (var t : result.tools()) registry.register(t);
        return result.errors();
    }

    public void shutdown() {
        for (var client : clients.values()) {
            try { client.closeGracefully(); } catch (Exception ignored) {}
        }
        clients.clear();
    }

    private McpSyncClient createClient(McpServerConfig cfg) {
        McpClientTransport transport;
        //Stdio分支
        if (cfg.getCommand() != null && !cfg.getCommand().isBlank()) {
            //组装ServerParameters（args、env，env值经resolveEnvVars()展开）
            var paramsBuilder = ServerParameters.builder(windowsSafe(cfg.getCommand()));
            if (cfg.getArgs() != null) {
                paramsBuilder.args(cfg.getArgs());
            }
            if (cfg.getEnv() != null) {
                var resolvedEnv = new HashMap<String, String>();
                for (var e : cfg.getEnv().entrySet()) {
                    resolvedEnv.put(e.getKey(), resolveEnvVars(e.getValue()));
                }
                paramsBuilder.env(resolvedEnv);
            }
            transport = new StdioClientTransport(paramsBuilder.build(), McpJsonDefaults.getMapper());
        }
        //Streamable Http分支
        else if (cfg.getUrl() != null && !cfg.getUrl().isBlank()) {
            var httpBuilder = HttpClientStreamableHttpTransport.builder(cfg.getUrl());
            if (cfg.getHeaders() != null && !cfg.getHeaders().isEmpty()) {
                httpBuilder.httpRequestCustomizer((rb, method, endpoint, body, ctx) -> {
                    for (var e : cfg.getHeaders().entrySet()) {
                        rb.header(e.getKey(), resolveEnvVars(e.getValue()));
                    }
                });
            }
            transport = httpBuilder.build();
        } else {
            throw new IllegalArgumentException("Neither command nor url configured");
        }

        return McpClient.sync(transport)
                .clientInfo(McpSchema.Implementation.builder("devecode", "0.1.0").build())
                .requestTimeout(Duration.ofSeconds(60))
                .build();
    }

    private static final Set<String> WIN_CMD_SUFFIXED = Set.of(
            "npx", "npm", "node", "uvx", "uv", "pnpm", "yarn", "bunx");

    /**
     * Windows上npx/npm/uvx这类命令实际是.cmd 脚本，Java的ProcessBuilder直接启动会失败，所以补.cmd后缀。
     * @param command cmd
     * @return not windows:command,windows:command+.cmd
     */
    public static String windowsSafe(String command) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) return command;
        String base = command.toLowerCase();
        if (WIN_CMD_SUFFIXED.contains(base)) return command + ".cmd";
        return command;
    }

    /**
     * 把非字母数字下划线的字符替换成_。server名叫my-server、工具名叫search.web 时，保证最终名mcp__my_server__search_web合法。
     * @param name 待替换字符串
     * @return 替换后的字符串
     */
    public static String sanitizeName(String name) {
        return NON_ALNUM.matcher(name).replaceAll("_");
    }

    /**
     * 把${HOME}、${API_KEY}替换成真实环境变量值；找不到则原样保留占位符（宁可让 server 报错，也不悄悄替换成空串）。
     * @param value 待替换字符串
     * @return 替换后的字符串
     */
    public static String resolveEnvVars(String value) {
        if (value == null) return null;
        return ENV_VAR.matcher(value).replaceAll(m -> {
            String env = System.getenv(m.group(1));
            return env != null ? env : m.group(0);
        });
    }

    // 将Mcp tool转化为本地tool

    private static class McpToolWrapper implements Tool {
        private final String serverName;
        private final McpSchema.Tool sdkTool;
        private final McpSyncClient client;

        McpToolWrapper(String serverName, McpSchema.Tool sdkTool, McpSyncClient client) {
            this.serverName = serverName;
            this.sdkTool = sdkTool;
            this.client = client;
        }

        @Override
        public String name() {
            return "mcp__" + sanitizeName(serverName) + "__" + sanitizeName(sdkTool.name());
        }

        @Override
        public String description() {
            return sdkTool.description() != null ? sdkTool.description() : "";
        }

        @Override
        public ToolCategory category() { return ToolCategory.COMMAND; }
        @Override
        //mcp工具默认延迟加载，需通过ToolSearch搜索后暴露
        public boolean shouldDefer() { return true; }
        //把SDK的inputSchema（JSON Schema）转成项目统一的 name/description/input_schema结构;server没给schema时兜底一个空object schema
        @Override
        public Map<String, Object> schema() {
            var input = new LinkedHashMap<String, Object>();
            var jsonSchema = sdkTool.inputSchema();
            if (jsonSchema != null && !jsonSchema.isEmpty()) {
                input.putAll(jsonSchema);
            } else {
                input.put("type", "object");
                input.put("properties", Map.of());
            }
            return Map.of("name", name(), "description", description(), "input_schema", input);
        }

        @Override
        public ToolResult execute(Map<String, Object> args) {
            try {
                var request = McpSchema.CallToolRequest.builder(sdkTool.name())
                        .arguments(args != null ? args : Map.of())
                        .build();
                var result = client.callTool(request);
                String text = extractTextContent(result);
                boolean isError = result.isError() != null && result.isError();
                return isError ? ToolResult.error(text) : ToolResult.success(text);
            } catch (Exception e) {
                return ToolResult.error("MCP tool call failed: " + e.getMessage());
            }
        }
    }

    /**
     * 提取result中的text文本
     * @param result call result
     * @return (no output) || call text
     */
    private static String extractTextContent(McpSchema.CallToolResult result) {
        if (result.content() == null || result.content().isEmpty()) return "(no output)";
        var sb = new StringBuilder();
        for (var content : result.content()) {
            if (content instanceof McpSchema.TextContent tc) {
                if (!sb.isEmpty()) sb.append("\n");
                sb.append(tc.text());
            }
        }
        return sb.isEmpty() ? "(no output)" : sb.toString();
    }
}
