package cn.iocoder.yudao.framework.dbswitch.dbsynch.hive;

import cn.iocoder.yudao.framework.dbswitch.common.util.JdbcValueConversions;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.NonTransactionalWriteExecution;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.AbstractDatabaseSynchronize;
import cn.iocoder.yudao.framework.dbswitch.product.hive.HiveAcidTable;
import cn.iocoder.yudao.framework.dbswitch.product.hive.HiveJdbcWriteSupport;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.*;
import java.util.stream.Collectors;

/** 完整 ACID Hive INSERT/UPDATE/DELETE；每条语句是服务器自有事务。 */
public class HiveDatabaseSyncImpl extends AbstractDatabaseSynchronize {

    private Map<String, ColumnDescription> nativeColumns = Map.of();

    private HiveAcidTable acid;

    private List<String> mutableColumns = List.of();

    /**
     * 初始化 {@code HiveDatabaseSyncImpl}及其父类状态。
     */
    public HiveDatabaseSyncImpl(DataSource source) {
        super(source);
    }

    /**
     * 说明当前同步路径是否允许使用未声明唯一约束的匹配键。
     */
    @Override
    public boolean supportsUnconstrainedMatchKeys() {
        return true;
    }

    /**
     * 获取查询列元信息的SQL语句。
     */
    @Override
    public String getColumnMetaDataSql(String schema, String table) {
        return "SELECT * FROM " + HiveJdbcWriteSupport.table(schema, table) + " WHERE 1=0";
    }

    /**
     * 生成使用参数占位符的 INSERT 语句。
     */
    @Override
    public String getInsertPrepareStatementSql(String schema, String table, List<String> fields) {
        return "INSERT INTO TABLE " + HiveJdbcWriteSupport.table(schema, table) + " (" + HiveJdbcWriteSupport.columns(
                fields)
                + ") VALUES (" + String.join(",", Collections.nCopies(fields.size(), "?")) + ")";
    }

    /**
     * 生成Update操作的SQL语句。
     */
    @Override
    public String getUpdatePrepareStatementSql(String schema, String table, List<String> fields, List<String> keys) {
        var assignments = fields.stream()
                .filter(field -> !keys.contains(field) && (acid == null || !acid.immutableColumns().contains(field)))
                .map(field -> HiveJdbcWriteSupport.quote(field) + "=" + (nativeColumns.containsKey(field)
                        ? HiveJdbcWriteSupport.placeholder(nativeColumns.get(field)) : "?"))
                .collect(Collectors.joining(","));
        return assignments.isEmpty() ? null : "UPDATE " + HiveJdbcWriteSupport.table(schema,
                table) + " SET " + assignments
                + " WHERE " + HiveJdbcWriteSupport.keys(keys, nativeColumns);
    }

    /**
     * 生成Delete操作的SQL语句。
     */
    @Override
    public String getDeletePrepareStatementSql(String schema, String table, List<String> keys) {
        return "DELETE FROM " + HiveJdbcWriteSupport.table(schema, table) + " WHERE " + HiveJdbcWriteSupport.keys(keys,
                nativeColumns);
    }

