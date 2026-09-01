package com.agent.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

import java.io.IOException;

/**
 * 常驻运行的测试 MCP server（Streamable HTTP 模式）。
 *
 * 与 stdio 模式（{@link TestMcpServer}）的区别：server 独立进程常驻监听端口，
 * coding agent（DeveCode）启动后通过 HTTP 连接，退出时只断开连接、不影响 server。
 *
 * 启动（一直运行直到 Ctrl+C）：
 * <pre>mvn -q test-compile exec:java -Dexec.mainClass=com.agent.mcp.TestHttpMcpServer -Dexec.classpathScope=test</pre>
 *
 * providers.yaml 对应配置（MCP 客户端默认 endpoint 为 /mcp）：
 * <pre>
 * mcp_servers:
 *   test-server:
 *     url: "http://localhost:3001"
 * </pre>
 */
public class TestHttpMcpServer {

    static final int PORT = 3001;
    static final String MCP_ENDPOINT = "/mcp";

    public static void main(String[] args) throws Exception {
        // Streamable HTTP transport：servlet 实现，挂在 MCP_ENDPOINT 路径上
        var transportProvider = HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(McpJsonDefaults.getMapper())
                .mcpEndpoint(MCP_ENDPOINT)
                .build();

        McpSyncServer mcpServer = McpServer.sync(transportProvider)
                .serverInfo("test-server", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .instructions(TestMcpServer.INSTRUCTIONS)
                .tools(TestMcpServer.tools())
                .build();

        // Jetty 嵌入式容器：context 根路径 /，servlet 挂在 MCP_ENDPOINT
        Server jetty = new Server(PORT);
        var context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        context.setContextPath("/");
        // Streamable HTTP 传输层要求 servlet 支持异步（SSE 长连接），缺省 false 会 500
        var servletHolder = new ServletHolder(transportProvider);
        servletHolder.setAsyncSupported(true);
        context.addServlet(servletHolder, MCP_ENDPOINT);
        // 健康检查：GET / → 200，用于确认 server 存活
        context.addServlet(new ServletHolder(new jakarta.servlet.http.HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                resp.setContentType("text/plain; charset=utf-8");
                resp.getWriter().write("test-mcp-server is running on " + MCP_ENDPOINT + "\n");
            }
        }), "/");
        jetty.setHandler(context);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { jetty.stop(); } catch (Exception ignored) {}
            mcpServer.closeGracefully();
        }));

        jetty.start();
        System.out.println("[test-http-server] listening on http://localhost:" + PORT + MCP_ENDPOINT);
        System.out.println("[test-http-server] health check: http://localhost:" + PORT + "/");
        System.out.println("[test-http-server] tools: echo / add / divide / get_current_time");
        jetty.join(); // 常驻运行
    }
}
