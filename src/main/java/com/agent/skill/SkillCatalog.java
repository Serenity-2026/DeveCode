
package com.agent.skill;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.stream.Stream;

/**
 * Manages skill discovery, loading, and context generation for project.
 * <p>
 */
public class SkillCatalog {

    // ── Data types ──────────────────────────────────────────────────────

    public record SkillMeta(
            String name,
            String description,
            String whenToUse,
            List<String> tags,
            //执行模式"inline"或"fork"
            String mode,
            String model,
            //控制fork子Agent继承多少父对话上下文
            String forkContext
    ) {}

    public record Skill(SkillMeta meta, String promptBody, Path sourceDir) {
        public Skill withBody(String newBody) {
            return new Skill(meta, newBody, sourceDir);
        }
    }

    // ── State ───────────────────────────────────────────────────────────
    //(skill name,skill)
    private final Map<String, Skill> skills = new LinkedHashMap<>();
    //(skill name,skill source),用于查询生效的skill标签来源
    private final Map<String, String> sources = new LinkedHashMap<>();
    private final Map<String, FileTime> dirModTimes = new LinkedHashMap<>();

    private String workDir;

    // ── Public API ──────────────────────────────────────────────────────

    public String getWorkDir() {
        return workDir;
    }

    public void register(Skill skill, String source) {
        skills.put(skill.meta().name(), skill);
        sources.put(skill.meta().name(), source);
    }

    public void register(Skill skill) {
        register(skill, "");
    }

    public Map<String, Skill> getSkills() {
        return Collections.unmodifiableMap(skills);
    }

    public Optional<Skill> get(String name) {
        return Optional.ofNullable(skills.get(name));
    }

    /**
     * 根据skill name获取对应skill,每次都会从sourceDir重新加载skill
     */
    public Optional<Skill> getFull(String name) {
        Skill skill = skills.get(name);
        if (skill == null) {
            return Optional.empty();
        }
        //内存skill,不支持热更新
        if (skill.sourceDir() == null) {
            return Optional.of(skill);
        }
        try {
            Skill reloaded = loadSkill(skill.sourceDir());
            if (reloaded != null) {
                skills.put(name, reloaded);
                return Optional.of(reloaded);
            }
        } catch (IOException ignored) {
            // Keep the previously-cached body
        }
        return Optional.of(skill);
    }

    public List<SkillMeta> list() {
        return skills.values().stream().map(Skill::meta).toList();
    }

    public String source(String name) {
        return sources.getOrDefault(name, "");
    }

    // ── Three-tier catalog loading ─────────────────────────────────────

    /**
     * 为项目加载对应的SkillCatalog,分为"builtin"、"user"、"project"
     */
    public static SkillCatalog loadCatalog(String workDir) {
        SkillCatalog c = new SkillCatalog();
        c.workDir = workDir;

        // Tier 1: 从 resources/builtins/加载嵌入的内置skill（优先级最低）
        for (var skill : BuiltinSkills.load()) {
            c.register(skill, "builtin");
        }

        // Tier 2: user global
        String home = System.getProperty("user.home");
        if (home != null) {
            c.loadTier(Path.of(home, ".devecode", "skills"), "user");
        }

        // Tier 3: project
        c.loadTier(Path.of(workDir, ".devecode", "skills"), "project");

        c.snapshotDirModTimes();
        return c;
    }
    /*从磁盘重新加载skill*/
    public void reload(String workDir) {
        SkillCatalog fresh = loadCatalog(workDir);
        this.skills.clear();
        this.skills.putAll(fresh.skills);
        this.sources.clear();
        this.sources.putAll(fresh.sources);
        this.workDir = fresh.workDir;
        this.dirModTimes.clear();
        this.dirModTimes.putAll(fresh.dirModTimes);
    }

    /**
     * 检查 skill 目录的 modtime 是否自上次加载以来发生了变化。
     * modtime 变化表示有新增或删除的 skill（已有 skill 的文件编辑
     * 由 getFull 的按需重读处理）。
     */
    public boolean needsReload() {
        for (var entry : dirModTimes.entrySet()) {
            Path dir = Path.of(entry.getKey());
            FileTime recorded = entry.getValue();
            try {
                FileTime current = Files.getLastModifiedTime(dir);
                if (!current.equals(recorded)) {
                    return true;
                }
            } catch (IOException e) {
                if (recorded != null) {
                    return true;
                }
            }
        }
        for (String dirPath : skillDirPaths(workDir)) {
            if (!dirModTimes.containsKey(dirPath)) {
                try {
                    Files.getLastModifiedTime(Path.of(dirPath));
                    return true;
                } catch (IOException ignored) {
                }
            }
        }
        return false;
    }
    /*存储当前时刻skill dir的修改时间*/
    private void snapshotDirModTimes() {
        dirModTimes.clear();
        for (String dirPath : skillDirPaths(workDir)) {
            try {
                dirModTimes.put(dirPath, Files.getLastModifiedTime(Path.of(dirPath)));
            } catch (IOException e) {
                dirModTimes.put(dirPath, null);
            }
        }
    }

    /**
     * 返回skill可能存在的目录
     */
    private static List<String> skillDirPaths(String workDir) {
        List<String> dirs = new ArrayList<>();
        String home = System.getProperty("user.home");
        if (home != null) {
            dirs.add(Path.of(home, ".devecode", "skills").toString());
        }
        if (workDir != null) {
            dirs.add(Path.of(workDir, ".devecode", "skills").toString());
        }
        return dirs;
    }


