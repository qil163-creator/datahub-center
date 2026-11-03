package cn.iocoder.yudao.framework.dbswitch.dbsynch.clickhouse;

import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import cn.iocoder.yudao.framework.dbswitch.common.util.JdbcValueConversions;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.AbstractDatabaseSynchronize;
import cn.iocoder.yudao.framework.dbswitch.product.clickhouse.ClickhouseSql;
import cn.iocoder.yudao.framework.dbswitch.product.clickhouse.ClickhouseWriteExecution;
import cn.iocoder.yudao.framework.dbswitch.product.clickhouse.ClickhouseValueConversions;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 原生 MergeTree 变更，仅在 mutations_sync=2 完成且回读后确认。
 * 要求任务独占目标表：ClickHouse 没有唯一约束
 * 或事务，无法排除预检与变更之间的无关并发写入器。
 *
 * @author jrl
 */
public class ClickhouseDatabaseSyncImpl extends AbstractDatabaseSynchronize {

    private java.util.Map<String, ClickhouseValueConversions.Column> nativeColumns = java.util.Map.of();

    private Set<String> immutableColumns = Set.of();

    private List<String> mutableColumns = List.of();

    /**
     * 初始化 {@code ClickhouseDatabaseSyncImpl}及其父类状态。
     */
    public ClickhouseDatabaseSyncImpl(DataSource ds) {
        super(ds);
    }

    /**
     * 获取查询列元信息的SQL语句。
     */
    @Override
    public String getColumnMetaDataSql(String schema, String table) {
        return "SELECT * FROM " + ClickhouseSql.table(schema, table) + " WHERE 1=0";
    }

    /**
     * 生成使用参数占位符的 INSERT 语句。
     */
    @Override
    public String getInsertPrepareStatementSql(String schema, String table, List<String> fields) {
        return ClickhouseSql.insert(schema, table, fields);
    }

    /**
     * 生成Update操作的SQL语句。
     */
    @Override
    public String getUpdatePrepareStatementSql(String schema, String table, List<String> fields, List<String> keys) {
        var assignments = fields.stream().filter(field -> !keys.contains(field) && !immutableColumns.contains(field))
                .map(field -> ClickhouseSql.quote(field) + "=" + ClickhouseValueConversions.placeholder(
                        nativeColumns.get(field)))
                .collect(Collectors.joining(","));
        return assignments.isEmpty() ? null : "ALTER TABLE " + ClickhouseSql.table(schema, table)
                + " UPDATE " + assignments + " WHERE " + keyPredicate(keys) + " SETTINGS mutations_sync=2";
    }

    /**
     * 生成Delete操作的SQL语句。
     */
    @Override
    public String getDeletePrepareStatementSql(String schema, String table, List<String> keys) {
        return "ALTER TABLE " + ClickhouseSql.table(schema, table) + " DELETE WHERE " + keyPredicate(keys)
                + " SETTINGS mutations_sync=2";
    }

    /**
     * 根据游标键值生成范围查询或匹配谓词。
     */
    private String keyPredicate(List<String> keys) {
        ClickhouseSql.columns(keys);
        return keys.stream().map(key -> ClickhouseSql.quote(key) + "=" + ClickhouseValueConversions.placeholder(
                        nativeColumns.get(key)))
                .collect(Collectors.joining(" AND "));
    }

