package cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.util.JdbcValueConversions;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.AbstractDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.NonTransactionalWriteExecution;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.StatementResultSet;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.PrepareStatementResultSet;
import cn.iocoder.yudao.framework.dbswitch.product.tdengine.TdengineJdbcSupport;
import cn.iocoder.yudao.framework.dbswitch.product.tdengine.TdengineNativeTables;
import org.springframework.jdbc.support.JdbcUtils;

import javax.sql.DataSource;
import java.util.*;
import java.sql.SQLException;

/** TDengine 管理请求提交；读取器既不启动也不恢复 JDBC 事务。 */
public class TdengineDatabaseOperator extends AbstractDatabaseOperator {

    /**
     * 初始化 {@code TdengineDatabaseOperator}及其父类状态。
     */
    public TdengineDatabaseOperator(DataSource source) {
        super(source);
    }

    /**
     * 返回当前实现对应的数据库产品类型。
     */
    @Override
    public ProductTypeEnum getDatabaseType() {
        return ProductTypeEnum.TDENGINE;
    }

    /**
     * 只读展开所选原生关系族，形成互不重复的物理迁移单元。 产品必须保留显式选择，将每个返回单元绑定到其 schema，限制目录 读取范围并拒绝有歧义的覆盖。此钩子不授予访问权限，也不执行目标 DDL。 迁移协调器在调度前重新应用排除规则并校验全部结果。
     */
    @Override
    public List<cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription> planMigrationSources(
            List<cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription> selected) {
        return TdengineNativeTables.expand(dataSource, selected, runtimeOptions.queryTimeoutSeconds());
    }

    /** 固定绑定、无快照的时间戳键集游标；交付边界见 TdengineCursorReader。 */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadSession openCursor(
            cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest request) {
        return TdengineCursorReader.open(dataSource, request, runtimeOptions, fetchSize);
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
    public cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTableDefinition describeNativeTable(String schema,
            String table) {
        return TdengineNativeTables.describe(dataSource, schema, table, runtimeOptions.queryTimeoutSeconds());
    }

    /**
     * 为当前产品准备原生目标对象及写入计划。
     */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTargetPlan prepareNativeTarget(
            cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTableDefinition definition,
            String schema, String table, Map<String, String> mapping, boolean replace) {
        return TdengineNativeTables.plan(dataSource, definition, schema, table, mapping, replace,
                runtimeOptions.queryTimeoutSeconds());
    }

    /**
     * 返回原生搬运路径使用的列定义。
     */
    @Override
    public List<ColumnDescription> nativeMigrationColumns(String schema, String table) {
        try (var connection = dataSource.getConnection()) {
            var nativeTable = TdengineJdbcSupport.metadata(connection, schema, table,
                    runtimeOptions.queryTimeoutSeconds());
            var columns = new ArrayList<ColumnDescription>();
            for (var nativeColumn : nativeTable.columns()) {
                var column = new ColumnDescription();
                column.setFieldName(nativeColumn.name());
                column.setLabelName(nativeColumn.name());
                column.setDbType(ProductTypeEnum.TDENGINE);
                column.setFieldType(nativeColumn.jdbcType());
                column.setFieldTypeName(nativeColumn.nativeType());
                column.setPrecisionSize(nativeColumn.precision());
                column.setScaleSize(nativeColumn.scale());
                column.setDisplaySize(nativeColumn.precision());
                column.setSigned(!nativeColumn.nativeType().contains("UNSIGNED"));
                column.setNullable(!nativeTable.keys().contains(nativeColumn.name()));
                columns.add(column);
            }
            return List.copyOf(columns);
        } catch (Exception failure) {
            throw new IllegalStateException("TDengine native migration column metadata failed");
        }
    }

    /**
     * 生成查询指定字段的select查询SQL语句。
     */
    @Override
    public String getSelectTableSql(String schema, String table, List<String> fields) {
        return "SELECT " + TdengineJdbcSupport.columns(fields) + " FROM " + TdengineJdbcSupport.table(schema, table);
    }

    /**
     * 获取指定schema下表的结果集。
     */
    @Override
    public StatementResultSet queryTableData(String schema, String table, List<String> fields) {
        return queryTableData(getSelectTableSql(schema, table, fields));
    }

    /**
     * 获取指定schema下表的按主键有序的结果集。
     */
    @Override
    public StatementResultSet queryTableData(String schema, String table, List<String> fields, List<String> orders) {
        return queryTableData(
                getSelectTableSql(schema, table, fields) + " ORDER BY " + TdengineJdbcSupport.columns(orders));
    }

    /**
     * 按指定模式、表及查询范围读取表数据。
     */
    @Override
    public StatementResultSet queryTableData(String sql, List<String> fields) {
        return queryTableData(
                "SELECT " + TdengineJdbcSupport.columns(fields) + " FROM (" + sql + ") AS dbswitch_source");
    }

    /**
     * 按指定模式、表及查询范围读取表数据。
     */
    @Override
    public StatementResultSet queryTableData(String sql, List<String> fields, List<String> orders) {
        return queryTableData("SELECT " + TdengineJdbcSupport.columns(fields) + " FROM (" + sql
                + ") AS dbswitch_source ORDER BY " + TdengineJdbcSupport.columns(orders));
    }

    /**
     * 按指定模式、表及查询范围读取表数据。
     */
    @Override
    public StatementResultSet queryTableData(String sql) {
        var result = new NativeResult();
        try {
            result.setConnection(dataSource.getConnection());
            TdengineJdbcSupport.requireValueDriver(result.getConnection());
            result.setStatement(result.getConnection().createStatement());
            result.getStatement().setQueryTimeout(runtimeOptions.queryTimeoutSeconds());
            result.getStatement().setFetchSize(fetchSize);
            result.setResultset(result.getStatement().executeQuery(sql));
            return result;
        } catch (Exception failure) {
            result.close();
            throw new IllegalStateException("TDengine WebSocket read failed before returning data");
        }
    }

    /**
     * 清除指定表的所有数据。
     */
    @Override
    public void truncateTableData(String schema, String table) {
        executeNative("DELETE FROM " + TdengineJdbcSupport.table(schema, table));
    }

    /**
     * 删除指定物理表。
     */
    @Override
    public void dropTable(String schema, String table) {
        executeNative("DROP TABLE IF EXISTS " + TdengineJdbcSupport.table(schema, table));
    }

    /**
     * 通过当前产品的原生写入路径应用数据库操作。
     */
    private void executeNative(String sql) {
        NonTransactionalWriteExecution.execute(dataSource, (connection, attempt) -> {
            TdengineJdbcSupport.requireValueDriver(connection);
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(runtimeOptions.queryTimeoutSeconds());
                attempt.sending();
                statement.execute(sql);
                return 0;
            }
        });
    }

