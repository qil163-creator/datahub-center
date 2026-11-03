package cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.util.JdbcValueConversions;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseClickhouseImpl;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.AbstractDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.PrepareStatementResultSet;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.StatementResultSet;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.clickhouse.ClickhouseDatabaseSyncImpl;
import cn.iocoder.yudao.framework.dbswitch.product.clickhouse.ClickhouseSql;
import cn.iocoder.yudao.framework.dbswitch.product.clickhouse.ClickhouseReadValues;
import cn.iocoder.yudao.framework.dbswitch.product.clickhouse.ClickhouseWriteExecution;
import org.springframework.jdbc.support.JdbcUtils;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** ClickHouse HTTP/JDBC 读取绝不启动或恢复 MySQL 式事务。 */
public class ClickhouseDatabaseOperator extends AbstractDatabaseOperator {

    /**
     * 初始化 {@code ClickhouseDatabaseOperator}及其父类状态。
     */
    public ClickhouseDatabaseOperator(DataSource dataSource) {
        super(dataSource);
    }

    /**
     * 返回当前实现对应的数据库产品类型。
     */
    @Override
    public ProductTypeEnum getDatabaseType() {
        return ProductTypeEnum.CLICKHOUSE;
    }

    /**
     * 设置读取(fetch)数据的批次大小。
     */
    @Override
    public void setFetchSize(int size) {
        if (size < 1) {
            throw new IllegalArgumentException("ClickHouse fetch size must be positive");
        }
        super.setFetchSize(size);
    }

    /**
     * 生成查询指定字段的select查询SQL语句。
     */
    @Override
    public String getSelectTableSql(String schema, String table, List<String> fields) {
        return "SELECT " + ClickhouseSql.columns(fields) + " FROM " + ClickhouseSql.table(schema, table);
    }

    /**
     * 返回原生搬运路径使用的列定义。
     */
    @Override
    public List<ColumnDescription> nativeMigrationColumns(String schema, String table) {
        try (var connection = dataSource.getConnection()) {
            var columns = new DatabaseClickhouseImpl().queryTableColumnMeta(connection, schema, table);
            var text = columns.stream().filter(column -> ClickhouseReadValues.stringType(
                            column.getMetadataEvidence().nativeType().value()))
                    .map(ColumnDescription::getFieldName).toList();
            var floating = columns.stream().filter(column -> ClickhouseReadValues.floatingType(
                            column.getMetadataEvidence().nativeType().value()))
                    .map(ColumnDescription::getFieldName).toList();
            if (!text.isEmpty() || !floating.isEmpty()) {
                // 仅用于提前拒绝：扫描后的源变化还会由实际读取查询再次检查。
                try (var statement = connection.createStatement()) {
                    statement.setQueryTimeout(runtimeOptions.queryTimeoutSeconds());
                    try (var rows = statement.executeQuery("SELECT count() FROM " + ClickhouseSql.table(schema, table)
                            + " WHERE " + ClickhouseReadValues.valuePredicate(text, floating, ""))) {
                        if (!rows.next() || rows.next()) {
                            throw new SQLException("ClickHouse source text preflight is unavailable");
                        }
                    }
                }
            }
            return columns;
        } catch (Exception failure) {
            throw new IllegalStateException("ClickHouse native source columns or exact text preflight failed");
        }
    }

    /** 固定绑定、无快照的排序键键集游标；交付边界见 ClickhouseCursorReader。 */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadSession openCursor(
            cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest request) {
        return ClickhouseCursorReader.open(dataSource, request, runtimeOptions, fetchSize);
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
        String from = ClickhouseSql.table(schema, table);
        return read(getSelectTableSql(schema, table, fields) + " ORDER BY " + ClickhouseSql.columns(orders), from,
                orders);
    }

    /**
     * 按指定模式、表及查询范围读取表数据。
     */
    @Override
    public StatementResultSet queryTableData(String sql, List<String> fields) {
        return queryTableData("SELECT " + ClickhouseSql.columns(fields) + " FROM (" + sql + ") AS dbswitch_source");
    }

    /**
     * 按指定模式、表及查询范围读取表数据。
     */
    @Override
    public StatementResultSet queryTableData(String sql, List<String> fields, List<String> orders) {
        String from = "(" + sql + ") AS dbswitch_source";
        return read("SELECT " + ClickhouseSql.columns(fields) + " FROM " + from + " ORDER BY "
                + ClickhouseSql.columns(orders), from, orders);
    }

    /**
     * 按指定模式、表及查询范围读取表数据。
     */
    @Override
    public StatementResultSet queryTableData(String sql) {
        return read(sql, null, List.of());
    }