    /**
     * 同步准备（主键比对模式）。
     */
    @Override
    public void prepare(String schema, String table, List<String> fields, List<String> keys) {
        ClickhouseSql.columns(fields);
        ClickhouseSql.columns(keys);
        var immutable = new HashSet<String>();
        try (var connection = dataSource.getConnection()) {
            try (var statement = connection.prepareStatement(
                    "SELECT engine FROM system.tables WHERE database=? AND name=?")) {
                statement.setQueryTimeout(runtimeOptions.queryTimeoutSeconds());
                statement.setString(1, schema);
                statement.setString(2, table);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next() || !Set.of("MergeTree", "ReplicatedMergeTree")
                            .contains(rows.getString(1)) || rows.next()) {
                        throw new IllegalArgumentException(
                                "Difference synchronization requires an ordinary MergeTree table");
                    }
                }
            }
            try (var statement = connection.prepareStatement(
                    "SELECT name FROM system.columns WHERE database=? AND table=?"
                            + " AND (is_in_sorting_key=1 OR is_in_partition_key=1 OR is_in_primary_key=1)")) {
                statement.setQueryTimeout(runtimeOptions.queryTimeoutSeconds());
                statement.setString(1, schema);
                statement.setString(2, table);
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        immutable.add(rows.getString(1));
                    }
                }
            }
            requireUniqueKeys(connection, ClickhouseSql.table(schema, table), keys,
                    runtimeOptions.queryTimeoutSeconds());
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(runtimeOptions.queryTimeoutSeconds());
                try (var rows = statement.executeQuery("SELECT " + ClickhouseSql.columns(fields) + " FROM "
                        + ClickhouseSql.table(schema, table) + " WHERE 1=0")) {
                    nativeColumns = ClickhouseValueConversions.columns(rows.getMetaData());
                }
            }
        } catch (Exception failure) {
            throw new IllegalStateException("ClickHouse synchronization key/engine preflight failed");
        }
        immutableColumns = Set.copyOf(immutable);
        super.prepare(schema, table, fields, keys);
        mutableColumns = fields.stream().filter(field -> !keys.contains(field) && !immutableColumns.contains(field))
                .toList();
    }

    /** 稀疏主索引不是唯一约束。必须在两个读取端检查实际数据。 */
    public static void requireUniqueKeys(Connection connection, String from, List<String> keys,
            int timeout) throws Exception {
        String columns = ClickhouseSql.columns(keys);
        String nulls = keys.stream().map(key -> "isNull(" + ClickhouseSql.quote(key) + ")")
                .collect(Collectors.joining(" OR "));
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            try (var rows = statement.executeQuery("SELECT 1 FROM " + from + " WHERE " + nulls + " LIMIT 1")) {
                if (rows.next()) {
                    throw new IllegalArgumentException("ClickHouse difference keys cannot contain NULL");
                }
            }
            try (var rows = statement.executeQuery(
                    "SELECT 1 FROM " + from + " GROUP BY " + columns + " HAVING count()>1 LIMIT 1")) {
                if (rows.next()) {
                    throw new IllegalArgumentException("ClickHouse difference keys are not unique");
                }
            }
        }
    }

    /**
     * 执行参数化数据插入操作。
     */
    @Override
    public long executeInsert(List<Object[]> records) {
        return execute(records, Operation.INSERT);
    }

    /**
     * 批量执行 UPDATE。
     */
    @Override
    public long executeUpdate(List<Object[]> records) {
        return execute(records, Operation.UPDATE);
    }

    /**
     * 批量执行 DELETE：从完整记录中按主键顺序抽取主键值作为 WHERE 条件参数，再批量执行。
     */
    @Override
    public long executeDelete(List<Object[]> records) {
        return execute(records, Operation.DELETE);
    }

    private enum Operation {INSERT, UPDATE, DELETE}

    /**
     * 在当前数据库连接上执行给定 SQL 语句。
     */
    private long execute(List<Object[]> records, Operation operation) {
        if (records.isEmpty()) {
            return 0;
        }
        if (fieldOrders == null) {
            throw new IllegalStateException("ClickHouse synchronizer is not prepared");
        }
        batchLimits.requireStatementParameters(fieldOrders.size());
        var rows = BatchValueConverter.convert(records, fieldOrders.size(), batchLimits,
                (value, column) -> operation == Operation.DELETE && !pksOrders.contains(fieldOrders.get(column)) ? value
                        : ClickhouseValueConversions.convert(value, nativeColumns.get(fieldOrders.get(column))));
        var batchKeys = new HashSet<List<Object>>();
        for (Object[] row : rows) {
            var key = pksOrders.stream().map(name -> canonicalKey(row[fieldOrders.indexOf(name)])).toList();
            if (key.contains(null) || !batchKeys.add(key)) {
                throw new IllegalArgumentException("Null or duplicate ClickHouse batch key");
            }
        }
        return ClickhouseWriteExecution.execute(dataSource, (connection, attempt) -> {
            // 首次请求前校验整个批次。变更无法回滚。
            for (Object[] row : rows) {
                Object[] current = readRow(connection, row);
                if (operation == Operation.INSERT ? current != null : current == null) {
                    throw new IllegalStateException("ClickHouse key cardinality changed before mutation");
                }
                if (operation == Operation.UPDATE) {
                    for (String field : immutableColumns) {
                        int index = fieldOrders.indexOf(field);
                        if (index >= 0 && !equalValue(current[index], row[index])) {
                            throw new IllegalArgumentException(
                                    "Changing a ClickHouse sorting or partition column requires table replacement");
                        }
                    }
                }
            }
            if (operation == Operation.INSERT) {
                return ClickhouseWriteExecution.insert(connection, attempt,
                        insertStatementSql, rows, runtimeOptions.queryTimeoutSeconds());
            }
            if (operation == Operation.UPDATE && updateStatementSql == null) {
                return 0;
            }
            long confirmed = 0;
            String sql = operation == Operation.DELETE ? deleteStatementSql : updateStatementSql;
            try (var statement = connection.prepareStatement(sql)) {
                statement.setQueryTimeout(runtimeOptions.queryTimeoutSeconds());
                for (Object[] row : rows) {
                    int parameter = 1;
                    if (operation == Operation.UPDATE) {
                        for (String field : mutableColumns) {
                            ClickhouseValueConversions.bindExpression(statement, parameter++,
                                    row[fieldOrders.indexOf(field)], nativeColumns.get(field));
                        }
                    }
                    for (String key : pksOrders) {
                        ClickhouseValueConversions.bindExpression(statement, parameter++, row[fieldOrders.indexOf(key)],
                                nativeColumns.get(key));
                    }
                    attempt.sending();
                    statement.executeUpdate(); // DDL 响应中的零不代表受影响行数。
                    Object[] observed = readRow(connection, row);
                    if (operation == Operation.DELETE) {
                        if (observed != null) {
                            throw new IllegalStateException("ClickHouse deletion is not visible");
                        }
                    } else {
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
            return confirmed;
        });
    }

    /**
     * 读取当前结果集中的一行数据。
     */
    private Object[] readRow(Connection connection, Object[] expected) throws Exception {
        String sql = "SELECT " + ClickhouseSql.columns(fieldOrders) + " FROM " + ClickhouseSql.table(schemaName,
                tableName)
                + " WHERE " + keyPredicate(pksOrders) + " LIMIT 2";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(runtimeOptions.queryTimeoutSeconds());
            for (int index = 0; index < pksOrders.size(); index++) {
                ClickhouseValueConversions.bindExpression(statement, index + 1,
                        expected[fieldOrders.indexOf(pksOrders.get(index))], nativeColumns.get(pksOrders.get(index)));
            }
            try (var values = statement.executeQuery()) {
                if (!values.next()) {
                    return null;
                }
                Object[] row = new Object[fieldOrders.size()];
                for (int column = 0; column < row.length; column++) {
                    row[column] = JdbcValueConversions.normalize(values.getObject(column + 1),
                            batchLimits.maxCellBytes());
                }
                if (values.next()) {
                    throw new IllegalStateException("ClickHouse key matched multiple rows");
                }
                return row;
            }
        }
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
        return Objects.deepEquals(left, right);
    }
}
