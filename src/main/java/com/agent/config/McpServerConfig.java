package com.agent.config;

import java.util.List;
import java.util.Map;

/**
 * mcp_servers:
 *   # stdio：有 command 字段 → 启动子进程，走管道
 *   GitHub:
 *     command: "npx"
 *     args: ["-y", "@modelcontextprotocol/server-github"]
 *     env:
 *       GITHUB_TOKEN: "${GITHUB_TOKEN}"
 *   # Streamable HTTP：有 url 字段 → 发 HTTP 请求
 *   remote-tool:
 *     url: "https://api.example.com/mcp"
 *     headers:
 *       Authorization: "Bearer ${API_TOKEN}"
 */
public class McpServerConfig {

    private String name;
    private String command;
    private List<String> args;
    private String url;
    private Map<String, String> headers;
    private Map<String, String> env;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCommand() { return command; }

    public void setCommand(String command) { this.command = command; }

    public List<String> getArgs() { return args; }
    public void setArgs(List<String> args) { this.args = args; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public Map<String, String> getHeaders() { return headers; }

    public void setHeaders(Map<String, String> headers) { this.headers = headers; }

    public Map<String, String> getEnv() { return env; }
    public void setEnv(Map<String, String> env) { this.env = env; }
}
