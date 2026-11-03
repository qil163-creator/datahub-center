package cn.iocoder.yudao.framework.etl.core.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 每次流程的临时文件及发布收据；单文件原子发布，多文件不宣称整体事务。 */
public final class EtlFileOutputs {

    private static final Set<Path> ACTIVE_TARGETS = ConcurrentHashMap.newKeySet();

    // 无法确定工作进程是否终止时，不得让 GC 释放写入器隔离锁。仅随进程退出释放。
    private static final Set<Output> UNCONFIRMED = ConcurrentHashMap.newKeySet();

    private final EtlFileOutputPolicy policy;

    private final cn.iocoder.yudao.framework.etl.core.connection.EtlFileOutputPort port;

    private final long maxBytes;

    private final Map<String, Output> outputs = new LinkedHashMap<>();

    private boolean closed;

    /**
     * 使用给定参数初始化 {@code EtlFileOutputs}。
     */
    public EtlFileOutputs(EtlFileOutputPolicy policy) {
        this(policy, null);
    }

    /**
     * 使用给定参数初始化 {@code EtlFileOutputs}。
     */
    public EtlFileOutputs(EtlFileOutputPolicy policy,
            cn.iocoder.yudao.framework.etl.core.connection.EtlFileOutputPort port) {
        this(policy, port, EtlFileStore.MAX_BYTES);
    }

    /**
     * 校验构造参数并初始化 {@code EtlFileOutputs}。
     */
    public EtlFileOutputs(EtlFileOutputPolicy policy,
            cn.iocoder.yudao.framework.etl.core.connection.EtlFileOutputPort port, long maxBytes) {
        if (maxBytes < 1 || maxBytes > EtlFileStore.MAX_BYTES) {
            throw new IllegalArgumentException("Invalid file byte budget");
        }
        this.policy = policy;
        this.port = port;
        this.maxBytes = maxBytes;
    }

    /** 仅能打开已登记的暂存输出，且只能打开一次。计数在文本编码后、磁盘写入前进行。 */
    public synchronized java.io.OutputStream open(String nodeNo) throws IOException {
        if (closed) {
            throw new IllegalStateException("流程文件输出已结束");
        }
        Output output = outputs.get(nodeNo);
        if (output == null || output.opened || output.ready) {
            throw new IllegalStateException("文件输出未登记或已打开");
        }
        if (output.binding != null && !output.fileKey.equals(EtlFileStore.fileKey(output.binding, output.temporary))) {
            throw new IOException("Staged file identity changed");
        }
        var stream = Files.newOutputStream(output.temporary, java.nio.file.StandardOpenOption.WRITE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING, java.nio.file.LinkOption.NOFOLLOW_LINKS);
        output.opened = true;
        return bounded(stream, maxBytes, () -> output.writeFailed = true, () -> output.streamClosed = true);
    }

    /**
     * 校验当前输入未超过约定的大小或数量上限。
     */
    static java.io.OutputStream bounded(java.io.OutputStream stream, long maxBytes, Runnable onFailure,
            Runnable onClosed) {
        return bounded(stream, new ByteBudget(maxBytes), onFailure, onClosed);
    }

    private static final class ByteBudget {

        private final long max;

        private long used;

        /**
         * 初始化 {@code ByteBudget}。
         */
        private ByteBudget(long max) {
            this.max = max;
        }

        /**
         * 为当前文件输出预留字节预算，超额时拒绝写入。
         */
        private synchronized void claim(long bytes) throws IOException {
            if (bytes > max - used) {
                throw new IOException("文件输出超过字节预算");
            }
            used += bytes;
        }
    }

    /**
     * 校验当前输入未超过约定的大小或数量上限。
     */
    private static java.io.OutputStream bounded(java.io.OutputStream stream, ByteBudget budget, Runnable onFailure,
            Runnable onClosed) {
        return new java.io.OutputStream() {
            private boolean streamClosed;

            private boolean failed;

            /**
             * 标记当前操作失败并调用其失败处理逻辑。
             */
            private void fail() {
                failed = true;
                onFailure.run();
            }

            /**
             * 检查字节数组是否满足当前操作的约定。
             */
            private void require(long bytes) throws IOException {
                if (streamClosed || failed) {
                    throw new IOException("文件输出流已关闭或失败");
                }
                if (Thread.currentThread().isInterrupted()) {
                    fail();
                    throw new java.io.InterruptedIOException("文件输出已中断");
                }
                try {
                    budget.claim(bytes);
                } catch (IOException failure) {
                    fail();
                    throw failure;
                }
            }

            /**
             * 按输出格式将数据写入当前文件或输出端口。
             */
            @Override
            public void write(int value) throws IOException {
                require(1);
                try {
                    stream.write(value);
                } catch (IOException failure) {
                    fail();
                    throw failure;
                }
            }

            /**
             * 按输出格式将数据写入当前文件或输出端口。
             */
            @Override
            public void write(byte[] data, int offset, int length) throws IOException {
                java.util.Objects.checkFromIndexSize(offset, length, data.length);
                require(length);
                try {
                    stream.write(data, offset, length);
                } catch (IOException failure) {
                    fail();
                    throw failure;
                }
            }

            /**
             * 将缓冲区中已积累的数据提交给当前输出端。
             */
            @Override
            public void flush() throws IOException {
                require(0);
                try {
                    stream.flush();
                } catch (IOException failure) {
                    fail();
                    throw failure;
                }
            }

            /**
             * 关闭 {@code EtlFileOutputs} 当前持有的资源。
             */
            @Override
            public void close() throws IOException {
                if (streamClosed) {
                    return;
                }
                streamClosed = true;
                try {
                    stream.close();
                } catch (IOException failure) {
                    fail();
                    throw failure;
                } finally {
                    onClosed.run();
                }
            }
        };
    }

