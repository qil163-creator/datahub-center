package cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl;

import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.AbstractDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.IDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.StatementResultSet;

import javax.sql.DataSource;
import java.util.List;

/**
 * GBase 8a数据库实现类。游标走 {@link Gbase8aCursorReader}（固定上界 + 无快照 keyset + 生成代次与关系证据复查），
 * 不借用 MySQL 的 InnoDB 快照语义；普通读取与 MySQL 协议族一致。
 */
public class GbaseDatabaseOperator extends AbstractDatabaseOperator implements IDatabaseOperator {

    private static final ProductTypeEnum PRODUCT = ProductTypeEnum.GBASE8A;

    /**
     * 初始化 {@code GbaseDatabaseOperator}及其父类状态。
     */
    public GbaseDatabaseOperator(DataSource dataSource) {
        super(dataSource);
    }

    /**
     * 创建并打开按键推进的读取游标。
     */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadSession openCursor(
            cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest request) {
        return Gbase8aCursorReader.open(dataSource, request, runtimeOptions, fetchSize);
    }

    /**
     * 生成查询指定字段的select查询SQL语句。
     */
    @Override
    public String getSelectTableSql(String schemaName, String tableName, List<String> fields) {
        return "SELECT " + columns(fields) + " FROM " + relation(schemaName, tableName);
    }

    /**
     * 获取指定schema下表的按主键有序的结果集。
     */
    @Override
    public StatementResultSet queryTableData(String schemaName, String tableName, List<String> fields,
            List<String> orders) {
        String sql = getSelectTableSql(schemaName, tableName, fields) + orderBy(orders);
        return this.selectTableData(sql, Integer.MIN_VALUE);
    }

    /**
     * 返回产品。
     */
    @Override
    public ProductTypeEnum getDatabaseType() {
        return PRODUCT;
    }

    /**
     * 按指定模式、表及查询范围读取表数据。
     */
    @Override
    public StatementResultSet queryTableData(String sql, List<String> fields, List<String> orders) {
        sql = "SELECT " + columns(fields) + " FROM (" + sql + ") t" + orderBy(orders);
        return this.selectTableData(sql, Integer.MIN_VALUE);
    }

    /**
     * 按指定模式、表及查询范围读取表数据。
     */
    @Override
    public StatementResultSet queryTableData(String sql) {
        return this.selectTableData(sql, Integer.MIN_VALUE);
    }

    /**
     * 获取指定schema下表的结果集。
     */
    @Override
    public StatementResultSet queryTableData(String schemaName, String tableName,
            List<String> fields) {
        String sql = getSelectTableSql(schemaName, tableName, fields);
        return this.selectTableData(sql, Integer.MIN_VALUE);
    }

    /**
     * 按指定模式、表及查询范围读取表数据。
     */
    @Override
    public StatementResultSet queryTableData(String sql, List<String> fields) {
        sql = "SELECT " + columns(fields) + " FROM (" + sql + ") t";
        return this.selectTableData(sql, Integer.MIN_VALUE);
    }

    /**
     * 清除指定表的所有数据。
     */
    @Override
    public void truncateTableData(String schemaName, String tableName) {
        String sql = "TRUNCATE TABLE " + relation(schemaName, tableName);
        this.executeSql(sql);
    }

    /**
     * 删除指定物理表。
     */
    @Override
    public void dropTable(String schemaName, String tableName) {
        String sql = "DROP TABLE " + relation(schemaName, tableName);
        this.executeSql(sql);
    }

    /**
     * 生成模式与表组成的限定关系名。
     */
    private String relation(String schema, String table) {
        return RestrictedSelectGuard.qualifiedTableName(PRODUCT, schema, table);
    }

    /**
     * 读取或组装当前操作所使用的列定义。
     */
    private String columns(List<String> fields) {
        if (fields == null || fields.isEmpty()) {
            throw new IllegalArgumentException("Missing projected columns");
        }
        return fields.stream().map(field -> RestrictedSelectGuard.quotePhysicalIdentifier(PRODUCT, field))
                .collect(java.util.stream.Collectors.joining(","));
    }

    /**
     * 按指定键列生成稳定排序子句。
     */
    private String orderBy(List<String> fields) {
        return fields == null || fields.isEmpty() ? "" : " ORDER BY " + columns(fields);
    }
}
