package com.agent.prompt;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * PromptSections只提供了“零件”,还需要一个角色决定零件如何变成最终的system prompt字符串
 */
public class PromptBuilder {

    // ── Inner types ─────────────────────────────────────────────────────
    /**
     * name：段落的标识符（"Identity"、"TextOutput"…），只用于调试和日志，从不进入最终 prompt
     * priority：排序依据，PromptSections 用的 0-70，这里给动态段落用 80/85/90
     * content：实际文本
     * */
    public record Section(String name, int priority, String content) {}
    /**
     *         String workDir,     // 工作目录 → 模型解析相对路径用
     *         String os,          // "windows"/"linux"/"macos"（已小写）
     *         String arch,        // CPU 架构 → 影响生成的命令
     *         String shell,       // 决定生成什么语法的 shell 命令
     *         boolean isGitRepo,  // 影响"能否直接改"的判断
     *         String gitBranch,   // 影响对未提交工作的态度
     *         String model,       // 模型名
     *         String date) {}     // "今天"的感知
     * */
    public record EnvironmentContext(
            String workDir,
            String os,
            String arch,
            String shell,
            boolean isGitRepo,
            String gitBranch,
            String model,
            String date) {}

    /**
     * 自定义的可选项
     */
    public record BuildOptions(
            String skillSection,
            String customInstructions,
            String memorySection) {}

    // ── Builder state ───────────────────────────────────────────────────

    private final List<Section> sections = new ArrayList<>();

    public PromptBuilder add(Section section) {
        sections.add(section);
        return this;
    }

    /**
     * 根据优先级构建prompt
     * @return prompt
     */
    public String build() {
        sections.sort(Comparator.comparingInt(Section::priority));

        var parts = new ArrayList<String>();
        for (Section s : sections) {
            String content = s.content() == null ? "" : s.content().strip();
            if (!content.isEmpty()) {
                parts.add(content);
            }
        }
        return String.join("\n\n", parts);
    }

    // ── Static convenience methods ──────────────────────────────────────

    /* 探测当前上下文 */
    public static EnvironmentContext detectEnvironment(String model) {
        String workDir = System.getProperty("user.dir");
        String osName = System.getProperty("os.name", "unknown").toLowerCase();
        String arch = System.getProperty("os.arch", "unknown");
        String shell = System.getenv("SHELL");
        if (shell == null || shell.isEmpty()) {
            shell = "bash";
        }

        boolean isGitRepo = false;
        String gitBranch = "";

        try {
            Process p = new ProcessBuilder("git", "-C", workDir, "rev-parse", "--is-inside-work-tree")
                    .redirectErrorStream(true)
                    .start();
            try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = reader.readLine();
                if ("true".equals(line != null ? line.strip() : "")) {
                    isGitRepo = true;
                }
            }
            p.waitFor();
        } catch (Exception ignored) {
            // not a git repo or git not available
        }

        if (isGitRepo) {
            try {
                Process p = new ProcessBuilder("git", "-C", workDir, "rev-parse", "--abbrev-ref", "HEAD")
                        .redirectErrorStream(true)
                        .start();
                try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line = reader.readLine();
                    if (line != null) {
                        gitBranch = line.strip();
                    }
                }
                p.waitFor();
            } catch (Exception ignored) {
                // branch detection failed
            }
        }

        String date = LocalDate.now().toString();
        return new EnvironmentContext(workDir, osName, arch, shell, isGitRepo, gitBranch, model, date);
    }

    /** Build a complete system prompt from the environment and options. */
    public static String buildSystemPrompt(EnvironmentContext env, BuildOptions options) {
        var builder = new PromptBuilder();

        builder.add(PromptSections.identitySection());
        builder.add(PromptSections.systemSection());
        builder.add(PromptSections.doingTasksSection());
        builder.add(PromptSections.executingActionsSection());
        builder.add(PromptSections.usingToolsSection());
        builder.add(PromptSections.toneStyleSection());
        builder.add(PromptSections.outputEfficiencySection());
        builder.add(PromptSections.environmentSection(env));

        if (options.skillSection() != null && !options.skillSection().isEmpty()) {
            builder.add(new Section("Skills", 90, options.skillSection()));
        }

        // 用户自定义指令（CLAUDE.md 等），优先级 80
        if (options.customInstructions() != null && !options.customInstructions().isEmpty()) {
            builder.add(new Section("CustomInstructions", 80, options.customInstructions()));
        }

        // 持久记忆区（自动提取的记忆），优先级 85
        if (options.memorySection() != null && !options.memorySection().isEmpty()) {
            builder.add(new Section("Memory", 85, options.memorySection()));
        }

        return builder.build();
    }
}
