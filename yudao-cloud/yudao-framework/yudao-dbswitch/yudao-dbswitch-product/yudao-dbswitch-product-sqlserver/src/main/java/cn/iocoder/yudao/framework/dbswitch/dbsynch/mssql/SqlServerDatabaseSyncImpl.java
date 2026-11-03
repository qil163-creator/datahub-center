package cn.iocoder.yudao.framework.dbswitch.dbsynch.mssql;

import cn.iocoder.yudao.framework.dbswitch.dbsynch.AbstractDatabaseSynchronize;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.IDatabaseSynchronize;
import cn.iocoder.yudao.framework.dbswitch.product.sqlserver.SqlServerWriteColumns;
import org.apache.commons.lang3.StringUtils;

import javax.sql.DataSource;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * SQLServer数据库DML同步实现类
 *
 * @author jrl
 */
public class SqlServerDatabaseSyncImpl extends AbstractDatabaseSynchronize implements
        IDatabaseSynchronize {

    private List<SqlServerWriteColumns.Column> insertColumns, updateColumns, deleteColumns;

    /**
     * 初始化 {@code SqlServerDatabaseSyncImpl}及其父类状态。
     */
    public SqlServerDatabaseSyncImpl(DataSource ds) {
        super(ds);
    }

    /** SQL Server 16 在第一条差异语句前拒绝服务器生成列和 sql_variant 目标。 */
    @Override
    public void prepare(String schemaName, String tableName, List<String> fieldNames, List<String> pks) {
        var columns = SqlServerWriteColumns.read(dataSource, schemaName, tableName,
                runtimeOptions.queryTimeoutSeconds());
        if (columns != null) {
            columns.requireWritable(fieldNames);
        }
        super.prepare(schemaName, tableName, fieldNames, pks);
        if (columns == null) {
            insertColumns = updateColumns = deleteColumns = null;
            return;
        }
        var updateOrder = new java.util.ArrayList<String>(
                fieldNames.stream().filter(field -> !pks.contains(field)).toList());
        updateOrder.addAll(pks);
        insertColumns = columns.columns(fieldNames);
        updateColumns = columns.columns(updateOrder);
        deleteColumns = columns.columns(pks);
    }

    /**
     * 批量插入（默认复用通用批处理实现，子类可覆盖以做方言优化）。
     */
    @Override
    protected void batchInsert(List<Object[]> rows, String sql, int[] types) {
        exact(rows, sql, types, insertColumns);
    }

    /**
     * 批量更新（默认复用通用批处理实现，子类可覆盖）。
     */
    @Override
    protected void batchUpdate(List<Object[]> rows, String sql, int[] types) {
        exact(rows, sql, types, updateColumns);
    }

    /**
     * 批量删除（默认复用通用批处理实现，子类可覆盖）。
     */
    @Override
    protected void batchDelete(List<Object[]> rows, String sql, int[] types) {
        exact(rows, sql, types, deleteColumns);
    }

    /** 借用连接前，在调用线程上强制验证 SQL Server 16 的精确值域。 */
    private void exact(List<Object[]> rows, String sql, int[] types, List<SqlServerWriteColumns.Column> order) {
        if (order == null) {
            super.batchOperator(
                    cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.SqlServerTimeValues.bind(rows, types),
                    sql, types);
            return;
        }
        super.batchOperator(SqlServerWriteColumns.exactRows(rows, order, false), sql,
                SqlServerWriteColumns.bindingTypes(types, order));
    }

    /**
     * 获取查询列元信息的SQL语句。
     */
    @Override
    public String getColumnMetaDataSql(String schemaName, String tableName) {
        return "SELECT * FROM " + table(schemaName, tableName) + " WHERE 1=2";
    }

    /**
     * 生成使用参数占位符的 INSERT 语句。
     */
    @Override
    public String getInsertPrepareStatementSql(String schemaName, String tableName,
            List<String> fieldNames) {
        List<String> placeHolders = Collections.nCopies(fieldNames.size(), "?");
        return "INSERT INTO " + table(schemaName, tableName) + " (" + fieldNames.stream()
                .map(SqlServerDatabaseSyncImpl::quote)
                .collect(Collectors.joining(", ")) + ") VALUES (" + String.join(",", placeHolders) + ")";
    }

    /**
     * 生成Update操作的SQL语句。
     */
    @Override
    public String getUpdatePrepareStatementSql(String schemaName, String tableName,
            List<String> fieldNames, List<String> pks) {
        List<String> uf = fieldNames.stream()
                .filter(field -> !pks.contains(field))
                .map(field -> quote(field) + "=?")
                .collect(Collectors.toList());

        if (pks.isEmpty()) {
            throw new IllegalArgumentException("Difference updates require explicit keys");
        }
        if (uf.isEmpty()) {
            uf.add(quote(pks.getFirst()) + "=" + quote(pks.getFirst()));
        }
        List<String> uw = pks.stream()
                .map(pk -> quote(pk) + "=?")
                .collect(Collectors.toList());

        return "UPDATE " + table(schemaName, tableName) + " SET " + String.join(", ", uf)
                + " WHERE " + String.join(" AND ", uw);
    }

    /**
     * 生成Delete操作的SQL语句。
     */
    @Override
    public String getDeletePrepareStatementSql(String schemaName, String tableName,
            List<String> pks) {
        if (pks.isEmpty()) {
            throw new IllegalArgumentException("Difference deletes require explicit keys");
        }
        List<String> uw = pks.stream()
                .map(pk -> quote(pk) + "=?")
                .collect(Collectors.toList());

        return "DELETE FROM " + table(schemaName, tableName) + " WHERE " + String.join(" AND ", uw);
    }

    /**
     * 按SQL Server方言转义并引用标识符。
     */
    private static String quote(String name) {
        return cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard.quotePhysicalIdentifier(
                cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.SQLSERVER, name);
    }

    /**
     * 返回表。
     */
    private static String table(String schema, String table) {
        return quote(schema) + "." + quote(table);
    }
}
