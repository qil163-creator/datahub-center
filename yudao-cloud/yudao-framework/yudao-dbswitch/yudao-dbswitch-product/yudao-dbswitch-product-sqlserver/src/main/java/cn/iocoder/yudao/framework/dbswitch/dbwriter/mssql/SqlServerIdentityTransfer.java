package cn.iocoder.yudao.framework.dbswitch.dbwriter.mssql;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.SqlServerIdentityTable;
import cn.iocoder.yudao.framework.dbswitch.core.schema.NativeSchemaReader;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.JdbcBatchExecutor;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.IdentityReplacement;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.IdentityTransferOptions;

import java.math.BigInteger;
import java.sql.*;
import java.util.List;
import javax.sql.DataSource;

/** 仅提供产品自增标识机制，事务、提交、结果不确定性和连接驱逐仍由共享层负责。 */
final class SqlServerIdentityTransfer {

    private final DataSource dataSource;

    private IdentityTransferOptions options = IdentityTransferOptions.disabled();

    private int timeout = 30;

    private String schema, table;

    private SqlServerIdentityTable preflight, beforeReplacement, target;

    private boolean preflightDone, prepared, completed;

    /**
     * 初始化 {@code SqlServerIdentityTransfer}。
     */
    SqlServerIdentityTransfer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 设置当前操作使用的配置，并检查配置阶段仍允许修改。
     */
    void configure(IdentityTransferOptions value) {
        if (preflightDone || prepared) {
            throw new IllegalStateException("Identity strategy is already active");
        }
        if (!value.sourceSequences().isEmpty()) {
            throw new IllegalArgumentException("SQL Server cannot use PostgreSQL sequence metadata");
        }
        // 已耗尽的观测状态不能承诺下一次可用分配，应在目标 DDL 或 DML 前拒绝。
        value.sourceIdentities().values().forEach(identity -> identity.alignedNext(identity, null, null));
        options = value;
    }

    /**
     * 配置超时设置。
     */
    void configureTimeout(int seconds) {
        if (prepared) {
            throw new IllegalStateException("Identity writer is already prepared");
        }
        timeout = seconds;
    }

    /**
     * 返回启用标记。
     */
    private boolean enabled() {
        return options.preserveExplicitValues() && !options.sourceIdentities().isEmpty();
    }

    /**
     * 在产生数据库或外部副作用前检查目标及资源条件。
     */
    void preflight(String schema, String table, boolean exists) {
        if (!enabled()) {
            return;
        }
        this.schema = schema;
        this.table = table;
        if (exists) {
            try (var connection = dataSource.getConnection()) {
                preflight = observe(connection);
                verifyDefinition(preflight);
                requireAlter(connection);
            } catch (SQLException failure) {
                throw new IllegalStateException("Target identity preflight failed");
            }
        }
        preflightDone = true;
    }

    /**
     * 在预检查完成后为当前 SQL Server 标识列写入准备目标状态。
     */
    void prepare(String schema, String table, List<String> fields) {
        if (!enabled()) {
            return;
        }
        if (!preflightDone || !schema.equals(this.schema) || !table.equals(this.table) || completed
                || !fields.containsAll(options.sourceIdentities().keySet())) {
            throw new IllegalStateException(
                    "Identity preparation requires matching preflight and complete identity fields");
        }
        try (var connection = dataSource.getConnection()) {
            var actual = observe(connection);
            verifyDefinition(actual);
            requireAlter(connection);
            if (target != null) {
                requireSameTable(target, actual);
            } else if (preflight != null) {
                requireSameTable(preflight, actual);
            }
            target = actual;
            prepared = true;
        } catch (SQLException failure) {
            throw new IllegalStateException("Target identity preparation failed");
        }
    }

