package cn.iocoder.yudao.framework.dbswitch.driver;

import cn.iocoder.yudao.framework.dbswitch.common.entity.JarClassLoader;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.*;

/** 上下文持有的运行时。连接池持有租约；物理连接可能仍使用运行时时，不得关闭运行时。 */
public final class DriverRuntimeRegistry implements AutoCloseable {

    public enum State {READY, DRAINING, CLOSED}

    public record Status(String fingerprint, State state, int leases, boolean isolated) {

    }

    private final Map<String, RuntimeHandle> handles = new HashMap<>();

    private final ConnectionBudget connections;

    private final NativeCallControl nativeCalls;

    private boolean closing;

    /**
     * 使用给定参数初始化 {@code DriverRuntimeRegistry}。
     */
    public DriverRuntimeRegistry() {
        this(ConnectionBudget.defaults());
    }

    /**
     * 校验构造参数并初始化 {@code DriverRuntimeRegistry}。
     */
    public DriverRuntimeRegistry(ConnectionBudget connections) {
        this.connections = Objects.requireNonNull(connections);
        this.nativeCalls = new NativeCallControl(connections.snapshot().limit());
    }

    /**
     * 返回数据库连接集合。
     */
    public ConnectionBudget connections() {
        return connections;
    }

    /**
     * 根据当前配置获取可用资源并登记其使用配额。
     */
    public synchronized Lease acquire(VerifiedDriverBundle bundle) {
        return acquire(bundle, ConnectionBudget.Role.WORKLOAD);
    }

    /**
     * 根据当前配置获取可用资源并登记其使用配额。
     */
    public synchronized Lease acquire(VerifiedDriverBundle bundle, ConnectionBudget.Role role) {
        if (closing) {
            throw new IllegalStateException("Driver runtime registry is draining");
        }
        bundle.verifyUnchanged();
        RuntimeHandle handle = handles.get(bundle.fingerprint());
        if (handle == null || handle.state == State.CLOSED) {
            handle = isolated(bundle);
            handles.put(bundle.fingerprint(), handle);
        }
        return lease(handle, role);
    }

    /** 显式构建类路径配置，不声称传递依赖构成已验证的外部制品包。 */
    public synchronized Lease acquireApplication(ProductTypeEnum product, String configuredEntry) {
        return acquireApplication(product, configuredEntry, ConnectionBudget.Role.WORKLOAD);
    }

    /**
     * 获取应用。
     */
    public synchronized Lease acquireApplication(ProductTypeEnum product, String configuredEntry,
            ConnectionBudget.Role role) {
        if (closing) {
            throw new IllegalStateException("Driver runtime registry is draining");
        }
        if (product == null || product == ProductTypeEnum.UNKNOWN) {
            throw new IllegalArgumentException("Known JDBC product required");
        }
        String entry = product.getDriveClassName();
        if (configuredEntry != null && !configuredEntry.isBlank() && !configuredEntry.equals(entry)) {
            throw new IllegalArgumentException("Custom driver classes require a verified deployment bundle");
        }
        ClassLoader loader = DriverRuntimeRegistry.class.getClassLoader();
        try (var input = loader.getResourceAsStream(entry.replace('.', '/') + ".class")) {
            if (input == null) {
                throw new IllegalArgumentException(
                        "Application JDBC driver is unavailable; configure a verified bundle");
            }
            byte[] entryBytes = input.readNBytes(4 * 1024 * 1024 + 1);
            if (entryBytes.length > 4 * 1024 * 1024) {
                throw new IllegalArgumentException("Application driver entry exceeds size limit");
            }
            String key = DriverBundleVerifier.digest(("APPLICATION_CLASSPATH_ENTRY_V1\0" + product + "\0" + entry
                    + "\0" + DriverBundleVerifier.digest(entryBytes)).getBytes(StandardCharsets.UTF_8));
            RuntimeHandle handle = handles.get(key);
            if (handle == null || handle.state == State.CLOSED) {
                handle = new RuntimeHandle(product, key, loader, constructor(loader, entry, product), null,
                        DriverBundleSpec.Lifecycle.PROCESS);
                handles.put(key, handle);
            }
            return lease(handle, role);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Application JDBC driver entry cannot be read");
        }
    }

