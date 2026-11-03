package cn.iocoder.yudao.framework.dbswitch.data.query;

import cn.iocoder.yudao.framework.dbswitch.common.entity.InvisibleDataSource;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.util.JdbcTypesUtils;
import cn.iocoder.yudao.framework.dbswitch.common.util.JdbcValueConversions;
import cn.iocoder.yudao.framework.dbswitch.core.database.AbstractDatabase;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.driver.ConnectionBudget;
import cn.iocoder.yudao.framework.dbswitch.driver.DriverPoolFactory;
import cn.iocoder.yudao.framework.dbswitch.driver.JdbcPoolOptions;
import com.zaxxer.hikari.HikariDataSource;

import java.lang.reflect.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** 上下文管理的 JDBC 操作生命周期；授权和持久任务归属由调用方负责。 */
public final class QueryRuntime implements AutoCloseable {

    public record AdmissionLimits(int maximumOperations, int maximumOperationsPerScope) {

        /**
         * 校验构造参数并初始化 {@code AdmissionLimits}。
         */
        public AdmissionLimits {
            if (maximumOperations < 2 || maximumOperations > 4096 || maximumOperationsPerScope < 1
                    || maximumOperationsPerScope >= maximumOperations) {
                throw new IllegalArgumentException(
                        "Query scope quota must be positive and smaller than total capacity");
            }
        }

        /**
         * 创建当前组件的默认配置。
         */
        public static AdmissionLimits defaults() {
            return new AdmissionLimits(256, 2);
        }
    }

    public enum Termination {RUNNING, CONFIRMED, UNCONFIRMED}

    public enum CancelResult {REQUESTED, CONFIRMED, UNCONFIRMED}

    public record ConnectionSpec(ProductTypeEnum product, String url, String username, String password,
                                 String driverProfileId, String configurationGeneration) {

        /**
         * 校验构造参数并初始化 {@code ConnectionSpec}。
         */
        public ConnectionSpec {
            Objects.requireNonNull(product);
            Objects.requireNonNull(url);
        }

        /**
         * 返回当前对象的文本表示。
         */
        @Override
        public String toString() {
            return "QueryConnection[product=" + product + "]";
        }
    }

    public record Page(List<ColumnDescription> columns, List<List<Object>> rows, boolean exhausted) {

    }

    private record Key(String scope, String executionId) {

    }

    private final DbSwitchRuntime runtime;

    private final AdmissionLimits limits;

    private final QueryMetrics metrics = new QueryMetrics();

    private final Map<String, Integer> quotaUsage = new HashMap<>();

    private final ConcurrentMap<Key, Operation> operations = new ConcurrentHashMap<>();

    private final ExecutorService cancellation = executor("dbswitch-query-cancel", 4, 128);

