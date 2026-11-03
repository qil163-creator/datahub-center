package cn.iocoder.yudao.framework.dbswitch.dbwriter.clickhouse;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.AbstractDatabaseWriter;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.BatchWriteResult;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.CommitAcknowledgement;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.IDatabaseUpsertWriter;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.clickhouse.ClickhouseDatabaseSyncImpl;
import cn.iocoder.yudao.framework.dbswitch.product.clickhouse.ClickhouseCursorMetadata;
import cn.iocoder.yudao.framework.dbswitch.product.clickhouse.ClickhouseSql;
import cn.iocoder.yudao.framework.dbswitch.product.clickhouse.ClickhouseValueConversions;
import cn.iocoder.yudao.framework.dbswitch.product.clickhouse.ClickhouseWriteExecution;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * 基于已验证变更回读路径的 ClickHouse 原生同步 UPSERT。ClickHouse 没有行级更新插入
 * 协议：不存在的键通过单次同步多行 VALUES 请求 INSERT，已有键则通过
 * mutations_sync=2 的 ALTER UPDATE 变更覆盖，并完整回读每行；必须确认全部值，
 * 才能将该行计入确认数量。每条语句自行渲染严格 SQL 字面值：JDBC 驱动的类型化参数会丢失
 * 亚秒时间精度，因此任何值都不通过驱动参数传输。每行操作都是
 * 独立确认的请求；没有批次事务，因此首次发送后的任何失败均为 UNKNOWN，
 * 此处绝不重试或回滚；检查点重放会依据每行幂等收敛性
 * 重新执行整个批次。已有行的排序键、分区键和主键列必须相等，否则拒绝批次
 * （ClickHouse 变更无法修改这些列，必须替换表）。目标结构和
 * 唯一键数据证明在首次发送前及最后一次确认后重新检查。任务必须独占
 * 目标表：ClickHouse 没有唯一约束或事务，无法排除
 * 预检与变更之间的无关并发写入器。
 */
public final class ClickhouseUpsertWriter extends AbstractDatabaseWriter implements IDatabaseUpsertWriter {

    private ClickhouseCursorMetadata.Shape shape;

    private List<String> fields, keys;

    private List<ClickhouseCursorMetadata.Column> columns, keyColumns;

    private String fingerprint;

    /**
     * 初始化 {@code ClickhouseUpsertWriter}及其父类状态。
     */
    public ClickhouseUpsertWriter(DataSource source) {
        super(source);
    }

    /**
     * 返回写入器对应的数据库产品名（用于日志输出）。
     */
    @Override
    protected String getDatabaseProductName() {
        return "ClickHouse";
    }

    /**
     * 写入准备：执行一条 WHERE 1=2 的探测查询（不取数据）， 从结果集元数据中缓存目标表“字段名 -> JDBC 类型”映射，供 write 阶段绑定参数类型使用； 查询失败或表无字段时抛出运行时异常。
     */
    @Override
    public void prepareWrite(String schema, String table, List<String> fields) {
        throw new IllegalArgumentException("Native UPSERT requires explicit unique keys");
    }

