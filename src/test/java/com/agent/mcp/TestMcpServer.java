package com.agent.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 本地 stdio 测试 MCP server，用于验证 DeveCode 的 MCP 客户端集成（McpManager）。
 *
 * 通过 McpManager 以如下方式启动：
 * <pre>
 * mcp_servers:
 *   test-server:
 *     command: "mvn"
 *     args: ["-q", "test-compile", "exec:java",
 *            "-Dexec.mainClass=com.agent.mcp.TestMcpServer",
 *            "-Dexec.classpathScope=test"]
 * </pre>
 *
 * 提供的测试工具（注册后名称为 mcp__test_server__&lt;tool&gt;）：
 * <ul>
 *   <li>echo             — 回显文本（验证基本调用 + 文本提取）</li>
 *   <li>add              — 两数相加（验证数值参数解析）</li>
 *   <li>divide           — 两数相除，除零返回 isError（验证错误路径）</li>
 *   <li>get_current_time — 返回当前时间（验证无参数工具）</li>
 * </ul>
 *
 * 注意：stdout 是 MCP 协议通道，诊断信息只能输出到 stderr。
 */
public class TestMcpServer {

    /** server 自述 instructions，握手时上报给客户端（会被拼进 system prompt）。 */
    static final String INSTRUCTIONS = """
            This is a local test MCP server for verifying DeveCode's MCP client \
            integration (connect, listTools, callTool and graceful shutdown).""";

    /** 测试工具集：echo / add / divide / get_current_time，stdio 与 HTTP 两种入口共用。 */
    static List<McpServerFeatures.SyncToolSpecification> tools() {
        return List.of(echoTool(), addTool(), divideTool(), currentTimeTool());
    }

    public static void main(String[] args) throws InterruptedException {
        var transportProvider = new StdioServerTransportProvider(McpJsonDefaults.getMapper());

        McpSyncServer server = McpServer.sync(transportProvider)
                .serverInfo("test-server", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .instructions(INSTRUCTIONS)
                .tools(tools())
                .build();

        Runtime.getRuntime().addShutdownHook(new Thread(server::closeGracefully));

        // 主线程挂起，server 在传输层线程上处理 stdio 请求；客户端断开/进程销毁时退出
        System.err.println("[test-server] started, waiting for stdio requests...");
        synchronized (TestMcpServer.class) {
            TestMcpServer.class.wait();
        }
    }

    // ── echo：回显文本 ──────────────────────────────────────────────

    private static McpServerFeatures.SyncToolSpecification echoTool() {
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder("echo")
                        .description("Echo the given text back to the caller.")
                        .inputSchema(Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "text", Map.of(
                                                "type", "string",
                                                "description", "Text to echo back")),
                                "required", List.of("text")))
                        .build())
                .callHandler((exchange, request) -> {
                    Object text = request.arguments().get("text");
                    return McpSchema.CallToolResult.builder()
                            .addTextContent("echo: " + text)
                            .build();
                })
                .build();
    }

    // ── add：两数相加（数值参数）────────────────────────────────────

    private static McpServerFeatures.SyncToolSpecification addTool() {
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder("add")
                        .description("Add two numbers and return the sum.")
                        .inputSchema(Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "a", Map.of("type", "number"),
                                        "b", Map.of("type", "number")),
                                "required", List.of("a", "b")))
                        .build())
                .callHandler((exchange, request) -> {
                    double a = toDouble(request.arguments().get("a"));
                    double b = toDouble(request.arguments().get("b"));
                    double sum = a + b;
                    // 整数结果去掉小数点，避免 "3.0" 这种输出干扰断言
                    String out = (sum == Math.floor(sum) && !Double.isInfinite(sum))
                            ? String.valueOf((long) sum)
                            : String.valueOf(sum);
                    return McpSchema.CallToolResult.builder()
                            .addTextContent(out)
                            .build();
                })
                .build();
    }

    // ── divide：两数相除，除零走 isError 错误路径 ────────────────────

    private static McpServerFeatures.SyncToolSpecification divideTool() {
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder("divide")
                        .description("Divide a by b. Returns an error result when b is zero.")
                        .inputSchema(Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "a", Map.of("type", "number"),
                                        "b", Map.of("type", "number")),
                                "required", List.of("a", "b")))
                        .build())
                .callHandler((exchange, request) -> {
                    double a = toDouble(request.arguments().get("a"));
                    double b = toDouble(request.arguments().get("b"));
                    if (b == 0) {
                        return McpSchema.CallToolResult.builder()
                                .addTextContent("Division by zero is not allowed")
                                .isError(true)
                                .build();
                    }
                    return McpSchema.CallToolResult.builder()
                            .addTextContent(String.valueOf(a / b))
                            .build();
                })
                .build();
    }

    // ── get_current_time：无参数工具 ────────────────────────────────

    private static McpServerFeatures.SyncToolSpecification currentTimeTool() {
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder("get_current_time")
                        .description("Get the current local date and time (no arguments).")
                        .inputSchema(Map.of(
                                "type", "object",
                                "properties", Map.of()))
                        .build())
                .callHandler((exchange, request) ->
                        McpSchema.CallToolResult.builder()
                                .addTextContent(LocalDateTime.now()
                                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                                .build())
                .build();
    }

    private static double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        return Double.parseDouble(String.valueOf(v));
    }
}
