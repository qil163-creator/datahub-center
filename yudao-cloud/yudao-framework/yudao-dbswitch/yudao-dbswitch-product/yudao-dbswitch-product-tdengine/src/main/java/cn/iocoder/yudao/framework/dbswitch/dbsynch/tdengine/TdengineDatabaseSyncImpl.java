package cn.iocoder.yudao.framework.dbswitch.dbsynch.tdengine;

import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.NonTransactionalWriteExecution;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.AbstractDatabaseSynchronize;
import cn.iocoder.yudao.framework.dbswitch.product.tdengine.TdengineJdbcSupport;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.recovery.GroupRewriteBinding;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.recovery.GroupRewriteBindingProvider;
import cn.iocoder.yudao.framework.dbswitch.product.tdengine.TdengineNativeTables;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.*;

/** 原生时间戳键覆盖与删除，先预检整个批次，再确认可见回读。 */
public class TdengineDatabaseSyncImpl extends AbstractDatabaseSynchronize {

    private TdengineJdbcSupport.Table metadata;

    private GroupRewriteBinding recoveryBinding;

    private GroupRewriteBindingProvider recoveryProvider;

    private TdengineGroupRewrite groupRewrite;

    /**
     * 绑定分组重写。
     */
    @Override
    public void bindGroupRewrite(GroupRewriteBinding binding) {
        if (metadata != null || recoveryProvider != null) {
            throw new IllegalStateException("Recovery binding must precede prepare and have one owner");
        }
        recoveryBinding = Objects.requireNonNull(binding);
    }

    /**
     * 绑定分组重写提供器。
     */
    @Override
    public void bindGroupRewriteProvider(GroupRewriteBindingProvider provider) {
        if (metadata != null || recoveryBinding != null) {
            throw new IllegalStateException("Recovery provider must precede prepare and have one owner");
        }
        recoveryProvider = Objects.requireNonNull(provider);
    }

    /**
     * 初始化 {@code TdengineDatabaseSyncImpl}及其父类状态。
     */
    public TdengineDatabaseSyncImpl(DataSource source) {
        super(source);
    }

    /**
     * 获取查询列元信息的SQL语句。
     */
    @Override
    public String getColumnMetaDataSql(String schema, String table) {
        return "SELECT * FROM " + TdengineJdbcSupport.table(schema, table) + " LIMIT 0";
    }

    /**
     * 生成使用参数占位符的 INSERT 语句。
     */
    @Override
    public String getInsertPrepareStatementSql(String schema, String table, List<String> fields) {
        return TdengineJdbcSupport.insertSql(schema, table, fields);
    }

    /**
     * 生成Update操作的SQL语句。
     */
    @Override
    public String getUpdatePrepareStatementSql(String schema, String table, List<String> fields, List<String> keys) {
        return getInsertPrepareStatementSql(schema, table, fields);
    }

    /**
     * 生成Delete操作的SQL语句。
     */
    @Override
    public String getDeletePrepareStatementSql(String schema, String table, List<String> keys) {
        if (keys.size() != 1) {
            throw new IllegalArgumentException("TDengine DELETE only supports the timestamp key");
        }
        return "DELETE FROM " + TdengineJdbcSupport.table(schema, table) + " WHERE " + TdengineJdbcSupport.quote(
                keys.getFirst()) + "=";
    }

    /**
     * 同步准备（主键比对模式）。
     */
    @Override
    public void prepare(String schema, String table, List<String> fields, List<String> keys) {
        TdengineJdbcSupport.columns(fields);
        try (var connection = dataSource.getConnection()) {
            metadata = TdengineJdbcSupport.metadata(connection, schema, table, runtimeOptions.queryTimeoutSeconds());
            if (metadata.kind().equals("SUPER_TABLE")) {
                throw new IllegalArgumentException(
                        "TDengine difference synchronization requires a physical leaf table");
            }
            fields.forEach(metadata::column);
            if (keys.isEmpty() || keys.size() > 2 || !metadata.keys().equals(keys) || !fields.containsAll(keys)) {
                throw new IllegalArgumentException(
                        "TDengine difference synchronization requires the complete physical timestamp/key");
            }
            if (keys.size() == 2 && recoveryBinding == null && recoveryProvider == null) {
                throw new IllegalArgumentException(
                        "TDengine composite-key difference requires a trusted durable recovery binding");
            }
            // 覆盖会写入完整测量。未映射的列可能被默默清空。
            if (!new HashSet<>(fields).equals(
                    new HashSet<>(metadata.columns().stream().map(TdengineJdbcSupport.Column::name).toList()))) {
                throw new IllegalArgumentException("TDengine overwrite requires the complete physical row");
            }
        } catch (Exception failure) {
            throw new IllegalStateException("TDengine difference metadata/key preflight failed");
        }
        schemaName = schema;
        tableName = table;
        fieldOrders = List.copyOf(fields);
        pksOrders = List.copyOf(keys);
        insertStatementSql = getInsertPrepareStatementSql(schema, table, fields);
        updateStatementSql = insertStatementSql;
        if (keys.size() == 2) {
            TdengineNativeTables.RecoveryIdentity identity;
            try (var connection = dataSource.getConnection()) {
                identity = TdengineNativeTables.recoveryIdentity(connection, schema, table,
                        runtimeOptions.queryTimeoutSeconds());
            } catch (Exception failure) {
                throw new IllegalStateException("TDengine recovery admission identity capture failed");
            }
            if (recoveryBinding == null) {
                recoveryBinding = Objects.requireNonNull(
                        recoveryProvider.resolve(schema, table, identity.logicalResource(), identity.physical(),
                                identity.structure()));
            }
            groupRewrite = new TdengineGroupRewrite(dataSource, schema, table, fieldOrders, metadata, recoveryBinding,
                    runtimeOptions.queryTimeoutSeconds(), identity);
            groupRewrite.reconcilePending();
        } else {
            deleteStatementSql = getDeletePrepareStatementSql(schema, table, keys);
        }
    }

