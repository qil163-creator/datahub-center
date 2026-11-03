package cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.product.doris.DorisNativeTables;
import cn.iocoder.yudao.framework.dbswitch.product.doris.DorisJdbcSupport;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.*;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.NonTransactionalWriteExecution;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;

import java.util.*;
import java.sql.*;

import org.springframework.jdbc.support.JdbcUtils;

import static cn.iocoder.yudao.framework.dbswitch.product.doris.DorisNativeSchema.*;

import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.IDatabaseOperator;

import javax.sql.DataSource;

/**
 * Doris数据库读取实现类。
 *
 * @author jrl
 */
public class DorisDatabaseOperator extends MysqlCompatibleOlapDatabaseOperator implements IDatabaseOperator {

    /**
     * 初始化 {@code DorisDatabaseOperator}及其父类状态。
     */
    public DorisDatabaseOperator(DataSource dataSource) {
        super(dataSource);
    }

    /**
     * 返回当前实现对应的数据库产品类型。
     */
    @Override
    public ProductTypeEnum getDatabaseType() {
        return ProductTypeEnum.DORIS;
    }

    /**
     * 原生索引及集合结构支持独立于关系型 SQL DDL。
     */
    @Override
    public boolean supportsNativeTableDefinition() {
        return true;
    }

    /**
     * 读取数据库原生表定义及其元数据证据。
     */
    @Override
    public NativeTableDefinition describeNativeTable(String schema, String table) {
        return DorisNativeTables.describe(dataSource, schema, table, runtimeOptions.queryTimeoutSeconds());
    }

    /**
     * 为当前产品准备原生目标对象及写入计划。
     */
    @Override
    public NativeTargetPlan prepareNativeTarget(NativeTableDefinition definition, String schema, String table,
            Map<String, String> fields, boolean replace) {
        return DorisNativeTables.plan(dataSource, definition, schema, table, fields, replace,
                runtimeOptions.queryTimeoutSeconds());
    }

    /**
     * 为当前产品准备原生目标对象及写入计划。
     */
    @Override
    public NativeTargetPlan prepareNativeTarget(NativeTableDefinition definition, String schema, String table,
            Map<String, String> fields, boolean replace,
            String comment, cn.iocoder.yudao.framework.dbswitch.core.model.DorisCreateTableOptions options) {
        return DorisNativeTables.plan(dataSource, definition, schema, table, fields, replace,
                runtimeOptions.queryTimeoutSeconds(), comment, options);
    }

    /**
     * 校验已有表配置选项。
     */
    @Override
    public void validateExistingTableOptions(String schema, String table, String comment,
            cn.iocoder.yudao.framework.dbswitch.core.model.DorisCreateTableOptions options) {
        try (var connection = dataSource.getConnection()) {
            var observed = DorisJdbcSupport.inspect(connection, schema, table, runtimeOptions.queryTimeoutSeconds())
                    .table();
            if (!observed.equals(
                    cn.iocoder.yudao.framework.dbswitch.product.doris.DorisTableLayouts.apply(observed, comment,
                            options))) {
                throw new IllegalArgumentException(
                        "Existing Doris target differs from the configured layout; explicit recreation is required");
            }
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException("Existing Doris layout could not be verified");
        }
    }

    /**
     * 返回原生搬运路径使用的列定义。
     */
    @Override
    public List<ColumnDescription> nativeMigrationColumns(String schema, String table) {
        try (var c = dataSource.getConnection()) {
            return DorisJdbcSupport.inspect(c, schema, table, runtimeOptions.queryTimeoutSeconds()).table().columns()
                    .stream().map(Column::description).toList();
        } catch (Exception failure) {
            throw new IllegalStateException("Doris migration metadata is unavailable");
        }
    }

    /** 固定绑定、无快照的精确键键集游标；交付边界见 DorisCursorReader。 */
    @Override
    public CursorReadSession openCursor(CursorReadRequest request) {
        return DorisCursorReader.open(dataSource, request, runtimeOptions, fetchSize);
    }

    /**
     * 生成查询指定字段的select查询SQL语句。
     */
    @Override
    public String getSelectTableSql(String schema, String table, List<String> fields) {
        return "SELECT " + names(fields) + " FROM " + relation(schema, table);
    }

    /**
     * 获取指定schema下表的结果集。
     */
    @Override
    public StatementResultSet queryTableData(String schema, String table, List<String> fields) {
        return read(schema, table, fields, List.of());
    }

    /**
     * 获取指定schema下表的按主键有序的结果集。
     */
    @Override
    public StatementResultSet queryTableData(String schema, String table, List<String> fields, List<String> order) {
        return read(schema, table, fields, order);
    }

    /**
     * 读取 Doris 原生列定义和数据行，并由返回结果持有读取资源。
     */
    private StatementResultSet read(String schema, String table, List<String> fields, List<String> order) {
        var result = new NativeRows();
        try {
            result.setConnection(dataSource.getConnection());
            var definition = DorisJdbcSupport.inspect(result.getConnection(), schema, table,
                    runtimeOptions.queryTimeoutSeconds());
            result.setStatement(result.getConnection().createStatement());
            result.getStatement().setQueryTimeout(runtimeOptions.queryTimeoutSeconds());
            result.getStatement().setFetchSize(Integer.MIN_VALUE);
            var rows = result.getStatement().executeQuery(
                    getSelectTableSql(schema, table, fields) + (order.isEmpty() ? "" : " ORDER BY " + names(order)));
            result.setResultset(rows);
            result.setResultset(
                    DorisJdbcSupport.typedRows(rows, fields.stream().map(definition.table()::column).toList()));
            return result;
        } catch (Exception failure) {
            result.close();
            throw new IllegalStateException("Doris native streaming read failed");
        }
    }

    /**
     * 清除指定表的所有数据。
     */
    @Override
    public void truncateTableData(String schema, String table) {
        ddl("TRUNCATE TABLE " + relation(schema, table));
    }

    /**
     * 删除指定物理表。
     */
    @Override
    public void dropTable(String schema, String table) {
        ddl("DROP TABLE " + relation(schema, table));
    }

    /**
     * 根据当前数据库对象定义生成 DDL 语句。
     */
    private void ddl(String sql) {
        NonTransactionalWriteExecution.execute(dataSource, (c, a) -> {
            try (var s = c.createStatement()) {
                s.setQueryTimeout(runtimeOptions.queryTimeoutSeconds());
                a.sending();
                s.execute(sql);
                return 0;
            }
        });
    }

    private static final class NativeRows extends StatementResultSet {

        private boolean closed;

        /**
         * 关闭 {@code DorisDatabaseOperator} 当前持有的资源。
         */
        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            JdbcUtils.closeResultSet(getResultset());
            JdbcUtils.closeStatement(getStatement());
            JdbcUtils.closeConnection(getConnection());
        }
    }
}
