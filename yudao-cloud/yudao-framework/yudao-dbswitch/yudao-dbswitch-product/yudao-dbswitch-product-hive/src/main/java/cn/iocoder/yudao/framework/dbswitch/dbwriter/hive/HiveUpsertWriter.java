package cn.iocoder.yudao.framework.dbswitch.dbwriter.hive;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.common.util.JdbcValueConversions;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.NonTransactionalWriteExecution;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.AbstractDatabaseWriter;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.BatchWriteResult;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.CommitAcknowledgement;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.IDatabaseUpsertWriter;
import cn.iocoder.yudao.framework.dbswitch.product.hive.HiveAcidTable;
import cn.iocoder.yudao.framework.dbswitch.product.hive.HiveCursorMetadata;
import cn.iocoder.yudao.framework.dbswitch.product.hive.HiveJdbcWriteSupport;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 完整 ACID ORC Hive upsert：通过有界多 VALUES 语句插入缺失行，并获得服务器
 * 确认；已有行更新后精确回读校验。不存在跨语句
 * 事务：每条语句由服务器提交，首次发送后的任何失败均为 UNKNOWN，此处绝不
 * 重试或回滚；因键控 upsert 幂等，重放批次会收敛。
 */
public final class HiveUpsertWriter extends AbstractDatabaseWriter implements IDatabaseUpsertWriter {

    private Map<String, ColumnDescription> nativeColumns = Map.of();

    private HiveAcidTable acid;

    private CursorKeyContract contract;

    private List<String> fields = List.of(), keys = List.of(), mutableColumns = List.of();

    private String fingerprint;

    /**
     * 初始化 {@code HiveUpsertWriter}及其父类状态。
     */
    public HiveUpsertWriter(DataSource source) {
        super(source);
    }

    /**
     * 返回写入器对应的数据库产品名（用于日志输出）。
     */
    @Override
    protected String getDatabaseProductName() {
        return "Hive";
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
        try {
            HiveJdbcWriteSupport.columns(names);
            HiveJdbcWriteSupport.columns(uniqueKeys);
            if (names.isEmpty() || uniqueKeys.isEmpty() || !names.containsAll(uniqueKeys)
                    || new HashSet<>(names).size() != names.size() || new HashSet<>(
                    uniqueKeys).size() != uniqueKeys.size()) {
                throw new IllegalArgumentException();
            }
            try (var connection = dataSource.getConnection()) {
                HiveJdbcWriteSupport.session(connection, runtimeOptions.queryTimeoutSeconds());
                nativeColumns = HiveJdbcWriteSupport.metadata(connection, schema, table, names);
                acid = HiveAcidTable.require(connection, schema, table, runtimeOptions.queryTimeoutSeconds());
                HiveJdbcWriteSupport.requireUniqueKeys(connection, schema, table, uniqueKeys,
                        runtimeOptions.queryTimeoutSeconds());
                var shape = HiveCursorMetadata.read(connection, schema, table, runtimeOptions.queryTimeoutSeconds());
                contract = HiveCursorMetadata.keys(shape, uniqueKeys);
                fingerprint = DigestUtil.sha256Hex("HIVE_UPSERT_TARGET_V1\0" + shape.fingerprint() + '\0'
                        + String.join("\0", names) + '\0' + String.join("\0", uniqueKeys));
            }
        } catch (Exception failure) {
            nativeColumns = Map.of();
            acid = null;
            contract = null;
            fingerprint = null;
            fields = List.of();
            keys = List.of();
            var rejection = new IllegalArgumentException(
                    "Hive UPSERT requires a full ACID ORC table with verified unique keys");
            rejection.addSuppressed(failure);
            throw rejection;
        }
        schemaName = schema;
        tableName = table;
        fields = List.copyOf(names);
        keys = List.copyOf(uniqueKeys);
        mutableColumns = names.stream()
                .filter(name -> !uniqueKeys.contains(name) && !acid.immutableColumns().contains(name)).toList();
    }

