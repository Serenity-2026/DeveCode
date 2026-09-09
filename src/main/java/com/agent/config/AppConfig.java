
package com.agent.config;

import java.util.List;
/*
* SnakeYAML用Constructor(AppConfig.class)绑定时，靠无参构造+setter（或字段）灌值，JavaBean对这类反序列化框架最友好。
* */
public class AppConfig {

    private List<ProviderConfig> providers;
    /**@PermissionMode*/
    private String permissionMode;

    private List<McpServerConfig> mcpServers;
    private List<HookConfig> hooks;

    // 沙箱配置（嵌套对象，对应YAML中的 sandbox: 节点）
    private SandboxYamlConfig sandbox;
    // 协作模式开关,为true时程序启用协调者/多Agent协作能力
    private boolean enableCoordinatorMode;

    public List<ProviderConfig> getProviders() { return providers; }

    public void setProviders(List<ProviderConfig> providers) { this.providers = providers; }

    public String getPermissionMode() { return permissionMode; }

    public void setPermissionMode(String permissionMode) { this.permissionMode = permissionMode; }

    public List<McpServerConfig> getMcpServers() { return mcpServers; }
    public void setMcpServers(List<McpServerConfig> mcpServers) { this.mcpServers = mcpServers; }

    public List<HookConfig> getHooks() { return hooks; }
    public void setHooks(List<HookConfig> hooks) { this.hooks = hooks; }

    public SandboxYamlConfig getSandbox() { return sandbox; }
    public void setSandbox(SandboxYamlConfig sandbox) { this.sandbox = sandbox; }

    public boolean isSandboxEnabled() { return sandbox != null && sandbox.isEnabled(); }
    public boolean isSandboxAutoAllow() { return sandbox == null || sandbox.isAutoAllow(); }
    public boolean isSandboxNetworkEnabled() { return sandbox != null && sandbox.isNetworkEnabled(); }

    public boolean isEnableCoordinatorMode() { return enableCoordinatorMode; }
    public void setEnableCoordinatorMode(boolean enableCoordinatorMode) { this.enableCoordinatorMode = enableCoordinatorMode; }
}
