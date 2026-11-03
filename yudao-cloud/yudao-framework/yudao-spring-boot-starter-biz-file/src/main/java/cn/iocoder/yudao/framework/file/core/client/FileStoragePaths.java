package cn.iocoder.yudao.framework.file.core.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 文件对象键与磁盘边界校验；不接受根目录、绝对路径或符号链接。 */
public final class FileStoragePaths {

    private FileStoragePaths() { }

    public static String validate(String path) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.indexOf('\\') >= 0
                || path.indexOf(':') >= 0 || path.chars().anyMatch(c -> c < 32 || c == 127)) {
            throw new IllegalArgumentException("文件路径不合法");
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("文件路径不合法");
            }
        }
        return path;
    }

    public static Path resolveLocal(String root, String key) {
        Path base = Path.of(root).toAbsolutePath().normalize();
        Path target = base.resolve(validate(key)).normalize();
        if (!target.startsWith(base)) {
            throw new IllegalArgumentException("文件超出配置目录");
        }
        Path cursor = target.getRoot();
        for (Path part : target) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) {
                throw new IllegalArgumentException("文件路径禁止使用符号链接");
            }
            // 根目录尚未创建时也检查所有已有祖先，防止 Windows Junction 将写入重定向到根外。
            if (Files.exists(cursor)) {
                try {
                    if (!cursor.toRealPath().equals(cursor)) {
                        throw new IllegalArgumentException("文件路径禁止真实目录重定向");
                    }
                } catch (IOException failure) {
                    throw new IllegalArgumentException("无法校验文件路径");
                }
            }
        }
        return target;
    }
}