    /**
     * 返回租约。
     */
    private Lease lease(RuntimeHandle handle, ConnectionBudget.Role role) {
        if (handle.state != State.READY) {
            throw new IllegalStateException("Driver runtime is draining");
        }
        handle.references++;
        return new Lease(this, handle, Objects.requireNonNull(role));
    }

    /**
     * 返回当前驱动运行时状态集合。
     */
    public synchronized List<Status> statuses() {
        return handles.values().stream().map(h -> new Status(h.fingerprint, h.state, h.references, h.directory != null))
                .toList();
    }

    /** 使物理准入与注册表关闭按同一线性顺序进行，但在等待
     * 额度或厂商 connect 时不持有此监视器。已准入调用保留现有租约直到退出。
     * 连接池本地排空不撤销无关存活目标租约中已准入的任务。 */
    private synchronized void admitConnect() throws SQLException {
        if (closing) {
            throw new SQLException("Driver runtime registry is draining", "08003");
        }
    }

    /**
     * 根据当前输入构建并返回 {@code RuntimeHandle}。
     */
    private static RuntimeHandle isolated(VerifiedDriverBundle bundle) {
        // DriverManager 在每个 JVM 中只发现应用 SPI 一次。在厂商执行
        // 或失败清理可能用隔离或外部 TCCL 初始化它之前，先完成该步骤。
        initializeApplicationJdbcSpi();
        Path directory = null;
        JarClassLoader loader = null;
        try {
            directory = Files.createTempDirectory("dbswitch-driver-");
            var copied = new ArrayList<VerifiedDriverBundle.Artifact>();
            int ordinal = 0;
            for (var artifact : bundle.artifacts()) {
                // 保留原始自索引名称，同时避免同名制品冲突。
                Path artifactDirectory = Files.createDirectory(
                        directory.resolve(String.format(Locale.ROOT, "%02d", ordinal++)));
                Path target = artifactDirectory.resolve(artifact.path().getFileName());
                Files.copy(artifact.path(), target);
                copied.add(new VerifiedDriverBundle.Artifact(target, artifact.sha256(), artifact.bytes()));
            }
            // 校验实际将加载的最终字节，而不只校验可替换的部署路径。
            DriverBundleVerifier.verifyArtifacts(copied, bundle.driverClassName());
            for (var artifact : copied) {
                if (!artifact.path().toFile().setReadOnly()) {
                    throw new IOException("Snapshot cannot be made read-only");
                }
            }
            loader = new JarClassLoader(copied.stream().map(VerifiedDriverBundle.Artifact::path).toList());
            return new RuntimeHandle(bundle.product(), bundle.fingerprint(), loader,
                    constructor(loader, bundle.driverClassName(), bundle.product()),
                    directory, bundle.lifecycle());
        } catch (Exception | LinkageError failure) {
            if (loader != null) {
                try {
                    loader.deregisterOwnedDrivers();
                } catch (Exception ignored) {
                }
                try {
                    loader.close();
                } catch (IOException ignored) {
                }
            }
            deleteSnapshot(directory);
            throw new IllegalArgumentException(
                    "JDBC bundle runtime initialization failed: " + failure.getClass().getSimpleName());
        }
    }

