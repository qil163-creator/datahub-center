package cn.iocoder.yudao.framework.dbswitch.dbwriter.pg;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnSequenceMetadata;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.schema.NativeSchemaReader;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.core.service.SqlScriptPlan;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.JdbcBatchExecutor;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.IdentityTransferOptions;

import javax.sql.DataSource;
import java.math.BigInteger;
import java.sql.*;
import java.util.*;

/** 一次全表传输：源端序列状态为采样值，调整前对目标状态加锁。 */
public final class PostgresIdentityTransfer {

    private final DataSource dataSource;

    private IdentityTransferOptions options = IdentityTransferOptions.disabled();

    private String schema, table;

    private long tableOid;

    private Map<String, ColumnSequenceMetadata> targetSequences = Map.of();

    private Map<String, ColumnSequenceMetadata> preflightSequences = Map.of();

    private Map<String, Long> sequenceOids = Map.of();

    private int timeoutSeconds = cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions.defaults()
            .queryTimeoutSeconds();

    private boolean prepared, completed;

    private cn.iocoder.yudao.framework.dbswitch.dbwriter.SequenceTransferScope.TableBinding shared;

    /**
     * 初始化 {@code PostgresIdentityTransfer}。
     */
    PostgresIdentityTransfer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 配置共享。
     */
    void configureShared(cn.iocoder.yudao.framework.dbswitch.dbwriter.SequenceTransferScope.TableBinding binding) {
        if (prepared) {
            throw new IllegalStateException("Shared sequence strategy cannot change after preparation");
        }
        shared = cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.PostgresSequenceTransferScope.requireBinding(
                binding, dataSource);
    }

    /**
     * 配置运行时。
     */
    void configureRuntime(cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions runtime) {
        if (prepared) {
            throw new IllegalStateException("Identity runtime options cannot change after preparation");
        }
        timeoutSeconds = Objects.requireNonNull(runtime).queryTimeoutSeconds();
    }

    /**
     * 设置当前操作使用的配置，并检查配置阶段仍允许修改。
     */
    void configure(IdentityTransferOptions options) {
        if (prepared) {
            throw new IllegalStateException("Identity strategy cannot change after writer preparation");
        }
        if (!Objects.requireNonNull(options).sourceIdentities().isEmpty()) {
            throw new IllegalArgumentException("PostgreSQL cannot use SQL Server identity metadata");
        }
        this.options = Objects.requireNonNull(options);
    }

    /**
     * 说明当前插入是否允许覆盖系统生成的列值。
     */
    boolean overrideSystemValue() {
        return options.preserveExplicitValues() && (shared != null && shared.overrideSystemValue()
                || targetSequences.values().stream()
                .anyMatch(sequence -> sequence.kind() == ColumnSequenceMetadata.Kind.IDENTITY_ALWAYS));
    }

    /**
     * 在产生数据库或外部副作用前检查目标及资源条件。
     */
    void preflight(String schema, String table, boolean exists) {
        if (!exists || !options.preserveExplicitValues()) {
            return;
        }
        try (var connection = dataSource.getConnection()) {
            preflightSequences = observe(connection, schema, table);
        } catch (SQLException failure) {
            throw new IllegalStateException("Target sequence preflight failed");
        }
    }

    /**
     * 为当前目标表准备标识列及其关联序列的迁移状态。
     */
    void prepare(String schema, String table, List<String> fields) {
        this.schema = schema;
        this.table = table;
        if (shared != null) {
            shared.prepareWriter(schema, table, fields);
        }
        if (!options.preserveExplicitValues()) {
            prepared = true;
            return;
        }
        if (!fields.containsAll(options.sourceSequences().keySet())) {
            throw new IllegalArgumentException("Identity mapping must retain every preserved sequence column");
        }
        try (var connection = dataSource.getConnection()) {
            targetSequences = observe(connection, schema, table);
            Map<String, Long> identities = new LinkedHashMap<>();
            for (var entry : targetSequences.entrySet()) {
                identities.put(entry.getKey(), sequenceOid(connection, entry.getValue()));
            }
            sequenceOids = Map.copyOf(identities);
            tableOid = oid(connection, schema, table);
            prepared = true;
        } catch (SQLException failure) {
            throw new IllegalStateException("Target identity observation failed");
        }
    }