    /**
     * 不支持的键、索引或存储语义必须在发送任何数据语句前失败。
     */
    @Override
    public void prepareUpsert(String schema, String table, List<String> names, List<String> uniqueKeys) {
        shape = null;
        fields = null;
        keys = null;
        fingerprint = null;
        try {
            if (names == null || names.isEmpty() || new HashSet<>(names).size() != names.size()
                    || uniqueKeys == null || uniqueKeys.isEmpty() || new HashSet<>(
                    uniqueKeys).size() != uniqueKeys.size()
                    || !names.containsAll(uniqueKeys)) {
                throw new IllegalArgumentException();
            }
            ClickhouseCursorMetadata.Shape current;
            try (var connection = dataSource.getConnection()) {
                current = ClickhouseCursorMetadata.read(connection, schema, table,
                        runtimeOptions.queryTimeoutSeconds());
                ClickhouseDatabaseSyncImpl.requireUniqueKeys(connection, ClickhouseSql.table(schema, table), uniqueKeys,
                        runtimeOptions.queryTimeoutSeconds());
            }
            var resolved = new ArrayList<ClickhouseCursorMetadata.Column>();
            for (String name : names) {
                var column = current.column(name);
                if (!ClickhouseCursorMetadata.supportedValueType(column)) {
                    throw new IllegalArgumentException();
                }
                resolved.add(column);
            }
            var keyResolved = new ArrayList<ClickhouseCursorMetadata.Column>();
            for (String key : uniqueKeys) {
                var column = current.column(key);
                if (!ClickhouseCursorMetadata.supportedKeyType(column)) {
                    throw new IllegalArgumentException();
                }
                keyResolved.add(column);
            }
            schemaName = schema;
            tableName = table;
            shape = current;
            fields = List.copyOf(names);
            keys = List.copyOf(uniqueKeys);
            columns = List.copyOf(resolved);
            keyColumns = List.copyOf(keyResolved);
            fingerprint = DigestUtil.sha256Hex("CLICKHOUSE_UPSERT_TARGET_V1\0" + current.relationEvidence() + '\0'
                    + String.join("\0", names) + '\0' + String.join("\0", uniqueKeys));
        } catch (Exception failure) {
            shape = null;
            fields = null;
            keys = null;
            fingerprint = null;
            throw new IllegalArgumentException("ClickHouse UPSERT target, keys or engine are unsupported");
        }
    }

    /**
     * 返回已观测目标表的结构指纹。
     */
    @Override
    public String targetSchemaFingerprint() {
        if (fields == null) {
            throw new IllegalStateException("ClickHouse UPSERT target is not prepared");
        }
        return fingerprint;
    }

    /**
     * 精确有序键的类型与排序规则契约，是无损类型化游标重放的必要条件。
     */
    @Override
    public String keyContractFingerprint() {
        return keyContract().nativeFingerprint();
    }

    /**
     * 返回游标读写使用的结构化键契约。
     */
    @Override
    public CursorKeyContract keyContract() {
        if (fields == null) {
            throw new IllegalStateException("ClickHouse UPSERT target is not prepared");
        }
        return shape.targetContract(keys);
    }

    /**
     * 批量数据写入。
     */
    @Override
    public long write(List<String> names, List<Object[]> rows) {
        return writeWithReceipt(names, rows).acknowledgedRows();
    }