    /**
     * 返回已观测目标表的结构指纹。
     */
    @Override
    public String targetSchemaFingerprint() {
        if (fingerprint == null) {
            throw new IllegalStateException("Hive UPSERT target is not prepared");
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
        if (contract == null) {
            throw new IllegalStateException("Hive UPSERT target is not prepared");
        }
        return contract;
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
        if (fingerprint == null || !fields.equals(names)) {
            throw new cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException(
                    "Hive UPSERT input fields differ from the prepared fields");
        }
        batchLimits.maxMultiValueRows(fields.size(), 0);
        var converted = HiveJdbcWriteSupport.convert(rows, fields, nativeColumns, batchLimits);
        if (converted.isEmpty()) {
            return new BatchWriteResult(0, null);
        }
        var seen = new HashSet<List<Object>>();
        for (Object[] row : converted) {
            var key = keys.stream().map(name -> canonical(row[fields.indexOf(name)])).toList();
            if (key.contains(null) || !seen.add(key)) {
                throw new cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException(
                        "Null or duplicate Hive batch key");
            }
        }
        int timeout = runtimeOptions.queryTimeoutSeconds();
        long written = NonTransactionalWriteExecution.execute(dataSource, (connection, attempt) -> {
            HiveJdbcWriteSupport.session(connection, timeout);
            HiveAcidTable.require(connection, schemaName, tableName, timeout);
            var existing = new ArrayList<Object[]>();
            var missing = new ArrayList<Object[]>();
            for (Object[] row : converted) {
                Object[] current = readRow(connection, row);
                if (current == null) {
                    missing.add(row);
                } else {
                    for (String immutable : acid.immutableColumns()) {
                        int index = fields.indexOf(immutable);
                        if (index >= 0 && !equal(current[index], row[index])) {
                            throw new IllegalArgumentException("Hive cannot update a partition or bucketing column");
                        }
                    }
                    existing.add(row);
                }
            }
            long confirmed = HiveJdbcWriteSupport.insert(connection, attempt, schemaName, tableName, fields,
                    nativeColumns,
                    missing, batchLimits, timeout);
            for (Object[] row : existing) {
                String assignments = mutableColumns.stream().map(field -> HiveJdbcWriteSupport.quote(field) + "="
                        + HiveJdbcWriteSupport.placeholder(nativeColumns.get(field))).collect(Collectors.joining(","));
                try (var statement = connection.prepareStatement(
                        "UPDATE " + HiveJdbcWriteSupport.table(schemaName, tableName)
                                + " SET " + assignments + " WHERE " + HiveJdbcWriteSupport.keys(keys, nativeColumns))) {
                    statement.setQueryTimeout(timeout);
                    int parameter = 1;
                    for (String field : mutableColumns) {
                        HiveJdbcWriteSupport.bind(statement, parameter++, row[fields.indexOf(field)],
                                nativeColumns.get(field));
                    }
                    for (String key : keys) {
                        HiveJdbcWriteSupport.bind(statement, parameter++, row[fields.indexOf(key)],
                                nativeColumns.get(key));
                    }
                    attempt.sending();
                    if (statement.executeUpdate() != 1) {
                        throw new IllegalStateException("Hive UPSERT update row count is not confirmed");
                    }
                }
                Object[] observed = readRow(connection, row);
                if (observed == null) {
                    throw new IllegalStateException("Hive UPSERT update is not visible");
                }
                for (int index = 0; index < row.length; index++) {
                    if (!equal(observed[index], row[index])) {
                        throw new IllegalStateException("Hive UPSERT values are not confirmed");
                    }
                }
                confirmed++;
            }
            return confirmed;
        });
        if (written != converted.size()) {
            throw new cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException();
        }
        return new BatchWriteResult(written, null, CommitAcknowledgement.JDBC_COMMIT_RETURNED);
    }

    /**
     * 读取当前结果集中的一行数据。
     */
    private Object[] readRow(Connection connection, Object[] key) throws Exception {
        try (var statement = connection.prepareStatement("SELECT " + HiveJdbcWriteSupport.columns(fields)
                + " FROM " + HiveJdbcWriteSupport.table(schemaName, tableName) + " WHERE "
                + HiveJdbcWriteSupport.keys(keys, nativeColumns) + " LIMIT 2")) {
            statement.setQueryTimeout(runtimeOptions.queryTimeoutSeconds());
            for (int index = 0; index < keys.size(); index++) {
                HiveJdbcWriteSupport.bind(statement, index + 1, key[fields.indexOf(keys.get(index))],
                        nativeColumns.get(keys.get(index)));
            }
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                Object[] result = new Object[fields.size()];
                for (int index = 0; index < result.length; index++) {
                    result[index] = JdbcValueConversions.normalize(rows.getObject(index + 1),
                            batchLimits.maxCellBytes());
                }
                if (rows.next()) {
                    throw new IllegalStateException("Hive key matched more than one row");
                }
                return result;
            }
        }
    }

    /**
     * 将值整理为当前协议使用的规范形式。
     */
    private static Object canonical(Object value) {
        if (value instanceof Number number) {
            return new BigDecimal(number.toString()).stripTrailingZeros();
        }
        if (value instanceof byte[] bytes) {
            return java.util.HexFormat.of().formatHex(bytes);
        }
        return value;
    }

    /**
     * 返回当前相等性。
     */
    private static boolean equal(Object left, Object right) {
        if (left instanceof Number && right instanceof Number) {
            return canonical(left).equals(canonical(right));
        }
        if (left instanceof java.sql.Timestamp timestamp && right instanceof java.time.LocalDateTime time) {
            return timestamp.toLocalDateTime().equals(time);
        }
        if (right instanceof java.sql.Timestamp timestamp && left instanceof java.time.LocalDateTime time) {
            return timestamp.toLocalDateTime().equals(time);
        }
        return Objects.deepEquals(left, right);
    }
}