    /**
     * 发布前完成类和构造方法初始化。
     */
    private static Constructor<? extends Driver> constructor(ClassLoader loader, String entry,
            ProductTypeEnum product) {
        initializeApplicationJdbcSpi();
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try {
            DriverVendorLogPolicy.beforeInitialization(product, entry);
            Thread.currentThread().setContextClassLoader(loader);
            Class<? extends Driver> type = Class.forName(entry, true, loader).asSubclass(Driver.class);
            Constructor<? extends Driver> constructor = type.getConstructor();
            constructor.newInstance(); // 发布前完成类和构造方法初始化。
            return constructor;
        } catch (ReflectiveOperationException | ClassCastException failure) {
            throw new IllegalArgumentException("JDBC entry must be an instantiable java.sql.Driver");
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    /**
     * 初始化应用类加载器中的 JDBC 驱动发现机制。
     */
    private static void initializeApplicationJdbcSpi() {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(DriverRuntimeRegistry.class.getClassLoader());
            // 使用 JDK 的同步单次 SPI 初始化；厂商列表或
            // 独立初始化标记均不能替代其进程级契约。
            DriverManager.getDrivers();
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    /**
     * 释放当前作用域占用的连接、内存或容量配额。
     */
    private synchronized void release(Lease lease) {
        RuntimeHandle handle = lease.handle;
        lease.released = true;
        if (--handle.references < 0) {
            throw new IllegalStateException("Driver lease underflow");
        }
        releaseDrainingIfExited(lease);
        if (handle.references == 0 && (closing || handle.lifecycle == DriverBundleSpec.Lifecycle.LEASED)) {
            if (handle.physical == 0) {
                retire(handle);
            } else {
                handle.state = State.DRAINING;
            }
        }
        restoreDrainedRuntime(handle);
        closeNativeWorkersIfDrained();
    }

    /**
     * 登记底层连接实际建立的状态。
     */
    private synchronized void physicalOpened(Lease lease) {
        lease.handle.physical++;
        lease.physical++;
    }

    /**
     * 登记底层连接实际关闭的状态。
     */
    private synchronized void physicalClosed(Lease lease) {
        RuntimeHandle handle = lease.handle;
        if (--lease.physical < 0) {
            throw new IllegalStateException("Driver lease physical connection underflow");
        }
        if (--handle.physical < 0) {
            throw new IllegalStateException("Driver physical connection underflow");
        }
        releaseDrainingIfExited(lease);
        if (handle.physical == 0 && handle.references == 0
                && (closing || handle.lifecycle == DriverBundleSpec.Lifecycle.LEASED)) {
            retire(handle);
        }
        restoreDrainedRuntime(handle);
        closeNativeWorkersIfDrained();
    }

    /**
     * 将当前运行资源切换为停止接收新操作的排空状态。
     */
    private synchronized void draining(Lease lease) {
        if (lease.draining || lease.released && lease.physical == 0) {
            return;
        }
        lease.draining = true;
        lease.handle.drainingLeases++;
        lease.handle.state = State.DRAINING;
    }

    /** 排空中的连接池持续阻止准入，直到自身租约及物理连接退出。
     * 无关存活目标租约不得阻止已完成的源连接池恢复准入。 */
    private void releaseDrainingIfExited(Lease lease) {
        if (lease.draining && lease.released && lease.physical == 0) {
            lease.draining = false;
            if (--lease.handle.drainingLeases < 0) {
                throw new IllegalStateException("Driver draining lease underflow");
            }
        }
    }

    /**
     * 恢复已经安全排空的驱动运行时。
     */
    private void restoreDrainedRuntime(RuntimeHandle handle) {
        if (!closing && handle.state == State.DRAINING && handle.drainingLeases == 0
                && (handle.references > 0 || handle.lifecycle == DriverBundleSpec.Lifecycle.PROCESS && handle.physical == 0)) {
            handle.state = State.READY;
        }
    }

    /**
     * 厂商原生后台活动没有通用终止证明。保持其类加载器驻留。
     */
    private void retire(RuntimeHandle handle) {
        handle.state = State.DRAINING;
        if (handle.directory != null && handle.lifecycle == DriverBundleSpec.Lifecycle.PROCESS) {
            // 厂商原生后台活动没有通用终止证明。保持其类加载器驻留。
            return;
        }
        if (handle.loader instanceof JarClassLoader loader) {
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            try {
                initializeApplicationJdbcSpi();
                Thread.currentThread().setContextClassLoader(loader);
                loader.deregisterOwnedDrivers();
                loader.close();
                if (!deleteSnapshot(handle.directory)) {
                    return;
                }
            } catch (Exception failure) {
                return;
            } finally {
                Thread.currentThread().setContextClassLoader(previous);
            }
        }
        handle.state = State.CLOSED;
    }

    /**
     * 关闭 {@code DriverRuntimeRegistry} 当前持有的资源。
     */
    @Override
    public synchronized void close() {
        closing = true;
        for (var handle : handles.values()) {
            if (handle.state == State.CLOSED) {
                continue;
            }
            handle.state = State.DRAINING;
            if (handle.references == 0 && handle.physical == 0) {
                retire(handle);
            }
        }
        closeNativeWorkersIfDrained();
    }

    /**
     * 在原生工作资源全部退出后关闭运行时。
     */
    private void closeNativeWorkersIfDrained() {
        if (closing && handles.values().stream().allMatch(handle -> handle.references == 0 && handle.physical == 0)) {
            nativeCalls.close();
        }
    }

    /**
     * 删除快照。
     */
    private static boolean deleteSnapshot(Path directory) {
        if (directory == null) {
            return true;
        }
        // 此处只传入本方法新创建的私有目录；遍历绝不跟随链接。
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                path.toFile().setWritable(true, true);
                Files.deleteIfExists(path);
            }
            return true;
        } catch (IOException failure) {
            return false;
        }
    }

    private static final class RuntimeHandle {

        final ProductTypeEnum product;

        final String fingerprint;

        final ClassLoader loader;

        final Constructor<? extends Driver> constructor;

        final Path directory;

        final DriverBundleSpec.Lifecycle lifecycle;

        State state = State.READY;

        int references;

        int physical;

        int drainingLeases;

        /**
         * 初始化 {@code RuntimeHandle}。
         */
        RuntimeHandle(ProductTypeEnum product, String fingerprint, ClassLoader loader,
                Constructor<? extends Driver> constructor,
                Path directory, DriverBundleSpec.Lifecycle lifecycle) {
            this.product = product;
            this.fingerprint = fingerprint;
            this.loader = loader;
            this.constructor = constructor;
            this.directory = directory;
            this.lifecycle = lifecycle;
        }
    }

    public static final class Lease implements AutoCloseable {

        private final DriverRuntimeRegistry owner;

        private final RuntimeHandle handle;

        private final ConnectionBudget.Role role;

        private boolean closed;

        /** 受注册表监视器保护；标记排空不得等待阻塞中的 connect()。 */
        private boolean draining, released;

        private int physical;

        /**
         * 初始化 {@code Lease}。
         */
        private Lease(DriverRuntimeRegistry owner, RuntimeHandle handle, ConnectionBudget.Role role) {
            this.owner = owner;
            this.handle = handle;
            this.role = role;
        }

        /**
         * 返回类加载器。
         */
        public ClassLoader classLoader() {
            return handle.loader;
        }

        /**
         * 返回驱动类名。
         */
        public String driverClassName() {
            return handle.constructor.getDeclaringClass().getName();
        }

        /**
         * 根据当前内容计算结构指纹，供一致性比较使用。
         */
        public String fingerprint() {
            return handle.fingerprint;
        }

        /**
         * 根据当前驱动及连接配置建立数据库连接。
         */
        public synchronized Connection connect(String url, Properties properties) throws SQLException {
            if (closed) {
                throw new SQLException("Driver lease is closed");
            }
            owner.admitConnect();
            var permit = owner.connections.acquire(role, java.time.Duration.ofSeconds(30));
            boolean transferred = false;
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            try {
                DriverVendorLogPolicy.beforeInitialization(handle.product, driverClassName());
                Thread.currentThread().setContextClassLoader(handle.loader);
                var driver = handle.constructor.newInstance();
                Connection connection;
                if (handle.product == ProductTypeEnum.MONGODB && driverClassName().equals(
                        "com.gitee.jdbc.mongodb.JdbcDriver")) {
                    var prepared = MongoNetworkPolicy.prepare(url, properties);
                    MongoNetworkPolicy.verifyParsed(prepared, handle.loader);
                    connection = driver.connect(prepared.url(), prepared.properties());
                } else {
                    connection = driver.connect(url, properties);
                }
                if (connection == null) {
                    throw new SQLException("JDBC driver did not accept the connection URL");
                }
                permit.connected();
                owner.physicalOpened(this);
                Connection tracked = trackPhysical(connection, permit);
                // 从此处起，仅物理跟踪器持有许可。适配器失败
                // 必须通过它关闭；关闭结果不确定时，资源仍处于隔离状态。
                transferred = true;
                try {
                    if (handle.product == ProductTypeEnum.INFLUXDB3
                            && driverClassName().equals("org.apache.arrow.driver.jdbc.ArrowFlightJdbcDriver")) {
                        return InfluxFlightJdbcAdapter.wrap(tracked, handle.loader);
                    }
                    return tracked;
                } catch (RuntimeException | Error failure) {
                    try {
                        tracked.close();
                    } catch (SQLException | RuntimeException ignored) {
                        permit.quarantine();
                    }
                    throw failure;
                }
            } catch (SQLException failure) {
                String state = failure.getSQLState();
                if (state != null && !state.matches("[A-Z0-9]{5}")) {
                    state = null;
                }
                throw new SQLException("JDBC connection establishment failed", state, failure.getErrorCode());
            } catch (ReflectiveOperationException | LinkageError failure) {
                throw new SQLException("JDBC driver instance could not be initialized");
            } catch (RuntimeException failure) {
                throw new SQLException("JDBC connection establishment failed");
            } finally {
                if (!transferred) {
                    permit.close();
                }
                Thread.currentThread().setContextClassLoader(previous);
            }
        }

        /**
         * Mongo 原生命令和 Influx 的 HTTP 写入及管理操作必须保留物理。
         */
        private Connection trackPhysical(Connection connection, ConnectionBudget.Permit permit) {
            var exited = new java.util.concurrent.atomic.AtomicBoolean();
            var physicalTermination = new java.util.concurrent.CompletableFuture<Void>();
            // Mongo 原生命令和 Influx 的 HTTP 写入及管理操作必须保留物理
            // 许可，直到原生工作线程与 JDBC 连接均退出。仅关闭 Flight
            // 不能证明并发 HTTP 请求已停止。
            var nativeSessions = new java.util.concurrent.atomic.AtomicReference<NativeCallControl.Session>(
                    handle.product == ProductTypeEnum.MONGODB
                            || handle.product == ProductTypeEnum.INFLUXDB3
                            && driverClassName().equals(ProductTypeEnum.INFLUXDB3.getDriveClassName())
                            ? owner.nativeCalls.bind(connection, handle.loader, permit::quarantine, () -> {
                        if (exited.compareAndSet(false, true)) {
                            permit.close();
                            owner.physicalClosed(this);
                            physicalTermination.complete(null);
                        }
                    }) : null);
            var directClosing = new java.util.concurrent.atomic.AtomicBoolean();
            return (Connection) java.lang.reflect.Proxy.newProxyInstance(DriverNativeAccess.class.getClassLoader(),
                    new Class<?>[]{Connection.class, DriverNativeAccess.ConnectionAccess.class},
                    (proxy, method, args) -> {
                        ClassLoader previous = Thread.currentThread().getContextClassLoader();
                        boolean exit = method.getName().equals("close") || method.getName().equals("abort");
                        NativeCallControl.Session nativeSession;
                        synchronized (nativeSessions) {
                            if (exit) {
                                directClosing.set(true);
                            }
                            // 普通 JDBC 保持原关闭路径；只有明确要求有界探测时才启用。
                            if (method.getDeclaringClass() == DriverNativeAccess.ConnectionAccess.class
                                    && (method.getName().equals("invokeNative") || method.getName()
                                    .equals("requestClose"))
                                    && nativeSessions.get() == null) {
                                if (directClosing.get() || exited.get()) {
                                    throw new SQLException("JDBC connection is closing", "08003");
                                }
                                nativeSessions.set(
                                        owner.nativeCalls.bind(connection, handle.loader, permit::quarantine, () -> {
                                            if (exited.compareAndSet(false, true)) {
                                                permit.close();
                                                owner.physicalClosed(this);
                                                physicalTermination.complete(null);
                                            }
                                        }));
                            }
                            nativeSession = nativeSessions.get();
                        }
                        try {
                            Thread.currentThread().setContextClassLoader(handle.loader);
                            if (method.getDeclaringClass() == DriverNativeAccess.ConnectionAccess.class) {
                                if (method.getName().equals("physicalTermination")) {
                                    return physicalTermination.minimalCompletionStage();
                                }
                                if (method.getName().equals("requestClose")) {
                                    nativeSession.abortConnection();
                                    return physicalTermination.minimalCompletionStage();
                                }
                                if (method.getName().equals("invokeNative")) {
                                    return nativeSession.invoke((java.util.concurrent.Callable<?>) args[0],
                                            (long) args[1]);
                                }
                                if (nativeSession != null && nativeSession.isClosing()) {
                                    throw new SQLException("Native connection is closing");
                                }
                                Class<?> type = Class.forName((String) args[0], false, handle.loader);
                                return type.isInstance(connection) ? connection : connection.unwrap(type);
                            }
                            if (method.getName().equals("unwrap") && ((Class<?>) args[0]).isInstance(proxy)) {
                                return proxy;
                            }
                            if (method.getName().equals("isWrapperFor") && ((Class<?>) args[0]).isInstance(proxy)) {
                                return true;
                            }
                            if (nativeSession != null) {
                                if (method.getName().equals("close")) {
                                    nativeSession.closeConnection();
                                    return null;
                                }
                                if (method.getName().equals("abort")) {
                                    nativeSession.abortConnection();
                                    return null;
                                }
                                if (nativeSession.isClosing()) {
                                    if (method.getName().equals("isClosed")) {
                                        return nativeSession.isTerminated();
                                    }
                                    if (method.getName().equals("isValid")) {
                                        return false;
                                    }
                                    // Hikari 在归还逻辑连接时清除警告。此
                                    // 维护操作不能重启原生任务，也不能表示物理退出。
                                    if (method.getName().equals("clearWarnings") || method.getName()
                                            .equals("getWarnings")) {
                                        return null;
                                    }
                                    throw new SQLException("Native connection is closing", "08003");
                                }
                            }
                            if (exit && !exited.get()) {
                                permit.quarantine();
                            }
                            try {
                                return method.invoke(connection, args);
                            } catch (java.lang.reflect.InvocationTargetException failure) {
                                throw failure.getCause();
                            }
                        } finally {
                            if (exit && nativeSession == null) {
                                try {
                                    if (connection.isClosed() && exited.compareAndSet(false, true)) {
                                        permit.close();
                                        owner.physicalClosed(this);
                                        physicalTermination.complete(null);
                                    }
                                } catch (SQLException ignored) { /* 不确定的物理资源仍计入额度。 */ }
                            }
                            Thread.currentThread().setContextClassLoader(previous);
                        }
                    });
        }

        /**
         * 将当前运行时标记为排空状态并停止接收新工作。
         */
        public void markDraining() {
            owner.draining(this);
        }

        /**
         * 关闭 {@code DriverRuntimeRegistry} 当前持有的资源。
         */
        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            owner.release(this);
        }
    }
}