    /**
     * 返回当前替换内容。
     */
    boolean replacement(IdentityReplacement request) {
        if (!enabled()) {
            return false;
        }
        if (!preflightDone || completed || !schema.equals(request.schema()) || !table.equals(request.table())) {
            throw new IllegalStateException("Identity replacement requires matching preflight");
        }
        target = JdbcBatchExecutor.execute(dataSource, context -> {
            var connection = context.connection();
            SqlServerIdentityTable expected = target != null ? target : preflight;
            if (expected != null) {
                lock(connection);
                var observed = observe(connection);
                requireSameTable(expected, observed);
                verifyDefinition(observed);
                requireAlter(connection);
                beforeReplacement = observed;
            } else if (exists(connection)) {
                throw new IllegalStateException("Target appeared after identity preflight");
            }
            if (request.mode() == IdentityReplacement.Mode.TRUNCATE) {
                if (expected == null) {
                    throw new IllegalStateException("Identity truncate target is missing");
                }
                mutate(context, "TRUNCATE TABLE " + relation());
            } else {
                if (expected != null) {
                    mutate(context, "DROP TABLE " + relation());
                }
                for (String sql : request.statements()) {
                    mutate(context, sql);
                }
            }
            var result = observe(connection);
            verifyDefinition(result);
            requireAlter(connection);
            return result;
        });
        return true;
    }

    /**
     * 提交当前预编译语句中已积累的批次参数。
     */
    void executeBatch(JdbcBatchExecutor.WriteContext context, String sql, List<Object[]> rows,
            int[] types) throws SQLException {
        if (!enabled()) {
            JdbcBatchExecutor.executeBatch(context, sql, rows, types, timeout);
            return;
        }
        if (!prepared || completed) {
            throw new IllegalStateException("Identity writer is not active");
        }
        var connection = context.connection();
        lock(connection);
        var actual = observe(connection);
        requireSameTable(target, actual);
        verifyDefinition(actual);
        withExplicitValues(context, schema, table, timeout, ctx -> {
            JdbcBatchExecutor.executeBatch(ctx, sql, rows, types, timeout);
            return null;
        });
    }

    /** 使用同一个自主管理的物理写入连接；OFF 失败由共享 UNKNOWN 和驱逐边界保留。 */
    static <T> T withExplicitValues(JdbcBatchExecutor.WriteContext context, String schema, String table, int timeout,
            JdbcBatchExecutor.WriteAction<T> action) throws SQLException {
        String reference = RestrictedSelectGuard.qualifiedTableName(ProductTypeEnum.SQLSERVER, schema, table);
        context.markWriteAttempted();
        try {
            try (var statement = context.connection().createStatement()) {
                statement.setQueryTimeout(timeout);
                statement.execute("SET IDENTITY_INSERT " + reference + " ON");
            }
            try {
                return action.execute(context);
            } catch (SQLException failure) {
                throw failure;
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new SQLException("SQL Server identity write failed");
            }
        } finally {
            try (var statement = context.connection().createStatement()) {
                statement.setQueryTimeout(timeout);
                statement.execute("SET IDENTITY_INSERT " + reference + " OFF");
            }
        }
    }