    /**
     * 按增量边界读取表中的新增或变更数据。
     */
    @Override
    public PrepareStatementResultSet queryIncreaseTableData(String schema, String table, List<String> fields,
            ColumnDescription column, String start, String end) {
        return range(getSelectTableSql(schema, table, fields), column, start, end);
    }

    /**
     * 按增量边界读取表中的新增或变更数据。
     */
    @Override
    public PrepareStatementResultSet queryIncreaseTableData(String sql, List<String> fields,
            ColumnDescription column, String start, String end) {
        return range("SELECT " + TdengineJdbcSupport.columns(fields) + " FROM (" + sql + ") AS dbswitch_source", column,
                start, end);
    }

    /**
     * 返回当前范围。
     */
    private PrepareStatementResultSet range(String base, ColumnDescription column, String start, String end) {
        if (column == null) {
            throw new IllegalArgumentException("A typed range column is required");
        }
        var parameters = new ArrayList<Object>();
        var sql = new StringBuilder(base).append(" WHERE 1=1");
        if (start != null) {
            sql.append(" AND ").append(TdengineJdbcSupport.quote(column.getFieldName())).append(">?");
            parameters.add(JdbcValueConversions.forJdbcType(start, column.getFieldType()));
        }
        if (end != null) {
            sql.append(" AND ").append(TdengineJdbcSupport.quote(column.getFieldName())).append("<=?");
            parameters.add(JdbcValueConversions.forJdbcType(end, column.getFieldType()));
        }
        return prepared(sql.toString(), parameters);
    }

    /**
     * 返回当前字段是否属于主键。
     */
    @Override
    public Boolean getPkExist(String schema, String table, Map<String, Object> keys) throws SQLException {
        if (keys == null || keys.isEmpty()) {
            return false;
        }
        var names = new ArrayList<>(keys.keySet());
        String where = names.stream().map(name -> TdengineJdbcSupport.quote(name) + "=?")
                .collect(java.util.stream.Collectors.joining(" AND "));
        try (var result = prepared(
                "SELECT 1 FROM " + TdengineJdbcSupport.table(schema, table) + " WHERE " + where + " LIMIT 1",
                names.stream().map(keys::get).toList())) {
            return result.getResultset().next();
        }
    }

    /**
     * 返回当前已准备。
     */
    private PrepareStatementResultSet prepared(String sql, List<Object> parameters) {
        var result = new NativePreparedResult();
        try {
            result.setConnection(dataSource.getConnection());
            TdengineJdbcSupport.requireValueDriver(result.getConnection());
            result.setStatement(result.getConnection().prepareStatement(sql));
            result.getStatement().setQueryTimeout(runtimeOptions.queryTimeoutSeconds());
            result.getStatement().setFetchSize(fetchSize);
            for (int index = 0; index < parameters.size(); index++) {
                Object value = parameters.get(index);
                if (value instanceof java.time.LocalDateTime time) {
                    value = java.sql.Timestamp.valueOf(time);
                }
                result.getStatement().setObject(index + 1, value);
            }
            result.setResultset(result.getStatement().executeQuery());
            return result;
        } catch (Exception failure) {
            result.close();
            throw new IllegalStateException("TDengine parameterized WebSocket read failed");
        }
    }

    private static final class NativeResult extends StatementResultSet {

        private boolean closed;

        /**
         * 关闭 {@code TdengineDatabaseOperator} 当前持有的资源。
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

    private static final class NativePreparedResult extends PrepareStatementResultSet {

        private boolean closed;

        /**
         * 关闭 {@code TdengineDatabaseOperator} 当前持有的资源。
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