    /**
     * 可选原生单批提交见证；旧实现继续工作，但 null 不能解释为已取得提交/到数证明。
     */
    @Override
    public BatchWriteResult writeWithReceipt(List<String> names, List<Object[]> rows) {
        if (fields == null || !fields.equals(names)) {
            throw new BatchRejectedException("ClickHouse UPSERT input fields differ from the prepared fields");
        }
        var converted = BatchValueConverter.convert(rows, fields.size(), batchLimits, (value, column) -> {
            var result = ClickhouseValueConversions.convert(value, conversionColumn(columns.get(column)));
            // 服务器会默默钳制超范围的本地日期时间；必须拒绝它们，避免写入被改变的值。
            if (result instanceof java.time.LocalDate || result instanceof java.time.LocalDateTime) {
                ClickhouseCursorMetadata.requireTemporalRange(columns.get(column), result);
            }
            return result;
        });
        if (converted.isEmpty()) {
            return new BatchWriteResult(0, null);
        }
        var batchKeys = new HashSet<List<Object>>();
        for (Object[] row : converted) {
            var key = new ArrayList<Object>();
            for (String name : keys) {
                Object value = row[fields.indexOf(name)];
                if (value == null) {
                    throw new BatchRejectedException("ClickHouse UPSERT key values must not be NULL");
                }
                key.add(value instanceof byte[] bytes ? java.util.HexFormat.of().formatHex(bytes) : value);
            }
            if (!batchKeys.add(key)) {
                throw new BatchRejectedException("ClickHouse UPSERT batch repeats a native key");
            }
        }
        long written = ClickhouseWriteExecution.execute(dataSource, (connection, attempt) -> {
            verifyTarget(connection);
            // 首次请求前校验整个批次。ClickHouse 无法回滚。
            for (Object[] row : converted) {
                Object[] current = readRow(connection, row);
                if (current != null) {
                    for (var column : columns) {
                        int index = fields.indexOf(column.name());
                        if (column.immutable() && !equalValue(current[index], row[index])) {
                            throw new IllegalArgumentException(
                                    "Changing a ClickHouse sorting or partition column requires table replacement");
                        }
                    }
                }
            }
            long confirmed = 0;
            for (Object[] row : converted) {
                Object[] current = readRow(connection, row);
                if (current == null) {
                    confirmed += insert(connection, attempt, java.util.Collections.singletonList(row));
                } else {
                    var assignments = new ArrayList<String>();
                    for (var column : columns) {
                        int index = fields.indexOf(column.name());
                        if (equalValue(current[index], row[index])) {
                            continue;
                        }
                        assignments.add(ClickhouseSql.quote(column.name()) + "=" + valueLiteral(column, row[index]));
                    }
                    if (!assignments.isEmpty()) {
                        String sql = "ALTER TABLE " + ClickhouseSql.table(schemaName,
                                tableName) + " UPDATE " + String.join(",", assignments)
                                + " WHERE " + keyPredicate(row) + " SETTINGS mutations_sync=2";
                        try (Statement statement = connection.createStatement()) {
                            statement.setQueryTimeout(runtimeOptions.queryTimeoutSeconds());
                            attempt.sending();
                            statement.execute(sql); // DDL 响应不包含受影响行数。
                        }
                        Object[] observed = readRow(connection, row);
                        if (observed == null) {
                            throw new IllegalStateException("ClickHouse update is not visible");
                        }
                        for (int column = 0; column < row.length; column++) {
                            if (!equalValue(observed[column], row[column])) {
                                throw new IllegalStateException("ClickHouse updated values are not confirmed");
                            }
                        }
                    }
                    confirmed++;
                }
            }
            verifyTarget(connection);
            return confirmed;
        });
        if (written != converted.size()) {
            throw new UnconfirmedWriteException();
        }
        // 不存在 JDBC 事务：确认依据是每行经服务器确认的请求及回读结果。
        return new BatchWriteResult(written, null, CommitAcknowledgement.JDBC_COMMIT_RETURNED);
    }