    private final ExecutorService cleanup = executor("dbswitch-query-cleanup", 4, 128);

    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
            threads("dbswitch-query-clock"));

    private volatile boolean closing;

    /**
     * 使用给定参数初始化 {@code QueryRuntime}。
     */
    public QueryRuntime(DbSwitchRuntime runtime) {
        this(runtime, AdmissionLimits.defaults());
    }

    /**
     * 校验构造参数并初始化 {@code QueryRuntime}。
     */
    public QueryRuntime(DbSwitchRuntime runtime, AdmissionLimits limits) {
        this.runtime = Objects.requireNonNull(runtime);
        this.limits = Objects.requireNonNull(limits);
        timer.scheduleWithFixedDelay(this::tick, 100, 100, TimeUnit.MILLISECONDS);
    }

    /**
     * 登记受管资源的停止回调及实际退出检查。
     */
    public Operation register(String authorizedScope, String executionId, UUID generation, OperationBudget budget) {
        return register(authorizedScope, authorizedScope, executionId, generation, budget);
    }

    /** quotaScope 是调用方已认证的资源归属，由其全部操作范围共享。 */
    public synchronized Operation register(String authorizedScope, String quotaScope, String executionId,
            UUID generation, OperationBudget budget) {
        if (closing) {
            throw new IllegalStateException("Query runtime is draining");
        }
        if (authorizedScope == null || authorizedScope.isBlank() || quotaScope == null || quotaScope.isBlank()
                || executionId == null || executionId.isBlank()) {
            throw new IllegalArgumentException("Authorized operation scope and identity are required");
        }
        var key = new Key(authorizedScope, executionId);
        var operation = new Operation(key, quotaScope, Objects.requireNonNull(generation),
                Objects.requireNonNull(budget));
        if (operations.size() >= limits.maximumOperations()
                || quotaUsage.getOrDefault(quotaScope, 0) >= limits.maximumOperationsPerScope()
                || operations.putIfAbsent(key, operation) != null) {
            metrics.rejected();
            throw new IllegalStateException("Operation registry is full or identity is already active");
        }
        quotaUsage.merge(quotaScope, 1, Integer::sum);
        if (closing) {
            operation.requestCancel(authorizedScope);
        }
        return operation;
    }

    /**
     * 返回当前仍在运行的操作数量。
     */
    public int activeOperations() {
        return operations.size();
    }

    /**
     * 返回资源限制。
     */
    public AdmissionLimits limits() {
        return limits;
    }

    /**
     * 返回指标。
     */
    public QueryMetrics metrics() {
        return metrics;
    }

    /**
     * 释放当前作用域占用的连接、内存或容量配额。
     */
    private synchronized boolean release(Operation operation) {
        if (!operations.remove(operation.key, operation)) {
            return false;
        }
        quotaUsage.computeIfPresent(operation.quotaScope, (scope, count) -> count == 1 ? null : count - 1);
        return true;
    }

    /**
     * 检查当前期限、停止请求或周期性运行状态。
     */
    private void tick() {
        for (var operation : operations.values()) {
            try {
                operation.tick();
            } catch (RuntimeException ignored) { /* 单个厂商驱动不能阻止上下文时钟运行。 */ }
        }
        if (closing && operations.isEmpty()) {
            finishClose();
        }
    }

    /**
     * 关闭 {@code QueryRuntime} 当前持有的资源。
     */
    @Override
    public synchronized void close() {
        closing = true;
        for (var operation : operations.values()) {
            operation.requestCancel(operation.key.scope());
        }
        if (operations.isEmpty()) {
            finishClose();
        }
    }

    /**
     * 完成关闭流程并释放剩余持有资源。
     */
    private void finishClose() {
        cleanup.shutdown();
        cancellation.shutdown();
        timer.shutdown();
    }

    /**
     * 创建或返回当前运行所使用的线程集合。
     */
    private static ThreadFactory threads(String prefix) {
        var counter = new AtomicInteger();
        return task -> Thread.ofPlatform().name(prefix + "-" + counter.incrementAndGet()).daemon()
                .inheritInheritableThreadLocals(false).unstarted(task);
    }

    /**
     * 返回执行器。
     */
    private static ExecutorService executor(String name, int workers, int queue) {
        return new ThreadPoolExecutor(workers, workers, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queue), threads(name), new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 将当前批次加入待执行队列。
     */
    private static boolean enqueue(ExecutorService executor, Runnable work) {
        try {
            executor.execute(work);
            return true;
        } catch (RejectedExecutionException ignored) {
            // 不降级为调用线程执行：JDBC 取消和关闭可能阻塞，操作仍保持未确认。
            return false;
        }
    }

    public final class Operation {

        private final Key key;

        private final String quotaScope;

        private final UUID generation;

        private final OperationBudget budget;

        private final long deadline;

        private final long registeredAt = System.nanoTime();

        private final AtomicLong cancellationStarted = new AtomicLong();

        private final Set<Session> sessions = ConcurrentHashMap.newKeySet();

        private final Set<Statement> statements = ConcurrentHashMap.newKeySet();

        private final AtomicInteger inFlight = new AtomicInteger();

        private final AtomicInteger pendingCancellations = new AtomicInteger();

        private final Object controlLifecycle = new Object();

        private final AtomicLong materializedRows = new AtomicLong(), materializedBytes = new AtomicLong();

        private final CompletableFuture<Termination> terminal = new CompletableFuture<>();

        private volatile Thread worker;

        private volatile boolean cancelled, expired, workerExited;

        private volatile Termination termination = Termination.RUNNING;

        /**
         * 初始化 {@code Operation}。
         */
        private Operation(Key key, String quotaScope, UUID generation, OperationBudget budget) {
            this.key = key;
            this.quotaScope = quotaScope;
            this.generation = generation;
            this.budget = budget;
            deadline = System.nanoTime() + budget.total().toNanos();
        }

        /**
         * 返回代次。
         */
        public UUID generation() {
            return generation;
        }

        /**
         * 返回已取消。
         */
        public boolean cancellationRequested() {
            return cancelled;
        }

        /**
         * 返回过期。
         */
        public boolean deadlineExpired() {
            return expired;
        }

        /**
         * 返回当前终止。
         */
        public Termination termination() {
            refreshTermination();
            return termination;
        }

        /**
         * 返回当前末尾。
         */
        public CompletionStage<Termination> terminal() {
            return terminal.minimalCompletionStage();
        }

        /**
         * 绑定工作进程。
         */
        public void bindWorker() throws SQLException {
            synchronized (this) {
                if (worker != null || workerExited) {
                    throw new IllegalStateException("Operation worker already installed");
                }
                worker = Thread.currentThread();
            }
            boolean success = false;
            try {
                check();
                success = true;
            } finally {
                metrics.record(QueryMetrics.Phase.QUEUE, success, registeredAt);
            }
        }

        /**
         * 检查当前状态是否满足当前操作的约定。
         */
        public void check() throws SQLException {
            if (expired || System.nanoTime() - deadline >= 0) {
                expired = true;
                cancelResources();
                throw new SQLTimeoutException("Query operation deadline exceeded");
            }
            if (cancelled || Thread.currentThread().isInterrupted()) {
                throw new SQLTransientException("Query operation cancelled");
            }
        }

        /**
         * 计算当前读取期限剩余的可用秒数。
         */
        private int remainingSeconds() throws SQLException {
            check();
            return (int) Math.max(1, Math.min(120, (deadline - System.nanoTime() + 999_999_999L) / 1_000_000_000L));
        }

        /**
         * 检查当前输入操作是否满足授权约束。
         */
        private void authorize(String scope) {
            if (!key.scope().equals(scope)) {
                throw new SecurityException("Operation scope mismatch");
            }
        }

        /**
         * 记录取消请求并通知当前运行中的操作。
         */
        public CancelResult requestCancel(String authorizedScope) {
            authorize(authorizedScope);
            if (termination() == Termination.CONFIRMED) {
                return CancelResult.CONFIRMED;
            }
            cancelled = true;
            cancelResources();
            return termination == Termination.UNCONFIRMED ? CancelResult.UNCONFIRMED : CancelResult.REQUESTED;
        }

        /**
         * 取消资源集合。
         */
        private void cancelResources() {
            synchronized (controlLifecycle) {
                if (termination == Termination.CONFIRMED) {
                    return;
                }
                cancellationStarted.compareAndSet(0, System.nanoTime());
                for (Statement statement : statements) {
                    cancelStatement(statement);
                }
                for (Session session : sessions) {
                    session.abort();
                }
            }
            Thread active = worker;
            if (active != null && active != Thread.currentThread()) {
                active.interrupt();
            }
        }

        private final Set<Statement> cancellationSent = ConcurrentHashMap.newKeySet();

        /**
         * 取消语句。
         */
        private void cancelStatement(Statement statement) {
            synchronized (controlLifecycle) {
                if (termination == Termination.CONFIRMED || !cancellationSent.add(statement)) {
                    return;
                }
                pendingCancellations.incrementAndGet();
                inFlight.incrementAndGet();
                if (!enqueue(cancellation, () -> {
                    try {
                        statement.cancel();
                    } catch (Exception ignored) {
                    } finally {
                        pendingCancellations.decrementAndGet();
                        inFlight.decrementAndGet();
                    }
                })) {
                    pendingCancellations.decrementAndGet();
                    inFlight.decrementAndGet();
                    cancellationSent.remove(statement);
                }
            }
        }

        /**
         * 中止可能使驱动的取消键或套接字失效，应先等待取消操作返回；。
         */
        private boolean readyToAbort() {
            // 中止可能使驱动的取消键或套接字失效，应先等待取消操作返回；
            // 厂商调用若阻塞，仍在调用方显式停止预算耗尽后升级处理。
            return pendingCancellations.get() == 0 && cancellationSent.containsAll(statements)
                    || System.nanoTime() - cancellationStarted.get() >= budget.cleanupWait().toNanos();
        }

        /**
         * 打开会话。
         */
        public Session openSession(ConnectionSpec spec, ConnectionBudget.Role role) throws SQLException {
            check();
            final Session session;
            synchronized (this) {
                if (workerExited) {
                    throw new SQLException("Query operation worker has exited");
                }
                if (sessions.stream().filter(existing -> !existing.hasExited()).count() >= 2) {
                    throw new SQLTransientException("Operation session limit exceeded");
                }
                session = new Session(this, spec, role);
                sessions.add(session);
            }
            try {
                session.connect();
                check();
                return session;
            } catch (SQLException | RuntimeException | Error failure) {
                session.close();
                throw failure;
            }
        }

        /**
         * 记录工作线程或进程退出并释放相关配额。
         */
        public synchronized void workerExited() {
            workerExited = true;
            worker = null;
            synchronized (controlLifecycle) {
                expireIfElapsed();
            }
            sessions.forEach(Session::close);
            refreshTermination();
        }

        /**
         * 等待当前工作资源真正退出。
         */
        public Termination awaitTermination() {
            boolean interrupted = Thread.interrupted();
            try {
                try {
                    terminal.get(budget.cleanupWait().toMillis(), TimeUnit.MILLISECONDS);
                } catch (InterruptedException failure) {
                    interrupted = true;
                } catch (ExecutionException | TimeoutException ignored) {
                }
                refreshTermination();
                synchronized (controlLifecycle) {
                    if (termination != Termination.CONFIRMED) {
                        termination = Termination.UNCONFIRMED;
                    }
                    return termination;
                }
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        /**
         * 检查当前期限、停止请求或周期性运行状态。
         */
        private void tick() {
            synchronized (controlLifecycle) {
                // 注册表迭代器可能已持有被完成操作释放的引用。
                if (termination == Termination.CONFIRMED) {
                    return;
                }
                if (System.nanoTime() - deadline >= 0) {
                    expired = true;
                }
                if (cancelled || expired) {
                    cancelResources();
                }
            }
            sessions.forEach(session -> {
                session.expireCursors();
                if (session.closing.get()) {
                    session.close();
                }
            });
            refreshTermination();
        }

        /**
         * 刷新终止。
         */
        private void refreshTermination() {
            boolean confirmed;
            synchronized (controlLifecycle) {
                expireIfElapsed();
                confirmed = workerExited && inFlight.get() == 0 && sessions.stream().allMatch(Session::hasExited);
                if (confirmed) {
                    termination = Termination.CONFIRMED;
                }
            }
            // 退出控制锁后再获取注册表锁；关闭时为请求取消全部操作，
            // 会按相反顺序获取这两把锁。
            if (confirmed && release(this)) {
                if (cancellationStarted.get() != 0) {
                    metrics.record(QueryMetrics.Phase.CANCEL_TO_EXIT, true, cancellationStarted.get());
                }
                terminal.complete(Termination.CONFIRMED);
            }
        }

        /** 在 controlLifecycle 保护下调用；即使完成操作先于下一次时钟检查，也不能漏掉过期。 */
        private void expireIfElapsed() {
            if (termination != Termination.CONFIRMED && !expired && System.nanoTime() - deadline >= 0) {
                expired = true;
                cancelResources();
            }
        }

        /**
         * 为当前数据行计入资源预算。
         */
        private void chargeRow() throws SQLException {
            if (materializedRows.incrementAndGet() > budget.maxRows()) {
                expired = true;
                cancelResources();
                throw new SQLTimeoutException("Query row budget exceeded");
            }
        }

        /**
         * 为当前字段值计入资源预算。
         */
        private void chargeValue(Object value) throws SQLException {
            long bytes = JdbcTypesUtils.getObjectSize(Types.OTHER, value);
            if (bytes > budget.maxCellBytes() || materializedBytes.addAndGet(bytes) > budget.maxBytes()) {
                expired = true;
                cancelResources();
                throw new SQLTimeoutException("Query byte budget exceeded");
            }
        }
    }

    /** 一个显式会话始终使用同一自主管理的连接池和连接；归还借用连接不会切换会话。 */
    public final class Session implements AutoCloseable {

        private final Operation operation;

        private final ConnectionSpec spec;

        private final ConnectionBudget.Role role;

        private final HikariDataSource pool;

        private final InvisibleDataSource physical;

        private final Set<Cursor> cursors = ConcurrentHashMap.newKeySet();

        private final AtomicBoolean closing = new AtomicBoolean(), abortSent = new AtomicBoolean(), cleanupQueued = new AtomicBoolean();

        private volatile Connection connection;

        private volatile Connection facade;

        private volatile boolean cleanupReturned;

        /**
         * 校验构造参数并初始化 {@code Session}。
         */
        private Session(Operation operation, ConnectionSpec spec, ConnectionBudget.Role role) throws SQLException {
            this.operation = operation;
            this.spec = Objects.requireNonNull(spec);
            this.role = Objects.requireNonNull(role);
            var defaults = runtime.options();
            var options = new JdbcPoolOptions(spec.product(), spec.url(), spec.product().getDriveClassName(),
                    spec.username(), spec.password(), new Properties(), 1, 300_000,
                    Math.max(250, Math.min(30_000, operation.remainingSeconds() * 1000L)),
                    defaults.poolValidationTimeoutMs(), defaults.poolIdleTimeoutMs(), 0, 0,
                    null, threads("dbswitch-query-pool"), null);
            pool = DriverPoolFactory.create(options,
                    runtime.drivers().find(spec.product(), spec.driverProfileId()).orElse(null),
                    runtime.drivers().runtimes(), role);
            physical = (InvisibleDataSource) pool.getDataSource();
        }

        /**
         * 拒绝当前实现未支持的 {@code connect} 操作。
         */
        private void connect() throws SQLException {
            long started = System.nanoTime();
            boolean success = false;
            operation.inFlight.incrementAndGet();
            try {
                operation.check();
                connection = pool.getConnection();
                operation.check();
                // 套接字断开是最终清理手段；使驱动取消键失效前，必须给截止时间或语句
                // 取消操作留出发送请求的时间。
                connection.setNetworkTimeout(command -> {
                    if (!enqueue(cancellation, command)) {
                        throw new RejectedExecutionException("Query timeout executor is full");
                    }
                }, (int) (operation.remainingSeconds() * 1000L
                        + Math.max(1000L, operation.budget.cleanupWait().toMillis())));
                connection.setReadOnly(true);
                connection.setAutoCommit(false);
                facade = (Connection) Proxy.newProxyInstance(QueryRuntime.class.getClassLoader(),
                        new Class<?>[]{Connection.class},
                        (proxy, method, args) -> {
                            if (method.getDeclaringClass() == Object.class) {
                                return objectMethod(proxy, method, args);
                            }
                            if (isWrapperMethod(method)) {
                                return wrapperMethod(proxy, method, args);
                            }
                            if (method.getName().equals("close")) {
                                return null;
                            }
                            if (Set.of("abort", "commit", "rollback", "setAutoCommit", "setReadOnly",
                                    "setNetworkTimeout", "setTransactionIsolation", "setSavepoint", "releaseSavepoint",
                                    "prepareCall", "createBlob", "createClob", "createNClob", "createSQLXML",
                                    "createArrayOf", "createStruct").contains(method.getName())) {
                                throw new SQLFeatureNotSupportedException(
                                        "Query session owns transaction and connection resources");
                            }
                            Object value = invoke(connection, method, args);
                            if (value instanceof Statement statement) {
                                return statement(statement);
                            }
                            if (value instanceof DatabaseMetaData metadata) {
                                return metadata(metadata);
                            }
                            return value;
                        });
                success = true;
            } finally {
                metrics.record(QueryMetrics.Phase.CONNECT, success, started);
                operation.inFlight.decrementAndGet();
            }
        }

        /**
         * 返回数据库连接。
         */
        public Connection connection() throws SQLException {
            operation.check();
            if (closing.get()) {
                throw new SQLException("Query session is closing");
            }
            return Objects.requireNonNull(facade);
        }

        /**
         * 返回当前运行时绑定的驱动指纹。
         */
        public String driverFingerprint() {
            return physical.fingerprint();
        }

        /**
         * 返回当前配置生效的代次标记。
         */
        public String configurationGeneration() {
            return spec.configurationGeneration();
        }

        /**
         * 检查当前状态是否满足当前操作的约定。
         */
        public void check() throws SQLException {
            operation.check();
        }

        /**
         * 解析当前产品对应的数据库方言。
         */
        public AbstractDatabase dialect() {
            return runtime.products().metadata(spec.product());
        }

        /**
         * 读取或整理当前操作涉及的表集合。
         */
        public List<cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription> tables(
                String schema) throws SQLException {
            operation.check();
            return dialect().queryTableList(connection(), schema);
        }

        /**
         * 读取或组装当前操作所使用的列定义。
         */
        public List<ColumnDescription> columns(String schema, String table) throws SQLException {
            operation.check();
            var dialect = dialect();
            var columns = dialect.queryTableColumnMeta(connection(), schema, table);
            dialect.setColumnDefaultValue(connection(), schema, table, columns);
            dialect.setColumnIndexInfo(connection(), schema, table, columns);
            return columns;
        }

        /** 调用方提供已授权的生成 SQL；用户 SQL 须先通过对应产品公开的 SQL 计划或校验器。 */
        public synchronized Cursor query(String authorizedScope, String sql, List<?> parameters) throws SQLException {
            operation.authorize(authorizedScope);
            operation.check();
            if (cursors.size() >= 4) {
                throw new SQLTransientException("Session cursor limit exceeded");
            }
            PreparedStatement statement = connection().prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY,
                    ResultSet.CONCUR_READ_ONLY);
            try {
                statement.setFetchSize(operation.budget.pageRows());
                statement.setMaxRows((int) operation.budget.maxRows() + 1);
                for (int i = 0; i < parameters.size(); i++) {
                    statement.setObject(i + 1, parameters.get(i));
                }
                Cursor cursor = new Cursor(this, statement, statement.executeQuery());
                cursors.add(cursor);
                return cursor;
            } catch (SQLException | RuntimeException failure) {
                statement.close();
                throw failure;
            }
        }

        /**
         * 调用当前受控运行时的目标方法并传播调用结果。
         */
        private Object invoke(Object target, Method method, Object[] args) throws Throwable {
            boolean exit = method.getName().equals("close") || method.getName().equals("isClosed");
            if (!exit) {
                operation.check();
            }
            operation.inFlight.incrementAndGet();
            long started = System.nanoTime();
            QueryMetrics.Phase phase = target instanceof DatabaseMetaData ? QueryMetrics.Phase.METADATA
                    : method.getName().startsWith("execute") ? role == ConnectionBudget.Role.METADATA
                    ? QueryMetrics.Phase.METADATA : QueryMetrics.Phase.EXECUTE
                    : target instanceof ResultSet && (method.getName().equals("next") || method.getName()
                    .startsWith("get"))
                    ? role == ConnectionBudget.Role.METADATA ? QueryMetrics.Phase.METADATA : QueryMetrics.Phase.FETCH : null;
            boolean success = false;
            try {
                try {
                    Object value = method.invoke(target, args);
                    success = true;
                    return value;
                } catch (InvocationTargetException failure) {
                    Throwable cause = failure.getCause();
                    if (cause instanceof SQLException sql) {
                        throw new SQLException("JDBC query operation failed",
                                sql.getSQLState() != null && sql.getSQLState()
                                        .matches("[A-Z0-9]{5}") ? sql.getSQLState() : null, sql.getErrorCode());
                    }
                    if (cause instanceof RuntimeException) {
                        throw new SQLException("JDBC query operation failed");
                    }
                    throw cause;
                }
            } finally {
                metrics.record(phase, success, started);
                operation.inFlight.decrementAndGet();
            }
        }

        /**
         * 拒绝当前实现未支持的 {@code statement} 操作。
         */
        private Statement statement(Statement statement) throws SQLException {
            operation.statements.add(statement);
            if (operation.cancelled || operation.expired) {
                operation.cancelStatement(statement);
                operation.check();
            }
            statement.setQueryTimeout(operation.remainingSeconds());
            Class<?> api = statement instanceof CallableStatement ? CallableStatement.class
                    : statement instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
            return (Statement) Proxy.newProxyInstance(QueryRuntime.class.getClassLoader(), new Class<?>[]{api},
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return objectMethod(proxy, method, args);
                        }
                        if (isWrapperMethod(method)) {
                            return wrapperMethod(proxy, method, args);
                        }
                        if (method.getName().equals("getConnection")) {
                            return facade;
                        }
                        if (Set.of("addBatch", "executeBatch", "executeLargeBatch", "executeUpdate",
                                        "executeLargeUpdate")
                                .contains(method.getName())) {
                            throw new SQLFeatureNotSupportedException("Query session does not execute mutations");
                        }
                        if (method.getName().equals("execute") || method.getName().startsWith("execute")) {
                            operation.check();
                        }
                        Object value = invoke(statement, method, args);
                        if (method.getName().equals("close")) {
                            operation.statements.remove(statement);
                        }
                        if (value instanceof ResultSetMetaData metadata) {
                            return jdbcMetadata(metadata, ResultSetMetaData.class);
                        }
                        if (value instanceof ParameterMetaData metadata) {
                            return jdbcMetadata(metadata, ParameterMetaData.class);
                        }
                        return value instanceof ResultSet rows ? rows(rows) : value;
                    });
        }

        /**
         * 返回元数据。
         */
        private DatabaseMetaData metadata(DatabaseMetaData metadata) {
            return (DatabaseMetaData) Proxy.newProxyInstance(QueryRuntime.class.getClassLoader(),
                    new Class<?>[]{DatabaseMetaData.class},
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return objectMethod(proxy, method, args);
                        }
                        if (isWrapperMethod(method)) {
                            return wrapperMethod(proxy, method, args);
                        }
                        if (method.getName().equals("getConnection")) {
                            return facade;
                        }
                        Object value = invoke(metadata, method, args);
                        return value instanceof ResultSet rows ? rows(rows) : value;
                    });
        }

        /**
         * 拒绝当前实现未支持的 {@code rows} 操作。
         */
        private ResultSet rows(ResultSet rows) {
            return (ResultSet) Proxy.newProxyInstance(QueryRuntime.class.getClassLoader(),
                    new Class<?>[]{ResultSet.class}, (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return objectMethod(proxy, method, args);
                        }
                        if (isWrapperMethod(method)) {
                            return wrapperMethod(proxy, method, args);
                        }
                        if (Set.of("getBinaryStream", "getAsciiStream", "getUnicodeStream", "getCharacterStream",
                                        "getNCharacterStream", "getBlob", "getClob", "getNClob", "getArray", "getRef",
                                        "getSQLXML", "getRowId")
                                .contains(method.getName())) {
                            throw new SQLFeatureNotSupportedException(
                                    "Use bounded getObject for JDBC stream and locator values");
                        }
                        if (method.getName().startsWith("update") || Set.of("insertRow", "deleteRow", "moveToInsertRow")
                                .contains(method.getName())) {
                            throw new SQLFeatureNotSupportedException("Query result is read-only");
                        }
                        operation.inFlight.incrementAndGet();
                        try {
                            Object value = invoke(rows, method, args);
                            if (method.getName().equals("getStatement")) {
                                return value == null ? null : statement((Statement) value);
                            }
                            if (value instanceof ResultSetMetaData metadata) {
                                return jdbcMetadata(metadata, ResultSetMetaData.class);
                            }
                            if (method.getName().equals("next") && Boolean.TRUE.equals(value)) {
                                operation.chargeRow();
                            }
                            if (method.getName().equals("getObject")) {
                                value = detachedValue(value);
                            }
                            if (method.getName()
                                    .startsWith("get") && value != null && !(value instanceof java.sql.Wrapper)) {
                                operation.chargeValue(value);
                            }
                            return value;
                        } finally {
                            operation.inFlight.decrementAndGet();
                        }
                    });
        }

        /**
         * 厂商标量值转换为有界文本；原生对象不得经公开查询游标向外暴露。
         */
        private Object detachedValue(Object value) throws SQLException {
            try {
                if (value instanceof java.io.InputStream stream) {
                    try {
                        stream.close();
                    } catch (java.io.IOException ignored) {
                    }
                    throw new SQLException("Driver stream values are unavailable on a public query cursor");
                }
                if (value instanceof java.io.Reader reader) {
                    try {
                        reader.close();
                    } catch (java.io.IOException ignored) {
                    }
                    throw new SQLException("Driver stream values are unavailable on a public query cursor");
                }
                value = JdbcValueConversions.normalize(value, operation.budget.maxCellBytes());
                if (value == null || value instanceof String || value instanceof Boolean || value instanceof Character
                        || value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                        || value instanceof Float || value instanceof Double || value instanceof java.math.BigInteger
                        || value instanceof java.math.BigDecimal || value instanceof byte[] || value instanceof UUID
                        || value instanceof java.sql.Date || value instanceof java.sql.Time || value instanceof java.sql.Timestamp
                        || value.getClass().getPackageName().equals("java.time")) {
                    return value;
                }
                // 厂商标量值转换为有界文本；原生对象不得经公开查询游标向外暴露。
                return JdbcValueConversions.toText(value);
            } catch (RuntimeException failure) {
                throw new SQLException("JDBC query value is unsupported or exceeds its limit");
            }
        }

        /**
         * 通过当前连接读取 JDBC 元数据。
         */
        private <T> T jdbcMetadata(T value, Class<T> api) {
            return api.cast(Proxy.newProxyInstance(QueryRuntime.class.getClassLoader(), new Class<?>[]{api},
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return objectMethod(proxy, method, args);
                        }
                        if (isWrapperMethod(method)) {
                            return wrapperMethod(proxy, method, args);
                        }
                        return invoke(value, method, args);
                    }));
        }

        /**
         * 判断当前调用是否为 JDBC 包装接口方法。
         */
        private boolean isWrapperMethod(Method method) {
            return method.getName().equals("unwrap") || method.getName().equals("isWrapperFor");
        }

        /**
         * 按当前包装协议处理 JDBC 包装接口调用。
         */
        private Object wrapperMethod(Object proxy, Method method, Object[] args) throws SQLException {
            boolean supported = ((Class<?>) args[0]).isInstance(proxy);
            if (method.getName().equals("isWrapperFor")) {
                return supported;
            }
            if (supported) {
                return proxy;
            }
            throw new SQLFeatureNotSupportedException("Native JDBC access is unavailable on a public query session");
        }

        /**
         * 处理代理对象的相等、哈希或文本表示方法。
         */
        private Object objectMethod(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "toString" -> "BoundedQueryJdbc[" + spec.product() + "]";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new IllegalStateException("Unsupported object method");
            };
        }

        /**
         * 请求终止当前数据库或运行资源。
         */
        private void abort() {
            if (operation.readyToAbort() && abortSent.compareAndSet(false, true)) {
                operation.inFlight.incrementAndGet();
                if (!enqueue(cancellation, () -> {
                    Connection active = connection;
                    try {
                        if (active != null) {
                            active.abort(Runnable::run);
                        }
                    } catch (Exception ignored) {
                    } finally {
                        operation.inFlight.decrementAndGet();
                    }
                })) {
                    operation.inFlight.decrementAndGet();
                    abortSent.set(false);
                }
            }
            close();
        }

        /**
         * 关闭 {@code QueryRuntime} 当前持有的资源。
         */
        @Override
        public void close() {
            synchronized (operation.controlLifecycle) {
                closing.set(true);
                // JDBC 工作线程或取消控制仍在运行时归还借用的 Hikari 连接，
                // 会导致语句驱逐或连接重置与执行过程产生竞争。
                if ((operation.cancelled || operation.expired)
                        && (!operation.workerExited || operation.inFlight.get() != 0
                        || !operation.cancellationSent.containsAll(operation.statements))) {
                    return;
                }
                if (!cleanupQueued.compareAndSet(false, true)) {
                    return;
                }
                if (!enqueue(cleanup, () -> {
                    long started = System.nanoTime();
                    boolean success = false;
                    boolean interrupted = Thread.interrupted();
                    try {
                        Connection active = connection;
                        try {
                            if (active != null) {
                                active.close();
                            }
                        } finally {
                            pool.close();
                        }
                        success = physical.hasExited();
                    } catch (
                            Exception ignored) { /* 终止状态由物理资源管理方的证据确定，不能由此处捕获异常决定。 */ } finally {
                        metrics.record(QueryMetrics.Phase.CLEANUP, success, started);
                        cleanupReturned = true;
                        if (interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    }
                })) {
                    cleanupQueued.set(false);
                }
            }
        }

        /**
         * 判断是否具有退出。
         */
        public boolean hasExited() {
            return closing.get() && cleanupReturned && physical.hasExited();
        }

        /**
         * 关闭当前作用域中已经过期的读取游标。
         */
        private void expireCursors() {
            long now = System.nanoTime();
            for (Cursor cursor : cursors) {
                if (now - cursor.lastUse > operation.budget.idleCursor().toNanos()) {
                    operation.expired = true;
                    operation.cancelResources();
                    break;
                }
            }
        }
    }

    public final class Cursor implements AutoCloseable {

        private final Session session;

        private final PreparedStatement statement;

        private final ResultSet rows;

        private final List<ColumnDescription> columns;

        private final UUID id = UUID.randomUUID();

        private volatile long lastUse = System.nanoTime();

        private boolean closed, exhausted;

        /**
         * 校验构造参数并初始化 {@code Cursor}。
         */
        private Cursor(Session session, PreparedStatement statement, ResultSet rows) throws SQLException {
            this.session = session;
            this.statement = statement;
            this.rows = rows;
            ResultSetMetaData metadata = rows.getMetaData();
            if (metadata.getColumnCount() > 1024) {
                throw new SQLException("Query column limit exceeded");
            }
            var columns = new ArrayList<ColumnDescription>();
            for (int i = 1; i <= metadata.getColumnCount(); i++) {
                var column = new ColumnDescription();
                column.setFieldName(metadata.getColumnName(i));
                column.setLabelName(metadata.getColumnLabel(i));
                column.setFieldType(metadata.getColumnType(i));
                column.setFieldTypeName(metadata.getColumnTypeName(i));
                column.setPrecisionSize(metadata.getPrecision(i));
                column.setScaleSize(metadata.getScale(i));
                column.setNullable(metadata.isNullable(i) != ResultSetMetaData.columnNoNulls);
                columns.add(column);
            }
            this.columns = List.copyOf(columns);
        }

        /**
         * 返回编号。
         */
        public UUID id() {
            return id;
        }

        /**
         * 根据当前输入构建并返回 {@code Page}。
         */
        public synchronized Page fetch(String authorizedScope, UUID cursorId, int maximumRows) throws SQLException {
            session.operation.authorize(authorizedScope);
            if (!id.equals(cursorId)) {
                throw new SecurityException("Cursor identity mismatch");
            }
            if (closed) {
                throw new SQLException("Cursor is closed");
            }
            session.operation.check();
            if (maximumRows < 1 || maximumRows > session.operation.budget.pageRows()) {
                throw new IllegalArgumentException("Invalid cursor page size");
            }
            lastUse = System.nanoTime();
            var result = new ArrayList<List<Object>>();
            while (result.size() < maximumRows) {
                if (!rows.next()) {
                    exhausted = true;
                    break;
                }
                var row = new ArrayList<Object>();
                for (int i = 1; i <= columns.size(); i++) {
                    row.add(rows.getObject(i));
                }
                result.add(Collections.unmodifiableList(row));
            }
            lastUse = System.nanoTime();
            return new Page(columns, List.copyOf(result), exhausted);
        }

        /**
         * 关闭 {@code QueryRuntime} 当前持有的资源。
         */
        @Override
        public synchronized void close() throws SQLException {
            if (closed) {
                return;
            }
            closed = true;
            try {
                rows.close();
            } finally {
                try {
                    statement.close();
                } finally {
                    session.cursors.remove(this);
                }
            }
        }
    }
}
