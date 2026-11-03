package cn.iocoder.yudao.framework.etl.core.runtime;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** 有界的本地文件发布与核对，不感知租户、主体、数据库或调度器。 */
public final class EtlFileStore {

    public static final long MAX_BYTES = 128L * 1024 * 1024;

    /**
     * 初始化 {@code EtlFileStore}。
     */
    private EtlFileStore() {
    }

    public record Binding(String operationKey, String storageIdentity, Path target, Path temporary, Path lock) {

        /**
         * 校验构造参数并初始化 {@code Binding}。
         */
        public Binding {
            UUID.fromString(operationKey);
            UUID.fromString(storageIdentity);
            target = target.toAbsolutePath().normalize();
            temporary = temporary.toAbsolutePath().normalize();
            lock = lock.toAbsolutePath().normalize();
            if (!target.getParent().equals(temporary.getParent()) || !target.getParent().equals(lock.getParent())
                    || !temporary.getFileName().toString().equals(".etl-" + operationKey + ".part")
                    || !lock.getFileName().toString().equals(".etl-" + operationKey + ".lock")) {
                throw new IllegalArgumentException("File binding is inconsistent");
            }
            validateName(target.getFileName().toString());
        }

        /**
         * 返回当前锚点。
         */
        public Path anchor() {
            return target.getParent().resolve(".etl-" + operationKey + ".anchor");
        }
    }

    public record Fingerprint(String fileKey, long bytes, String sha256) {

        /**
         * 校验构造参数并初始化 {@code Fingerprint}。
         */
        public Fingerprint {
            if (fileKey == null || fileKey.isBlank() || fileKey.length() > 1024 || bytes < 0 || bytes > MAX_BYTES
                    || sha256 == null || !sha256.matches("[a-f0-9]{64}")) {
                throw new IllegalArgumentException("Invalid file fingerprint");
            }
        }
    }

    public record Prepared(Binding binding, Fingerprint fingerprint) {

    }

    public record Reconciled(String state, Fingerprint fingerprint) {

    }

    /** 名称是技术槽位，绝非路径。缺少旧版清单表示没有已知辅助对象。 */
    public record TemporaryResource(String name, String state, String fileKey) {

        /**
         * 校验构造参数并初始化 {@code TemporaryResource}。
         */
        public TemporaryResource {
            if (name == null || !name.matches("[a-z][a-z0-9-]{0,47}") || !Set.of("INTENT", "CREATED", "CLEANED")
                    .contains(state)
                    || fileKey != null && (fileKey.isBlank() || fileKey.length() > 1024)
                    || "CREATED".equals(state) && fileKey == null || "INTENT".equals(state) && fileKey != null) {
                throw new IllegalArgumentException("Invalid temporary resource manifest");
            }
        }
    }

    /**
     * 根据操作标识生成受管临时文件路径。
     */
    public static Path temporaryPath(Binding binding, String name) {
        new TemporaryResource(name, "INTENT", null);
        return binding.target().getParent().resolve(".etl-" + binding.operationKey() + "-" + name + ".tmp");
    }

    /**
     * 根据操作标识生成受管临时文件锚点路径。
     */
    public static Path temporaryAnchor(Binding binding, String name) {
        new TemporaryResource(name, "INTENT", null);
        return binding.target().getParent().resolve(".etl-" + binding.operationKey() + "-" + name + ".anchor");
    }

    /** 调用方持有父操作的操作系统锁，并已持久化 INTENT。 */
    public static String createTemporary(Binding binding, String name) throws IOException {
        Path path = temporaryPath(binding, name), anchor = temporaryAnchor(binding, name);
        rejectLinks(path);
        rejectLinks(anchor);
        Files.createFile(path);
        Files.createLink(anchor, path);
        return temporaryKey(binding, name);
    }

