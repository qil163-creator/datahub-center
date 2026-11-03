package cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.StatementResultSet;

import javax.sql.DataSource;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Sybase数据库实现类
 *
 * @author tang
 */
public class SybaseDatabaseOperator extends SqlServerDatabaseOperator {

    /**
     * 初始化 {@code SybaseDatabaseOperator}及其父类状态。
     */
    public SybaseDatabaseOperator(DataSource dataSource) {
        super(dataSource, ProductTypeEnum.SYBASE);
    }

    /**
     * 返回当前实现对应的数据库产品类型。
     */
    @Override
    public ProductTypeEnum getDatabaseType() {
        return ProductTypeEnum.SYBASE;
    }

    /** ASE 16 自主管理 rowcount 和键集游标，绝不借用 SQL Server 快照读取器。 */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadSession openCursor(
            cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest request) {
        return SybaseCursorReader.open(dataSource, request, runtimeOptions, fetchSize);
    }

    /** jconn4 既不实现类型化 JDBC 4.2 TIME 访问器，也不实现 SQL Server 值传输；普通
     *  getObject 路径对 time 返回 java.sql.Time，对 datetime、bigdatetime 和 bigtime 返回 SybTimestamp，
     *  这是已在 ASE 16.0 SP02 PL02 验证的 Sybase 编解码方式；继承的 JdbcTimeValues 包装器会调用
     *  ResultSet.getObject(index, LocalTime.class)，而此驱动未实现该抽象方法。 */
    @Override
    public StatementResultSet queryTableData(String sql) {
        return this.selectTableData(sql, this.fetchSize);
    }

    /**
     * 按指定模式、表及查询范围读取表数据。
     */
    @Override
    public StatementResultSet queryTableData(String sql, List<String> fields) {
        return this.selectTableData("SELECT " + columns(fields) + " FROM (" + sql + ") t", this.fetchSize);
    }

    /**
     * 按指定模式、表及查询范围读取表数据。
     */
    @Override
    public StatementResultSet queryTableData(String sql, List<String> fields, List<String> orders) {
        return this.selectTableData("SELECT " + columns(fields) + " FROM (" + sql + ") t" + order(orders),
                this.fetchSize);
    }

    /**
     * 获取指定schema下表的结果集。
     */
    @Override
    public StatementResultSet queryTableData(String schemaName, String tableName, List<String> fields) {
        return this.selectTableData("SELECT " + columns(fields) + " FROM " + table(schemaName, tableName),
                this.fetchSize);
    }

    /**
     * 获取指定schema下表的按主键有序的结果集。
     */
    @Override
    public StatementResultSet queryTableData(String schemaName, String tableName, List<String> fields,
            List<String> orders) {
        return this.selectTableData(
                "SELECT " + columns(fields) + " FROM " + table(schemaName, tableName) + order(orders),
                this.fetchSize);
    }

    /**
     * 返回表。
     */
    private static String table(String schema, String table) {
        return cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard
                .qualifiedTableName(ProductTypeEnum.SYBASE, schema, table);
    }

    /**
     * 读取或组装当前操作所使用的列定义。
     */
    private static String columns(List<String> names) {
        return names.stream().map(name -> cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard
                .quotePhysicalIdentifier(ProductTypeEnum.SYBASE, name)).collect(Collectors.joining(", "));
    }

    /**
     * 返回顺序。
     */
    private static String order(List<String> names) {
        return names == null || names.isEmpty() ? "" : " ORDER BY " + columns(names);
    }

}
