package cn.iocoder.yudao.framework.etl.core.script;

import cn.hutool.json.JSONUtil;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32;

/** 本地固定槽位的进程崩溃台账。活动工作进程由操作系统锁隔离，不依赖 PID 或心跳过期。
 * 管理员必须提供所有权和权限受保护的持久化本地目录。
 * 不保证任意文件系统断电时的持久性，也不保证分布式文件系统锁的语义。 */
final class EtlDockerWorkerLedger {

    private static final int MAGIC = 0x45544c44, MAX_FRAME = 1536, MAX_BYTES = 8192;

    // 未经确认退出的 Session 必须保留隔离锁，即使其构造器失败且消费方将其丢弃。
    private static final Set<Registration> LIVE = ConcurrentHashMap.newKeySet();

    private final EtlScriptWorkerPolicy.Recovery config;

    private final Path directory;

    private final String endpointHash, configurationHash;

    private volatile Marker marker;

    record Marker(String storage, String service, String endpoint, int slots) {

    }

    record Entry(String storage, String service, String endpoint, String configuration, String image, String mounts,
                 String owner, String state, String id) {

    }

    private record Snapshot(Entry entry, int validBytes, boolean corrupt) {

    }

    record Scan(int scanned, int active, int cleaned, int pending, int incompatible) {

    }

    @FunctionalInterface
    interface Reconcile {

        /**
         * 按工作进程协议处理当前任务并记录运行结果。
         */
        boolean run(Registration registration);
    }

    /**
     * 校验构造参数并初始化 {@code EtlDockerWorkerLedger}。
     */
    EtlDockerWorkerLedger(EtlScriptWorkerPolicy policy) {
        this.config = Objects.requireNonNull(policy.docker().recovery());
        this.directory = config.directory().normalize().resolve(config.serviceId());
        endpointHash = hash(policy.docker().endpoint());
        configurationHash = hash(
                "etl-supervisor-v1\n" + policy.docker().image() + "\n" + policy.docker().maxLifetimeMillis());
    }

    /**
     * 检查可用。
     */
    void checkAvailable() {
        initialize();
        if (!compatibleMarker()) {
            throw unavailable();
        }
    }

    /**
     * 初始化受管账本后返回其存储身份。
     */
    String storageIdentity() {
        initialize();
        return marker.storage();
    }

    /**
     * 返回当前受管服务的稳定身份。
     */
    String serviceIdentity() {
        return config.serviceId();
    }

    /**
     * 判断当前类型或结构能否按目标契约兼容处理。
     */
    boolean compatible(Entry entry) {
        initialize();
        return registered(entry) && entry.configuration().equals(configurationHash);
    }

    /**
     * 返回当前已注册的 Stream Load 产品配置。
     */
    private boolean registered(Entry entry) {
        return compatibleMarker() && entry.storage().equals(marker.storage()) && entry.service()
                .equals(config.serviceId())
                && entry.endpoint().equals(endpointHash);
    }

    /**
     * 检查当前存储标记与预期服务身份兼容。
     */
    private boolean compatibleMarker() {
        return marker.service().equals(config.serviceId()) && marker.endpoint()
                .equals(endpointHash) && marker.slots() == config.maxRecords();
    }

    /**
     * 为后续写入预留资源并检查当前容量上限。
     */
    Registration reserve(String owner, String imageId, String mountProof) {
        checkAvailable();
        canonicalUuid(owner);
        if (imageId == null || !imageId.matches("sha256:[a-f0-9]{64}")
                || mountProof == null || !mountProof.matches("[a-f0-9]{64}")) {
            throw unavailable();
        }
        for (int i = 0; i < marker.slots(); i++) {
            Registration registration = acquire(i);
            if (registration == null) {
                continue;
            }
            try {
                Snapshot snapshot = registration.snapshot;
                if (!snapshot.corrupt() && (snapshot.entry() == null || snapshot.entry().state()
                        .equals("CLEANED") && registered(snapshot.entry()))) {
                    registration.channel.truncate(0);
                    registration.channel.force(true);
                    registration.validBytes = 0;
                    registration.append(new Entry(marker.storage(), config.serviceId(), endpointHash, configurationHash,
                            imageId, mountProof, owner, "INTENT", null));
                    return registration;
                }
            } catch (IOException failure) {
                registration.close();
                throw unavailable();
            }
            registration.close();
        }
        throw new IllegalStateException("ETL Docker worker 持久资源槽位已满或待对账，请管理员核查");
    }