    /**
     * 单次同步多行 INSERT 请求，使用严格渲染的字面值。JDBC 驱动的类型化参数
     * 无法精确表示极端或亚秒时间（已验证 DateTime64 精度丢失），因此整个
     * VALUES 块在此根据转换后的规范值渲染，并作为单条语句发送；同步插入
     * （async_insert=0, wait_for_async_insert=1）的响应确认块中的每一行。
     */
    private long insert(Connection connection, ClickhouseWriteExecution.Attempt attempt,
            List<Object[]> rows) throws Exception {
        var values = new ArrayList<String>();
        for (Object[] row : rows) {
            var rendered = new ArrayList<String>();
            for (int index = 0; index < row.length; index++) {
                rendered.add(valueLiteral(columns.get(index), row[index]));
            }
            values.add("(" + String.join(",", rendered) + ")");
        }
        String sql = "INSERT INTO " + ClickhouseSql.table(schemaName, tableName) + " (" + ClickhouseSql.columns(fields)
                + ") SETTINGS async_insert=0, wait_for_async_insert=1 VALUES " + String.join(",", values);
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(runtimeOptions.queryTimeoutSeconds());
            attempt.sending();
            statement.execute(sql);
            return rows.size();
        }
    }

    /** 根据转换后的规范值生成严格 SQL 字面值；字符串需转义，绝不通过驱动参数传输。 */
    private String valueLiteral(ClickhouseCursorMetadata.Column column, Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof String text) {
            return stringLiteral(text);
        }
        if (value instanceof java.math.BigDecimal decimal) {
            return "CAST('" + decimal.toPlainString() + "' AS Decimal(" + column.precision() + "," + column.scale() + "))";
        }
        if (value instanceof java.math.BigInteger || value instanceof Boolean || value instanceof Number) {
            return value.toString();
        }
        if (value instanceof java.time.LocalDate || value instanceof java.time.LocalDateTime) {
            return stringLiteral(ClickhouseCursorMetadata.temporalText(column, value));
        }
        throw new BatchRejectedException("ClickHouse UPSERT value cannot be rendered as an exact literal");
    }

    /**
     * 根据游标键值生成范围查询或匹配谓词。
     */
    private String keyPredicate(Object[] row) {
        var terms = new ArrayList<String>();
        for (int index = 0; index < keyColumns.size(); index++) {
            var column = keyColumns.get(index);
            terms.add(ClickhouseSql.quote(column.name()) + "=" + valueLiteral(column,
                    row[fields.indexOf(keys.get(index))]));
        }
        return String.join(" AND ", terms);
    }

    /**
     * 读取当前结果集中的一行数据。
     */
    private Object[] readRow(Connection connection, Object[] expected) throws Exception {
        String sql = "SELECT " + ClickhouseSql.columns(fields) + " FROM " + ClickhouseSql.table(schemaName, tableName)
                + " WHERE " + keyPredicate(expected) + " LIMIT 2";
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(runtimeOptions.queryTimeoutSeconds());
            try (var values = statement.executeQuery(sql)) {
                if (!values.next()) {
                    return null;
                }
                Object[] row = new Object[fields.size()];
                for (int column = 0; column < row.length; column++) {
                    row[column] = ClickhouseCursorMetadata.readValue(values, column + 1, columns.get(column));
                }
                if (values.next()) {
                    throw new IllegalStateException("ClickHouse key matched multiple rows");
                }
                return row;
            }
        }
    }

    /**
     * 校验目标端。
     */
    private void verifyTarget(Connection connection) throws Exception {
        var current = ClickhouseCursorMetadata.read(connection, schemaName, tableName,
                runtimeOptions.queryTimeoutSeconds());
        if (!current.relationEvidence().equals(shape.relationEvidence())) {
            throw new IllegalStateException("ClickHouse UPSERT target generation or structure changed");
        }
    }

    /**
     * 根据当前输入构建并返回 {@code ClickhouseValueConversions.Column}。
     */
    private static ClickhouseValueConversions.Column conversionColumn(ClickhouseCursorMetadata.Column column) {
        return new ClickhouseValueConversions.Column(column.jdbcType(), column.type(), column.precision(),
                column.scale(), column.nullable());
    }

    /**
     * 按当前数据库语法转义并生成字符串字面量。
     */
    private static String stringLiteral(String text) {
        var escaped = new StringBuilder(text.length() + 2).append('\'');
        for (int index = 0; index < text.length(); index++) {
            switch (text.charAt(index)) {
                case '\\' -> escaped.append("\\\\");
                case '\'' -> escaped.append("\\'");
                case '\0' -> escaped.append("\\0");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> escaped.append(text.charAt(index));
            }
        }
        return escaped.append('\'').toString();
    }

    /**
     * 根据当前输入构建并返回 {@code java.math.BigDecimal}。
     */
    private static Object canonicalKey(Object value) {
        if (value instanceof Number number) {
            return new java.math.BigDecimal(number.toString()).stripTrailingZeros();
        }
        if (value instanceof byte[] bytes) {
            return java.util.HexFormat.of().formatHex(bytes);
        }
        return value;
    }

    /**
     * 按当前类型语义比较两个值是否相等。
     */
    private static boolean equalValue(Object left, Object right) {
        if (left instanceof Number && right instanceof Number) {
            return canonicalKey(left).equals(canonicalKey(right));
        }
        if (left instanceof java.sql.Timestamp timestamp && right instanceof java.time.LocalDateTime time) {
            return timestamp.toLocalDateTime().equals(time);
        }
        if (right instanceof java.sql.Timestamp timestamp && left instanceof java.time.LocalDateTime time) {
            return timestamp.toLocalDateTime().equals(time);
        }
        return java.util.Objects.deepEquals(left, right);
    }
}