    /**
     * 建立连接并按当前查询范围读取 ClickHouse 数据及列结构。
     */
    private StatementResultSet read(String sql, String from, List<String> keys) {
        var result = new NativeStatementResult();
        try {
            result.setConnection(dataSource.getConnection());
            if (!keys.isEmpty()) {
                ClickhouseDatabaseSyncImpl.requireUniqueKeys(result.getConnection(), from, keys,
                        runtimeOptions.queryTimeoutSeconds());
            }
            String guarded;
            ClickhouseReadValues.Signature signature;
            try (var header = result.getConnection().createStatement()) {
                header.setQueryTimeout(runtimeOptions.queryTimeoutSeconds());
                try (var rows = header.executeQuery(ClickhouseReadValues.headerSql(sql))) {
                    signature = ClickhouseReadValues.describe(rows.getMetaData());
                    guarded = ClickhouseReadValues.guardedSql(sql, signature);
                }
            }
            result.setStatement(result.getConnection().createStatement());
            result.getStatement().setQueryTimeout(runtimeOptions.queryTimeoutSeconds());
            result.getStatement().setFetchSize(fetchSize);
            result.setResultset(result.getStatement().executeQuery(guarded));
            signature.verify(result.getResultset().getMetaData());
            result.setResultset(ClickhouseReadValues.exactUnsigned(result.getResultset()));
            return result;
        } catch (Exception failure) {
            result.close();
            throw new IllegalStateException("ClickHouse read or difference-key preflight failed");
        }
    }

    /**
     * 清除指定表的所有数据。
     */
    @Override
    public void truncateTableData(String schema, String table) {
        ddl("TRUNCATE TABLE " + ClickhouseSql.table(schema, table));
    }

    /**
     * 删除指定物理表。
     */
    @Override
    public void dropTable(String schema, String table) {
        ddl("DROP TABLE " + ClickhouseSql.table(schema, table) + " SYNC");
    }

    /**
     * 根据当前数据库对象定义生成 DDL 语句。
     */
    private void ddl(String sql) {
        ClickhouseWriteExecution.execute(dataSource, (connection, attempt) -> {
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
        return range("SELECT " + ClickhouseSql.columns(fields) + " FROM (" + sql + ") AS dbswitch_source", column,
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
        StringBuilder sql = new StringBuilder(base).append(" WHERE 1=1");
        String field = ClickhouseSql.quote(column.getFieldName());
        if (start != null) {
            sql.append(" AND ").append(field).append(">?");
            parameters.add(JdbcValueConversions.forJdbcType(start, column.getFieldType()));
        }
        if (end != null) {
            sql.append(" AND ").append(field).append("<=?");
            parameters.add(JdbcValueConversions.forJdbcType(end, column.getFieldType()));
        }
        return prepared(sql.toString(), parameters);
    }

    /**
     * 返回当前字段是否属于主键。
     */
    @Override
    public Boolean getPkExist(String schema, String table, Map<String, Object> key) throws SQLException {
        if (key == null || key.isEmpty()) {
            return false;
        }
        var fields = new ArrayList<>(key.keySet());
        try (var result = prepared("SELECT 1 FROM " + ClickhouseSql.table(schema, table) + " WHERE "
                + ClickhouseSql.keys(fields) + " LIMIT 1", fields.stream().map(key::get).toList())) {
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
            String guarded;
            ClickhouseReadValues.Signature signature;
            try (var header = result.getConnection().prepareStatement(ClickhouseReadValues.headerSql(sql))) {
                header.setQueryTimeout(runtimeOptions.queryTimeoutSeconds());
                ClickhouseWriteExecution.bind(header, parameters.toArray());
                try (var rows = header.executeQuery()) {
                    signature = ClickhouseReadValues.describe(rows.getMetaData());
                    guarded = ClickhouseReadValues.guardedSql(sql, signature);
                }
            }
            result.setStatement(result.getConnection().prepareStatement(guarded));
            result.getStatement().setQueryTimeout(runtimeOptions.queryTimeoutSeconds());
            result.getStatement().setFetchSize(fetchSize);
            ClickhouseWriteExecution.bind(result.getStatement(), parameters.toArray());
            result.setResultset(result.getStatement().executeQuery());
            signature.verify(result.getResultset().getMetaData());
            result.setResultset(ClickhouseReadValues.exactUnsigned(result.getResultset()));
            return result;
        } catch (Exception failure) {
            result.close();
            throw new IllegalStateException("ClickHouse parameterized read failed");
        }
    }

    private static final class NativeStatementResult extends StatementResultSet {

        private boolean closed;

        /**
         * 关闭 {@code ClickhouseDatabaseOperator} 当前持有的资源。
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
         * 关闭 {@code ClickhouseDatabaseOperator} 当前持有的资源。
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