    public void loadFromDirectory(Path dir) {
        loadTier(dir, dir.toString());
    }

    /**
     * 将dir下的skill加载为同等级
     * @param dir skill source dir
     * @param source "user"、"project"
     */
    private void loadTier(Path dir, String source) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> entries = Files.list(dir)) {
            entries.filter(Files::isDirectory).forEach(skillDir -> {
                try {
                    Skill skill = loadSkill(skillDir);
                    if (skill != null) {
                        register(skill, source);
                    }
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    // ── Context building ───────────────────────────────────────────────

    /**
     * 构建已激活的skill上下文
     */
    public String buildActiveContext(Set<String> activeSkillNames) {
        if (activeSkillNames == null || activeSkillNames.isEmpty()) {
            return "";
        }
        var sb = new StringBuilder();
        sb.append("## Active Skills\n\n");
        for (var name : activeSkillNames) {
            var skill = skills.get(name);
            if (skill != null) {
                sb.append("### ").append(name).append("\n");
                sb.append(skill.promptBody()).append("\n\n");
            }
        }
        return sb.toString();
    }

    // ── Loading internals ───────────────────────────────────────────────

    /**
     *  加载dir下的skill.yaml或者SKILL。md
     * @param dir source skill dir
     * @return SkiLL
     */
    private static Skill loadSkill(Path dir) throws IOException {
        // Strategy 1: skill.yaml + prompt.md
        Path metaPath = dir.resolve("skill.yaml");
        if (Files.isRegularFile(metaPath)) {
            return loadFromYamlAndPrompt(dir, metaPath);
        }

        // Strategy 2: SKILL.md with optional YAML front-matter
        Path mdPath = dir.resolve("SKILL.md");
        if (Files.isRegularFile(mdPath)) {
            String content = Files.readString(mdPath);
            return parseSkillMD(dir, content);
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    private static Skill loadFromYamlAndPrompt(Path dir, Path metaPath) throws IOException {
        String yamlText = Files.readString(metaPath);
        Yaml yaml = new Yaml();
        Map<String, Object> map = yaml.load(yamlText);
        if (map == null) {
            map = Map.of();
        }

        SkillMeta meta = metaFromMap(map, dir);

        String promptBody = "";
        Path promptPath = dir.resolve("prompt.md");
        if (Files.isRegularFile(promptPath)) {
            promptBody = Files.readString(promptPath);
        }

        return new Skill(meta, promptBody, dir);
    }

    @SuppressWarnings("unchecked")
    private static Skill parseSkillMD(Path dir, String content) {
        String body = content;
        Map<String, Object> frontMatter = Map.of();

        String trimmed = content.stripLeading();
        if (trimmed.startsWith("---")) {
            int firstSep = content.indexOf("---");
            int secondSep = content.indexOf("---", firstSep + 3);
            if (secondSep >= 0) {
                String yamlBlock = content.substring(firstSep + 3, secondSep);
                body = content.substring(secondSep + 3).strip();
                try {
                    Yaml yaml = new Yaml();
                    Map<String, Object> parsed = yaml.load(yamlBlock);
                    if (parsed != null) {
                        frontMatter = parsed;
                    }
                } catch (Exception ignored) {
                }
            }
        }

        SkillMeta meta = metaFromMap(frontMatter, dir);

        // Auto-generate description from first non-empty, non-heading line if absent
        String description = meta.description();
        if (description == null || description.isBlank()) {
            for (String line : body.split("\n")) {
                String stripped = line.strip();
                if (!stripped.isEmpty() && !stripped.startsWith("#")) {
                    description = stripped;
                    break;
                }
            }
            meta = new SkillMeta(meta.name(), description != null ? description : "",
                    meta.whenToUse(), meta.tags(),
                    meta.mode(), meta.model(), meta.forkContext());
        }

        return new Skill(meta, body, dir);
    }

    @SuppressWarnings("unchecked")
    private static SkillMeta metaFromMap(Map<String, Object> map, Path dir) {
        String name = stringVal(map, "name");
        if (name == null || name.isBlank()) {
            name = dir.getFileName().toString().toLowerCase().replace(' ', '-');
        }
        String description = stringVal(map, "description");
        String whenToUse = stringVal(map, "when_to_use");

        List<String> tags = List.of();
        Object rawTags = map.get("tags");
        if (rawTags instanceof List<?> list) {
            tags = list.stream().map(Object::toString).toList();
        }

        String mode = stringVal(map, "mode");
        if (mode == null || mode.isBlank()) {
            // Backward compat: context: "fork" treated same as mode: "fork"
            String ctx = stringVal(map, "context");
            if ("fork".equals(ctx)) {
                mode = "fork";
            } else {
                mode = "inline";
            }
        }

        String model = stringVal(map, "model");

        String forkContext = stringVal(map, "fork_context");
        if (forkContext == null || forkContext.isBlank()) {
            forkContext = "none";
        }

        return new SkillMeta(
                name,
                description != null ? description : "",
                whenToUse != null ? whenToUse : "",
                tags,
                mode,
                model != null ? model : "",
                forkContext
        );
    }

    private static String stringVal(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }
}