    /** 在同一操作中登记有界辅助对象；绝不接受任意文件系统路径。 */
    public synchronized EtlTemporaryResource temporary(String nodeNo, String name) throws IOException {
        Output output = outputs.get(nodeNo);
        if (closed || output == null || output.ready || output.resources.containsKey(
                name) || output.resources.size() >= 4) {
            throw new IllegalStateException("文件临时资源不可登记");
        }
        var intent = new EtlFileStore.TemporaryResource(name, "INTENT", null);
        if (output.resourceBinding == null) {
            if (output.binding != null) {
                output.resourceBinding = output.binding;
            } else {
                String operation = java.util.UUID.randomUUID().toString();
                output.resourceBinding = new EtlFileStore.Binding(operation, java.util.UUID.randomUUID().toString(),
                        output.target,
                        output.target.getParent().resolve(".etl-" + operation + ".part"),
                        output.target.getParent().resolve(".etl-" + operation + ".lock"));
                // 独立策略使用者没有持久化恢复执行方。其不透明临时命名空间仅属于此输出。
            }
            output.scratchBudget = new ByteBudget(maxBytes);
        }
        var resource = new Temporary(output, intent);
        output.resources.put(name, resource);
        try {
            if (port != null) {
                port.temporaryIntent(output.binding, name);
            }
            String key = EtlFileStore.createTemporary(output.resourceBinding, name);
            resource.receipt = new EtlFileStore.TemporaryResource(name, "CREATED", key);
            if (port != null) {
                port.temporaryCreated(output.binding, name, key);
            }
            return resource;
        } catch (IOException | RuntimeException failure) {
            output.writeFailed = true;
            throw failure;
        }
    }

    private final class Temporary implements EtlTemporaryResource {

        private final Output owner;

        private EtlFileStore.TemporaryResource receipt;

        private java.io.OutputStream stream;

        private boolean streamClosed, cleaned;

        /**
         * 初始化 {@code Temporary}。
         */
        Temporary(Output owner, EtlFileStore.TemporaryResource receipt) {
            this.owner = owner;
            this.receipt = receipt;
        }

        /**
         * 返回路径。
         */
        private Path path() {
            return EtlFileStore.temporaryPath(owner.resourceBinding, receipt.name());
        }

        /**
         * 返回身份信息。
         */
        private void identity() throws IOException {
            if (cleaned || receipt.fileKey() == null || !receipt.fileKey()
                    .equals(EtlFileStore.temporaryKey(owner.resourceBinding, receipt.name()))) {
                throw new IOException("Temporary resource identity changed");
            }
        }

        /**
         * 打开输出流并准备后续访问。
         */
        @Override
        public synchronized java.io.OutputStream open() throws IOException {
            if (stream != null || closed || owner.writeFailed) {
                throw new IOException("Temporary resource cannot be opened");
            }
            identity();
            stream = bounded(Files.newOutputStream(path(), java.nio.file.StandardOpenOption.WRITE,
                            java.nio.file.LinkOption.NOFOLLOW_LINKS),
                    owner.scratchBudget, () -> owner.writeFailed = true, () -> streamClosed = true);
            return stream;
        }

        /**
         * 校验并解析允许读取的本地文件路径。
         */
        @Override
        public synchronized Path pathForRead() throws IOException {
            if (stream == null || !streamClosed || owner.writeFailed) {
                throw new IOException("Temporary resource write is incomplete");
            }
            identity();
            return path();
        }

        /**
         * 校验当前临时资源身份后返回已登记的路径。
         */
        @Override
        public synchronized Path registeredPath() throws IOException {
            identity();
            return path();
        }

        /**
         * 打开输入。
         */
        @Override
        public synchronized java.io.InputStream openInput() throws IOException {
            return Files.newInputStream(pathForRead(), java.nio.file.LinkOption.NOFOLLOW_LINKS);
        }

