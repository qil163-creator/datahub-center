package cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.product.greenplum.GreenplumNativeTables;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTableDefinition;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTargetPlan;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.StatementResultSet;

import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

/**
 * Greenplum数据库实现类
 *
 * @author jrl
 */
public class GreenplumDatabaseOperator extends PostgreSqlDatabaseOperator {

    /**
     * 初始化 {@code GreenplumDatabaseOperator}及其父类状态。
     */
    public GreenplumDatabaseOperator(DataSource dataSource) {
        super(dataSource);
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
        return GreenplumNativeTables.describe(getDataSource(), schema, table, runtimeOptions.queryTimeoutSeconds());
    }

    /**
     * 为当前产品准备原生目标对象及写入计划。
     */
    @Override
    public NativeTargetPlan prepareNativeTarget(NativeTableDefinition source, String schema, String table,
            Map<String, String> mapping, boolean replace) {
        return GreenplumNativeTables.plan(getDataSource(), source, schema, table, mapping, replace,
                runtimeOptions.queryTimeoutSeconds());
    }

    /**
     * 生成查询指定字段的select查询SQL语句。
     */
    @Override
    public String getSelectTableSql(String schema, String table, List<String> fields) {
        return "SELECT " + GreenplumNativeTables.fields(fields) + " FROM " + GreenplumNativeTables.table(schema, table);
    }

    /**
     * 获取指定schema下表的结果集。
     */
    @Override
    public StatementResultSet queryTableData(String schema, String table, List<String> fields) {
        return selectTableData(getSelectTableSql(schema, table, fields), fetchSize);
    }

    /**
     * 获取指定schema下表的按主键有序的结果集。
     */
    @Override
    public StatementResultSet queryTableData(String schema, String table, List<String> fields, List<String> orders) {
        return selectTableData(getSelectTableSql(schema, table, fields) + order(orders), fetchSize);
    }

    /**
     * 按指定模式、表及查询范围读取表数据。
     */
    @Override
    public StatementResultSet queryTableData(String sql, List<String> fields) {
        return selectTableData("SELECT " + GreenplumNativeTables.fields(fields) + " FROM (" + sql + ") t", fetchSize);
    }

    /**
     * 按指定模式、表及查询范围读取表数据。
     */
    @Override
    public StatementResultSet queryTableData(String sql, List<String> fields, List<String> orders) {
        return selectTableData(
                "SELECT " + GreenplumNativeTables.fields(fields) + " FROM (" + sql + ") t" + order(orders), fetchSize);
    }

    /**
     * 返回顺序。
     */
    private static String order(List<String> fields) {
        return fields == null || fields.isEmpty() ? "" : " ORDER BY " + GreenplumNativeTables.fields(fields);
    }

    /**
     * 清除指定表的所有数据。
     */
    @Override
    public void truncateTableData(String schema, String table) {
        executeSql("TRUNCATE TABLE " + GreenplumNativeTables.table(schema, table));
    }

    /**
     * 删除指定物理表。
     */
    @Override
    public void dropTable(String schema, String table) {
        executeSql("DROP TABLE " + GreenplumNativeTables.table(schema, table));
    }

    /**
     * 创建并打开按键推进的读取游标。
     */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadSession openCursor(
            cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest request) {
        return GreenplumCursorReader.open(dataSource, request, runtimeOptions, fetchSize);
    }

    /**
     * 返回当前实现对应的数据库产品类型。
     */
    @Override
    public ProductTypeEnum getDatabaseType() {
        return ProductTypeEnum.GREENPLUM;
    }
}
