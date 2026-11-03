package cn.iocoder.yudao.framework.file.core.remote;

import java.io.FileNotFoundException;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 协议无关的受限遍历、打包与提交机制；业务权限必须由调用方先行校验。 */
public final class RemoteFileOperations {
    private RemoteFileOperations() { }

    /** 包含起点，父节点先于子节点；扫描不完整时抛错，不返回伪完整统计。 */
    public static List<RemoteFileEntry> walk(RemoteFileClient client, String path, int maxEntries, int maxDepth) throws IOException {
        if (maxEntries < 1 || maxEntries > 100_000 || maxDepth < 0 || maxDepth > 64) throw new IllegalArgumentException("遍历上限不合法");
        record Pending(RemoteFileEntry entry, int depth) { }
        ArrayDeque<Pending> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        List<RemoteFileEntry> result = new ArrayList<>();
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(60).toNanos();
        queue.add(new Pending(client.stat(path), 0));
        while (!queue.isEmpty()) {
            if (System.nanoTime() > deadline) throw new IOException("目录扫描超过时间上限");
            Pending current = queue.removeFirst();
            String normalized = RemoteFilePaths.normalize(current.entry().path());
            if (!visited.add(normalized)) throw new IOException("远程目录包含循环或重复项");
            result.add(current.entry());
            if (result.size() > maxEntries) throw new IOException("目录扫描数量超过上限");
            if (current.entry().directory()) {
                List<RemoteFileEntry> children = client.list(normalized);
                if (!children.isEmpty() && current.depth() >= maxDepth) throw new IOException("目录扫描深度超过上限");
                if (result.size() + queue.size() + children.size() > maxEntries) throw new IOException("目录扫描数量超过上限");
                for (RemoteFileEntry child : children) {
                    if (!RemoteFilePaths.parent(child.path()).equals(normalized)) throw new IOException("远程目录项越界");
                    queue.addLast(new Pending(child, current.depth() + 1));
                }
            }
        }
        return result;
    }

    /** 先完整预检再逐节点删除；远程文件系统不提供跨节点事务，失败时允许部分完成。 */
    public static void deleteRecursively(RemoteFileClient client, String path, int maxEntries, int maxDepth) throws IOException {
        RemoteFilePaths.requireMutable(path);
        List<RemoteFileEntry> entries = walk(client, path, maxEntries, maxDepth);
        for (int index = entries.size() - 1; index >= 0; index--) client.delete(entries.get(index).path());
    }

    /** 输出前预检所有选中对象；按完整虚拟路径命名，避免同名目录碰撞。 */
    public static void zip(RemoteFileClient client, List<String> paths, OutputStream output,
            int maxEntries, int maxDepth, long maxBytes) throws IOException {
        if (paths == null || paths.isEmpty() || paths.size() > 100 || maxBytes < 0
                || maxBytes > RemoteFileStreams.MAX_TRANSFER) throw new IllegalArgumentException("打包参数不合法");
        Map<String, RemoteFileEntry> selected = new LinkedHashMap<>();
        List<String> roots = paths.stream().map(RemoteFilePaths::normalize).distinct()
                .sorted(Comparator.comparingInt(String::length)).toList();
        for (String root : roots) {
            boolean covered = selected.values().stream().anyMatch(entry -> entry.directory()
                    && (entry.path().equals("/") || root.startsWith(entry.path() + "/")));
            if (covered) continue;
            for (RemoteFileEntry entry : walk(client, root, maxEntries, maxDepth)) selected.putIfAbsent(entry.path(), entry);
            if (selected.size() > maxEntries) throw new IOException("打包文件数量超过上限");
        }
        long total = 0;
        for (RemoteFileEntry entry : selected.values()) {
            if (!entry.directory()) {
                if (entry.size() < 0 || entry.size() > maxBytes - total) throw new IOException("打包文件大小超过上限");
                total += entry.size();
            }
        }
        // 关闭压缩器释放 Deflater，但输出流的生命周期仍由调用方管理。
        try (ZipOutputStream zip = new ZipOutputStream(new FilterOutputStream(output) {
            @Override public void close() throws IOException { flush(); }
        })) {
            long actual = 0;
            for (RemoteFileEntry entry : selected.values()) {
                if (entry.path().equals("/")) continue;
                String name = RemoteFilePaths.normalize(entry.path()).substring(1) + (entry.directory() ? "/" : "");
                ZipEntry item = new ZipEntry(name);
                if (entry.modifiedTime() > 0) item.setTime(entry.modifiedTime());
                zip.putNextEntry(item);
                if (!entry.directory()) {
                    CountingOutput count = new CountingOutput(zip);
                    client.download(entry.path(), count, maxBytes - actual);
                    actual += count.written;
                }
                zip.closeEntry();
            }
            zip.finish();
        }
    }

    static void checkUploadTarget(RemoteFileClient client, String target, boolean overwrite) throws IOException {
        RemoteFileEntry existing = existing(client, target);
        if (existing != null && (existing.directory() || !overwrite)) throw new IOException("目标名称已存在");
    }

    /** 原件先备份，提交失败尝试恢复；FTP 无跨外部客户端的原子 CAS，需限制并发写入主体。 */
    static void commitUpload(RemoteFileClient client, String temporary, String target, boolean overwrite) throws IOException {
        checkUploadTarget(client, target, overwrite);
        String backup = null;
        if (existing(client, target) != null) {
            backup = RemoteFilePaths.child(RemoteFilePaths.parent(target), ".remote-file-backup-" + UUID.randomUUID());
            client.rename(target, backup);
        }
        try {
            client.rename(temporary, target);
        } catch (IOException failure) {
            if (backup != null) {
                try {
                    if (existing(client, target) != null) throw new IOException("目标已被其他写入占用");
                    client.rename(backup, target);
                } catch (IOException restoreFailure) {
                    IOException incomplete = new IOException("上传提交失败，原件备份已保留，需检查远程目录");
                    incomplete.addSuppressed(restoreFailure);
                    throw incomplete;
                }
            }
            throw failure;
        }
        if (backup != null) {
            try { client.delete(backup); }
            catch (IOException failure) { throw new IOException("上传已提交，但原件备份清理失败，需检查远程目录"); }
        }
    }

    private static RemoteFileEntry existing(RemoteFileClient client, String path) throws IOException {
        try { return client.stat(path); }
        catch (FileNotFoundException missing) { return null; }
    }

    private static final class CountingOutput extends OutputStream {
        private final OutputStream delegate;
        private long written;
        CountingOutput(OutputStream delegate) { this.delegate = delegate; }
        @Override public void write(int value) throws IOException { delegate.write(value); written++; }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException { delegate.write(bytes, offset, length); written += length; }
    }
}