    /**
     * 记录当前操作完成并发布最终结果。
     */
    void complete() {
        if (!enabled()) {
            return;
        }
        if (!prepared || completed) {
            throw new IllegalStateException("Identity completion is out of order");
        }
        JdbcBatchExecutor.execute(dataSource, context -> {
            var connection = context.connection();
            lock(connection);
            var current = observe(connection);
            requireSameTable(target, current);
            verifyDefinition(current);
            requireAlter(connection);
            String field = options.sourceIdentities().keySet().iterator().next();
            var source = options.sourceIdentities().get(field);
            var observed = current.columns().get(field);
            BigInteger extreme;
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(timeout);
                try (var rows = statement.executeQuery(
                        "SELECT " + (source.increment().signum() > 0 ? "MAX(" : "MIN(") + quote(
                                field) + ") FROM " + relation())) {
                    if (!rows.next()) {
                        throw new SQLException("Identity extreme observation unavailable");
                    }
                    var value = rows.getBigDecimal(1);
                    extreme = value == null ? null : value.toBigIntegerExact();
                }
            }
            var original = preflight == null ? null : preflight.columns().get(field);
            BigInteger next = source.alignedNext(observed, original, extreme);
            if (beforeReplacement != null) {
                BigInteger prior = source.alignedNext(observed, beforeReplacement.columns().get(field), extreme);
                next = source.increment().signum() > 0 ? next.max(prior) : next.min(prior);
            }
            BigInteger reseed = observed.called() ? next.subtract(source.increment()) : next;
            mutate(context, "DBCC CHECKIDENT ('" + relation().replace("'",
                    "''") + "', RESEED, " + reseed + ") WITH NO_INFOMSGS");
            var after = observe(connection);
            requireSameTable(current, after);
            verifyDefinition(after);
            if (!next.equals(after.columns().get(field).nextObservedValue())) {
                throw new SQLException("Identity alignment was not confirmed");
            }
            return null;
        });
        completed = true;
    }

    /**
     * 校验定义。
     */
    private void verifyDefinition(SqlServerIdentityTable observed) {
        if (!observed.columns().keySet().equals(options.sourceIdentities().keySet())
                || observed.columns().entrySet().stream()
                .anyMatch(entry -> !entry.getValue().sameDefinition(options.sourceIdentities().get(entry.getKey())))) {
            throw new IllegalArgumentException("Source and target native identity definitions differ");
        }
        observed.columns()
                .forEach((field, identity) -> options.sourceIdentities().get(field).alignedNext(identity, null, null));
    }

    /**
     * 校验并取得相同表。
     */
    private void requireSameTable(SqlServerIdentityTable expected, SqlServerIdentityTable actual) {
        if (!expected.samePhysicalTable(actual) || !expected.sameDefinition(actual)) {
            throw new IllegalStateException("Identity target was replaced or altered");
        }
    }

    /**
     * 读取当前目标对象或运行状态的最新事实。
     */
    private SqlServerIdentityTable observe(Connection connection) throws SQLException {
        return NativeSchemaReader.readSqlServerIdentityTable(connection, schema, table, Math.min(120, timeout));
    }

    /**
     * 返回当前存在性。
     */
    private boolean exists(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT OBJECT_ID(?, 'U')")) {
            statement.setQueryTimeout(timeout);
            statement.setString(1, relation());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("Missing identity target observation");
                }
                return rows.getObject(1) != null;
            }
        }
    }

    /**
     * 校验并取得结构变更。
     */
    private void requireAlter(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT HAS_PERMS_BY_NAME(?, 'OBJECT', 'ALTER')")) {
            statement.setQueryTimeout(timeout);
            statement.setString(1, relation());
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || rows.getInt(1) != 1) {
                    throw new SQLException("Identity preservation requires ALTER permission");
                }
            }
        }
    }

    /**
     * 获取当前操作所需的数据库或资源锁。
     */
    private void lock(Connection connection) throws SQLException {
        String field = options.sourceIdentities().keySet().iterator().next();
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            try (var rows = statement.executeQuery(
                    "SELECT TOP(1) " + quote(field) + " FROM " + relation() + " WITH (TABLOCKX,HOLDLOCK)")) {
                while (rows.next()) {
                }
            }
        }
    }

    /**
     * 读取当前数据库或会话设置。
     */
    private void setting(Connection connection, String state) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            statement.execute("SET IDENTITY_INSERT " + relation() + ' ' + state);
        }
    }

    /**
     * 按当前变更定义修改目标对象或数据。
     */
    private void mutate(JdbcBatchExecutor.WriteContext context, String sql) throws SQLException {
        try (var statement = context.connection().createStatement()) {
            statement.setQueryTimeout(timeout);
            context.markWriteAttempted();
            statement.execute(sql);
        }
    }

    /**
     * 生成模式与表组成的限定关系名。
     */
    private String relation() {
        return RestrictedSelectGuard.qualifiedTableName(ProductTypeEnum.SQLSERVER, schema, table);
    }

    /**
     * 按SQL Server方言转义并引用标识符。
     */
    private static String quote(String name) {
        return RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.SQLSERVER, name);
    }
}