    /**
     * 读取当前目标对象或运行状态的最新事实。
     */
    private Map<String, ColumnSequenceMetadata> observe(Connection connection, String schema,
            String table) throws SQLException {
        var metadata = NativeSchemaReader.read(connection, ProductTypeEnum.POSTGRESQL, schema, table,
                metadataTimeout());
        Map<String, ColumnSequenceMetadata> found = new TreeMap<>();
        for (var column : metadata.columns()) {
            if (shared != null && shared.managesColumn(column.getFieldName())) {
                continue;
            }
            var facts = column.getMetadataEvidence();
            boolean sequenceColumn = facts.identity().state() == MetadataValue.State.VALUE
                    || facts.defaultExpression().value() != null && facts.defaultExpression().value()
                    .contains("nextval(");
            if (!sequenceColumn) {
                continue;
            }
            if (!options.preserveExplicitValues()) {
                throw new IllegalArgumentException(
                        "Explicit values for identity/sequence targets require the preservation strategy");
            }
            var actual = facts.sequence().value();
            var source = options.sourceSequences().get(column.getFieldName());
            if (actual == null || source == null || !actual.structuralIdentity().equals(source.structuralIdentity())
                    || actual.position().state() != MetadataValue.State.VALUE) {
                throw new IllegalArgumentException("Target sequence structure or position is unverified");
            }
            sequenceOid(connection, actual); // 仅 SELECT 读取位置不能证明具备 ALTER SEQUENCE 能力。
            found.put(column.getFieldName(), actual);
        }
        if (!found.keySet().equals(options.sourceSequences().keySet())) {
            throw new IllegalArgumentException("Source and target sequence columns do not match");
        }
        return Map.copyOf(found);
    }

    /**
     * 在写入下一批数据前检查当前连接和目标状态。
     */
    void beforeBatch(Connection connection) throws SQLException {
        if (shared != null) {
            shared.beforeBatch(connection);
        }
        if (targetSequences.isEmpty()) {
            return;
        }
        if (!prepared || completed) {
            throw new IllegalStateException("Identity writer is not active");
        }
        try (var lock = connection.createStatement()) {
            lock.setQueryTimeout(timeoutSeconds);
            lock.execute("LOCK TABLE " + relation() + " IN ROW EXCLUSIVE MODE");
        }
        if (tableOid != oid(connection, schema, table)) {
            throw new IllegalStateException("Target table was replaced");
        }
        verifySequenceIdentities(connection);
    }

