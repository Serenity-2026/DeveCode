
package com.agent.config;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.introspector.Property;
import org.yaml.snakeyaml.introspector.PropertyUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class ConfigLoader {

    private static final Set<String> VALID_PROTOCOLS = Set.of("anthropic", "openai", "openai-compat");

    public static AppConfig load(String path) throws ConfigException {
        if (path != null && !path.isEmpty()) {
            var cfg = loadSingleFile(Path.of(path));
            validate(cfg);
            return cfg;
        }

        var cwd = Path.of(System.getProperty("user.dir"));
        var home = Path.of(System.getProperty("user.home"));
        var candidates = List.of(
                home.resolve(".devecode").resolve("config.yaml"),
                cwd.resolve(".devecode").resolve("config.yaml"),
                cwd.resolve(".devecode").resolve("config.local.yaml")
        );

        AppConfig merged = null;
        for (var p : candidates) {
            if (!Files.exists(p)) continue;
            var layer = loadSingleFile(p);
            if (merged == null) {
                merged = layer;
            } else {
                mergeConfig(merged, layer);
            }
        }

        if (merged == null) {
            throw new ConfigException(
                    "No config file found. Expected .devecode/config.yaml in project or ~/.devecode/config.yaml");
        }

        validate(merged);
        return merged;
    }

    private static AppConfig loadSingleFile(Path configPath) throws ConfigException {
        if (!Files.exists(configPath)) {
            throw new ConfigException("Config file not found: " + configPath);
        }
        String content;
        try {
            content = Files.readString(configPath);
        } catch (IOException e) {
            throw new ConfigException("Failed to read config " + configPath + ": " + e.getMessage());
        }
        var loaderOptions = new LoaderOptions();
        var constructor = new Constructor(AppConfig.class, loaderOptions);
        constructor.setPropertyUtils(new SnakeCasePropertyUtils());
        var yaml = new Yaml(constructor);
        AppConfig cfg;
        try {
            cfg = yaml.load(content);
        } catch (Exception e) {
            throw new ConfigException("Failed to parse config " + configPath + ": " + e.getMessage());
        }
        if (cfg == null) cfg = new AppConfig();
        return cfg;
    }
    /*
    * 对不同的配置采取不同的合并处理:
    * Model Provider:整表替换项目级有providers就整体丢掉用户级的，不是按name合并
    * permissionMode:标量覆盖（last-wins）项目级非空就替换用户级
    * mcpServers:按 name 合并同名替换、新名追加，两者可以共存
    * hooks:追加合并用户级和项目级的 hooks 全部保留、拼接在一起
    * */
    private static void mergeConfig(AppConfig base, AppConfig override) {
        if (override.getProviders() != null && !override.getProviders().isEmpty()) {
            base.setProviders(override.getProviders());
        }
        if (override.getPermissionMode() != null && !override.getPermissionMode().isBlank()) {
            base.setPermissionMode(override.getPermissionMode());
        }
        if (override.getMcpServers() != null && !override.getMcpServers().isEmpty()) {
            var servers = base.getMcpServers() != null
                    ? new ArrayList<>(base.getMcpServers()) : new ArrayList<McpServerConfig>();
            var byName = new java.util.LinkedHashMap<String, Integer>();
            for (int i = 0; i < servers.size(); i++) {
                byName.put(servers.get(i).getName(), i);
            }
            for (var s : override.getMcpServers()) {
                var idx = byName.get(s.getName());
                if (idx != null) {
                    servers.set(idx, s);
                } else {
                    servers.add(s);
                    byName.put(s.getName(), servers.size() - 1);
                }
            }
            base.setMcpServers(servers);
        }
        if (override.getHooks() != null) {
            var hooks = base.getHooks() != null
                    ? new ArrayList<>(base.getHooks()) : new ArrayList<HookConfig>();
            hooks.addAll(override.getHooks());
            base.setHooks(hooks);
        }
        // 沙箱配置：override 层级可以覆盖
        if (override.getSandbox() != null) {
            if (base.getSandbox() == null) {
                base.setSandbox(override.getSandbox());
            } else {
                if (override.getSandbox().isEnabled()) base.getSandbox().setEnabled(true);
                if (override.getSandbox().isNetworkEnabled()) base.getSandbox().setNetworkEnabled(true);
            }
        }
        if (override.isEnableCoordinatorMode()) {
            base.setEnableCoordinatorMode(true);
        }
    }

    private static void validate(AppConfig cfg) throws ConfigException {
        for (int i = 0; i < cfg.getProviders().size(); i++) {
            var p = cfg.getProviders().get(i);
            var missing = new ArrayList<String>();

            if (isBlank(p.getName())) missing.add("name");
            if (isBlank(p.getProtocol())) missing.add("protocol");
            if (isBlank(p.getBaseUrl())) missing.add("base_url");
            if (isBlank(p.getModel())) missing.add("model");

            if (!missing.isEmpty()) {
                throw new ConfigException(
                        "Provider #%d: missing fields: %s".formatted(i + 1, String.join(", ", missing))
                );
            }

            if (!VALID_PROTOCOLS.contains(p.getProtocol())) {
                throw new ConfigException(
                        "Provider #%d: invalid protocol '%s', must be one of: anthropic, openai, openai-compat"
                                .formatted(i + 1, p.getProtocol())
                );
            }
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static class SnakeCasePropertyUtils extends PropertyUtils {

        @Override
        public Property getProperty(Class<?> type, String name) {
            String camel = snakeToCamel(name);
            return super.getProperty(type, camel);
        }

        private static String snakeToCamel(String snake) {
            if (!snake.contains("_")) return snake;
            var sb = new StringBuilder();
            boolean upper = false;
            for (char c : snake.toCharArray()) {
                if (c == '_') {
                    upper = true;
                } else {
                    sb.append(upper ? Character.toUpperCase(c) : c);
                    upper = false;
                }
            }
            return sb.toString();
        }
    }

    public static class ConfigException extends Exception {
        public ConfigException(String message) {
            super(message);
        }
    }
}