    /**
     * 校验临时文件及锚点后取得对应的稳定文件标识。
     */
    public static String temporaryKey(Binding binding, String name) throws IOException {
        Path path = temporaryPath(binding, name), anchor = temporaryAnchor(binding, name);
        rejectLinks(path);
        rejectLinks(anchor);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(anchor,
                LinkOption.NOFOLLOW_LINKS)
                || !Files.isSameFile(path, anchor)) {
            throw new UnprovenIdentityException("Temporary resource identity is unproven");
        }
        var attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return "hardlink-v1:" + binding.operationKey() + ":" + name + (attrs.fileKey() == null ? "" : ":" + attrs.fileKey());
    }

    /** 绝不自行发现文件；持有父操作系统锁时，只能清理明确登记的对象对。 */
    public static TemporaryResource discardTemporary(Binding binding, TemporaryResource resource) throws IOException {
        Path path = temporaryPath(binding, resource.name()), anchor = temporaryAnchor(binding, resource.name());
        rejectLinks(path);
        rejectLinks(anchor);
        boolean dataExists = Files.exists(path, LinkOption.NOFOLLOW_LINKS), anchorExists = Files.exists(anchor,
                LinkOption.NOFOLLOW_LINKS);
        if (dataExists || anchorExists) {
            if ("CLEANED".equals(resource.state()) || resource.fileKey() == null || !dataExists || !anchorExists
                    || !resource.fileKey().equals(temporaryKey(binding, resource.name()))) {
                throw new UnprovenIdentityException("Temporary resource requires manual identity reconciliation");
            }
            Files.delete(path);
            Files.delete(anchor);
        }
        return new TemporaryResource(resource.name(), "CLEANED", resource.fileKey());
    }

    public static final class UnprovenIdentityException extends IOException {

        /**
         * 初始化 {@code UnprovenIdentityException}及其父类状态。
         */
        private UnprovenIdentityException(String message) {
            super(message);
        }
    }

    /** 服务端选定的根目录加不透明路径段。不接受任意相对路径、路径穿越或符号链接。 */
    public static Binding binding(Path configuredRoot, List<String> segments, String operationKey,
            String fileName) throws IOException {
        return binding(configuredRoot, segments, operationKey, fileName, true);
    }

    /** 读取回执时仅解析已有命名空间，不创建标记、目录或锁。 */
    public static Binding existingBinding(Path configuredRoot, List<String> segments, String operationKey,
            String fileName) throws IOException {
        return binding(configuredRoot, segments, operationKey, fileName, false);
    }

    /**
     * 返回绑定信息。
     */
    private static Binding binding(Path configuredRoot, List<String> segments, String operationKey, String fileName,
            boolean create) throws IOException {
        Path root = configuredRoot.toAbsolutePath().normalize();
        rejectLinks(root);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Configured file root is unavailable");
        }
        root = root.toRealPath();
        Path marker = root.resolve(".etl-storage-id");
        if (create) {
            try {
                Files.writeString(marker, UUID.randomUUID().toString(), StandardCharsets.US_ASCII,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (FileAlreadyExistsException exists) { /* 以现有挂载身份为准。 */ }
        }
        rejectLinks(marker);
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) || Files.size(marker) != 36) {
            throw new IOException("Invalid file storage identity");
        }
        String identity = Files.readString(marker, StandardCharsets.US_ASCII);
        UUID.fromString(identity);
        Path directory = root;
        if (segments.isEmpty() || segments.size() > 8) {
            throw new IllegalArgumentException("Invalid file namespace");
        }
        for (String segment : segments) {
            if (segment == null || !segment.matches("[a-zA-Z0-9_-]{1,96}")) {
                throw new IllegalArgumentException("Invalid file namespace segment");
            }
            directory = directory.resolve(segment);
            if (create) {
                try {
                    Files.createDirectory(directory);
                } catch (FileAlreadyExistsException exists) { /* 在下面校验。 */ }
            }
            rejectLinks(directory);
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("File namespace is unavailable");
            }
        }
        validateName(fileName);
        return new Binding(operationKey, identity, directory.resolve(fileName),
                directory.resolve(".etl-" + operationKey + ".part"),
                directory.resolve(".etl-" + operationKey + ".lock"));
    }

    /**
     * 根据当前配置获取可用资源并登记其使用配额。
     */
    public static Lock acquire(Binding binding) throws IOException {
        return acquire(binding, true);
    }

    /**
     * 根据当前配置获取可用资源并登记其使用配额。
     */
    private static Lock acquire(Binding binding, boolean create) throws IOException {
        rejectLinks(binding.target().getParent());
        rejectLinks(binding.lock());
        if (!create && !Files.isRegularFile(binding.lock(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("File operation lock is unavailable");
        }
        var channel = create
                ? FileChannel.open(binding.lock(), StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS)
                : FileChannel.open(binding.lock(), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                throw new IOException("File operation still has an active writer");
            }
            return new Lock(channel, lock);
        } catch (IOException | OverlappingFileLockException failure) {
            channel.close();
            throw new IOException("File operation is busy", failure);
        }
    }

    public static final class Lock implements AutoCloseable {

        private final FileChannel channel;

        private final FileLock lock;

        /**
         * 初始化 {@code Lock}。
         */
        private Lock(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        /**
         * 关闭 {@code EtlFileStore} 当前持有的资源。
         */
        @Override
        public void close() throws IOException {
            try {
                lock.release();
            } finally {
                channel.close();
            }
        }
    }

    /**
     * 根据给定参数创建 {@code String} 实例。
     */
    public static String create(Binding binding) throws IOException {
        rejectLinks(binding.temporary());
        rejectLinks(binding.anchor());
        Files.createFile(binding.temporary());
        Files.createLink(binding.anchor(), binding.temporary());
        return fileKey(binding, binding.temporary());
    }

    /** Windows 可能不提供 BasicFileAttributes.fileKey。受保护的硬链接锚点用于证明实际对象一致性。 */
    public static String fileKey(Binding binding, Path path) throws IOException {
        if (!sameObject(binding, path)) {
            throw new IOException("File no longer matches its protected identity anchor");
        }
        var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return "hardlink-v1:" + binding.operationKey() + (attributes.fileKey() == null ? "" : ":" + attributes.fileKey());
    }

    /**
     * 通过受管锚点校验路径仍指向原登记的文件对象。
     */
    private static boolean sameObject(Binding binding, Path path) throws IOException {
        rejectLinks(path);
        rejectLinks(binding.anchor());
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.isRegularFile(binding.anchor(),
                LinkOption.NOFOLLOW_LINKS)
                && Files.isSameFile(path, binding.anchor());
    }

    /**
     * 根据当前内容计算结构指纹，供一致性比较使用。
     */
    public static Fingerprint fingerprint(Binding binding, Path path) throws IOException {
        try (var channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            return fingerprint(binding, path, channel);
        }
    }

    /**
     * 根据当前内容计算结构指纹，供一致性比较使用。
     */
    private static Fingerprint fingerprint(Binding binding, Path path, FileChannel channel) throws IOException {
        String key = fileKey(binding, path);
        var before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (before.size() > MAX_BYTES) {
            throw new IOException("File output exceeds its byte budget");
        }
        try {
            var hash = MessageDigest.getInstance("SHA-256");
            long bytes = 0;
            channel.position(0);
            ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
            for (int read; (read = channel.read(buffer)) >= 0; ) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new java.io.InterruptedIOException("File fingerprint interrupted");
                }
                bytes += read;
                if (bytes > MAX_BYTES) {
                    throw new IOException("File output exceeds its byte budget");
                }
                buffer.flip();
                hash.update(buffer);
                buffer.clear();
            }
            var after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!key.equals(fileKey(binding, path)) || before.size() != bytes || after.size() != bytes
                    || !before.lastModifiedTime().equals(after.lastModifiedTime())) {
                throw new IOException("File changed during fingerprinting");
            }
            return new Fingerprint(key, bytes, HexFormat.of().formatHex(hash.digest()));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * 在调用方发送 HTTP 响应头前，完整验证有界的已发布字节。同一个打开的通道和
     * 操作锁会保留用于流式传输；此方法不发布、恢复、删除或发现文件。
     */
    public static Download openPublished(Binding binding, Fingerprint expected) throws IOException {
        Lock lock = acquire(binding, false);
        FileChannel channel = null;
        try {
            rejectLinks(binding.target());
            channel = FileChannel.open(binding.target(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
            if (!expected.equals(fingerprint(binding, binding.target(), channel))) {
                throw new IOException("Published file evidence changed");
            }
            channel.position(0);
            return new Download(binding, expected, channel, lock,
                    Files.getLastModifiedTime(binding.target(), LinkOption.NOFOLLOW_LINKS));
        } catch (IOException | RuntimeException failure) {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException close) {
                    failure.addSuppressed(close);
                }
            }
            try {
                lock.close();
            } catch (IOException close) {
                failure.addSuppressed(close);
            }
            throw failure;
        }
    }

    /** 只能使用一次的流。路径和文件系统身份保持私有，调用方负责此句柄的生命周期。 */
    public static final class Download implements AutoCloseable {

        private final Binding binding;

        private final Fingerprint expected;

        private final FileChannel channel;

        private final Lock lock;

        private final java.nio.file.attribute.FileTime modified;

        private boolean transferred;

        private boolean closed;

        /**
         * 初始化 {@code Download}。
         */
        private Download(Binding binding, Fingerprint expected, FileChannel channel, Lock lock,
                java.nio.file.attribute.FileTime modified) {
            this.binding = binding;
            this.expected = expected;
            this.channel = channel;
            this.lock = lock;
            this.modified = modified;
        }

        /**
         * 返回字节数组。
         */
        public long bytes() {
            return expected.bytes();
        }

        /**
         * 计算输入内容的 SHA-256 摘要。
         */
        public String sha256() {
            return expected.sha256();
        }

        /** 不重新打开文件，也不将整个载荷读入内存，即可检测预检后的替换或截断。 */
        public void verifyUnchanged() throws IOException {
            if (closed || transferred || !expected.fileKey().equals(fileKey(binding, binding.target()))
                    || channel.size() != expected.bytes() || Files.size(binding.target()) != expected.bytes()
                    || !modified.equals(Files.getLastModifiedTime(binding.target(), LinkOption.NOFOLLOW_LINKS))) {
                throw new IOException("Published file evidence changed");
            }
        }

        /**
         * 重新校验输入对象后将文件内容流式传输到输出端。
         */
        public void transferTo(OutputStream output) throws IOException {
            Objects.requireNonNull(output);
            verifyUnchanged();
            transferred = true;
            byte[] bytes = new byte[64 * 1024];
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            long remaining = expected.bytes();
            while (remaining > 0) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new java.io.InterruptedIOException("File download interrupted");
                }
                buffer.clear();
                buffer.limit((int) Math.min(bytes.length, remaining));
                int read = channel.read(buffer);
                if (read < 0) {
                    throw new IOException("Published file changed during download");
                }
                if (read == 0) {
                    continue;
                }
                output.write(bytes, 0, read);
                remaining -= read;
            }
            if (channel.size() != expected.bytes()) {
                throw new IOException("Published file changed during download");
            }
        }

        /**
         * 关闭 {@code EtlFileStore} 当前持有的资源。
         */
        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            try {
                channel.close();
            } finally {
                lock.close();
            }
        }
    }

    /**
     * 对当前恢复材料进行封装或加密并返回可保存的载荷。
     */
    public static Fingerprint seal(Binding binding) throws IOException {
        rejectLinks(binding.temporary());
        try (var channel = FileChannel.open(binding.temporary(), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            channel.force(true);
        }
        return fingerprint(binding, binding.temporary());
    }

    /**
     * 将当前已汇总的数据或状态发布给结果接收方。
     */
    public static void publish(Prepared output) throws IOException {
        if (!output.fingerprint().equals(fingerprint(output.binding(), output.binding().temporary()))) {
            throw new IOException("Staged file identity changed");
        }
        rejectLinks(output.binding().target());
        // 独占硬链接发布绝不覆盖其他对象；同目录链接保持对象身份。
        Files.createLink(output.binding().target(), output.binding().temporary());
        Files.delete(output.binding().temporary());
    }

    /**
     * 丢弃当前未发布的数据或临时输出状态。
     */
    public static void discard(Binding binding, String expectedKey) throws IOException {
        if (!Files.exists(binding.temporary(), LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (expectedKey == null || !expectedKey.equals(fileKey(binding, binding.temporary()))) {
            throw new IOException("Temporary file identity cannot be proven");
        }
        Files.delete(binding.temporary());
    }

    /** 报告清理成功后，锚点下不得残留未发布的字节。 */
    public static void discardUnpublished(Binding binding, String expectedKey) throws IOException {
        if (Files.exists(binding.target(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Unexpected target requires reconciliation");
        }
        if (Files.exists(binding.anchor(), LinkOption.NOFOLLOW_LINKS) && (!Files.exists(binding.temporary(),
                LinkOption.NOFOLLOW_LINKS)
                || expectedKey == null || !expectedKey.equals(fileKey(binding, binding.temporary())))) {
            throw new IOException("Unpublished anchor requires its known temporary object");
        }
        discard(binding, expectedKey);
        Files.deleteIfExists(binding.anchor());
    }

    /** 持久化技术检查点；调用方负责授权、有效期和回执事务。 */
    public enum RetirementPhase {NONE, INTENT, TARGET_REMOVED, COMPLETE}

    /**
     * 仅删除先前封存的发布文件及其受保护硬链接锚点。现有操作
     * 锁也隔离下载。不得删除目录、挂载标记、锁文件、未登记的名称或外部路径。
     * 回调必须在首次解除链接前提交 INTENT；回调在存储方拥有的
     * 数据库事务之外运行，并且必须拒绝已失去的认领。若仅剩锚点，则必须具备原生文件键：
     * 在不提供 fileKey 的文件系统上，崩溃后仅凭字节相同无法证明身份。
     */
    public static void retirePublished(Binding binding, Fingerprint expected, RetirementPhase persisted,
            java.util.function.Consumer<RetirementPhase> checkpoint) throws IOException {
        Objects.requireNonNull(expected);
        Objects.requireNonNull(persisted);
        Objects.requireNonNull(checkpoint);
        try (var ignored = acquire(binding, false)) {
            rejectLinks(binding.target());
            rejectLinks(binding.anchor());
            rejectLinks(binding.temporary());
            if (Files.exists(binding.temporary(), LinkOption.NOFOLLOW_LINKS)) {
                throw new UnprovenIdentityException("A staged object still requires reconciliation");
            }
            boolean target = Files.exists(binding.target(), LinkOption.NOFOLLOW_LINKS);
            boolean anchor = Files.exists(binding.anchor(), LinkOption.NOFOLLOW_LINKS);
            if (persisted == RetirementPhase.COMPLETE) {
                if (target || anchor) {
                    throw new UnprovenIdentityException("Completed retirement has an unexpected object");
                }
                checkpoint.accept(RetirementPhase.COMPLETE);
                return;
            }
            if (!target && !anchor) {
                if (persisted == RetirementPhase.NONE) {
                    throw new UnprovenIdentityException("Missing publication has no durable retirement intent");
                }
                checkpoint.accept(RetirementPhase.COMPLETE);
                return;
            }
            if (!target) {
                if (persisted == RetirementPhase.NONE) {
                    throw new UnprovenIdentityException("Missing publication has no durable retirement intent");
                }
                requireDetachedAnchor(binding, expected);
                checkpoint.accept(RetirementPhase.TARGET_REMOVED);
                requireDetachedAnchor(binding, expected);
                // 第二次有界哈希计算不得在最终解除链接前悄然耗尽调用方的认领有效期。
                checkpoint.accept(RetirementPhase.TARGET_REMOVED);
                Files.delete(binding.anchor());
                checkpoint.accept(RetirementPhase.COMPLETE);
                return;
            }
            if (!anchor || persisted == RetirementPhase.TARGET_REMOVED || !sameObject(binding, binding.target())
                    || !expected.equals(fingerprint(binding, binding.target()))) {
                throw new UnprovenIdentityException("Published object no longer matches its sealed identity");
            }
            requireLinkCount(binding.target(), 2);
            var modified = Files.getLastModifiedTime(binding.target(), LinkOption.NOFOLLOW_LINKS);
            checkpoint.accept(RetirementPhase.INTENT);
            // 持久化回调后、删除任一名称前，重新检查已知对象对。
            if (!expected.fileKey().equals(fileKey(binding, binding.target())) || Files.size(
                    binding.target()) != expected.bytes()
                    || !modified.equals(Files.getLastModifiedTime(binding.target(), LinkOption.NOFOLLOW_LINKS))) {
                throw new UnprovenIdentityException("Published object changed during retirement admission");
            }
            requireLinkCount(binding.target(), 2);
            Files.delete(binding.target());
            // 两次解除链接须紧邻执行，避免中间的应用回调替换孤立锚点。
            Files.delete(binding.anchor());
            checkpoint.accept(RetirementPhase.TARGET_REMOVED);
            checkpoint.accept(RetirementPhase.COMPLETE);
        }
    }

    /**
     * 校验并取得脱离链接锚点。
     */
    private static void requireDetachedAnchor(Binding binding, Fingerprint expected) throws IOException {
        rejectLinks(binding.anchor());
        if (!Files.isRegularFile(binding.anchor(), LinkOption.NOFOLLOW_LINKS)) {
            throw new UnprovenIdentityException("Remaining anchor identity is unproven");
        }
        var attrs = Files.readAttributes(binding.anchor(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attrs.fileKey() == null || !expected.fileKey()
                .equals("hardlink-v1:" + binding.operationKey() + ":" + attrs.fileKey())
                || !expected.equals(fingerprint(binding, binding.anchor()))) {
            throw new UnprovenIdentityException("Remaining anchor identity is unproven");
        }
        requireLinkCount(binding.anchor(), 1);
    }

    /**
     * 校验并取得链接数量。
     */
    private static void requireLinkCount(Path path, int expected) throws IOException {
        // 公开的 NIO unix 视图能证明已知的额外硬链接。其他提供方不提供可移植的数量统计；
        // 此 API 仅删除托管名称，并不保证枚举全部外部引用。
        if (!Files.getFileStore(path).supportsFileAttributeView("unix")) {
            return;
        }
        Object count = Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
        if (!(count instanceof Number number) || number.longValue() != expected) {
            throw new UnprovenIdentityException("Additional file references require manual reconciliation");
        }
    }

    /** 调用方必须证明原运行已失去租约。操作系统锁独立隔离仍在运行的写入器。 */
    public static Reconciled reconcile(Binding binding, String expectedKey, Fingerprint prepared,
            boolean publicationApproved) throws IOException {
        return reconcile(binding, expectedKey, prepared, publicationApproved, List.of(), ignored -> {
        });
    }

    /**
     * 根据当前输入构建并返回 {@code Reconciled}。
     */
    public static Reconciled reconcile(Binding binding, String expectedKey, Fingerprint prepared,
            boolean publicationApproved,
            List<TemporaryResource> resources,
            java.util.function.Consumer<TemporaryResource> onCleaned) throws IOException {
        if (resources.size() > 4 || resources.stream().map(TemporaryResource::name).distinct()
                .count() != resources.size()) {
            throw new IllegalArgumentException("Invalid temporary resource manifest");
        }
        try (var ignored = acquire(binding)) {
            for (var resource : resources) {
                try {
                    var cleaned = discardTemporary(binding, resource);
                    if (!"CLEANED".equals(resource.state())) {
                        onCleaned.accept(cleaned);
                    }
                } catch (UnprovenIdentityException unknown) {
                    return new Reconciled("MANUAL", null);
                }
            }
            if (Files.exists(binding.target(), LinkOption.NOFOLLOW_LINKS)) {
                if (!sameObject(binding, binding.target())) {
                    return new Reconciled("MANUAL", null);
                }
                Fingerprint actual = fingerprint(binding, binding.target());
                if (!publicationApproved || prepared == null || !prepared.equals(actual)) {
                    return new Reconciled("MANUAL", null);
                }
                discard(binding, expectedKey);
                return new Reconciled("PUBLISHED", actual);
            }
            if (!Files.exists(binding.temporary(), LinkOption.NOFOLLOW_LINKS)) {
                if (publicationApproved) {
                    return new Reconciled("MANUAL", null);
                }
                // 若两个原始名称都已消失，仅剩的锚点本身无法证明其未被替换。
                if (Files.exists(binding.anchor(), LinkOption.NOFOLLOW_LINKS)) {
                    return new Reconciled("MANUAL", null);
                }
                discardUnpublished(binding, expectedKey);
                return new Reconciled("DISCARDED", null);
            }
            if (expectedKey == null || !sameObject(binding, binding.temporary()) || !expectedKey.equals(
                    fileKey(binding, binding.temporary()))) {
                return new Reconciled("MANUAL", null);
            }
            if (publicationApproved) {
                if (prepared == null || !prepared.equals(fingerprint(binding, binding.temporary()))) {
                    return new Reconciled("MANUAL", null);
                }
                publish(new Prepared(binding, prepared));
                return new Reconciled("PUBLISHED", prepared);
            }
            discardUnpublished(binding, expectedKey);
            return new Reconciled("DISCARDED", null);
        }
    }

    /**
     * 校验名称。
     */
    public static void validateName(String name) {
        if (name == null || name.isBlank() || name.length() > 255 || name.toLowerCase(Locale.ROOT)
                .startsWith(".etl-") || name.equals(".") || name.equals("..")
                || name.endsWith(".") || name.endsWith(" ") || name.chars()
                .anyMatch(c -> c < 32 || "<>\"|?*".indexOf(c) >= 0)
                || name.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?")
                || name.contains("/") || name.contains("\\") || name.contains(":")) {
            throw new IllegalArgumentException("Invalid file name");
        }
    }

    /**
     * 拒绝符号链接或不允许的链接文件。
     */
    private static void rejectLinks(Path path) throws IOException {
        for (Path next = path; next != null; next = next.getParent()) {
            if (Files.isSymbolicLink(next)) {
                throw new IOException("File paths must not contain symlinks");
            }
        }
    }
}