    /**
     * 同步准备（主键比对模式）。
     */
    @Override
    public void prepare(String schema, String table, List<String> fields, List<String> keys) {
        HiveJdbcWriteSupport.columns(fields);
        HiveJdbcWriteSupport.columns(keys);
        if (!fields.containsAll(keys)) {
            throw new IllegalArgumentException("Hive match keys must be selected columns");
        }
        try (var connection = dataSource.getConnection()) {
            HiveJdbcWriteSupport.session(connection, runtimeOptions.queryTimeoutSeconds());
            nativeColumns = HiveJdbcWriteSupport.metadata(connection, schema, table, fields);
            acid = HiveAcidTable.require(connection, schema, table, runtimeOptions.queryTimeoutSeconds());
            HiveJdbcWriteSupport.requireUniqueKeys(connection, schema, table, keys,
                    runtimeOptions.queryTimeoutSeconds());
        } catch (Exception failure) {
            throw new IllegalStateException("Hive ACID/key metadata preflight failed");
        }
        schemaName = schema;
        tableName = table;
        fieldOrders = List.copyOf(fields);
        pksOrders = List.copyOf(keys);
        mutableColumns = fields.stream()
                .filter(field -> !keys.contains(field) && !acid.immutableColumns().contains(field)).toList();
        insertStatementSql = getInsertPrepareStatementSql(schema, table, fields);
        updateStatementSql = getUpdatePrepareStatementSql(schema, table, fields, keys);
        deleteStatementSql = getDeletePrepareStatementSql(schema, table, keys);
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
        if (acid == null || fieldOrders == null) {
            throw new IllegalStateException("Hive synchronizer is not prepared");
        }
        batchLimits.maxMultiValueRows(fieldOrders.size(), 0);
        var rows = HiveJdbcWriteSupport.convert(records, fieldOrders, nativeColumns, batchLimits);
        var keys = new HashSet<List<Object>>();
        for (Object[] row : rows) {
            var key = pksOrders.stream().map(name -> canonical(row[fieldOrders.indexOf(name)])).toList();
            if (key.contains(null) || !keys.add(key)) {
                throw new IllegalArgumentException("Null or duplicate Hive batch key");
            }
        }
        return NonTransactionalWriteExecution.execute(dataSource, (connection, attempt) -> {
            HiveJdbcWriteSupport.session(connection, runtimeOptions.queryTimeoutSeconds());
            HiveAcidTable.require(connection, schemaName, tableName, runtimeOptions.queryTimeoutSeconds());
            for (Object[] row : rows) {
                Object[] current = readRow(connection, row);
                if (operation == Operation.INSERT ? current != null : current == null) {
                    throw new IllegalStateException("Hive key cardinality changed before write");
                }
                if (operation == Operation.UPDATE) {
                    for (String immutable : acid.immutableColumns()) {
                        int index = fieldOrders.indexOf(immutable);
                        if (index >= 0 && !equal(current[index], row[index])) {
                            throw new IllegalArgumentException("Hive cannot update a partition or bucketing column");
                        }
                    }
                }
            }
            if (operation == Operation.INSERT) {
                return HiveJdbcWriteSupport.insert(connection, attempt, schemaName, tableName,
                        fieldOrders, nativeColumns, rows, batchLimits, runtimeOptions.queryTimeoutSeconds());
            }
            if (operation == Operation.UPDATE && updateStatementSql == null) {
                return 0;
            }
            String sql = operation == Operation.UPDATE ? updateStatementSql : deleteStatementSql;
            long confirmed = 0;
            try (var statement = connection.prepareStatement(sql)) {
                statement.setQueryTimeout(runtimeOptions.queryTimeoutSeconds());
                for (Object[] row : rows) {
                    int parameter = 1;
                    if (operation == Operation.UPDATE) {
                        for (String field : mutableColumns) {
                            HiveJdbcWriteSupport.bind(statement, parameter++, row[fieldOrders.indexOf(field)],
                                    nativeColumns.get(field));
                        }
                    }
                    for (String key : pksOrders) {
                        HiveJdbcWriteSupport.bind(statement, parameter++, row[fieldOrders.indexOf(key)],
                                nativeColumns.get(key));
                    }
                    attempt.sending();
                    if (statement.executeUpdate() != 1) {
                        throw new IllegalStateException("Hive mutation row count is not confirmed");
                    }
                    Object[] observed = readRow(connection, row);
                    if (operation == Operation.DELETE) {
                        if (observed != null) {
                            throw new IllegalStateException("Hive deletion is not visible");
                        }
                    } else {
                        if (observed == null) {
                            throw new IllegalStateException("Hive update is not visible");
                        }
                        for (int index = 0; index < row.length; index++) {
                            if (!equal(observed[index], row[index])) {
                                throw new IllegalStateException("Hive updated values are not confirmed");
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
    private Object[] readRow(Connection connection, Object[] key) throws Exception {
        try (var statement = connection.prepareStatement("SELECT " + HiveJdbcWriteSupport.columns(fieldOrders)
                + " FROM " + HiveJdbcWriteSupport.table(schemaName, tableName) + " WHERE " + HiveJdbcWriteSupport.keys(
                pksOrders, nativeColumns) + " LIMIT 2")) {
            statement.setQueryTimeout(runtimeOptions.queryTimeoutSeconds());
            for (int index = 0; index < pksOrders.size(); index++) {
                HiveJdbcWriteSupport.bind(statement, index + 1, key[fieldOrders.indexOf(pksOrders.get(index))],
                        nativeColumns.get(pksOrders.get(index)));
            }
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                Object[] result = new Object[fieldOrders.size()];
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
            return new java.math.BigDecimal(number.toString()).stripTrailingZeros();
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
