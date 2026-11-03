package cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.AbstractDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.*;
import cn.iocoder.yudao.framework.dbswitch.product.influxdb3.*;

import javax.sql.DataSource;
import java.util.*;
import java.util.stream.Collectors;

/** Flight 源读取和显式原生结构规划共享受控运行时。 */
public class InfluxdbDatabaseOperator extends AbstractDatabaseOperator {

    /**
     * 初始化 {@code InfluxdbDatabaseOperator}及其父类状态。
     */
    public InfluxdbDatabaseOperator(DataSource source) {
        super(source);
    }

    /**
     * 返回当前实现对应的数据库产品类型。
     */
    @Override
    public ProductTypeEnum getDatabaseType() {
        return ProductTypeEnum.INFLUXDB3;
    }

    /**
     * 返回字段集合。
     */
    private static String fields(List<String> fields) {
        if (fields == null || fields.isEmpty() || new HashSet<>(fields).size() != fields.size()) {
            throw new IllegalArgumentException("Influx requires distinct projected fields");
        }
        return fields.stream().map(InfluxNativeSession::quote).collect(Collectors.joining(","));
    }

    /**
     * 返回顺序。
     */
    private static String order(List<String> orders) {
        return orders == null || orders.isEmpty() ? "" : " ORDER BY " + fields(orders);
    }

    /**
     * 生成查询指定字段的select查询SQL语句。
     */
    @Override
    public String getSelectTableSql(String schema, String table, List<String> columns) {
        InfluxNativeCatalog.schema(schema);
        return "SELECT " + fields(columns) + " FROM \"iox\"." + InfluxNativeSession.quote(table);
    }

    /**
     * 按指定模式、表及查询范围读取表数据。
     */
    @Override
    public StatementResultSet queryTableData(String sql, List<String> columns, List<String> orders) {
        return selectTableData("SELECT " + fields(columns) + " FROM (" + sql + ") t" + order(orders), fetchSize);
    }

    /**
     * 按指定模式、表及查询范围读取表数据。
     */
    @Override
    public StatementResultSet queryTableData(String sql, List<String> columns) {
        return queryTableData(sql, columns, List.of());
    }

    /**
     * 获取指定schema下表的按主键有序的结果集。
     */
    @Override
    public StatementResultSet queryTableData(String schema, String table, List<String> columns, List<String> orders) {
        return selectTableData(getSelectTableSql(schema, table, columns) + order(orders), fetchSize);
    }

    /**
     * 获取指定schema下表的结果集。
     */
    @Override
    public StatementResultSet queryTableData(String schema, String table, List<String> columns) {
        return queryTableData(schema, table, columns, List.of());
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
        return InfluxNativeTables.describe(dataSource, schema, table, runtimeOptions.maxCacheBytes(),
                runtimeOptions.queryTimeoutSeconds());
    }

    /** 固定绑定、无快照的纳秒时间/标签键集游标；交付边界见 InfluxCursorReader。 */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadSession openCursor(
            cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest request) {
        return InfluxCursorReader.open(dataSource, request, runtimeOptions, fetchSize);
    }

    /**
     * 为当前产品准备原生目标对象及写入计划。
     */
    @Override
    public NativeTargetPlan prepareNativeTarget(NativeTableDefinition source, String schema, String table,
            Map<String, String> mapping, boolean replace) {
        return InfluxNativeTables.plan(dataSource, source, schema, table, mapping, replace,
                runtimeOptions.maxCacheBytes(), runtimeOptions.queryTimeoutSeconds());
    }

    /**
     * 校验目标原生定义并生成可复核的结构演进计划。
     */
    @Override
    public NativeTargetPlan prepareNativeSchemaEvolution(NativeTableDefinition desired, String schema, String table) {
        return InfluxNativeEvolution.plan(dataSource, desired, schema, table, runtimeOptions.maxCacheBytes(),
                runtimeOptions.queryTimeoutSeconds());
    }

    /**
     * 清除指定表的所有数据。
     */
    @Override
    public void truncateTableData(String schema, String table) {
        throw new UnsupportedOperationException(
                "Influx Core 3.11 requires explicit native replacement; data_only is not supported by this server version");
    }

    /**
     * 删除指定物理表。
     */
    @Override
    public void dropTable(String schema, String table) {
        throw new UnsupportedOperationException("Influx table deletion requires an explicit native structure plan");
    }
}
