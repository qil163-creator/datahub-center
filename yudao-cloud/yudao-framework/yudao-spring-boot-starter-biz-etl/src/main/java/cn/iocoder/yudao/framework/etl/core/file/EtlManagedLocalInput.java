package cn.iocoder.yudao.framework.etl.core.file;

import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributeView;
import java.util.*;

/** 以固定根目录和无符号链接目录句柄读取受管普通文件，回调不能接管流的生命周期。 */
public final class EtlManagedLocalInput {
    private EtlManagedLocalInput() { }
    @FunctionalInterface public interface Reader { void read(InputStream source) throws Exception; }

    public static void read(Set<Path> allowedRoots, String rootPath, String relativePath, long maxBytes, Reader reader) throws Exception {
        if (allowedRoots == null || allowedRoots.isEmpty() || maxBytes < 1 || maxBytes > 67_108_864)
            throw new IllegalArgumentException("工作节点文件根目录授权或字节预算无效");
        Path root = Path.of(rootPath).toAbsolutePath().normalize();
        if (root.getParent() == null || !allowedRoots.contains(root) || !root.equals(root.toRealPath()))
            throw new IllegalArgumentException("文件根目录未获工作节点授权或存在符号链接");
        if (relativePath == null || !relativePath.matches("[A-Za-z0-9_./-]+") || relativePath.startsWith("/") || relativePath.contains("//")
                || Arrays.stream(relativePath.split("/", -1)).anyMatch(value -> value.isEmpty() || value.equals(".") || value.equals("..")))
            throw new IllegalArgumentException("文件必须使用受管根目录中的普通相对路径");
        var opened = new ArrayList<SecureDirectoryStream<Path>>();
        try {
            var initial = Files.newDirectoryStream(root);
            if (!(initial instanceof SecureDirectoryStream<Path> directory)) {
                initial.close(); throw new IllegalArgumentException("工作节点文件系统不支持安全目录句柄");
            }
            opened.add(directory);
            String[] segments = relativePath.split("/");
            for (int index = 0; index < segments.length - 1; index++) {
                directory = directory.newDirectoryStream(Path.of(segments[index]), LinkOption.NOFOLLOW_LINKS); opened.add(directory);
            }
            Path file = Path.of(segments[segments.length - 1]);
            var attributes = directory.getFileAttributeView(file, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).readAttributes();
            if (!attributes.isRegularFile() || attributes.size() < 1 || attributes.size() > maxBytes)
                throw new IllegalArgumentException("输入不是普通文件、为空或超过字节预算");
            try (var channel = directory.newByteChannel(file, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)); InputStream source = Channels.newInputStream(channel)) {
                reader.read(new java.io.FilterInputStream(source) {
                    private long remaining = maxBytes;
                    private void count(int consumed) throws java.io.IOException {
                        if (Thread.currentThread().isInterrupted() || consumed > remaining) throw new java.io.IOException("文件读取已取消或超过字节预算");
                        if (consumed > 0) remaining -= consumed;
                    }
                    @Override public int read() throws java.io.IOException { int value = in.read(); count(value < 0 ? 0 : 1); return value; }
                    @Override public int read(byte[] bytes, int offset, int length) throws java.io.IOException {
                        java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
                        if (length == 0) return 0;
                        int value = in.read(bytes, offset, (int) Math.min(length, remaining + 1)); count(Math.max(value, 0)); return value;
                    }
                });
            }
        } finally { for (int index = opened.size() - 1; index >= 0; index--) opened.get(index).close(); }
    }
}
