package cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.*;
import cn.iocoder.yudao.framework.dbswitch.product.starrocks.StarrocksNativeTables;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.NonTransactionalWriteExecution;
import org.springframework.jdbc.support.JdbcUtils;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

import static cn.iocoder.yudao.framework.dbswitch.product.starrocks.StarrocksJdbcSupport.*;

/** 在同一连接上，原生目录捕获先于流式读取，包括连接池大小为一的读取器。 */
public class StarrocksDatabaseOperator extends MysqlCompatibleOlapDatabaseOperator {

    /**
     * 初始化 {@code StarrocksDatabaseOperator}及其父类状态。
     */
    public StarrocksDatabaseOperator(DataSource source) {
        super(source);
    }

    /**
     * 返回当前实现对应的数据库产品类型。
     */
    @Override
    public ProductTypeEnum getDatabaseType() {
        return ProductTypeEnum.STARROCKS;
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
        return StarrocksNativeTables.describe(dataSource, schema, table, runtimeOptions.queryTimeoutSeconds());
    }

    /**
     * 为当前产品准备原生目标对象及写入计划。
     */
    @Override
    public NativeTargetPlan prepareNativeTarget(NativeTableDefinition definition, String schema, String table,
            Map<String, String> mapping, boolean replace) {
        return StarrocksNativeTables.plan(dataSource, definition, schema, table, mapping, replace,
                runtimeOptions.queryTimeoutSeconds());
    }

    /**
     * 返回原生搬运路径使用的列定义。
     */
    @Override
    public List<ColumnDescription> nativeMigrationColumns(String schema, String table) {
        try (var c = dataSource.getConnection()) {
            return metadata(c, schema, table, runtimeOptions.queryTimeoutSeconds()).columns().stream()
                    .map(Column::description).toList();
        } catch (Exception failure) {
            throw new IllegalStateException("StarRocks native migration metadata failed");
        }
    }

    /** 固定绑定、无快照的精确键键集游标；交付边界见 StarrocksCursorReader。 */
    @Override
    public CursorReadSession openCursor(CursorReadRequest request) {
        return StarrocksCursorReader.open(dataSource, request, runtimeOptions, fetchSize);
    }

    /**
     * 生成查询指定字段的select查询SQL语句。
     */
    @Override
    public String getSelectTableSql(String schema, String table, List<String> fields) {
        return "SELECT " + columns(fields) + " FROM " + table(schema, table);
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
     * 在同一连接上读取 StarRocks 原生列结构并打开数据结果集。
     */
    private StatementResultSet read(String schema, String table, List<String> fields, List<String> order) {
        var result = new NativeResult();
        try {
            result.setConnection(dataSource.getConnection());
            var nativeTable = metadata(result.getConnection(), schema, table, runtimeOptions.queryTimeoutSeconds());
            String sql = getSelectTableSql(schema, table, fields) + (order.isEmpty() ? "" : " ORDER BY " + columns(
                    order));
            open(result, sql, fields.stream().map(nativeTable::column).toList());
            return result;
        } catch (Exception failure) {
            result.close();
            throw new IllegalStateException("StarRocks native streaming read failed");
        }
    }

    /**
     * 按指定模式、表及查询范围读取表数据。
     */
    @Override
    public StatementResultSet queryTableData(String sql, List<String> fields) {
        return projected(sql, fields, List.of());
    }

    /**
     * 按指定模式、表及查询范围读取表数据。
     */
    @Override
    public StatementResultSet queryTableData(String sql, List<String> fields, List<String> order) {
        return projected(sql, fields, order);
    }

    /**
     * 按指定模式、表及查询范围读取表数据。
     */
    @Override
    public StatementResultSet queryTableData(String sql) {
        var fields = new ArrayList<String>();
        try (var c = dataSource.getConnection(); var s = c.createStatement()) {
            s.setQueryTimeout(runtimeOptions.queryTimeoutSeconds());
            try (var r = s.executeQuery("SELECT * FROM (" + sql + ") dbswitch_shape LIMIT 0")) {
                var m = r.getMetaData();
                for (int i = 1; i <= m.getColumnCount(); i++) {
                    fields.add(m.getColumnLabel(i));
                }
            }
        } catch (Exception failure) {
            throw new IllegalStateException("StarRocks SQL projection capture failed");
        }
        return projected(sql, fields, List.of());
    }

    /**
     * 返回当前投影。
     */
    private StatementResultSet projected(String sql, List<String> fields, List<String> order) {
        var result = new NativeResult();
        try {
            result.setConnection(dataSource.getConnection());
            columns(fields);
            var typed = new ArrayList<Column>();
            String expressions = String.join(",", fields.stream()
                    .map(f -> "typeof((SELECT " + quote(f) + " FROM (" + sql + ") dbswitch_types LIMIT 1))").toList());
            try (var s = result.getConnection().createStatement()) {
                s.setQueryTimeout(runtimeOptions.queryTimeoutSeconds());
                try (var r = s.executeQuery("SELECT " + expressions)) {
                    if (!r.next()) {
                        throw new SQLException("StarRocks expression type evidence absent");
                    }
                    for (int i = 0; i < fields.size(); i++) {
                        String type = r.getString(i + 1).replace(" ", "").toLowerCase(Locale.ROOT);
                        type = type.replaceFirst("^decimal(?:32|64|128)", "decimal");
                        String kind = type.contains("(") ? type.substring(0, type.indexOf('(')) : type;
                        if (type.equals("varchar")) {
                            type = "varchar(1048576)";
                        } else if (type.equals("char")) {
                            type = "char(255)";
                        }
                        int precision = 0, scale = 0;
                        var matcher = java.util.regex.Pattern.compile("decimal\\(([0-9]+),([0-9]+)\\)").matcher(type);
                        if (matcher.matches()) {
                            precision = Integer.parseInt(matcher.group(1));
                            scale = Integer.parseInt(matcher.group(2));
                        }
                        typed.add(
                                new Column(fields.get(i), nativeSql(type), jdbcType(kind), precision, scale, true, ""));
                    }
                }
            }
            open(result, "SELECT " + columns(
                    fields) + " FROM (" + sql + ") dbswitch_rows" + (order.isEmpty() ? "" : " ORDER BY " + columns(
                    order)), typed);
            return result;
        } catch (Exception failure) {
            result.close();
            throw new IllegalStateException("StarRocks native SQL streaming read failed");
        }
    }

    /**
     * 打开空结果并准备后续访问。
     */
    private void open(NativeResult result, String sql, List<Column> columns) throws SQLException {
        if (!result.getConnection().getAutoCommit()) {
            throw new SQLException("StarRocks reader requires idle owned connection");
        }
        result.setStatement(result.getConnection().createStatement());
        result.getStatement().setQueryTimeout(runtimeOptions.queryTimeoutSeconds());
        result.getStatement().setFetchSize(Integer.MIN_VALUE);
        var rows = result.getStatement().executeQuery(sql);
        result.setResultset(rows);
        result.setResultset(typedRows(rows, columns));
    }

    /**
     * 清除指定表的所有数据。
     */
    @Override
    public void truncateTableData(String schema, String table) {
        ddl("TRUNCATE TABLE " + table(schema, table));
    }

    /**
     * 删除指定物理表。
     */
    @Override
    public void dropTable(String schema, String table) {
        ddl("DROP TABLE " + table(schema, table));
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

    private static final class NativeResult extends StatementResultSet {

        private boolean closed;

        /**
         * 关闭 {@code StarrocksDatabaseOperator} 当前持有的资源。
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
