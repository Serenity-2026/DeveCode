package com.agent.plan;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Manages plan files stored under {@code .devecode/plans/} in the working directory.
 * PlanFile是Plan Mode（计划模式）的文件层支撑。Agent进入只读模式后，只允许调研代码、不允许直接改文件，产出物是一份计划文档（markdown），
 * 写到工作目录的 .devecode/plans/ 下，用户审批后才切换回正常模式执行。
 */
public class PlanFile {

    private static final String PLANS_DIR = ".devecode/plans";
    //15个形容词词表，slug的第一段
    private static final String[] ADJECTIVES = {
            "bright", "calm", "bold", "swift", "quiet",
            "vivid", "clear", "keen", "warm", "cool",
            "sharp", "light", "deep", "pure", "soft",
    };
    //15个名词词表，slug 的第二段
    private static final String[] NOUNS = {
            "plan", "draft", "design", "sketch", "blueprint",
            "outline", "strategy", "approach", "scheme", "map",
            "vision", "path", "route", "guide", "frame",
    };

    private static String currentPlanPath;

    // ── Slug generation ─────────────────────────────────────────────────

    /**
     * 用形容词-名词-时间戳生成人类友好的 slug（如 bold-sketch-0515-1423.md），一眼可读、不冲突、不泄露内容。 如{@code bold-sketch-0515-1423}.
     */
    public static String generateSlug() {
        long nanos = System.nanoTime();
        int ai = (int) ((nanos / 1000) % ADJECTIVES.length);
        int ni = (int) ((nanos / 100) % NOUNS.length);
        if (ai < 0) ai += ADJECTIVES.length;
        if (ni < 0) ni += NOUNS.length;
        String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("MMdd-HHmm"));
        return ADJECTIVES[ai] + "-" + NOUNS[ni] + "-" + timestamp;
    }

    // ── Path management ─────────────────────────────────────────────────

    /**
     * 返回plan文件路径或者创建新plan_path
     * @param workDir 工作目录
     * @return Plan_Path绝对路径
     */
    public static String getOrCreatePlanPath(String workDir) {
        if (currentPlanPath != null) {
            return currentPlanPath;
        }
        Path dir = Path.of(workDir, PLANS_DIR);
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
            // best effort
        }
        String slug = generateSlug();
        currentPlanPath = dir.resolve(slug + ".md").toString();
        return currentPlanPath;
    }


    public static void setPlanFilePath(String path) {
        currentPlanPath = path;
    }

    public static void resetPlanPath() {
        currentPlanPath = null;
    }

    // ── Persistence ─────────────────────────────────────────────────────

    public static boolean planExists() {
        return currentPlanPath != null && Files.exists(Path.of(currentPlanPath));
    }

    /**
     * 返回plan_file内容
     * @return plan_file路径
     */
    public static String loadPlan() throws IOException {
        if (currentPlanPath == null) {
            return "";
        }
        Path path = Path.of(currentPlanPath);
        if (!Files.exists(path)) {
            return "";
        }
        return Files.readString(path);
    }

    public static void savePlan(String workDir, String content) throws IOException {
        String path = getOrCreatePlanPath(workDir);
        Path target = Path.of(path);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }

    // ── Utilities ───────────────────────────────────────────────────────

    /**
     * Returns {@code true} when {@code targetPath} refers to the same file
     * as {@code planPath} (after normalization) or when one is a suffix of
     * the other. This matches the Go helper
     * {@code IsPlanFilePath(targetPath, planPath)}.
     */
    public static boolean isPlanFilePath(String targetPath, String planPath) {
        if (planPath == null || planPath.isBlank()) {
            return false;
        }
        String cleanTarget = Path.of(targetPath).normalize().toString();
        String cleanPlan = Path.of(planPath).normalize().toString();
        return cleanTarget.equals(cleanPlan) || cleanTarget.endsWith(cleanPlan);
    }
}
