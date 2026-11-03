package cn.iocoder.yudao.framework.dbswitch.dbchange;

import cn.iocoder.yudao.framework.dbswitch.common.config.DiffSortLimits;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/**
 * 每次比较独立管理一个加密且受磁盘限额约束的暂存区；双路归并限制打开的读取器数量，
 * 归并时最多保留两行；回调可以修改目标前，先验证全部归并段。
 * 随机密钥仅存在于本次操作中；进程崩溃后残留的文件无法用于恢复。
 */
final class DiffRowSort implements AutoCloseable {

    @FunctionalInterface
    interface Source {

        /**
         * 推进游标并返回下一条数据或后续读取状态。
         */
        Object[] next() throws Exception;
    }

    record Run(Path path, long id, long rows) {

    }

    private record Item(Object[] values, byte[] encoded) {

    }

    private final DiffSortLimits limits;

    private final DiffRowCodec codec;

    private final Runnable cancellation;

    private final Path directory;

    private final Object directoryFileKey;

    private final FileTime directoryCreated;

    private final byte[] directoryIdentity = new byte[32];

    private static final String IDENTITY_FILE = ".dbswitch-owner";

    private final DiffSpillBudget.Lease sharedBudget;

    private final byte[] keyBytes = new byte[32];

    private final byte[] noncePrefix = new byte[4];

    private final LinkedHashMap<Path, Long> ownedFiles = new LinkedHashMap<>();

    private long diskBytes;

    private long sequence;

    private long nextRun;

    private final int maxRowBytes;

    private boolean closed;

    private boolean cleanupComplete;

