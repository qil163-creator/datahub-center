package cn.iocoder.yudao.framework.etl.core.connection;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.util.TypedRowFingerprint;
import cn.iocoder.yudao.framework.dbswitch.common.util.TypedRowFingerprint.KeyValue;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.data.query.*;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.driver.ConnectionBudget;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRowOrigin;

import java.time.Duration;
import java.util.*;

/** 可选的精确行捕获。元数据缺失或不受支持时不生成证明，绝不猜测身份。 */
public final class EtlRowOriginCapture {

    private final EtlConnectionInfo connection;

    private final String sourceNode, schema, table;

    private final List<String> keys;

    private final Set<String> columns;

    /**
     * 初始化 {@code EtlRowOriginCapture}。
     */
    private EtlRowOriginCapture(EtlConnectionInfo connection, String sourceNode, String schema, String table,
            List<String> keys, Set<String> columns) {
        this.connection = connection;
        this.sourceNode = sourceNode;
        this.schema = schema;
        this.table = table;
        this.keys = List.copyOf(keys);
        this.columns = Set.copyOf(columns);
    }

    /**
     * 校验 PostgreSQL 连接配置及版本后准备精确数据行来源捕获。
     */
    public static EtlRowOriginCapture prepare(DbSwitchRuntime runtime, EtlConnectionInfo connection,
            String sourceNode, String originalSql, String authorizedSql) {
        if (connection.getProductType() != ProductTypeEnum.POSTGRESQL || connection.getConfigurationRevision() == null
                || connection.getConfigurationRevision().isBlank()) {
            return null;
        }
        try {
            var table = RestrictedSelectGuard.parseBound(connection.getProductType(), originalSql,
                    connection.getSchema());
            var actual = RestrictedSelectGuard.parseBound(connection.getProductType(), authorizedSql,
                    connection.getSchema());
            if (!Objects.equals(table.getSchemaName(), actual.getSchemaName()) || !Objects.equals(table.getTableName(),
                    actual.getTableName())) {
                return null;
            }
            var operation = runtime.queries()
                    .register("etl-source-origin", UUID.randomUUID().toString(), UUID.randomUUID(),
                            new OperationBudget(Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(3),
                                    1024, 1024 * 1024, 64 * 1024, 256));
            List<String> keys;
            Set<String> columns = new HashSet<>();
            QueryRuntime.Termination termination;
            try {
                operation.bindWorker();
                try (var session = operation.openSession(spec(connection), ConnectionBudget.Role.METADATA)) {
                    keys = session.dialect()
                            .queryTablePrimaryKeys(session.connection(), table.getSchemaName(), table.getTableName());
                    var metadata = session.dialect()
                            .queryTableColumnMeta(session.connection(), table.getSchemaName(), table.getTableName());
                    operation.check();
                    // 旧版 getObject(Time) 会丢失亚毫秒精度；带时区 Timestamp 会丢失类型身份。
                    // 在普通 ETL 和有界查询都具备经过验证的精确时间读取器前，不生成证明。
                    if (metadata.stream().anyMatch(column -> column.getFieldType() == java.sql.Types.TIME
                            || column.getFieldType() == java.sql.Types.TIME_WITH_TIMEZONE
                            || column.getFieldType() == java.sql.Types.TIMESTAMP_WITH_TIMEZONE
                            || Set.of("time", "timetz", "timestamptz").contains(
                            Objects.toString(column.getFieldTypeName(), "").toLowerCase(Locale.ROOT)))) {
                        return null;
                    }
                    metadata.forEach(column -> columns.add(column.getFieldName()));
                    operation.check();
                }
            } finally {
                operation.workerExited();
                termination = operation.awaitTermination();
            }
            if (termination != QueryRuntime.Termination.CONFIRMED || keys == null || keys.isEmpty() || keys.size() > 4
                    || new HashSet<>(
                    keys).size() != keys.size() || columns.isEmpty() || columns.size() > 256 || !columns.containsAll(
                    keys)) {
                return null;
            }
            return new EtlRowOriginCapture(connection, sourceNode, table.getSchemaName(), table.getTableName(), keys,
                    columns);
        } catch (Exception unsupported) {
            if (Thread.currentThread().isInterrupted() || cancelled(unsupported)) {
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CancellationException(
                        "Source provenance capture was cancelled or timed out");
            }
            return null;
        }
    }

    /**
     * 返回当前已取消。
     */
    private static boolean cancelled(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof InterruptedException || cause instanceof java.util.concurrent.CancellationException
                    || cause instanceof java.sql.SQLTimeoutException) {
                return true;
            }
            if (cause instanceof java.sql.SQLException sql
                    && Set.of("57014", "HYT00", "HYT01").contains(Objects.toString(sql.getSQLState(), ""))) {
                return true;
            }
        }
        return false;
    }

    /** 在脱敏策略可能原地修改输入映射前，先计算原始哈希。 */
    public List<EtlRowOrigin> snapshot(List<Map<String, Object>> rows) {
        List<EtlRowOrigin> result = new ArrayList<>();
        for (var row : rows) {
            EtlRowOrigin origin = null;
            try {
                if (row.keySet().equals(columns)) {
                    String hash = TypedRowFingerprint.hash(row);
                    origin = new EtlRowOrigin(1, sourceNode, connection.getDatabaseId(),
                            connection.getConfigurationRevision(),
                            connectionHash(connection), schema, table, key(row), hash, hash);
                }
            } catch (IllegalArgumentException unsupported) { /* 任何无法验证的标量都不能进入证明。 */ }
            result.add(origin);
        }
        return result;
    }

    /**
     * 返回当前来源。
     */
    public EtlRowOrigin origin(EtlRowOrigin raw, Map<String, Object> emitted) {
        try {
            if (raw == null || !raw.keys().equals(key(emitted))) {
                return null;
            }
            return new EtlRowOrigin(1, sourceNode, connection.getDatabaseId(), connection.getConfigurationRevision(),
                    connectionHash(connection), schema, table, raw.keys(), raw.sourceHash(),
                    TypedRowFingerprint.hash(emitted));
        } catch (IllegalArgumentException unsupported) {
            return null;
        }
    }

    /**
     * 返回键。
     */
    private Map<String, KeyValue> key(Map<String, Object> row) {
        Map<String, KeyValue> values = new TreeMap<>();
        for (String key : keys) {
            values.put(key, KeyValue.of(row.get(key)));
        }
        return Collections.unmodifiableMap(values);
    }

    /**
     * 根据连接身份计算哈希，供数据行来源关联使用。
     */
    public static String connectionHash(EtlConnectionInfo connection) {
        var identity = new LinkedHashMap<String, Object>();
        identity.put("product", connection.getProductType().name());
        identity.put("url", connection.getJdbcUrl());
        identity.put("user", connection.getUsername());
        identity.put("schema", connection.getSchema());
        identity.put("driver", connection.getDriverProfileId());
        identity.put("revision", connection.getConfigurationRevision());
        return TypedRowFingerprint.hash(identity);
    }

    /**
     * 返回当前规格。
     */
    public static QueryRuntime.ConnectionSpec spec(EtlConnectionInfo connection) {
        return new QueryRuntime.ConnectionSpec(connection.getProductType(), connection.getJdbcUrl(),
                connection.getUsername(),
                connection.getPassword(), connection.getDriverProfileId(), connection.getConfigurationRevision());
    }
}