    /**
     * 记录当前操作完成并发布最终结果。
     */
    void complete() {
        if (!prepared || completed) {
            throw new IllegalStateException("Identity writer completion is out of order");
        }
        if (!targetSequences.isEmpty()) {
            JdbcBatchExecutor.execute(dataSource, context -> {
                var connection = context.connection();
                try (var lock = connection.createStatement()) {
                    lock.setQueryTimeout(timeoutSeconds);
                    lock.execute("LOCK TABLE " + relation() + " IN ACCESS EXCLUSIVE MODE");
                }
                if (tableOid != oid(connection, schema, table)) {
                    throw new IllegalStateException("Target table was replaced");
                }
                verifySequenceIdentities(connection);
                // 采用确定顺序的锁，在观测和重启序列期间阻止并发 nextval 和 DDL。
                var entries = targetSequences.entrySet().stream()
                        .sorted(Comparator.comparing(entry -> qualified(entry.getValue()))).toList();
                for (var entry : entries) {
                    execute(context,
                            "ALTER SEQUENCE " + qualified(entry.getValue()) + " CACHE " + entry.getValue().cache());
                }
                verifySequenceIdentities(connection); // 此项检查至事务完成期间，对象不能变化。
                for (var entry : entries) {
                    var observed = NativeSchemaReader.readPostgresSequence(connection, schema, table, entry.getKey(),
                            metadataTimeout()).value();
                    var expected = entry.getValue();
                    if (observed == null || !qualified(expected).equals(qualified(observed))
                            || !expected.structuralIdentity().equals(observed.structuralIdentity())) {
                        throw new IllegalStateException("Target sequence changed during transfer");
                    }
                    BigInteger extreme;
                    String column = RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.POSTGRESQL,
                            entry.getKey());
                    try (var query = connection.createStatement()) {
                        query.setQueryTimeout(timeoutSeconds);
                        try (var rows = query.executeQuery(
                                "SELECT " + (observed.increment() > 0 ? "max(" : "min(") + column + ") FROM " + relation())) {
                            if (!rows.next()) {
                                throw new SQLException("Missing target aggregate");
                            }
                            var value = rows.getBigDecimal(1);
                            extreme = value == null ? null : value.toBigIntegerExact();
                        }
                    }
                    long next = alignedNext(options.sourceSequences().get(entry.getKey()), observed, extreme);
                    // TRUNCATE RESTART IDENTITY 和显式替换可能重置物理序列。
                    // 同时保留破坏性操作前的观测结果和当前已加锁的位置。
                    long preparedNext = alignedNext(expected, observed, extreme);
                    next = observed.increment() > 0 ? Math.max(next, preparedNext) : Math.min(next, preparedNext);
                    var original = preflightSequences.get(entry.getKey());
                    if (original != null) {
                        long originalNext = alignedNext(original, observed, extreme);
                        next = observed.increment() > 0 ? Math.max(next, originalNext) : Math.min(next, originalNext);
                    }
                    execute(context, "ALTER SEQUENCE " + qualified(observed) + " RESTART WITH " + next);
                }
                return null;
            });
        }
        if (shared != null) {
            shared.tableCompleted();
        }
        completed = true;
    }

    /** 沿当前序列推进，绝不回退到采样的源端位置或已有目标位置。 */
    public static long alignedNext(ColumnSequenceMetadata source, ColumnSequenceMetadata target, BigInteger extreme) {
        if (!source.structuralIdentity().equals(target.structuralIdentity()) || source.cycle()) {
            throw new IllegalArgumentException("Incompatible sequence alignment");
        }
        BigInteger next = target.nextObservedValue(), sampled = source.nextObservedValue();
        BigInteger step = BigInteger.valueOf(target.increment()), stride = step.abs();
        BigInteger lower = target.increment() > 0 ? sampled : sampled.negate();
        if (extreme != null) {
            lower = lower.max((target.increment() > 0 ? extreme : extreme.negate()).add(BigInteger.ONE));
        }
        BigInteger forward = target.increment() > 0 ? next : next.negate();
        if (forward.compareTo(lower) < 0) {
            BigInteger distance = lower.subtract(forward);
            forward = forward.add(distance.add(stride).subtract(BigInteger.ONE).divide(stride).multiply(stride));
        }
        next = target.increment() > 0 ? forward : forward.negate();
        if (next.compareTo(BigInteger.valueOf(target.minimum())) < 0 || next.compareTo(
                BigInteger.valueOf(target.maximum())) > 0) {
            throw new IllegalArgumentException("Sequence exhausted after explicit-value transfer");
        }
        return next.longValueExact();
    }

    /**
     * 在当前数据库连接上执行给定 SQL 语句。
     */
    private void execute(JdbcBatchExecutor.WriteContext context, String sql) throws SQLException {
        var plan = SqlScriptPlan.parse(ProductTypeEnum.POSTGRESQL, sql);
        if (plan.steps().size() != 1) {
            throw new IllegalArgumentException("One sequence operation required");
        }
        try (var statement = context.connection().createStatement()) {
            statement.setQueryTimeout(timeoutSeconds);
            context.markWriteAttempted();
            statement.execute(plan.steps().getFirst().sql());
        }
    }

    /**
     * 生成模式与表组成的限定关系名。
     */
    private String relation() {
        return RestrictedSelectGuard.qualifiedTableName(ProductTypeEnum.POSTGRESQL, schema, table);
    }

    /**
     * 返回当前数据库对象经过引用处理的限定名称。
     */
    private static String qualified(ColumnSequenceMetadata sequence) {
        return RestrictedSelectGuard.qualifiedTableName(ProductTypeEnum.POSTGRESQL, sequence.sourceSchema(),
                sequence.sourceName());
    }

    /**
     * 读取数据库对象在系统目录中的标识。
     */
    private long oid(Connection connection, String schema, String table) throws SQLException {
        try (var query = connection.prepareStatement(
                "SELECT c.oid::bigint FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=? AND c.relname=? AND c.relkind='r' AND c.relpersistence='p'")) {
            query.setQueryTimeout(metadataTimeout());
            query.setString(1, schema);
            query.setString(2, table);
            try (var result = query.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Target relation identity unavailable");
                }
                return result.getLong(1);
            }
        }
    }

    /**
     * 返回当前元数据读取允许的超时设置。
     */
    private int metadataTimeout() {
        return Math.min(120, timeoutSeconds);
    }

    /**
     * 重新核对目标序列对象标识与准备阶段一致。
     */
    private void verifySequenceIdentities(Connection connection) throws SQLException {
        for (var entry : targetSequences.entrySet()) {
            if (!Objects.equals(sequenceOids.get(entry.getKey()), sequenceOid(connection, entry.getValue()))) {
                throw new IllegalStateException("Target sequence was replaced");
            }
        }
    }

    /**
     * 查询序列在系统目录中的对象标识。
     */
    private long sequenceOid(Connection connection, ColumnSequenceMetadata sequence) throws SQLException {
        try (var query = connection.prepareStatement(
                "SELECT c.oid::bigint,pg_catalog.pg_has_role(current_user,c.relowner,'USAGE') "
                        + "FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace "
                        + "WHERE n.nspname=? AND c.relname=? AND c.relkind='S' AND c.relpersistence='p'")) {
            query.setQueryTimeout(metadataTimeout());
            query.setString(1, sequence.sourceSchema());
            query.setString(2, sequence.sourceName());
            try (var row = query.executeQuery()) {
                if (!row.next() || !row.getBoolean(2)) {
                    throw new IllegalArgumentException(
                            "Target sequence alteration requires effective ownership before copying rows");
                }
                long value = row.getLong(1);
                if (row.next()) {
                    throw new SQLException("Ambiguous sequence identity");
                }
                return value;
            }
        }
    }
}