    /**
     * 执行参数化数据插入操作。
     */
    @Override
    public long executeInsert(List<Object[]> rows) {
        return execute(rows, 0);
    }

    /**
     * 批量执行 UPDATE。
     */
    @Override
    public long executeUpdate(List<Object[]> rows) {
        return execute(rows, 1);
    }

    /**
     * 批量执行 DELETE：从完整记录中按主键顺序抽取主键值作为 WHERE 条件参数，再批量执行。
     */
    @Override
    public long executeDelete(List<Object[]> rows) {
        return execute(rows, 2);
    }

    /**
     * 在当前数据库连接上执行给定 SQL 语句。
     */
    private long execute(List<Object[]> input, int operation) {
        if (input.isEmpty()) {
            return 0;
        }
        if (metadata == null || fieldOrders == null) {
            throw new IllegalStateException("TDengine synchronizer is not prepared");
        }
        var rows = TdengineJdbcSupport.convert(input, fieldOrders, metadata, batchLimits);
        if (groupRewrite != null) {
            return operation == 2 ? groupRewrite.delete(rows) : groupRewrite.upsert(rows, operation == 0);
        }
        return NonTransactionalWriteExecution.execute(dataSource, (connection, attempt) -> {
            TdengineJdbcSupport.requireValueDriver(connection);
            // 首次变更发送前检查全部键；存在外部写入器时，要求任务拥有目标独占权。
            for (var row : rows) {
                Object[] current = readRow(connection, row);
                if (operation == 0 ? current != null : current == null) {
                    throw new IllegalStateException("TDengine key cardinality changed before mutation");
                }
            }
            long confirmed = 0;
            for (var row : rows) {
                if (operation == 2) {
                    // WS 参数绑定仅接受 SELECT/INSERT。键是经过验证的 long，
                    // 绝不是用户 SQL，也不是时区或精度有歧义的文本时间戳。
                    long key = (Long) row[fieldOrders.indexOf(pksOrders.getFirst())];
                    try (var statement = connection.createStatement()) {
                        statement.setQueryTimeout(runtimeOptions.queryTimeoutSeconds());
                        attempt.sending();
                        if (statement.executeUpdate(deleteStatementSql + key) != 1) {
                            throw new IllegalStateException("TDengine delete count is not confirmed");
                        }
                    }
                } else {
                    TdengineJdbcSupport.insert(connection, attempt, schemaName, tableName, fieldOrders, metadata,
                            Collections.singletonList(row), runtimeOptions.queryTimeoutSeconds());
                }
                Object[] observed = readRow(connection, row);
                if (operation == 2 ? observed != null : observed == null) {
                    throw new IllegalStateException("TDengine mutation is not visible");
                }
                if (operation != 2) {
                    for (int index = 0; index < row.length; index++) {
                        Object actual = TdengineJdbcSupport.convert(observed[index],
                                metadata.column(fieldOrders.get(index)), metadata.fractionalDigits());
                        if (!Objects.deepEquals(actual, row[index])) {
                            throw new IllegalStateException("TDengine value read-back is not confirmed");
                        }
                    }
                }
                confirmed++;
            }
            return confirmed;
        });
    }

    /**
     * 读取当前结果集中的一行数据。
     */
    private Object[] readRow(Connection connection, Object[] row) throws Exception {
        long key = (Long) row[fieldOrders.indexOf(pksOrders.getFirst())];
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(runtimeOptions.queryTimeoutSeconds());
            try (var result = statement.executeQuery("SELECT " + TdengineJdbcSupport.columns(fieldOrders) + " FROM "
                    + TdengineJdbcSupport.table(schemaName, tableName) + " WHERE " + TdengineJdbcSupport.quote(
                    pksOrders.getFirst()) + "=" + key + " LIMIT 2")) {
                if (!result.next()) {
                    return null;
                }
                Object[] values = new Object[fieldOrders.size()];
                for (int index = 0; index < values.length; index++) {
                    values[index] = result.getObject(index + 1);
                }
                if (result.next()) {
                    throw new IllegalStateException("TDengine timestamp key is not unique");
                }
                return values;
            }
        }
    }
}