        /**
         * 关闭 {@code EtlFileOutputs} 当前持有的资源。
         */
        @Override
        public synchronized void close() throws IOException {
            if (cleaned) {
                return;
            }
            IOException closing = null;
            try {
                if (stream != null) {
                    stream.close();
                }
            } catch (IOException failure) {
                closing = failure;
                owner.writeFailed = true;
            }
            try {
                var next = EtlFileStore.discardTemporary(owner.resourceBinding, receipt);
                if (port != null) {
                    port.temporaryCleaned(owner.binding, next.name(), next.fileKey());
                }
                receipt = next;
                cleaned = true;
            } catch (IOException | RuntimeException failure) {
                owner.writeFailed = true;
                if (closing == null) {
                    closing = new IOException("Temporary resource cleanup is unconfirmed", failure);
                } else {
                    closing.addSuppressed(failure);
                }
            }
            if (closing != null) {
                throw closing;
            }
        }
    }

    /**
     * 准备当前阶段的数据或目标状态。
     */
    public synchronized Path stage(String nodeNo, String directory, String fileName) throws IOException {
        if (closed) {
            throw new IllegalStateException("流程文件输出已结束");
        }
        if (outputs.containsKey(nodeNo)) {
            throw new IllegalStateException("节点已登记文件输出");
        }
        if (port != null) {
            var binding = port.reserve(nodeNo, directory, fileName); // 创建任何临时文件或锁文件前，先持久化意图。
            Path allowed = policy.resolve(binding.target().getParent().toString(),
                    binding.target().getFileName().toString());
            if (!allowed.equals(binding.target()) || Files.exists(binding.target(),
                    java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("文件目标已存在或授权绑定不一致");
            }
            if (!ACTIVE_TARGETS.add(allowed)) {
                throw new IllegalStateException("输出目标已被其他节点或流程占用");
            }
            Output output = new Output(binding.target(), binding.temporary());
            output.binding = binding;
            try {
                output.lock = EtlFileStore.acquire(binding);
                outputs.put(nodeNo, output);
                port.beforeCreate(binding);
                output.fileKey = EtlFileStore.create(binding);
                port.created(binding, output.fileKey);
                return binding.temporary();
            } catch (IOException | RuntimeException failure) {
                if (output.lock == null) {
                    ACTIVE_TARGETS.remove(allowed);
                }
                throw failure;
            }
        }
        Path target = policy.resolve(directory, fileName);
        if (!ACTIVE_TARGETS.add(target)) {
            throw new IllegalStateException("输出目标已被其他节点或流程占用");
        }
        try {
            Path temporary = Files.createTempFile(target.getParent(), ".etl-", ".part");
            outputs.put(nodeNo, new Output(target, temporary));
            return temporary;
        } catch (IOException | RuntimeException ex) {
            ACTIVE_TARGETS.remove(target);
            throw ex;
        }
    }

    /**
     * 检查当前资源是否已经准备就绪。
     */
    public synchronized void ready(String nodeNo) {
        if (closed) {
            throw new IllegalStateException("流程文件输出已结束");
        }
        Output output = outputs.get(nodeNo);
        if (output == null) {
            throw new IllegalStateException("节点未登记文件输出");
        }
        if (output.writeFailed || output.opened && !output.streamClosed || output.resources.values().stream()
                .anyMatch(r -> !r.cleaned)) {
            throw new IllegalStateException("文件输出流或临时资源尚未成功完成");
        }
        try {
            if (Files.size(output.temporary) > maxBytes) {
                throw new IllegalStateException("文件输出超过字节预算");
            }
        } catch (IOException failure) {
            throw new java.io.UncheckedIOException("文件暂存核验失败", failure);
        }
        if (port != null) {
            try {
                output.fingerprint = EtlFileStore.seal(output.binding);
                if (!output.fileKey.equals(output.fingerprint.fileKey())) {
                    throw new IOException("Staged file identity changed");
                }
                port.ready(new EtlFileStore.Prepared(output.binding, output.fingerprint));
            } catch (IOException failure) {
                throw new java.io.UncheckedIOException("文件暂存核验失败", failure);
            }
        }
        output.ready = true;
    }

    /** 所有节点确认退出并成功后调用；不支持原子移动的文件系统直接失败。 */
    public synchronized void publish() throws IOException {
        if (closed) {
            throw new IllegalStateException("流程文件输出已结束");
        }
        for (Output output : outputs.values()) {
            if (!output.ready) {
                throw new IllegalStateException("文件节点尚未完成临时写入");
            }
            policy.validateTarget(output.target);
        }
        if (port != null) {
            // 确认失败无法证明持久化决策未成功提交。
            outputs.values().forEach(o -> o.publicationAttempted = true);
            port.approvePublish(
                    outputs.values().stream().map(o -> new EtlFileStore.Prepared(o.binding, o.fingerprint)).toList());
            for (Output output : outputs.values()) {
                try {
                    EtlFileStore.publish(new EtlFileStore.Prepared(output.binding, output.fingerprint));
                } catch (IOException failure) {
                    try {
                        if (output.fingerprint.equals(EtlFileStore.fingerprint(output.binding, output.target))) {
                            output.state = "PUBLISHED_CLEANUP_PENDING";
                        }
                    } catch (IOException unknown) { /* 持久化 PUBLISHING 意图必须对未知结果进行核对。 */ }
                    throw failure;
                }
                output.state = "PUBLISHED";
                port.published(new EtlFileStore.Prepared(output.binding, output.fingerprint));
            }
            return;
        }
        for (Output output : outputs.values()) {
            if (policy.replaceExisting()) {
                Files.move(output.temporary, output.target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                output.state = "PUBLISHED";
            } else {
                // ATOMIC_MOVE 可能忽略“不覆盖”选项；排他硬链接创建防止跨进程覆盖。
                Files.createLink(output.target, output.temporary);
                output.state = "PUBLISHED";
                Files.delete(output.temporary);
            }
        }
    }

    /** 关闭后不接受迟到的写入登记，清理失败或节点未退出保留可核查状态。 */
    public synchronized void discard(boolean terminationConfirmed) {
        closed = true;
        for (Output output : outputs.values()) {
            if (!terminationConfirmed) {
                output.state = output.state.startsWith("PUBLISHED") ? "PUBLISHED_CLEANUP_PENDING" : "UNKNOWN";
                UNCONFIRMED.add(output);
                if (port != null) {
                    try {
                        port.discarded(output.binding, output.state);
                    } catch (RuntimeException ignored) { /* 持久化意图仍可恢复。 */ }
                }
                continue;
            }
            boolean resourcesCleaned = true;
            for (var resource : output.resources.values()) {
                try {
                    resource.close();
                } catch (IOException failure) {
                    resourcesCleaned = false;
                }
            }
            if (!resourcesCleaned) {
                output.state = output.state.startsWith("PUBLISHED") ? "PUBLISHED_CLEANUP_PENDING" : "CLEANUP_PENDING";
            } else if (output.publicationAttempted && !output.state.startsWith("PUBLISHED")) {
                // 持久化发布决策可能已经生效。保留封存的字节供核对。
                output.state = "UNKNOWN";
            } else if (!output.state.startsWith("PUBLISHED")) {
                try {
                    if (port == null) {
                        Files.deleteIfExists(output.temporary);
                    } else {
                        EtlFileStore.discardUnpublished(output.binding, output.fileKey);
                    }
                    output.state = terminationConfirmed ? "DISCARDED" : "UNKNOWN";
                } catch (IOException ex) {
                    output.state = "CLEANUP_PENDING";
                }
            } else {
                try {
                    if (port == null) {
                        Files.deleteIfExists(output.temporary);
                    } else {
                        EtlFileStore.discard(output.binding, output.fileKey);
                    }
                    output.state = "PUBLISHED";
                } catch (IOException ex) {
                    output.state = "PUBLISHED_CLEANUP_PENDING";
                }
            }
            if (port != null) {
                try {
                    port.discarded(output.binding, output.state);
                } catch (RuntimeException failure) {
                    output.state = output.state.startsWith(
                            "PUBLISHED") ? "PUBLISHED_CLEANUP_PENDING" : "CLEANUP_PENDING";
                }
            }
            try {
                if (output.lock != null) {
                    output.lock.close();
                }
            } catch (IOException failure) {
                output.state = "CLEANUP_PENDING";
                UNCONFIRMED.add(output);
            }
            if (terminationConfirmed) {
                ACTIVE_TARGETS.remove(output.target);
            }
        }
    }

    /**
     * 返回各文件输出节点当前状态的不可变快照。
     */
    public synchronized Map<String, String> states() {
        Map<String, String> result = new LinkedHashMap<>();
        outputs.forEach((node, output) -> result.put(node, output.state));
        return Map.copyOf(result);
    }

    private final class Output {

        final Path target;

        final Path temporary;

        boolean ready;

        boolean opened;

        volatile boolean streamClosed;

        volatile boolean writeFailed;

        boolean publicationAttempted;

        String state = "STAGED";

        EtlFileStore.Binding binding;

        EtlFileStore.Binding resourceBinding;

        ByteBudget scratchBudget;

        final Map<String, Temporary> resources = new LinkedHashMap<>();

        EtlFileStore.Lock lock;

        String fileKey;

        EtlFileStore.Fingerprint fingerprint;

        /**
         * 初始化 {@code Output}。
         */
        Output(Path target, Path temporary) {
            this.target = target;
            this.temporary = temporary;
        }
    }
}
