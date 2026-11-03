package cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.util.DmSqlNames;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.JdbcTimeValues;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.StatementResultSet;

import java.util.List;

import javax.sql.DataSource;

/**
 * DM数据库实现类
 *
 * @author jrl
 */
public class DmDatabaseOperator extends OracleDatabaseOperator {

    /**
     * 初始化 {@code DmDatabaseOperator}及其父类状态。
     */
    public DmDatabaseOperator(DataSource dataSource) {
        super(dataSource);
    }

    /**
     * 返回当前实现对应的数据库产品类型。
     */
    @Override
    public ProductTypeEnum getDatabaseType() {
        return ProductTypeEnum.DM;
    }

    /**
     * 按DM方言转义并引用标识符。
     */
    @Override
    protected String quote(String name) {
        return DmSqlNames.quote(name);
    }

    /**
     * 原生索引及集合结构支持独立于关系型 SQL DDL。
     */
    @Override
    public boolean supportsNativeTableDefinition() {
        return true;
    }

    /**
     * 每个游标使用一个 SERIALIZABLE 快照；读取器在自己的连接上重新证明版本、驱动、NLS 及代次。
     */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadSession openCursor(
            cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest request) {
        return DmCursorReader.open(dataSource, request, runtimeOptions, fetchSize);
    }

    /**
     * 读取数据库原生表定义及其元数据证据。
     */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTableDefinition describeNativeTable(String schema,
            String table) {
        return cn.iocoder.yudao.framework.dbswitch.product.dm.DmNativeTables.describe(dataSource, schema, table,
                runtimeOptions.queryTimeoutSeconds());
    }

    /**
     * 为当前产品准备原生目标对象及写入计划。
     */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTargetPlan prepareNativeTarget(
            cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTableDefinition source, String schema,
            String table, java.util.Map<String, String> mapping, boolean replace) {
        return cn.iocoder.yudao.framework.dbswitch.product.dm.DmNativeTables.plan(dataSource, source, schema, table,
                mapping, replace, runtimeOptions.queryTimeoutSeconds());
    }

    /**
     * 按指定模式、表及查询范围读取表数据。
     */
    @Override
    public StatementResultSet queryTableData(String sql) {
        return JdbcTimeValues.losslessRead(super.queryTableData(sql));
    }

    /**
     * 按指定模式、表及查询范围读取表数据。
     */
    @Override
    public StatementResultSet queryTableData(String sql, List<String> fields) {
        return JdbcTimeValues.losslessRead(super.queryTableData(sql, fields));
    }

    /**
     * 按指定模式、表及查询范围读取表数据。
     */
    @Override
    public StatementResultSet queryTableData(String sql, List<String> fields, List<String> orders) {
        return JdbcTimeValues.losslessRead(super.queryTableData(sql, fields, orders));
    }

    /**
     * 获取指定schema下表的结果集。
     */
    @Override
    public StatementResultSet queryTableData(String schema, String table, List<String> fields) {
        return JdbcTimeValues.losslessRead(super.queryTableData(schema, table, fields));
    }

    /**
     * 获取指定schema下表的按主键有序的结果集。
     */
    @Override
    public StatementResultSet queryTableData(String schema, String table, List<String> fields, List<String> orders) {
        return JdbcTimeValues.losslessRead(super.queryTableData(schema, table, fields, orders));
    }
}
