package cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl;

import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.AbstractDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.IDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.StatementResultSet;
import cn.iocoder.yudao.framework.dbswitch.product.mysql.MysqlCompatibleProfile;

import javax.sql.DataSource;
import java.util.List;
import java.util.Objects;

/**
 * MySQL数据库实现类
 *
 * @author jrl
 */
public class MysqlDatabaseOperator extends AbstractDatabaseOperator implements IDatabaseOperator {

    private final MysqlCompatibleProfile profile;

    /**
     * 使用给定参数初始化 {@code MysqlDatabaseOperator}。
     */
    public MysqlDatabaseOperator(DataSource dataSource) {
        this(dataSource, MysqlCompatibleProfile.MYSQL);
    }

    /**
     * 校验构造参数并初始化 {@code MysqlDatabaseOperator}及其父类状态。
     */
    public MysqlDatabaseOperator(DataSource dataSource, MysqlCompatibleProfile profile) {
        super(dataSource);
        this.profile = Objects.requireNonNull(profile, "profile");
    }

    /**
     * 创建并打开按键推进的读取游标。
     */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadSession openCursor(
            cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest request) {
        return MysqlCursorReader.open(dataSource, profile, request, runtimeOptions, fetchSize);
    }

    /**
     * 按数据库产品转换 fetchSize（对齐 dbswitch 2.0.2 的 ProductFeatures.convertFetchSize 设计）： MySQL 协议族（MySQL/MariaDB/GBase8a/TiDB/Doris/StarRocks）需要设置为 Integer.MIN_VALUE 才能启用逐行流式读取，否则驱动会把整个结果集加载进内存， 大表读取存在 OOM 风险；其余数据库原样返回。
     */
    @Override
    protected int convertFetchSize(int fetchSize) {
        return profile.convertFetchSize(fetchSize, super::convertFetchSize);
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
     * 返回当前实现对应的数据库产品类型。
     */
    @Override
    public ProductTypeEnum getDatabaseType() {
        return profile.product();
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
        return RestrictedSelectGuard.qualifiedTableName(profile.product(), schema, table);
    }

    /**
     * 读取或组装当前操作所使用的列定义。
     */
    private String columns(List<String> fields) {
        if (fields == null || fields.isEmpty()) {
            throw new IllegalArgumentException("Missing projected columns");
        }
        return fields.stream().map(field -> RestrictedSelectGuard.quotePhysicalIdentifier(profile.product(), field))
                .collect(java.util.stream.Collectors.joining(","));
    }

    /**
     * 按指定键列生成稳定排序子句。
     */
    private String orderBy(List<String> fields) {
        return fields == null || fields.isEmpty() ? "" : " ORDER BY " + columns(fields);
    }
}
