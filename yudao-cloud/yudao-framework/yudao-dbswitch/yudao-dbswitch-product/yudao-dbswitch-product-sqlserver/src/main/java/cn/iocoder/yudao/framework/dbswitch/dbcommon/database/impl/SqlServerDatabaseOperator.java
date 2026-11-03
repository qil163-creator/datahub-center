package cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.AbstractDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.IDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.StatementResultSet;
import org.apache.commons.lang3.StringUtils;

import javax.sql.DataSource;
import java.util.List;

/**
 * SQLServer数据库实现类
 *
 * @author jrl
 */
public class SqlServerDatabaseOperator extends AbstractDatabaseOperator implements
        IDatabaseOperator {

    private final ProductTypeEnum configuredProduct;

    /**
     * 使用给定参数初始化 {@code SqlServerDatabaseOperator}。
     */
    public SqlServerDatabaseOperator(DataSource dataSource) {
        this(dataSource, ProductTypeEnum.SQLSERVER);
    }

    /**
     * 校验构造参数并初始化 {@code SqlServerDatabaseOperator}及其父类状态。
     */
    public SqlServerDatabaseOperator(DataSource dataSource, ProductTypeEnum product) {
        super(dataSource);
        if (product != ProductTypeEnum.SQLSERVER && product != ProductTypeEnum.SQLSERVER2000 && product != ProductTypeEnum.SYBASE) {
            throw new IllegalArgumentException("Invalid SQL Server route");
        }
        configuredProduct = product;
    }

    /**
     * 创建并打开按键推进的读取游标。
     */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadSession openCursor(
            cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest request) {
        if (configuredProduct != ProductTypeEnum.SQLSERVER) {
            throw new UnsupportedOperationException("SQL Server 2000 cursor is not implemented");
        }
        return SqlServerCursorReader.open(dataSource, request, runtimeOptions, fetchSize);
    }

    /** sql_variant 无法通过 JDBC 保留其基础类型，任何目标 DDL、截断或写入前即拒绝该表。 */
    @Override
    public List<cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription> nativeMigrationColumns(
            String schemaName, String tableName) {
        if (configuredProduct != ProductTypeEnum.SQLSERVER) {
            return List.of();
        }
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM sys.columns WHERE object_id=OBJECT_ID(?, 'U') AND system_type_id=98")) {
            statement.setQueryTimeout(runtimeOptions.queryTimeoutSeconds());
            statement.setString(1, table(schemaName, tableName));
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new java.sql.SQLException("SQL Server column type observation is unavailable");
                }
                if (rows.getInt(1) > 0) {
                    throw new IllegalArgumentException(
                            "SQL Server sql_variant columns have no exact transport; convert them explicitly in a source query");
                }
            }
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException("SQL Server column types could not be verified");
        }
        return List.of();
    }

    /**
     * 按指定模式、表及查询范围读取表数据。
     */
    @Override
    public StatementResultSet queryTableData(String sql) {
        return exact(super.queryTableData(sql));
    }

    /**
     * 返回已配置产品。
     */
    @Override
    public ProductTypeEnum getDatabaseType() {
        return configuredProduct;
    }

    /**
     * 按指定模式、表及查询范围读取表数据。
     */
    @Override
    public StatementResultSet queryTableData(String sql, List<String> fields, List<String> orders) {
        sql = "SELECT " + columns(fields) + " FROM (" + sql + ") t" + order(orders);
        return exact(this.selectTableData(sql, this.fetchSize));
    }

    /**
     * 按指定模式、表及查询范围读取表数据。
     */
    @Override
    public StatementResultSet queryTableData(String sql, List<String> fields) {
        sql = "SELECT " + columns(fields) + " FROM (" + sql + ") t";
        return exact(this.selectTableData(sql, this.fetchSize));
    }

    /**
     * 生成查询指定字段的select查询SQL语句。
     */
    @Override
    public String getSelectTableSql(String schemaName, String tableName, List<String> fields) {
        return "SELECT " + columns(fields) + " FROM " + table(schemaName, tableName);
    }

    /**
     * 获取指定schema下表的按主键有序的结果集。
     */
    @Override
    public StatementResultSet queryTableData(String schemaName, String tableName, List<String> fields,
            List<String> orders) {
        String sql = getSelectTableSql(schemaName, tableName, fields) + order(orders);
        return exact(this.selectTableData(sql, this.fetchSize));
    }

    /**
     * 获取指定schema下表的结果集。
     */
    @Override
    public StatementResultSet queryTableData(String schemaName, String tableName,
            List<String> fields) {
        String sql = getSelectTableSql(schemaName, tableName, fields);
        return exact(this.selectTableData(sql, this.fetchSize));
    }

    /**
     * 清除指定表的所有数据。
     */
    @Override
    public void truncateTableData(String schemaName, String tableName) {
        String sql = "TRUNCATE TABLE " + table(schemaName, tableName);
        this.executeSql(sql);
    }

    /**
     * 删除指定物理表。
     */
    @Override
    public void dropTable(String schemaName, String tableName) {
        String sql = "DROP TABLE " + table(schemaName, tableName);
        this.executeSql(sql);
    }

    /** SQL Server 2000 路由保留历史上未经认证的值表示。 */
    private StatementResultSet exact(StatementResultSet resource) {
        return configuredProduct == ProductTypeEnum.SQLSERVER
                ? SqlServerTimeValues.losslessRead(resource, runtimeOptions.maxCacheBytes())
                : cn.iocoder.yudao.framework.dbswitch.dbcommon.database.JdbcTimeValues.losslessRead(resource);
    }

    /**
     * 返回表。
     */
    private static String table(String schema, String table) {
        return cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard
                .qualifiedTableName(ProductTypeEnum.SQLSERVER, schema, table);
    }

    /**
     * 读取或组装当前操作所使用的列定义。
     */
    private static String columns(List<String> names) {
        return names.stream().map(name -> cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard
                        .quotePhysicalIdentifier(ProductTypeEnum.SQLSERVER, name))
                .collect(java.util.stream.Collectors.joining(", "));
    }

    /**
     * 返回顺序。
     */
    private static String order(List<String> names) {
        return names == null || names.isEmpty() ? "" : " ORDER BY " + columns(names);
    }
}
