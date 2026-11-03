package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.core.model.ReadSnapshot;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.TransactionCommitReceipt;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.CommitRangeReceipt;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Collections;

/** PostgreSQL 独占借用连接的只读 REPEATABLE READ；不接管已有事务，不自动重试。 */
public final class PostgresReadSnapshot implements ReadSnapshot {

    private static final String SNAPSHOT_SQL = """
            SELECT pg_catalog.pg_current_snapshot()::text, pg_catalog.pg_backend_pid(),
                   pg_catalog.current_setting('transaction_isolation'),
                   pg_catalog.current_setting('transaction_read_only'), l.virtualxid
            FROM pg_catalog.pg_locks l
            WHERE l.pid = pg_catalog.pg_backend_pid() AND l.locktype = 'virtualxid'
              AND l.mode = 'ExclusiveLock' AND l.granted
            """;

    private final Connection connection;

    private final boolean originalReadOnly;

    private final int originalIsolation;

    private final int timeoutSeconds;

    private Evidence evidence;

    private boolean closed;

    /**
     * 初始化 {@code PostgresReadSnapshot}。
     */
    private PostgresReadSnapshot(Connection connection, boolean readOnly, int isolation, int timeoutSeconds) {
        this.connection = connection;
        this.originalReadOnly = readOnly;
        this.originalIsolation = isolation;
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * 打开读取快照并准备后续访问。
     */
    public static ReadSnapshot open(Connection connection, int timeoutSeconds) throws SQLException {
        if (connection == null || timeoutSeconds < 1 || timeoutSeconds > 300) {
            throw new IllegalArgumentException("A connection and bounded snapshot timeout are required");
        }
        // 所有所有权校验先于更改连接；调用方原有事务不提交、不回滚、不关闭。
        PostgresReadSnapshot snapshot;
        try {
            if (!connection.getAutoCommit()) {
                throw new SQLException("Read snapshot cannot adopt an existing transaction");
            }
            if (!"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())) {
                throw new SQLException("Read snapshot product does not match PostgreSQL");
            }
            snapshot = new PostgresReadSnapshot(connection, connection.isReadOnly(),
                    connection.getTransactionIsolation(), timeoutSeconds);
        } catch (SQLException invalid) {
            // 支持产品的驱动失败不是产品能力缺失，不能返回 FeatureNotSupported 诱发静默降级。
            throw new SQLException("Read snapshot connection validation failed");
        }
        try {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            snapshot.evidence = snapshot.observe();
            return snapshot;
        } catch (Throwable failure) {
            // 初始化失败也不能将半配置会话交回后续规则；从不 reset autoCommit 来隐式提交。
            snapshot.discard();
            if (failure instanceof Error fatal) {
                throw fatal;
            }
            throw new SQLException("Read snapshot could not be established");
        }
    }

    /**
     * 返回证据。
     */
    @Override
    public Evidence evidence() {
        return evidence;
    }

    /**
     * 有界、同连接、同快照；只在作用域正常 close 后发布。旧实现明确不支持，不伪造可见。
     */
    @Override
    public CommitVisibility inspectCommits(List<TransactionCommitReceipt> receipts) throws SQLException {
        if (closed) {
            throw new SQLException("Read snapshot is already closed");
        }
        if (receipts == null || receipts.isEmpty() || receipts.size() > MAX_COMMIT_OBSERVATIONS
                || receipts.stream().anyMatch(java.util.Objects::isNull)
                || new HashSet<>(receipts).size() != receipts.size()) {
            throw new IllegalArgumentException("A bounded, distinct set of commit receipts is required");
        }
        receipts = List.copyOf(receipts);
        requireCommitProduct();
        // 诊断失败不能打断有效的普通质检；保存点只恢复可选查询，不更换实际读取事务。
        try (var ignored = checkpoint()) {
            requireUnchanged();
            String route = observeRoute();
            var matched = receipts.stream().filter(r -> route.equals(r.routeFingerprint())).toList();
            var states = new HashMap<String, String>();
            if (!matched.isEmpty()) {
                String values = String.join(",", Collections.nCopies(matched.size(), "(?::text)"));
                String sql = "WITH requested(xid) AS (VALUES " + values + ") SELECT xid, "
                        + "pg_catalog.pg_xact_status(xid::xid8), "
                        + "pg_catalog.pg_visible_in_snapshot(xid::xid8, pg_catalog.pg_current_snapshot()) FROM requested";
                try (var statement = connection.prepareStatement(sql)) {
                    statement.setQueryTimeout(Math.min(timeoutSeconds, 5));
                    statement.setMaxRows(MAX_COMMIT_OBSERVATIONS + 1);
                    for (int i = 0; i < matched.size(); i++) {
                        statement.setString(i + 1, matched.get(i).transactionId());
                    }
                    try (var rows = statement.executeQuery()) {
                        while (rows.next()) {
                            String xid = rows.getString(1), status = rows.getString(2);
                            boolean visible = rows.getBoolean(3);
                            if (rows.wasNull() || states.size() >= matched.size()
                                    || matched.stream().noneMatch(r -> r.transactionId().equals(xid))) {
                                throw new SQLException("Commit visibility response invalid");
                            }
                            String state = status == null ? "STATUS_UNAVAILABLE" : !"committed".equals(status)
                                    ? "NOT_COMMITTED" : visible ? "VISIBLE" : "NOT_VISIBLE";
                            if (states.put(xid, state) != null) {
                                throw new SQLException("Commit visibility response duplicated");
                            }
                        }
                    }
                }
                if (states.size() != matched.size()) {
                    throw new SQLException("Commit visibility response incomplete");
                }
            }
            requireUnchanged();
            if (!route.equals(observeRoute())) {
                throw new SQLException("Commit visibility route changed");
            }
            var observations = new ArrayList<CommitObservation>();
            for (var receipt : receipts) {
                observations.add(new CommitObservation(receipt,
                        route.equals(receipt.routeFingerprint()) ? states.get(
                                receipt.transactionId()) : "ROUTE_MISMATCH"));
            }
            return new CommitVisibility(1, evidence, observations);
        } catch (SQLException | RuntimeException failure) {
            // 不传播 SQL、xid、地址或厂商错误；checkpoint 清理失败会废弃连接，最终 close 仍拒绝发布。
            throw new SQLException("Commit visibility could not be observed");
        }
    }

    /**
     * 检查提交范围集合。
     */
    @Override
    public RangeVisibility inspectCommitRanges(List<CommitRangeReceipt> ranges) throws SQLException {
        if (closed) {
            throw new SQLException("Read snapshot is already closed");
        }
        if (ranges == null || ranges.isEmpty() || ranges.size() > MAX_COMMIT_RANGES
                || ranges.stream().anyMatch(java.util.Objects::isNull) || new HashSet<>(
                ranges).size() != ranges.size()) {
            throw new IllegalArgumentException("A bounded, distinct set of commit ranges is required");
        }
        ranges = List.copyOf(ranges);
        requireCommitProduct();
        try (var ignored = checkpoint()) {
            requireUnchanged();
            String route = observeRoute();
            var matched = ranges.stream().filter(r -> route.equals(r.routeFingerprint())).toList();
            var states = new HashMap<Integer, String>();
            if (!matched.isEmpty()) {
                String values = String.join(",", Collections.nCopies(matched.size(), "(?::integer,?::xid8,?::xid8)"));
                // 只查端点可见会漏掉区间内在快照时仍未完成的事务。整个区间必须低于 xmax 且不与 xip 相交。
                // 区间内无关的活动事务会保守拒绝；摘要自身保证实际批次均已确认提交，SQL 不认证摘要真伪。
                String sql = "WITH requested(ord,first_xid,last_xid) AS (VALUES " + values + "), "
                        + "snapshot AS (SELECT pg_catalog.pg_current_snapshot() s) "
                        + "SELECT ord, pg_catalog.pg_xact_status(first_xid), pg_catalog.pg_xact_status(last_xid), "
                        + "last_xid < pg_catalog.pg_snapshot_xmax(s) AND NOT EXISTS "
                        + "(SELECT 1 FROM pg_catalog.pg_snapshot_xip(s) x WHERE x BETWEEN first_xid AND last_xid) "
                        + "FROM requested CROSS JOIN snapshot";
                try (var statement = connection.prepareStatement(sql)) {
                    statement.setQueryTimeout(Math.min(timeoutSeconds, 5));
                    statement.setMaxRows(MAX_COMMIT_RANGES + 1);
                    for (int i = 0; i < matched.size(); i++) {
                        statement.setInt(i * 3 + 1, i);
                        statement.setString(i * 3 + 2, matched.get(i).firstTransactionId());
                        statement.setString(i * 3 + 3, matched.get(i).lastTransactionId());
                    }
                    try (var rows = statement.executeQuery()) {
                        while (rows.next()) {
                            int ordinal = rows.getInt(1);
                            if (rows.wasNull() || ordinal < 0 || ordinal >= matched.size()) {
                                throw new SQLException();
                            }
                            String first = rows.getString(2), last = rows.getString(3);
                            boolean visible = rows.getBoolean(4);
                            if (rows.wasNull()) {
                                throw new SQLException();
                            }
                            String state = first == null || last == null ? "STATUS_UNAVAILABLE"
                                    : !"committed".equals(first) || !"committed".equals(last) ? "NOT_COMMITTED"
                                    : visible ? "RANGE_VISIBLE" : "RANGE_UNPROVEN";
                            if (states.put(ordinal, state) != null) {
                                throw new SQLException();
                            }
                        }
                    }
                }
                if (states.size() != matched.size()) {
                    throw new SQLException();
                }
            }
            requireUnchanged();
            if (!route.equals(observeRoute())) {
                throw new SQLException();
            }
            var observations = new ArrayList<RangeObservation>();
            int ordinal = 0;
            for (var range : ranges) {
                observations.add(new RangeObservation(range,
                        route.equals(range.routeFingerprint()) ? states.get(ordinal++) : "ROUTE_MISMATCH"));
            }
            return new RangeVisibility(1, evidence, observations);
        } catch (SQLException | RuntimeException failure) {
            throw new SQLException("Commit range visibility could not be observed");
        }
    }

    /**
     * 拒绝当前实现未支持的 {@code requireCommitProduct} 操作。
     */
    private void requireCommitProduct() throws SQLException {
        int major;
        try {
            var metadata = connection.getMetaData();
            if (!"PostgreSQL".equals(metadata.getDatabaseProductName())) {
                throw new SQLException();
            }
            major = metadata.getDatabaseMajorVersion();
        } catch (SQLException failure) {
            throw new SQLException("Commit visibility product validation failed");
        }
        if (major != 16) {
            throw new SQLFeatureNotSupportedException("Commit visibility requires verified PostgreSQL 16");
        }
    }

    /**
     * 额外绑定本次实际读取的关系；MATCH 也不表示值相等、恢复代际或业务准入。
     */
    @Override
    public RangeVisibility inspectCommitRanges(List<CommitRangeReceipt> ranges, String reference) throws SQLException {
        if (closed) {
            throw new SQLException("Read snapshot is already closed");
        }
        if (ranges == null || ranges.isEmpty() || ranges.size() > MAX_COMMIT_RANGES
                || ranges.stream().anyMatch(java.util.Objects::isNull) || new HashSet<>(ranges).size() != ranges.size()
                || reference == null || reference.isBlank() || reference.length() > 4096) {
            throw new IllegalArgumentException("Bounded ranges and an actual read relation are required");
        }
        ranges = List.copyOf(ranges);
        requireCommitProduct();
        try (var ignored = checkpoint()) {
            requireUnchanged();
            String relation = PostgresRelationIdentity.storageFingerprint(connection, reference, true);
            var visibility = inspectCommitRanges(ranges);
            if (!java.util.Objects.equals(relation,
                    PostgresRelationIdentity.storageFingerprint(connection, reference, false))) {
                throw new SQLException("Read relation changed");
            }
            requireUnchanged();
            var observations = visibility.observations().stream().map(o -> new RangeObservation(o.receipt(), o.status(),
                    "ROUTE_MISMATCH".equals(o.status()) ? "ROUTE_MISMATCH"
                            : relation == null || o.receipt().relationFingerprint() == null ? "UNPROVEN"
                            : relation.equals(o.receipt().relationFingerprint()) ? "MATCH" : "MISMATCH")).toList();
            return new RangeVisibility(1, evidence, observations);
        } catch (SQLException | RuntimeException unavailable) {
            throw new SQLException("Relation-bound commit visibility could not be observed");
        }
    }

    /**
     * 校验并取得未变更。
     */
    private void requireUnchanged() throws SQLException {
        if (!evidence.equals(observe())) {
            throw new SQLException("Read snapshot changed");
        }
    }

    /**
     * 观测连接路由。
     */
    private String observeRoute() throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(Math.min(timeoutSeconds, 5));
            statement.setMaxRows(2);
            try (var rows = statement.executeQuery(
                    "SELECT " + PostgresTransactionRoute.COLUMNS + PostgresTransactionRoute.FROM)) {
                if (!rows.next()) {
                    throw new SQLException("Read transaction route unavailable");
                }
                String route = PostgresTransactionRoute.fingerprint(rows, 1);
                if (rows.next()) {
                    throw new SQLException("Read transaction route ambiguous");
                }
                return route;
            }
        }
    }

    /**
     * 返回检查点。
     */
    @Override
    public Checkpoint checkpoint() throws SQLException {
        if (closed) {
            throw new SQLException("Read snapshot is already closed");
        }
        try {
            var savepoint = connection.setSavepoint();
            return new Checkpoint() {
                private boolean released;

                /**
                 * 关闭 {@code PostgresReadSnapshot} 当前持有的资源。
                 */
                @Override
                public void close() throws SQLException {
                    if (released) {
                        return;
                    }
                    released = true;
                    try {
                        connection.rollback(savepoint);
                        connection.releaseSavepoint(savepoint);
                    } catch (Throwable failure) {
                        discard();
                        if (failure instanceof Error fatal) {
                            throw fatal;
                        }
                        throw new SQLException("Read checkpoint cleanup failed");
                    }
                }
            };
        } catch (SQLException failure) {
            throw new SQLException("Read checkpoint could not be established");
        }
    }

    /**
     * 读取当前目标对象或运行状态的最新事实。
     */
    private Evidence observe() throws SQLException {
        if (Thread.currentThread().isInterrupted()) {
            throw new SQLException("Read snapshot interrupted");
        }
        if (connection.getAutoCommit() || !connection.isReadOnly()
                || connection.getTransactionIsolation() != Connection.TRANSACTION_REPEATABLE_READ) {
            throw new SQLException("Read snapshot connection state changed");
        }
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeoutSeconds);
            statement.setMaxRows(2);
            try (var result = statement.executeQuery(SNAPSHOT_SQL)) {
                if (!result.next()) {
                    throw new SQLException("Read snapshot identity unavailable");
                }
                String nativeSnapshot = result.getString(1);
                int backend = result.getInt(2);
                String isolation = result.getString(3);
                String readOnly = result.getString(4);
                String virtualXid = result.getString(5);
                if (nativeSnapshot == null || nativeSnapshot.isBlank() || nativeSnapshot.length() > 1_000_000
                        || backend <= 0 || !"repeatable read".equals(isolation) || !"on".equals(readOnly)
                        || virtualXid == null || !virtualXid.matches("[0-9]+/[0-9]+") || result.next()) {
                    throw new SQLException("Read snapshot identity invalid");
                }
                // 只比较快照文本无法识别无并发写入时的中途 commit；另绑定 backend + virtualxid。
                return new Evidence(1, "POSTGRESQL_REPEATABLE_READ_V1", DigestUtil.sha256Hex(
                        "PG_READ_SNAPSHOT_V1\0" + backend + "\0" + virtualXid + "\0" + nativeSnapshot));
            }
        }
    }

    /**
     * 结束当前读取作用域，回滚只读事务并恢复借用连接的状态。
     */
    @Override
    public void close() throws SQLException {
        if (closed) {
            return;
        }
        closed = true;
        Throwable failure = null;
        try {
            if (!evidence.equals(observe())) {
                throw new SQLException("Read snapshot changed");
            }
        } catch (Throwable invalid) {
            failure = invalid;
        }
        try {
            connection.rollback();
            // 只有回滚确定完成后才恢复自动提交；不提交任何调用方查询的副作用。
            connection.setAutoCommit(true);
            connection.setTransactionIsolation(originalIsolation);
            connection.setReadOnly(originalReadOnly);
        } catch (Throwable cleanup) {
            if (failure == null) {
                failure = cleanup;
            }
            discard();
        }
        if (failure instanceof Error fatal) {
            throw fatal;
        }
        if (failure != null) {
            throw new SQLException("Read snapshot validation or cleanup failed");
        }
    }

    /**
     * 丢弃当前未发布的数据或临时输出状态。
     */
    private void discard() {
        try {
            connection.abort(Runnable::run);
        } catch (Throwable ignored) { /* 仍须关闭自主管理的会话 */ }
        try {
            connection.close();
        } catch (Throwable ignored) { /* 调用方收到失败结果，绝不能得到有效证据 */ }
    }
}
