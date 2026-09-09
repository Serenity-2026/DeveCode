

package com.agent.worktree;

import java.util.regex.Pattern;

/**
 * 路径校验及worktree名称生成:
 * 允许大小写字母、数字、点号、连字符和下划线。名称可以包含/作为嵌套slug的分隔符，比如team-refactor/alice。
 * 按/分割成段后，每一段都要匹配 ^[a-zA-Z0-9._-]+$ 。总长度不超过 64。
 * 这里有个陷阱。虽然点号本身是允许的字符， v1.0 完全合法。但 . 和 .. 作为独立段名必须显式拒绝，它们是操作系统的特殊路径。正则本身会放行它们，不拦等于放行路径遍历。
 */
public final class SlugValidator {

    public static final int MAX_LENGTH = 64;

    private static final Pattern VALID_SEGMENT = Pattern.compile("^[a-zA-Z0-9._-]+$");

    private SlugValidator() {}

    public static void validate(String slug) {
        if (slug == null || slug.isEmpty()) {
            throw new IllegalArgumentException("Invalid worktree name: cannot be empty");
        }
        if (slug.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "Invalid worktree name: must be %d characters or fewer (got %d)"
                            .formatted(MAX_LENGTH, slug.length()));
        }
        for (String segment : slug.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException(
                        "Invalid worktree name \"%s\": must not contain \".\" or \"..\" path segments"
                                .formatted(slug));
            }
            if (!VALID_SEGMENT.matcher(segment).matches()) {
                throw new IllegalArgumentException(
                        "Invalid worktree name \"%s\": each \"/\"-separated segment must be non-empty and contain only letters, digits, dots, underscores, and dashes"
                                .formatted(slug));
            }
        }
    }

    public static String flatten(String slug) {
        return slug.replace('/', '+');
    }

    public static String branchName(String slug) {
        return "worktree-" + flatten(slug);
    }
}