    /**
     * 校验构造参数并初始化 {@code DiffRowSort}。
     */
    DiffRowSort(DiffSortLimits limits, int columns, long maxRowBytes, Runnable cancellation) {
        this.limits = Objects.requireNonNull(limits);
        this.cancellation = Objects.requireNonNull(cancellation);
        this.maxRowBytes = (int) Math.min(Math.min(maxRowBytes, limits.memoryBytes() / 4), Integer.MAX_VALUE - 64L);
        this.codec = new DiffRowCodec(columns, this.maxRowBytes);
        DiffSpillBudget.Lease acquired = null;
        Path createdDirectory = null;
        try {
            var random = new SecureRandom();
            random.nextBytes(keyBytes);
            random.nextBytes(noncePrefix);
            random.nextBytes(directoryIdentity);
            Path parent = Path.of(limits.directory()).toRealPath();
            acquired = DiffSpillBudget.acquire(parent, limits.maxSharedSpillBytes());
            createdDirectory = Files.getFileStore(parent).supportsFileAttributeView("posix")
                    ? Files.createTempDirectory(parent, "dbswitch-diff-", PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rwx------")))
                    : Files.createTempDirectory(parent, "dbswitch-diff-");
            BasicFileAttributes attributes = Files.readAttributes(createdDirectory, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
                throw new IOException("Difference spool ownership cannot be established");
            }
            directory = createdDirectory;
            directoryFileKey = attributes.fileKey();
            directoryCreated = attributes.creationTime();
            Files.write(directory.resolve(IDENTITY_FILE), directoryIdentity, StandardOpenOption.CREATE_NEW);
            sharedBudget = acquired;
        } catch (IOException | RuntimeException failure) {
            if (createdDirectory != null) {
                try {
                    Files.deleteIfExists(createdDirectory.resolve(IDENTITY_FILE));
                } catch (IOException ignored) {
                }
                try {
                    Files.deleteIfExists(createdDirectory);
                } catch (IOException ignored) {
                }
            }
            if (acquired != null) {
                acquired.close();
            }
            throw failed("Cannot create difference spool: " + failure.getClass().getSimpleName());
        }
    }

    /**
     * 返回排序。
     */
    Run sort(Source source, Comparator<Object[]> keys, int[] keyIndexes) {
        check();
        var runs = new ArrayList<Run>();
        var chunk = new ArrayList<Item>();
        long memory = 0;
        try {
            for (Object[] row; (row = source.next()) != null; ) {
                check();
                for (int index : keyIndexes) {
                    if (index < 0 || index >= row.length || row[index] == null
                            || row[index] == cn.iocoder.yudao.framework.dbswitch.common.entity.MissingCellValue.INSTANCE
                            || row[index] instanceof cn.iocoder.yudao.framework.dbswitch.common.entity.NativeCellValue nativeValue
                            && !nativeValue.hasMatchingKey()) {
                        throw failed("Difference matching keys must be present, non-null and proven comparable");
                    }
                }
                byte[] encoded = codec.encode(row);
                Object[] detached = codec.decode(encoded);
                keys.compare(detached, detached); // 即使输入只有一行，也拒绝不受支持的键类型。
                long rowBytes = Math.addExact(64L + encoded.length, BatchValueConverter.estimatedRowBytes(detached));
                if (rowBytes > limits.memoryBytes() / 3) {
                    throw failed("One difference row exceeds the sort memory budget");
                }
                if (!chunk.isEmpty() && rowBytes > limits.memoryBytes() / 2 - memory) {
                    runs.add(writeChunk(chunk, keys));
                    chunk.clear();
                    memory = 0;
                }
                chunk.add(new Item(detached, encoded));
                memory = Math.addExact(memory, rowBytes);
                if (runs.size() > 16) {
                    runs.add(writeChunk(chunk, keys));
                    chunk.clear();
                    memory = 0;
                    row = null;
                    encoded = null;
                    detached = null;
                    compact(runs, keys, 16);
                }
            }
            if (!chunk.isEmpty() || runs.isEmpty()) {
                runs.add(writeChunk(chunk, keys));
            }
            chunk.clear();
            compact(runs, keys, 1);
            return runs.getFirst();
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw failed("Difference input could not be materialized");
        }
    }

    /**
     * 写入分块。
     */
    private Run writeChunk(List<Item> chunk, Comparator<Object[]> keys) throws IOException {
        chunk.sort((left, right) -> {
            check();
            return keys.compare(left.values(), right.values());
        });
        for (int i = 1; i < chunk.size(); i++) {
            if (keys.compare(chunk.get(i - 1).values(), chunk.get(i).values()) == 0) {
                throw failed("Duplicate difference matching key");
            }
        }
        try (var output = new Sink()) {
            for (Item item : chunk) {
                output.write(item.encoded());
            }
            return output.run();
        }
    }

    /**
     * 优先归并最小段，限制重复 I/O，并使最多 16 个已完成归并段可被打开。
     */
    private void compact(List<Run> runs, Comparator<Object[]> keys, int maximum) throws IOException {
        while (runs.size() > maximum) {
            // 优先归并最小段，限制重复 I/O，并使最多 16 个已完成归并段可被打开。
            runs.sort(Comparator.comparingLong(run -> ownedFiles.get(run.path())));
            Run first = runs.removeFirst();
            Run second = runs.removeFirst();
            Run combined;
            try (var left = rows(first); var right = rows(second); var output = new Sink()) {
                Object[] one = left.next();
                Object[] two = right.next();
                while (one != null || two != null) {
                    check();
                    int order = one == null ? 1 : two == null ? -1 : keys.compare(one, two);
                    if (order == 0) {
                        throw failed("Duplicate difference matching key");
                    }
                    if (order < 0) {
                        output.write(codec.encode(one));
                        one = left.next();
                    } else {
                        output.write(codec.encode(two));
                        two = right.next();
                    }
                }
                combined = output.run();
            }
            remove(first.path());
            remove(second.path());
            runs.add(combined);
        }
    }

    /**
     * 返回数据行。
     */
    Rows rows(Run run) {
        check();
        if (!ownedFiles.containsKey(run.path())) {
            throw failed("Difference run does not belong to this operation");
        }
        try {
            return new Rows(run);
        } catch (IOException failure) {
            throw failed("Cannot read difference spool");
        }
    }

    final class Rows implements AutoCloseable {

        private final Run run;

        private final DataInputStream input;

        private long ordinal;

        private boolean ended;

        /**
         * 初始化 {@code Rows}。
         */
        private Rows(Run run) throws IOException {
            this.run = run;
            input = new DataInputStream(new BufferedInputStream(Files.newInputStream(run.path()), 8192));
        }

        /**
         * 推进游标并返回下一条数据或后续读取状态。
         */
        Object[] next() {
            check();
            if (ended) {
                return null;
            }
            try {
                if (ordinal == run.rows()) {
                    if (input.read() != -1) {
                        throw failed("Trailing difference spool data");
                    }
                    ended = true;
                    return null;
                }
                int length = input.readInt();
                if (length < 16 || length > maxRowBytes + 16) {
                    throw failed("Invalid difference spool frame");
                }
                byte[] nonce = input.readNBytes(12);
                byte[] ciphertext = input.readNBytes(length);
                if (nonce.length != 12 || ciphertext.length != length) {
                    throw failed("Truncated difference spool frame");
                }
                byte[] plaintext = crypt(Cipher.DECRYPT_MODE, ciphertext, nonce, run.id(), ordinal++);
                return codec.decode(plaintext);
            } catch (IOException | GeneralSecurityException failure) {
                throw failed("Difference spool integrity check failed");
            }
        }

        /**
         * 关闭 {@code DiffRowSort} 当前持有的资源。
         */
        @Override
        public void close() {
            try {
                input.close();
            } catch (IOException failure) {
                throw failed("Cannot close difference spool reader");
            }
        }
    }

    private final class Sink implements AutoCloseable {

        private final Path path;

        private final long id;

        private final DataOutputStream output;

        private long rows;

        /**
         * 初始化 {@code Sink}。
         */
        Sink() throws IOException {
            check();
            id = Math.incrementExact(nextRun);
            nextRun = id;
            path = Files.createTempFile(directory, "run-", ".bin");
            ownedFiles.put(path, 0L);
            output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path), 8192));
        }

        /**
         * 按当前序号和随机数加密数据行并写入排序临时文件。
         */
        void write(byte[] plaintext) throws IOException {
            check();
            try {
                byte[] nonce = ByteBuffer.allocate(12).put(noncePrefix).putLong(sequence).array();
                sequence = Math.incrementExact(sequence);
                byte[] ciphertext = crypt(Cipher.ENCRYPT_MODE, plaintext, nonce, id, rows);
                long bytes = 16L + ciphertext.length;
                if (bytes > limits.maxSpillBytes() - diskBytes) {
                    throw failed("Difference spool exceeds the disk budget");
                }
                sharedBudget.reserve(bytes);
                diskBytes = Math.addExact(diskBytes, bytes);
                ownedFiles.put(path, Math.addExact(ownedFiles.get(path), bytes));
                output.writeInt(ciphertext.length);
                output.write(nonce);
                output.write(ciphertext);
                rows = Math.incrementExact(rows);
            } catch (GeneralSecurityException failure) {
                throw failed("Cannot encrypt difference spool");
            }
        }

        /**
         * 返回当前排序文件、运行编号及已写行数构成的分段描述。
         */
        Run run() {
            return new Run(path, id, rows);
        }

        /**
         * 关闭 {@code DiffRowSort} 当前持有的资源。
         */
        @Override
        public void close() throws IOException {
            output.close();
        }
    }

    /**
     * 使用当前密码协议加密或解密载荷。
     */
    private byte[] crypt(int mode, byte[] input, byte[] nonce, long id, long ordinal) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(keyBytes, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(ByteBuffer.allocate(16).putLong(id).putLong(ordinal).array());
        return cipher.doFinal(input);
    }

    /**
     * 移除当前注册条目或资源记录。
     */
    private void remove(Path path) throws IOException {
        Long bytes = ownedFiles.get(path);
        if (bytes == null || !path.getParent().equals(directory)) {
            throw failed("Invalid difference spool ownership");
        }
        if (ownedDirectoryExists()) {
            Files.deleteIfExists(path);
        }
        ownedFiles.remove(path);
        diskBytes -= bytes;
        sharedBudget.deleted(bytes);
    }

    /**
     * 检查当前作用域独占的目录是否已经存在。
     */
    private boolean ownedDirectoryExists() throws IOException {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException absent) {
            return false;
        }
        if (!attributes.isDirectory() || attributes.isSymbolicLink()
                || directoryFileKey != null && !directoryFileKey.equals(attributes.fileKey())
                || !directoryCreated.equals(attributes.creationTime())) {
            throw new IOException("Difference spool directory ownership changed");
        }
        Path marker = directory.resolve(IDENTITY_FILE);
        BasicFileAttributes markerAttributes = Files.readAttributes(marker, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!markerAttributes.isRegularFile() || markerAttributes.isSymbolicLink() || markerAttributes.size() != directoryIdentity.length) {
            throw new IOException("Difference spool identity changed");
        }
        try (var input = Files.newInputStream(marker, LinkOption.NOFOLLOW_LINKS)) {
            if (!MessageDigest.isEqual(directoryIdentity, input.readNBytes(directoryIdentity.length + 1))) {
                throw new IOException("Difference spool identity changed");
            }
        }
        return true;
    }

    /**
     * 删除独占目录。
     */
    private void deleteOwnedDirectory() throws IOException {
        if (!ownedDirectoryExists()) {
            return;
        }
        Path marker = directory.resolve(IDENTITY_FILE);
        // 外部文件或临时阻塞项导致无法删除时，完整保留归属证据。
        try (var entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                if (!entry.equals(marker)) {
                    throw new DirectoryNotEmptyException(directory.toString());
                }
            }
        }
        Files.delete(marker);
        try {
            Files.deleteIfExists(directory);
        } catch (IOException failure) {
            // 仅在同一目录恢复重试证据，不覆盖其他方的文件。
            try {
                var attributes = Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isDirectory() && !attributes.isSymbolicLink()
                        && (directoryFileKey == null || directoryFileKey.equals(attributes.fileKey()))
                        && directoryCreated.equals(attributes.creationTime())) {
                    Files.write(marker, directoryIdentity, StandardOpenOption.CREATE_NEW);
                }
            } catch (IOException ignored) {
            }
            throw failure;
        }
    }

    /**
     * 检查当前状态是否满足当前操作的约定。
     */
    private void check() {
        if (closed || Thread.currentThread().isInterrupted()) {
            throw failed("Difference sort closed or interrupted");
        }
        cancellation.run();
    }

    /**
     * 关闭 {@code DiffRowSort} 当前持有的资源。
     */
    @Override
    public void close() {
        if (cleanupComplete) {
            return;
        }
        closed = true;
        Arrays.fill(keyBytes, (byte) 0);
        boolean failed = false;
        for (Path path : List.copyOf(ownedFiles.keySet())) {
            try {
                remove(path);
            } catch (IOException failure) {
                failed = true;
            }
        }
        try {
            deleteOwnedDirectory();
        } catch (IOException failure) {
            failed = true;
        }
        if (ownedFiles.isEmpty()) {
            sharedBudget.close();
        }
        if (failed) {
            throw failed("Difference spool cleanup failed");
        }
        cleanupComplete = true;
    }

    /**
     * 记录当前批次或任务的失败状态。
     */
    private static IllegalStateException failed(String message) {
        return new IllegalStateException(message);
    }
}
