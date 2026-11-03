package cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.AbstractDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.IDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.StatementResultSet;
import cn.iocoder.yudao.framework.dbswitch.core.util.OracleSqlNames;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;

import javax.sql.DataSource;
import java.util.List;

/** Oracle 表读取与 DDL 操作；继承产品保留各自的标识符方言。 */
public class OracleDatabaseOperator extends AbstractDatabaseOperator implements IDatabaseOperator {

    /**
     * 初始化 {@code OracleDatabaseOperator}及其父类状态。
     */
    public OracleDatabaseOperator(DataSource dataSource) {
        super(dataSource);
    }

    /**
     * 返回当前实现对应的数据库产品类型。
     */
    @Override
    public ProductTypeEnum getDatabaseType() {
        return ProductTypeEnum.ORACLE;
    }

    /**
     * 按Oracle方言转义并引用标识符。
     */
    protected String quote(String name) {
        return getDatabaseType() == ProductTypeEnum.ORACLE ? OracleSqlNames.quote(name)
                : RestrictedSelectGuard.quotePhysicalIdentifier(getDatabaseType(), name);
    }

    /**
     * 读取或组装当前操作所使用的列定义。
     */
    private String columns(List<String> names) {
        return String.join(",", names.stream().map(this::quote).toList());
    }

    /**
     * 生成模式与表组成的限定关系名。
     */
    private String relation(String schema, String table) {
        return quote(schema) + '.' + quote(table);
    }

    /**
     * 原生索引及集合结构支持独立于关系型 SQL DDL。
     */
    @Override
    public boolean supportsNativeTableDefinition() {
        return getDatabaseType() == ProductTypeEnum.ORACLE;
    }

    /**
     * 读取数据库原生表定义及其元数据证据。
     */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTableDefinition describeNativeTable(String schema,
            String table) {
        if (!supportsNativeTableDefinition()) {
            throw new UnsupportedOperationException("Native Oracle definition requires native Oracle product");
        }
        return cn.iocoder.yudao.framework.dbswitch.product.oracle.OracleNativeTables.describe(dataSource, schema, table,
                runtimeOptions.queryTimeoutSeconds());
    }

    /**
     * 为当前产品准备原生目标对象及写入计划。
     */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTargetPlan prepareNativeTarget(
            cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTableDefinition source, String schema,
            String table, java.util.Map<String, String> mapping, boolean replace) {
        if (!supportsNativeTableDefinition()) {
            throw new UnsupportedOperationException("Native Oracle target requires native Oracle product");
        }
        return cn.iocoder.yudao.framework.dbswitch.product.oracle.OracleNativeTables.plan(dataSource, source, schema,
                table, mapping, replace, runtimeOptions.queryTimeoutSeconds());
    }

    /**
     * 按指定模式、表及查询范围读取表数据。
     */
    @Override
    public StatementResultSet queryTableData(String sql, List<String> fields, List<String> orders) {
        return selectTableData(
                "SELECT " + columns(fields) + " FROM (" + sql + ") t ORDER BY " + columns(orders) + " ASC", fetchSize);
    }

    /** 每个游标使用一个 SERIALIZABLE 快照；读取器在自己的连接上重新证明版本、驱动、NLS 及代次。 */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadSession openCursor(
            cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest request) {
        return OracleCursorReader.open(dataSource, request, runtimeOptions, getFetchSize());
    }

    /**
     * 按指定模式、表及查询范围读取表数据。
     */
    @Override
    public StatementResultSet queryTableData(String sql, List<String> fields) {
        return selectTableData("SELECT " + columns(fields) + " FROM (" + sql + ") t", fetchSize);
    }

    /**
     * 生成查询指定字段的select查询SQL语句。
     */
    @Override
    public String getSelectTableSql(String schema, String table, List<String> fields) {
        return "SELECT " + columns(fields) + " FROM " + relation(schema, table);
    }

    /**
     * 获取指定schema下表的按主键有序的结果集。
     */
    @Override
    public StatementResultSet queryTableData(String schema, String table, List<String> fields, List<String> orders) {
        return selectTableData(getSelectTableSql(schema, table, fields) + " ORDER BY " + columns(orders) + " ASC",
                fetchSize);
    }

    /**
     * 获取指定schema下表的结果集。
     */
    @Override
    public StatementResultSet queryTableData(String schema, String table, List<String> fields) {
        return selectTableData(getSelectTableSql(schema, table, fields), fetchSize);
    }

    /**
     * 清除指定表的所有数据。
     */
    @Override
    public void truncateTableData(String schema, String table) {
        executeSql("TRUNCATE TABLE " + relation(schema, table));
    }

    /**
     * 删除指定物理表。
     */
    @Override
    public void dropTable(String schema, String table) {
        executeSql("DROP TABLE " + relation(schema, table));
    }
}