    /**
     * 扫描当前查询结果并收集受限的质量键摘要。
     */
    Scan scan(Reconcile reconcile) {
        initialize();
        int scanned = 0, active = 0, cleaned = 0, pending = 0, incompatible = 0;
        for (int i = 0; i < marker.slots(); i++) {
            Registration registration;
            try {
                registration = acquire(i);
            } catch (IllegalStateException invalidFile) {
                pending++;
                continue;
            }
            if (registration == null) {
                active++;
                continue;
            }
            try (registration) {
                if (registration.snapshot.corrupt()) {
                    pending++;
                    continue;
                }
                Entry entry = registration.entry;
                if (entry == null) {
                    continue;
                }
                if (entry.state().equals("CLEANED")) {
                    if (!registered(entry)) {
                        pending++;
                        incompatible++;
                    }
                    continue;
                }
                scanned++;
                if (!compatible(entry)) {
                    incompatible++;
                    pending++;
                    continue;
                }
                try {
                    if (reconcile.run(registration)) {
                        cleaned++;
                    } else {
                        pending++;
                    }
                } catch (RuntimeException unknown) {
                    pending++;
                }
            }
        }
        return new Scan(scanned, active, cleaned, pending, incompatible);
    }

    /**
     * 初始化当前组件的运行资源和内部状态。
     */
    private synchronized void initialize() {
        try {
            rejectLinks(config.directory());
            if (!Files.isDirectory(config.directory(), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Missing trusted directory");
            }
            String type = Files.getFileStore(config.directory()).type().toLowerCase(Locale.ROOT);
            if (List.of("nfs", "cifs", "smb", "afs", "9p").stream().anyMatch(type::contains)) {
                throw new IOException("Nonlocal lease filesystem");
            }
            try {
                Files.createDirectory(directory);
            } catch (FileAlreadyExistsException exists) {
            }
            rejectLinks(directory);
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Invalid namespace");
            }
            Path control = directory.resolve(".control"), anchor = directory.resolve(
                    ".control-anchor"), identity = directory.resolve(".identity");
            boolean identityExists = Files.exists(identity, LinkOption.NOFOLLOW_LINKS);
            if (!identityExists) {
                try (var entries = Files.newDirectoryStream(directory)) {
                    if (entries.iterator().hasNext()) {
                        throw new IOException("Unproven initialization state");
                    }
                }
            }
            boolean created = false;
            if (!identityExists) {
                Files.createFile(control);
                created = true;
                Files.createLink(anchor, control);
            }
            verifyPair(control, anchor);
            try (var channel = FileChannel.open(control, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                FileLock lock;
                try {
                    lock = channel.tryLock();
                } catch (OverlappingFileLockException busy) {
                    throw new IOException("Initialization busy");
                }
                if (lock == null) {
                    throw new IOException("Initialization busy");
                }
                try (lock) {
                    verifyPair(control, anchor);
                    boolean newIdentity = false;
                    if (!Files.exists(identity, LinkOption.NOFOLLOW_LINKS)) {
                        if (!created) {
                            throw new IOException("Unproven initialization state");
                        }
                        byte[] body = JSONUtil.toJsonStr(Map.of("version", 1, "storage", UUID.randomUUID().toString(),
                                        "service", config.serviceId(), "endpoint", endpointHash, "slots", config.maxRecords()))
                                .getBytes(StandardCharsets.US_ASCII);
                        try (var out = FileChannel.open(identity, StandardOpenOption.CREATE_NEW,
                                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                            writeFully(out, ByteBuffer.wrap(body));
                            out.force(true);
                        }
                        newIdentity = true;
                    }
                    rejectLinks(identity);
                    if (!Files.isRegularFile(identity, LinkOption.NOFOLLOW_LINKS) || Files.size(identity) > 1024) {
                        throw new IOException("Invalid identity");
                    }
                    var value = flatObject(Files.readString(identity, StandardCharsets.US_ASCII));
                    if (!value.keySet().equals(Set.of("version", "storage", "service", "endpoint", "slots"))) {
                        throw new IOException("Unexpected identity fields");
                    }
                    if (!Integer.valueOf(1).equals(value.getInt("version"))) {
                        throw new IOException("Unsupported identity");
                    }
                    var read = new Marker(value.getStr("storage"), value.getStr("service"), value.getStr("endpoint"),
                            value.getInt("slots", 0));
                    canonicalUuid(read.storage());
                    if (!read.service().equals(config.serviceId()) || !read.endpoint().matches("[a-f0-9]{64}")
                            || read.slots() < 4 || read.slots() > 256) {
                        throw new IOException("Invalid identity");
                    }
                    if (marker != null && !marker.equals(read)) {
                        throw new IOException("Storage identity changed");
                    }
                    marker = read;
                    for (int i = 0; i < marker.slots(); i++) {
                        Path slot = slot(i), slotAnchor = slotAnchor(i);
                        if (newIdentity) {
                            Files.createFile(slot);
                            Files.createLink(slotAnchor, slot);
                        }
                        verifyPair(slot, slotAnchor);
                    }
                }
            }
        } catch (IOException | RuntimeException failure) {
            throw unavailable();
        }
    }

    /**
     * 根据当前配置获取可用资源并登记其使用配额。
     */
    private Registration acquire(int index) {
        FileChannel channel = null;
        try {
            rejectLinks(directory);
            verifyPair(slot(index), slotAnchor(index));
            channel = FileChannel.open(slot(index), StandardOpenOption.READ, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS);
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException busy) {
                channel.close();
                return null;
            }
            if (lock == null) {
                channel.close();
                return null;
            }
            verifyPair(slot(index), slotAnchor(index));
            Registration registration = new Registration(channel, lock, read(channel));
            LIVE.add(registration);
            return registration;
        } catch (IOException | RuntimeException failure) {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException ignored) {
                }
            }
            throw unavailable();
        }
    }

    final class Registration implements AutoCloseable {

        private final FileChannel channel;

        private final FileLock lock;

        private final Snapshot snapshot;

        private Entry entry;

        private int validBytes;

        private boolean closed;

        /**
         * 初始化 {@code Registration}。
         */
        private Registration(FileChannel channel, FileLock lock, Snapshot snapshot) {
            this.channel = channel;
            this.lock = lock;
            this.snapshot = snapshot;
            entry = snapshot.entry();
            validBytes = snapshot.validBytes();
        }

        /**
         * 返回条目。
         */
        Entry entry() {
            return entry;
        }

        /**
         * 将已创建容器的真实编号登记为已确认状态。
         */
        void acknowledge(String id) {
            if (entry == null || !entry.state().equals("INTENT") || !id.matches("[a-f0-9]{64}")) {
                throw unavailable();
            }
            try {
                append(new Entry(entry.storage(), entry.service(), entry.endpoint(), entry.configuration(),
                        entry.image(), entry.mounts(), entry.owner(), "ACK", id));
            } catch (IOException failure) {
                throw unavailable();
            }
        }

        /**
         * 将已确认容器登记为清理完成状态。
         */
        void cleaned() {
            if (entry == null || !entry.state().equals("ACK")) {
                throw unavailable();
            }
            try {
                append(new Entry(entry.storage(), entry.service(), entry.endpoint(), entry.configuration(),
                        entry.image(), entry.mounts(), entry.owner(), "CLEANED", entry.id()));
            } catch (IOException failure) {
                throw unavailable();
            }
        }

        /**
         * 将给定内容追加到当前缓冲区，并更新累计大小。
         */
        private void append(Entry next) throws IOException {
            if (closed || !lock.isValid()) {
                throw new IOException("Lease lost");
            }
            var fields = new LinkedHashMap<String, Object>();
            fields.put("storage", next.storage());
            fields.put("service", next.service());
            fields.put("endpoint", next.endpoint());
            fields.put("configuration", next.configuration());
            fields.put("image", next.image());
            fields.put("mounts", next.mounts());
            fields.put("owner", next.owner());
            fields.put("state", next.state());
            fields.put("id", next.id());
            byte[] payload = JSONUtil.toJsonStr(fields).getBytes(StandardCharsets.US_ASCII);
            if (payload.length > MAX_FRAME || validBytes + 16 + payload.length > MAX_BYTES) {
                throw new IOException("Ledger size limit");
            }
            var crc = new CRC32();
            crc.update(payload);
            var bytes = ByteBuffer.allocate(16 + payload.length).putInt(MAGIC).putInt(payload.length)
                    .putLong(crc.getValue()).put(payload);
            bytes.flip();
            channel.truncate(validBytes);
            channel.position(validBytes);
            writeFully(channel, bytes);
            channel.force(true);
            validBytes += 16 + payload.length;
            entry = next;
        }

        /**
         * 关闭 {@code EtlDockerWorkerLedger} 当前持有的资源。
         */
        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                lock.release();
            } catch (IOException ignored) {
            }
            try {
                channel.close();
            } catch (IOException ignored) {
            }
            if (channel.isOpen() || lock.isValid()) {
                throw unavailable();
            }
            LIVE.remove(this);
        }
    }

    /**
     * 按大小和记录边界读取本地工作进程账本快照。
     */
    private static Snapshot read(FileChannel channel) throws IOException {
        long size = channel.size();
        if (size > MAX_BYTES) {
            return new Snapshot(null, 0, true);
        }
        var buffer = ByteBuffer.allocate((int) size);
        channel.position(0);
        while (buffer.hasRemaining()) {
            int n = channel.read(buffer);
            if (n < 0) {
                throw new EOFException();
            }
        }
        buffer.flip();
        Entry previous = null;
        int valid = 0;
        while (buffer.hasRemaining()) {
            if (buffer.remaining() < 16) {
                return new Snapshot(previous, valid, previous == null);
            }
            int magic = buffer.getInt(), length = buffer.getInt();
            long expected = buffer.getLong();
            if (magic != MAGIC || length < 1 || length > MAX_FRAME) {
                return new Snapshot(null, valid, true);
            }
            if (buffer.remaining() < length) {
                return new Snapshot(previous, valid, previous == null);
            }
            byte[] bytes = new byte[length];
            buffer.get(bytes);
            var crc = new CRC32();
            crc.update(bytes);
            if (crc.getValue() != expected) {
                return new Snapshot(null, valid, true);
            }
            try {
                var json = flatObject(new String(bytes, StandardCharsets.US_ASCII));
                if (!json.keySet()
                        .equals(Set.of("storage", "service", "endpoint", "configuration", "image", "mounts", "owner",
                                "state"))
                        && !json.keySet()
                        .equals(Set.of("storage", "service", "endpoint", "configuration", "image", "mounts", "owner",
                                "state", "id"))) {
                    return new Snapshot(null, valid, true);
                }
                var next = new Entry(json.getStr("storage"), json.getStr("service"), json.getStr("endpoint"),
                        json.getStr("configuration"), json.getStr("image"), json.getStr("mounts"), json.getStr("owner"),
                        json.getStr("state"), json.getStr("id"));
                canonicalUuid(next.storage());
                canonicalUuid(next.owner());
                if (next.service() == null || !next.service().matches("[a-zA-Z0-9_-]{1,64}") || !next.endpoint()
                        .matches("[a-f0-9]{64}")
                        || !next.configuration().matches("[a-f0-9]{64}") || !next.image().matches("sha256:[a-f0-9]{64}")
                        || !next.mounts().matches("[a-f0-9]{64}") || !Set.of("INTENT", "ACK", "CLEANED")
                        .contains(next.state())
                        || next.state().equals("INTENT") && next.id() != null || !next.state()
                        .equals("INTENT") && (next.id() == null || !next.id().matches("[a-f0-9]{64}"))
                        || previous == null && !next.state().equals("INTENT")
                        || previous != null && (!previous.owner().equals(next.owner()) || !previous.storage()
                        .equals(next.storage())
                        || !previous.service().equals(next.service()) || !previous.endpoint().equals(next.endpoint())
                        || !previous.configuration().equals(next.configuration()) || !previous.image()
                        .equals(next.image()) || !previous.mounts().equals(next.mounts())
                        || !(previous.state().equals("INTENT") && next.state().equals("ACK") || previous.state()
                        .equals("ACK") && next.state().equals("CLEANED"))
                        || previous.id() != null && !previous.id().equals(next.id()))) {
                    return new Snapshot(null, valid, true);
                }
                previous = next;
                valid = buffer.position();
            } catch (RuntimeException invalid) {
                return new Snapshot(null, valid, true);
            }
        }
        return new Snapshot(previous, valid, false);
    }

    /**
     * 返回当前工作进程使用的固定槽位。
     */
    private Path slot(int index) {
        return directory.resolve(String.format(Locale.ROOT, "slot-%04d.log", index));
    }

    /**
     * 返回当前工作进程槽位对应的受管锚点。
     */
    private Path slotAnchor(int index) {
        return directory.resolve(String.format(Locale.ROOT, "slot-%04d.anchor", index));
    }

    /**
     * 校验成对元素。
     */
    private static void verifyPair(Path file, Path anchor) throws IOException {
        rejectLinks(file);
        rejectLinks(anchor);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(anchor,
                LinkOption.NOFOLLOW_LINKS)
                || !Files.isSameFile(file, anchor)) {
            throw new IOException("Unproven ledger identity");
        }
    }

    /**
     * 拒绝符号链接或不允许的链接文件。
     */
    private static void rejectLinks(Path path) throws IOException {
        for (Path current = path.toAbsolutePath().normalize(); current != null; current = current.getParent()) {
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            var attrs = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attrs.isSymbolicLink() || attrs.isOther()
                    // APFS 的 NOFOLLOW 规范路径可能选同一 inode 的另一硬链接名；归属按实际文件身份核对。
                    || !Files.isSameFile(current.toRealPath(), current.toRealPath(LinkOption.NOFOLLOW_LINKS))) {
                throw new IOException("Unsafe ledger path");
            }
        }
    }

    /**
     * 写入完整限定。
     */
    private static void writeFully(FileChannel channel, ByteBuffer bytes) throws IOException {
        while (bytes.hasRemaining()) {
            channel.write(bytes);
        }
    }

    // 记录仅含固定的标量标识符；调用复用的 JSON 解析器前，拒绝嵌套或转义展开。

    /**
     * 检查当前 JSON 文本为不含嵌套对象的简单对象结构。
     */
    private static cn.hutool.json.JSONObject flatObject(String text) {
        String value = text.strip();
        if (!value.startsWith("{") || !value.endsWith("}") || value.indexOf('{', 1) >= 0
                || value.indexOf('}') != value.length() - 1 || value.indexOf('[') >= 0 || value.indexOf(']') >= 0
                || value.indexOf('\\') >= 0) {
            throw unavailable();
        }
        return JSONUtil.parseObj(value);
    }

    /**
     * 要求标识符采用规范的 UUID 文本表示。
     */
    private static void canonicalUuid(String value) {
        if (value == null || !UUID.fromString(value).toString().equals(value)) {
            throw new IllegalArgumentException("Invalid opaque identity");
        }
    }

    /**
     * 根据当前内容计算摘要，供一致性比较使用。
     */
    private static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    /**
     * 根据当前输入构建并返回 {@code IllegalStateException}。
     */
    private static IllegalStateException unavailable() {
        return new IllegalStateException("ETL Docker worker 持久账本不可用或归属未证明，请管理员核查");
    }
}
