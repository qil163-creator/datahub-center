package cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.util.JdbcValueConversions;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.AbstractDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.NonTransactionalWriteExecution;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.PrepareStatementResultSet;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.StatementResultSet;
import cn.iocoder.yudao.framework.dbswitch.product.hive.HiveJdbcWriteSupport;
import org.springframework.jdbc.support.JdbcUtils;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Hive 会话读取和语句自有事务；不通过 JDBC setAutoCommit(false) 模拟。 */
public class HiveDatabaseOperator extends AbstractDatabaseOperator {

    /**
     * 初始化 {@code HiveDatabaseOperator}及其父类状态。
     */
    public HiveDatabaseOperator(DataSource source) {
        super(source);
    }

    /**
     * 返回当前实现对应的数据库产品类型。
     */
    @Override
    public ProductTypeEnum getDatabaseType() {
        return ProductTypeEnum.HIVE;
    }

    /**
     * 设置读取(fetch)数据的批次大小。
     */
    @Override
    public void setFetchSize(int size) {
        if (size < 1) {
            throw new IllegalArgumentException("Hive fetch size must be positive");
        }
        super.setFetchSize(size);
    }

    /**
     * 生成查询指定字段的select查询SQL语句。
     */
    @Override
    public String getSelectTableSql(String schema, String table, List<String> fields) {
        return "SELECT " + HiveJdbcWriteSupport.columns(fields) + " FROM " + HiveJdbcWriteSupport.table(schema, table);
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
                getSelectTableSql(schema, table, fields) + " ORDER BY " + HiveJdbcWriteSupport.columns(orders));
    }

    /**
     * 按指定模式、表及查询范围读取表数据。
     */
    @Override
    public StatementResultSet queryTableData(String sql, List<String> fields) {
        return queryTableData("SELECT " + HiveJdbcWriteSupport.columns(fields) + " FROM (" + sql + ") dbswitch_source");
    }

    /**
     * 按指定模式、表及查询范围读取表数据。
     */
    @Override
    public StatementResultSet queryTableData(String sql, List<String> fields, List<String> orders) {
        return queryTableData(
                "SELECT " + HiveJdbcWriteSupport.columns(fields) + " FROM (" + sql + ") dbswitch_source ORDER BY "
                        + HiveJdbcWriteSupport.columns(orders));
    }

    /**
     * 按指定模式、表及查询范围读取表数据。
     */
    @Override
    public StatementResultSet queryTableData(String sql) {
        var result = new NativeStatementResult();
        try {
            result.setConnection(dataSource.getConnection());
            HiveJdbcWriteSupport.session(result.getConnection(), runtimeOptions.queryTimeoutSeconds());
            result.setStatement(result.getConnection().createStatement());
            result.getStatement().setQueryTimeout(runtimeOptions.queryTimeoutSeconds());
            result.getStatement().setFetchSize(fetchSize);
            result.setResultset(result.getStatement().executeQuery(sql));
            return result;
        } catch (Exception failure) {
            result.close();
            throw new IllegalStateException("Hive table read failed");
        }
    }

    /**
     * 清除指定表的所有数据。
     */
    @Override
    public void truncateTableData(String schema, String table) {
        ddl("TRUNCATE TABLE " + HiveJdbcWriteSupport.table(schema, table));
    }

    /**
     * 删除指定物理表。
     */
    @Override
    public void dropTable(String schema, String table) {
        ddl("DROP TABLE " + HiveJdbcWriteSupport.table(schema, table));
    }

    /**
     * 根据当前数据库对象定义生成 DDL 语句。
     */
    private void ddl(String sql) {
        NonTransactionalWriteExecution.execute(dataSource, (connection, attempt) -> {
            HiveJdbcWriteSupport.session(connection, runtimeOptions.queryTimeoutSeconds());
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
        return range("SELECT " + HiveJdbcWriteSupport.columns(fields) + " FROM (" + sql + ") dbswitch_source", column,
                start, end);
    }

    /**
     * 返回当前范围。
     */
    private PrepareStatementResultSet range(String base, ColumnDescription column, String start, String end) {
        if (column == null) {
            throw new IllegalArgumentException("Hive requires an explicit range column");
        }
        var parameters = new ArrayList<Object>();
        StringBuilder sql = new StringBuilder(base).append(" WHERE 1=1");
        if (start != null) {
            sql.append(" AND ").append(HiveJdbcWriteSupport.quote(column.getFieldName())).append(">?");
            parameters.add(JdbcValueConversions.forJdbcType(start, column.getFieldType()));
        }
        if (end != null) {
            sql.append(" AND ").append(HiveJdbcWriteSupport.quote(column.getFieldName())).append("<=?");
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
        try (var result = prepared("SELECT 1 FROM " + HiveJdbcWriteSupport.table(schema, table) + " WHERE "
                + HiveJdbcWriteSupport.keys(names) + " LIMIT 1", names.stream().map(keys::get).toList())) {
            return result.getResultset().next();
        }
    }

    /** Hive 4.1 自行实现目录标识键集游标；不借用其他产品的快照读取器。 */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadSession openCursor(
            cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest request) {
        return HiveCursorReader.open(dataSource, request, runtimeOptions, fetchSize);
    }

    /**
     * 返回当前已准备。
     */
    private PrepareStatementResultSet prepared(String sql, List<Object> values) {
        var result = new NativePreparedResult();
        try {
            result.setConnection(dataSource.getConnection());
            HiveJdbcWriteSupport.session(result.getConnection(), runtimeOptions.queryTimeoutSeconds());
            result.setStatement(result.getConnection().prepareStatement(sql));
            result.getStatement().setQueryTimeout(runtimeOptions.queryTimeoutSeconds());
            result.getStatement().setFetchSize(fetchSize);
            for (int index = 0; index < values.size(); index++) {
                result.getStatement().setObject(index + 1, values.get(index));
            }
            result.setResultset(result.getStatement().executeQuery());
            return result;
        } catch (Exception failure) {
            result.close();
            throw new IllegalStateException("Hive parameterized read failed");
        }
    }

    private static final class NativeStatementResult extends StatementResultSet {

        private boolean closed;

        /**
         * 关闭 {@code HiveDatabaseOperator} 当前持有的资源。
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
         * 关闭 {@code HiveDatabaseOperator} 当前持有的资源。
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
