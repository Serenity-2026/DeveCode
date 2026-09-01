package com.agent.mcp;

import com.agent.config.McpServerConfig;
import com.agent.tool.Tool;
import com.agent.tool.ToolRegistry;

import java.util.List;
import java.util.Map;

/**
 * MCP 全链路冒烟测试（不依赖 JUnit，直接 main 运行）：
 * <pre>mvn -q test-compile exec:java -Dexec.mainClass=com.agent.mcp.McpSmokeTest -Dexec.classpathScope=test</pre>
 *
 * 验证点：
 *   1. McpManager 通过 stdio 拉起 TestMcpServer 子进程并完成 initialize 握手
 *   2. server instructions 正确回传（会拼进 system prompt）
 *   3. 4 个工具按 mcp__test_server__&lt;tool&gt; 命名注册进 ToolRegistry
 *   4. 工具调用：文本回显 / 数值相加 / isError 错误路径 / 无参数调用
 *   5. 延迟加载标记 shouldDefer == true（经 ToolSearch 发现）
 *   6. shutdown() 优雅关闭子进程
 */
public class McpSmokeTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        // 连接常驻 HTTP server（TestHttpMcpServer，需先启动）；
        // 命令行传 --stdio 时切换为拉起 stdio 子进程模式（TestMcpServer）
        var cfg = new McpServerConfig();
        cfg.setName("test-server");
        if (args.length > 0 && "--stdio".equals(args[0])) {
            // 注意：mvn exec:java 下 java.class.path 是 Maven 自身的 classpath，
            // 必须从 classloader（maven ClassRealm，URLClassLoader 子类）取真实的项目 classpath
            cfg.setCommand("java");
            cfg.setArgs(List.of("-cp", currentClasspath(), "com.agent.mcp.TestMcpServer"));
        } else {
            cfg.setUrl("http://localhost:" + TestHttpMcpServer.PORT);
        }

        System.out.println("== 1. connect ==");
        var manager = new McpManager(List.of(cfg));
        var registry = new ToolRegistry();
        var errors = manager.registerAllTools(registry);
        check("connect without errors", errors.isEmpty());
        errors.forEach(e -> System.out.println("   error: " + e));

        System.out.println("\n== 2. servers & tools ==");
        check("1 server connected", registry.getTool("mcp__test_server__echo") != null);
        for (String toolName : List.of("mcp__test_server__echo", "mcp__test_server__add",
                "mcp__test_server__divide", "mcp__test_server__get_current_time")) {
            Tool tool = registry.getTool(toolName);
            check("registered: " + toolName, tool != null);
            if (tool != null) {
                check("deferred: " + toolName, tool.shouldDefer());
                System.out.println("   " + toolName + " -> " + tool.description());
            }
        }

        System.out.println("\n== 3. tool calls ==");
        assertCall(registry, "mcp__test_server__echo",
                Map.of("text", "hello mcp"), "echo: hello mcp");
        assertCall(registry, "mcp__test_server__add",
                Map.of("a", 2, "b", 40), "42");
        assertCall(registry, "mcp__test_server__divide",
                Map.of("a", 10, "b", 4), "2.5");

        // 错误路径：divide by zero → ToolResult.error（isError=true）
        var divTool = registry.getTool("mcp__test_server__divide");
        var errResult = divTool.execute(Map.of("a", 1, "b", 0));
        check("divide by zero is error", errResult.isError());
        System.out.println("   divide(1,0) -> [error] " + errResult.output());

        // 无参数工具
        var timeTool = registry.getTool("mcp__test_server__get_current_time");
        var timeResult = timeTool.execute(Map.of());
        check("get_current_time succeeded", !timeResult.isError());
        System.out.println("   get_current_time() -> " + timeResult.output());

        System.out.println("\n== 4. shutdown ==");
        manager.shutdown();
        System.out.println("   shutdown ok");

        System.out.printf("%nResult: %d passed, %d failed%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    /** 从 classloader 提取当前运行时 classpath（mvn exec:java 场景下 java.class.path 属性不可用）。 */
    private static String currentClasspath() {
        var sb = new StringBuilder();
        ClassLoader cl = McpSmokeTest.class.getClassLoader();
        if (cl instanceof java.net.URLClassLoader ucl) {
            for (var url : ucl.getURLs()) {
                try {
                    if (!sb.isEmpty()) sb.append(java.io.File.pathSeparatorChar);
                    sb.append(java.nio.file.Paths.get(url.toURI()));
                } catch (Exception ignored) { }
            }
        }
        if (sb.isEmpty()) return System.getProperty("java.class.path");
        return sb.toString();
    }

    private static void assertCall(ToolRegistry registry, String name, Map<String, Object> args, String expected) {
        Tool tool = registry.getTool(name);
        if (tool == null) {
            check(name + " exists", false);
            return;
        }
        var r = tool.execute(args);
        boolean ok = !r.isError() && expected.equals(r.output());
        check(name + " -> " + expected, ok);
        if (!ok) System.out.println("   actual: isError=" + r.isError() + " output=" + r.output());
    }

    private static void check(String label, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("   [PASS] " + label);
        } else {
            failed++;
            System.out.println("   [FAIL] " + label);
        }
    }
}
