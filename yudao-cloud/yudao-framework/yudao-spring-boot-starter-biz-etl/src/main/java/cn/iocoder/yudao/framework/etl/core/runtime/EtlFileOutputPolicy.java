package cn.iocoder.yudao.framework.etl.core.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** 服务端控制的文件输出目录策略，不从节点配置接受目录授权。 */
public record EtlFileOutputPolicy(List<Path> allowedDirectories, boolean replaceExisting) {

    /**
     * 初始化 {@code EtlFileOutputPolicy}。
     */
    public EtlFileOutputPolicy {
        allowedDirectories = List.copyOf(allowedDirectories);
    }

    /**
     * 构造未启用当前功能的配置。
     */
    public static EtlFileOutputPolicy disabled() {
        return new EtlFileOutputPolicy(List.of(), false);
    }

    /**
     * 根据当前配置和输入类型解析匹配的实现或定义。
     */
    public Path resolve(String directory, String fileName) throws IOException {
        if (allowedDirectories.isEmpty()) {
            throw new IllegalStateException("文件输出未配置服务端允许目录");
        }
        Path name = Path.of(fileName);
        if (name.isAbsolute() || name.getNameCount() != 1 || fileName.contains("/") || fileName.contains("\\")
                || fileName.contains(":") || fileName.equals(".") || fileName.equals("..") || fileName.isBlank()) {
            throw new IllegalArgumentException("输出文件名必须为单个文件名");
        }
        Path requested = Path.of(directory).toAbsolutePath().normalize();
        rejectSymlinks(requested);
        Path realDirectory = requested.toRealPath();
        if (!Files.isDirectory(realDirectory)) {
            throw new IllegalArgumentException("输出目录不存在");
        }
        boolean allowed = false;
        for (Path root : allowedDirectories) {
            Path absolute = root.toAbsolutePath().normalize();
            rejectSymlinks(absolute);
            if (realDirectory.startsWith(absolute.toRealPath())) {
                allowed = true;
                break;
            }
        }
        if (!allowed) {
            throw new IllegalArgumentException("输出目录不在服务端允许范围内");
        }
        Path target = realDirectory.resolve(name);
        validateTarget(target);
        return target;
    }

    /**
     * 校验目标端。
     */
    public void validateTarget(Path target) throws IOException {
        rejectSymlinks(target.getParent());
        if (Files.isSymbolicLink(target) || Files.exists(target) && !Files.isRegularFile(target)) {
            throw new IllegalArgumentException("输出目标必须为普通文件，禁止符号链接");
        }
        if (!replaceExisting && Files.exists(target)) {
            throw new IllegalStateException("输出文件已存在，服务端未允许覆盖");
        }
    }

    /**
     * 沿路径逐级拒绝符号链接，避免输出目录越界。
     */
    private static void rejectSymlinks(Path path) {
        for (Path part = path; part != null; part = part.getParent()) {
            if (Files.isSymbolicLink(part)) {
                throw new IllegalArgumentException("输出路径禁止符号链接");
            }
        }
    }
}
