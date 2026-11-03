package cn.iocoder.yudao.framework.file.core.remote;

import java.util.regex.Pattern;

/** 拒绝不明确的路径表示，不把越界输入静默转换为可访问路径。 */
public final class RemoteFilePaths {
    private static final Pattern ENCODED_OCTET = Pattern.compile("%[0-9a-fA-F]{2}");
    private RemoteFilePaths() { }

    public static String normalize(String path) {
        if (path == null || path.isEmpty()) return "/";
        if (!path.startsWith("/") || path.length() > 4096 || path.contains("\\")
                || ENCODED_OCTET.matcher(path).find() || path.chars().anyMatch(c -> c < 32 || c == 127)) {
            throw new IllegalArgumentException("文件路径不合法");
        }
        if (path.equals("/")) return path;
        String normalized = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        for (String segment : normalized.substring(1).split("/", -1)) {
            validateName(segment);
        }
        return normalized;
    }

    public static String child(String parent, String name) {
        validateName(name);
        String path = normalize(parent);
        return normalize((path.equals("/") ? "" : path) + "/" + name);
    }

    public static String parent(String path) {
        path = normalize(path);
        int separator = path.lastIndexOf('/');
        return separator <= 0 ? "/" : path.substring(0, separator);
    }

    public static String name(String path) {
        path = normalize(path);
        return path.equals("/") ? "/" : path.substring(path.lastIndexOf('/') + 1);
    }

    public static void requireMutable(String path) {
        if (normalize(path).equals("/")) throw new IllegalArgumentException("不允许修改根目录");
    }

    static String resolve(String root, String path) {
        path = normalize(path);
        return root.equals("/") ? path : path.equals("/") ? root : root + path;
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank() || name.equals(".") || name.equals("..")
                || name.length() > 255 || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
                || ENCODED_OCTET.matcher(name).find() || name.chars().anyMatch(c -> c < 32 || c == 127)) {
            throw new IllegalArgumentException("文件名称不合法");
        }
    }
}
