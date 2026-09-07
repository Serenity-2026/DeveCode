package com.agent.skill;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;

/**
 * 从 GitHub 下载并原子安装 skill 到本地目录。
 * <p>
 * 核心流程：解析 URL → 通过 GitHub Contents API 递归下载 → staging 临时目录 → rename 到最终位置。
 * 失败时 staging 目录被清理，不会留下残缺文件。
 * <p>
 * 安全限制：单文件最大 1 MiB，总大小最大 8 MiB，最多 64 个文件，最深 4 层目录。
 */
public final class SkillInstaller {

    // ── 安全限制常量 ──────────────────────────────────────────────────
    private static final int MAX_FILE_SIZE = 1 << 20;       // 1 MiB
    private static final long MAX_TOTAL_SIZE = 8L << 20;    // 8 MiB
    private static final int MAX_FILE_COUNT = 64;
    private static final int MAX_RECURSION_DEPTH = 4;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);
    private static final String USER_AGENT = "devecode-skill-installer";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final HttpClient httpClient;
    private final String apiBase;

    public SkillInstaller() {
        this("https://api.github.com");
    }

    /** 支持测试时替换 API 地址。 */
    public SkillInstaller(String apiBase) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.apiBase = apiBase;
    }

    // ── URL 解析 ──────────────────────────────────────────────────────

    /**
     * 将用户提供的 URL 解析为 {@link SkillSource}。
     * <p>
     * 支持四种格式：
     * <ol>
     *   <li>{@code https://github.com/<owner>/<repo>} 或 {@code .../<repo>.git}（仓库根即 skill 根）</li>
     *   <li>{@code https://www.skills.sh/<owner>/<repo>/<skill-name>}</li>
     *   <li>{@code https://github.com/<owner>/<repo>/tree/<ref>/<subpath>}</li>
     *   <li>{@code https://raw.githubusercontent.com/<owner>/<repo>/<ref>/<subpath>/SKILL.md}</li>
     * </ol>
     * <p>
     * ref 为 null 表示 URL 未携带分支信息（裸仓库 / skills.sh），
     * 由 {@link #install} 查询仓库默认分支补全。
     */
    public static SkillSource parseSkillURL(String raw) {
        raw = raw.strip();
        URI uri = URI.create(raw);
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equals("http") && !scheme.equals("https"))) {
            throw new IllegalArgumentException("only http(s) URLs are supported");
        }

        String host = uri.getHost();
        // 去掉首尾 / 后按 / 拆分
        String path = uri.getPath();
        if (path == null) path = "";
        String trimmed = path.replaceAll("^/+|/+$", "");
        String[] parts = trimmed.isEmpty() ? new String[0] : trimmed.split("/");

        return switch (host) {
            case "github.com" -> {
                // 格式1: /<owner>/<repo>(.git) — 仓库根即 skill 根，分支待查默认
                String repo = parts.length == 2 && parts[1].endsWith(".git")
                        ? parts[1].substring(0, parts[1].length() - 4)
                        : null;
                if (repo != null || parts.length == 2) {
                    String r = repo != null ? repo : parts[1];
                    yield new SkillSource(parts[0], r, null, "",
                            normalizeName(r), raw);
                }
                // 格式2: /<owner>/<repo>/tree/<ref>/<...subpath>
                if (parts.length < 5 || !"tree".equals(parts[2])) {
                    throw new IllegalArgumentException(
                            "github.com URL must be /<owner>/<repo>[.git] or /<owner>/<repo>/tree/<ref>/<subpath>");
                }
                String sub = String.join("/", java.util.Arrays.copyOfRange(parts, 4, parts.length));
                yield new SkillSource(parts[0], parts[1], parts[3], sub,
                        normalizeName(parts[parts.length - 1]), raw);
            }
            case "www.skills.sh", "skills.sh" -> {
                // /<owner>/<repo>/<skill-name>
                if (parts.length < 3) {
                    throw new IllegalArgumentException(
                            "skills.sh URL must be /<owner>/<repo>/<skill-name>");
                }
                String subpath = "skills/" + String.join("/", java.util.Arrays.copyOfRange(parts, 2, parts.length));
                // 分支未在 URL 中给出 → 留空，install 时查默认分支
                yield new SkillSource(parts[0], parts[1], null, subpath,
                        normalizeName(parts[parts.length - 1]), raw);
            }
            case "raw.githubusercontent.com" -> {
                // /<owner>/<repo>/<ref>/<...subpath>/SKILL.md
                if (parts.length < 4) {
                    throw new IllegalArgumentException("raw.githubusercontent.com URL too short");
                }
                String[] subParts = java.util.Arrays.copyOfRange(parts, 3, parts.length);
                // 去掉尾部文件名（含 . 的视为文件）
                if (subParts.length > 0 && subParts[subParts.length - 1].contains(".")) {
                    subParts = java.util.Arrays.copyOfRange(subParts, 0, subParts.length - 1);
                }
                if (subParts.length == 0) {
                    throw new IllegalArgumentException("raw URL missing skill subpath");
                }
                yield new SkillSource(parts[0], parts[1], parts[2],
                        String.join("/", subParts),
                        normalizeName(subParts[subParts.length - 1]), raw);
            }
            default -> throw new IllegalArgumentException(
                    "unsupported host \"%s\" (try github.com or skills.sh)".formatted(host));
        };
    }

    /** skill 目录名归一化：小写化（skill 真实名称由 SKILL.md front-matter 决定，目录名只是安装位置）。 */
    private static String normalizeName(String name) {
        return name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
    }

    // ── 安装主流程 ────────────────────────────────────────────────────

    /**
     * 将 src 描述的 skill 下载并安装到 {@code installRoot/<name>/}。
     * <p>
     * 写入过程是原子的：先 stage 到同级临时目录，成功后 rename。
     */
    public SkillInstallReport install(SkillSource src, String installRoot) throws IOException {
        if (src == null) {
            throw new IllegalArgumentException("nil source");
        }
        validateSkillName(src.name());

        // URL 未携带分支信息（裸仓库 / skills.sh）→ 查询仓库默认分支
        SkillSource resolved = src;
        if (src.ref() == null || src.ref().isEmpty()) {
            String defaultBranch = fetchDefaultBranch(src.owner(), src.repo());
            resolved = new SkillSource(src.owner(), src.repo(), defaultBranch,
                    src.subpath(), src.name(), src.original());
        }

        Path root = Path.of(installRoot);
        Files.createDirectories(root);

        // staging 目录与最终目录同级，保证 rename 在同一文件系统内
        Path staging = Files.createTempDirectory(root, ".install-" + src.name() + "-");
        try {
            // 计数器用可变 holder（record 不可变，这里用数组绕开）
            int[] fileCount = {0};
            int[] skippedFiles = {0};
            long[] totalBytes = {0L};

            walkAndDownload(resolved, resolved.subpath(), staging, fileCount, skippedFiles, totalBytes, 0);

            if (!hasSkillManifest(staging)) {
                throw new IOException("downloaded tree missing SKILL.md or skill.yaml — not a skill?");
            }

            // 覆盖已有安装
            Path finalDir = root.resolve(src.name());
            if (Files.exists(finalDir)) {
                deleteRecursively(finalDir);
            }
            Files.move(staging, finalDir);

            return new SkillInstallReport(src.name(), finalDir.toString(), fileCount[0], skippedFiles[0], totalBytes[0]);

        } catch (Exception e) {
            // 失败时清理 staging
            deleteRecursively(staging);
            if (e instanceof IOException io) throw io;
            throw new IOException(e);
        }
    }

    /**
     * 返回用户全局 skill 安装目录 ~/.devecode/skills（SkillCatalog 的 user 层加载路径），不存在则创建。
     */
    public static String userSkillsRoot() throws IOException {
        Path root = Path.of(System.getProperty("user.home"), ".devecode", "skills");
        Files.createDirectories(root);
        return root.toString();
    }

    // ── GitHub Contents API 交互 ─────────────────────────────────────

    /** GET /repos/{owner}/{repo} 的最小响应（仅取默认分支）。 */
    private record RepoInfo(String default_branch) {}

    /** 查询仓库默认分支（裸仓库 / skills.sh URL 未携带 ref 时使用）。 */
    private String fetchDefaultBranch(String owner, String repo) throws IOException {
        String endpoint = "%s/repos/%s/%s".formatted(apiBase, owner, repo);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", USER_AGENT)
                .timeout(HTTP_TIMEOUT)
                .GET()
                .build();

        HttpResponse<String> resp;
        try {
            resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("HTTP request interrupted", e);
        }
        if (resp.statusCode() == 404) {
            throw new IOException("repository not found: %s/%s".formatted(owner, repo));
        }
        if (resp.statusCode() != 200) {
            throw new IOException("github API returned %d for %s".formatted(resp.statusCode(), endpoint));
        }
        try {
            RepoInfo info = MAPPER.readValue(resp.body(), RepoInfo.class);
            if (info.default_branch() == null || info.default_branch().isEmpty()) {
                throw new IOException("repo %s/%s has no default branch".formatted(owner, repo));
            }
            return info.default_branch();
        } catch (IOException e) {
            throw new IOException("failed to parse repo info for %s/%s".formatted(owner, repo), e);
        }
    }

    /**
     * GitHub Contents API 返回的单条条目（只保留需要的字段）。
     */
    private record ContentEntry(
            String name,
            String path,
            String type,        // "file" | "dir" | "symlink" | "submodule"
            String download_url,
            String content,
            String encoding,
            int size
    ) {}

    /**
     * 列出 GitHub 仓库指定路径下的条目。
     * 目录返回数组，单文件返回包装成单元素列表。
     * subpath 为空串时表示仓库根目录。
     */
    private List<ContentEntry> listContents(SkillSource src, String subpath) throws IOException {
        String body = fetchContentsJson(src, subpath);
        // 目录返回 JSON 数组，单文件返回 JSON 对象
        if (body.strip().startsWith("[")) {
            return MAPPER.readValue(body, new TypeReference<>() {});
        }
        return List.of(MAPPER.readValue(body, ContentEntry.class));
    }

    /** GET 单文件 Contents API：返回带 base64 content 的条目。 */
    private ContentEntry listContentsSingle(SkillSource src, String path) throws IOException {
        return MAPPER.readValue(fetchContentsJson(src, path), ContentEntry.class);
    }

    /** 调用 Contents API 并返回原始 JSON body（含 403 限流与状态码校验）。 */
    private String fetchContentsJson(SkillSource src, String subpath) throws IOException {
        // 逐段 URL 编码（空格等特殊字符），空 subpath → 仓库根
        String encodedPath = encodePathSegments(subpath);
        String endpoint = "%s/repos/%s/%s/contents%s?ref=%s".formatted(
                apiBase, src.owner(), src.repo(),
                encodedPath.isEmpty() ? "" : "/" + encodedPath,
                URLEncoder.encode(src.ref(), StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", USER_AGENT)
                .timeout(HTTP_TIMEOUT)
                .GET()
                .build();

        HttpResponse<String> resp;
        try {
            resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("HTTP request interrupted", e);
        }

        if (resp.statusCode() == 403) {
            String body = resp.body();
            if (body != null && body.length() > 512) body = body.substring(0, 512);
            throw new IOException("github API forbidden (rate-limited?): " + (body != null ? body.strip() : ""));
        }
        if (resp.statusCode() != 200) {
            throw new IOException("github API returned %d for %s".formatted(resp.statusCode(), endpoint));
        }

        String body = resp.body();
        if (body == null || body.isBlank()) {
            throw new IOException("github returned empty body");
        }
        return body;
    }

    /**
     * 下载单个文件的内容。
     * 优先级：内联 base64 → 单文件 Contents API（api.github.com）→ download_url（raw.githubusercontent.com）。
     * 目录列表接口的条目不含 content 字段，因此多数情况走单文件 API 拿 base64，
     * 仅当 API 失败才回退 raw 直链（部分网络环境不可达）。
     */
    private byte[] fetchBlob(SkillSource src, ContentEntry entry) throws IOException {
        if (entry.size() > MAX_FILE_SIZE) {
            throw new IOException("file %s too large: %d bytes (max %d)".formatted(
                    entry.path(), entry.size(), MAX_FILE_SIZE));
        }

        // 内联 base64（单文件接口直接返回内容时）
        if ("base64".equals(entry.encoding()) && entry.content() != null && !entry.content().isEmpty()) {
            String clean = entry.content().replace("\n", "");
            return Base64.getDecoder().decode(clean);
        }

        // 单文件 Contents API（与目录列表同一 host，网络可达性一致）
        try {
            return fetchViaContentsApi(src, entry.path());
        } catch (IOException apiError) {
            // 回退到 download_url
            if (entry.download_url() == null || entry.download_url().isEmpty()) {
                throw apiError;   // 没有回退渠道，抛原始 API 错误（信息更准确）
            }
            return fetchViaDownloadUrl(entry);
        }
    }

    /** GET /repos/{owner}/{repo}/contents/{path} 单文件接口：返回 base64 内容并解码。 */
    private byte[] fetchViaContentsApi(SkillSource src, String path) throws IOException {
        ContentEntry single = listContentsSingle(src, path);
        if (!"base64".equals(single.encoding()) || single.content() == null || single.content().isEmpty()) {
            throw new IOException("contents API returned no inline content for " + path);
        }
        String clean = single.content().replace("\n", "");
        return Base64.getDecoder().decode(clean);
    }

    /** 从 download_url（raw.githubusercontent.com 直链）下载文件。 */
    private byte[] fetchViaDownloadUrl(ContentEntry entry) throws IOException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(entry.download_url()))
                .header("User-Agent", USER_AGENT)
                .timeout(HTTP_TIMEOUT)
                .GET()
                .build();

        HttpResponse<byte[]> resp;
        try {
            resp = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("HTTP request interrupted", e);
        }

        if (resp.statusCode() != 200) {
            throw new IOException("download %s: status %d".formatted(entry.download_url(), resp.statusCode()));
        }
        byte[] body = resp.body();
        // 声明的 size 可能与实际不符，以实际 body 大小为准再校验一次
        if (body != null && body.length > MAX_FILE_SIZE) {
            throw new IOException("file %s too large: %d bytes (max %d)".formatted(
                    entry.path(), body.length, MAX_FILE_SIZE));
        }
        return body == null ? new byte[0] : body;
    }

    /** 对 subpath 逐段做 URL 编码后用 / 重新连接（保留 / 分隔语义）。 */
    private static String encodePathSegments(String subpath) {
        if (subpath == null || subpath.isEmpty()) return "";
        var sb = new StringBuilder();
        for (String seg : subpath.split("/")) {
            if (seg.isEmpty()) continue;
            if (sb.length() > 0) sb.append('/');
            sb.append(URLEncoder.encode(seg, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return sb.toString();
    }

    // ── 递归下载 ──────────────────────────────────────────────────────

    /**
     * 递归遍历 GitHub 目录树，下载所有文件到 localDir，
     * 同时检查安全限制（文件数、总大小、深度）。
     */
    private void walkAndDownload(
            SkillSource src, String subpath, Path localDir,
            int[] fileCount, int[] skippedFiles, long[] totalBytes, int depth) throws IOException {

        if (depth > MAX_RECURSION_DEPTH) {
            throw new IOException("install tree too deep (>%d levels)".formatted(MAX_RECURSION_DEPTH));
        }

        List<ContentEntry> entries = listContents(src, subpath);
        for (ContentEntry entry : entries) {
            if (fileCount[0] >= MAX_FILE_COUNT) {
                throw new IOException("install file count limit (%d) reached".formatted(MAX_FILE_COUNT));
            }

            // 防止路径穿越
            if (entry.name().contains("..") || entry.name().contains("/") || entry.name().contains("\\")) {
                throw new IOException("suspicious entry name: \"%s\"".formatted(entry.name()));
            }

            Path target = localDir.resolve(entry.name());

            switch (entry.type()) {
                case "file" -> {
                    // 超大文件（仓库里的 hero 图/截图等宣传素材）跳过而非失败：
                    // 对 skill 功能无用，且 SKILL.md 等核心文件都很小
                    if (entry.size() > MAX_FILE_SIZE) {
                        skippedFiles[0]++;
                        continue;
                    }
                    byte[] data = fetchBlob(src, entry);
                    if (totalBytes[0] + data.length > MAX_TOTAL_SIZE) {
                        throw new IOException("install total size limit (%d bytes) reached".formatted(MAX_TOTAL_SIZE));
                    }
                    Files.write(target, data);
                    fileCount[0]++;
                    totalBytes[0] += data.length;
                }
                case "dir" -> {
                    Files.createDirectories(target);
                    walkAndDownload(src, entry.path(), target, fileCount, skippedFiles, totalBytes, depth + 1);
                }
                default -> {
                    // symlink / submodule 直接跳过
                }
            }
        }
    }

    // ── 校验辅助 ──────────────────────────────────────────────────────

    /**
     * 检查目录下是否有 SKILL.md 或 skill.yaml（合法 skill 的最低要求）。
     */
    private static boolean hasSkillManifest(Path dir) {
        return Files.isRegularFile(dir.resolve("SKILL.md"))
                || Files.isRegularFile(dir.resolve("skill.yaml"));
    }

    /**
     * 校验 skill 名称：只允许小写字母、数字、连字符、下划线，不能以 . 开头。
     */
    static void validateSkillName(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("empty skill name");
        }
        if (name.startsWith(".")) {
            throw new IllegalArgumentException("skill name cannot start with '.'");
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_') {
                continue;
            }
            throw new IllegalArgumentException(
                    "skill name \"%s\" contains invalid char '%c' (use a-z 0-9 - _)".formatted(name, c));
        }
    }

    /**
     * 递归删除目录及其所有内容。
     */
    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
