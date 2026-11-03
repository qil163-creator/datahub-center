package cn.iocoder.yudao.framework.dbswitch.dbsynch.starrocks;

import cn.iocoder.yudao.framework.dbswitch.dbsynch.mysql.MySqlDatabaseSyncImpl;
import cn.iocoder.yudao.framework.dbswitch.product.starrocks.StarrocksJdbcSupport;

import javax.sql.DataSource;
import java.util.*;

import static cn.iocoder.yudao.framework.dbswitch.product.starrocks.StarrocksJdbcSupport.*;

/** 仅使用原生主键模型；每个 DML 请求都有明确的非事务确认。 */
public class StarrocksDatabaseSyncImpl extends MySqlDatabaseSyncImpl {

    private Table metadata;

    private List<Column> insertColumns, updateColumns, deleteColumns;

    /**
     * 初始化 {@code StarrocksDatabaseSyncImpl}及其父类状态。
     */
    public StarrocksDatabaseSyncImpl(DataSource source) {
        super(source);
    }

    /**
     * 同步准备（主键比对模式）。
     */
    @Override
    public void prepare(String schema, String table, List<String> fields, List<String> keys) {
        metadata = null;
        insertColumns = null;
        updateColumns = null;
        deleteColumns = null;
        fieldOrders = null;
        pksOrders = null;
        try (var c = dataSource.getConnection()) {
            metadata = metadata(c, schema, table, runtimeOptions.queryTimeoutSeconds());
            if (!metadata.model().equals("PRIMARY_KEYS") || !metadata.keys().equals(keys)) {
                throw new IllegalArgumentException("StarRocks difference writes require the exact native primary key");
            }
        } catch (Exception failure) {
            throw new IllegalStateException("StarRocks difference key preflight failed");
        }
        super.prepare(schema, table, fields, keys);
        insertColumns = fields.stream().map(metadata::column).toList();
        var updates = new ArrayList<>(fields.stream().filter(f -> !keys.contains(f)).toList());
        updates.addAll(keys);
        updateColumns = updates.stream().map(metadata::column).toList();
        deleteColumns = keys.stream().map(metadata::column).toList();
    }

    /**
     * 校验并取得已准备。
     */
    private void requirePrepared() {
        if (metadata == null || insertColumns == null) {
            throw new IllegalStateException("StarRocks synchronizer is not prepared");
        }
    }

    /**
     * 执行参数化数据插入操作。
     */
    @Override
    public long executeInsert(List<Object[]> records) {
        requirePrepared();
        var rows = convert(records, fieldOrders, metadata, batchLimits);
        batchInsert(rows, insertStatementSql, insertArgsType);
        return rows.size();
    }

    /**
     * 批量执行 UPDATE。
     */
    @Override
    public long executeUpdate(List<Object[]> records) {
        requirePrepared();
        return super.executeUpdate(convert(records, fieldOrders, metadata, batchLimits));
    }

    /**
     * 批量执行 DELETE：从完整记录中按主键顺序抽取主键值作为 WHERE 条件参数，再批量执行。
     */
    @Override
    public long executeDelete(List<Object[]> records) {
        requirePrepared();
        return super.executeDelete(convert(records, fieldOrders, metadata, batchLimits));
    }

    /**
     * 批量插入（默认复用通用批处理实现，子类可覆盖以做方言优化）。
     */
    @Override
    protected void batchInsert(List<Object[]> rows, String sql, int[] ignored) {
        StarrocksJdbcSupport.writeDifferenceInsert(dataSource, sql, rows, insertColumns,
                runtimeOptions.queryTimeoutSeconds(), batchLimits, metadata, schemaName, tableName);
    }

    /**
     * 批量更新（默认复用通用批处理实现，子类可覆盖）。
     */
    @Override
    protected void batchUpdate(List<Object[]> rows, String sql, int[] ignored) {
        StarrocksJdbcSupport.writeMutation(dataSource, sql, rows, updateColumns, runtimeOptions.queryTimeoutSeconds(),
                batchLimits, metadata, schemaName, tableName, false);
    }

    /**
     * 批量删除（默认复用通用批处理实现，子类可覆盖）。
     */
    @Override
    protected void batchDelete(List<Object[]> rows, String sql, int[] ignored) {
        StarrocksJdbcSupport.writeMutation(dataSource, sql, rows, deleteColumns, runtimeOptions.queryTimeoutSeconds(),
                batchLimits, metadata, schemaName, tableName, true);
    }
}
