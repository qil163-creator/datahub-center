package cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.AbstractDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.IDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.JdbcTimeValues;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.StatementResultSet;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.util.PostgreSqlUtils;

import javax.sql.DataSource;
import java.util.List;

/**
 * PostgreSQL数据库实现类
 *
 * @author jrl
 */
public class PostgreSqlDatabaseOperator extends AbstractDatabaseOperator implements
        IDatabaseOperator {

    /**
     * 初始化 {@code PostgreSqlDatabaseOperator}及其父类状态。
     */
    public PostgreSqlDatabaseOperator(DataSource dataSource) {
        super(dataSource);
    }

    /**
     * 准备序列值迁移所需的资源和状态。
     */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.dbwriter.SequenceTransferScope prepareSequenceTransfer(
            cn.iocoder.yudao.framework.dbswitch.core.model.SequenceDependencyGraph graph,
            java.util.List<cn.iocoder.yudao.framework.dbswitch.dbwriter.SequenceTransferScope.TablePlan> tables,
            boolean replace, boolean truncate, boolean failIfExists) {
        return prepareSequenceTransfer(graph, tables, replace, truncate, failIfExists,
                cn.iocoder.yudao.framework.dbswitch.dbwriter.SequenceTransferScope.Mode.DATA_AND_STRUCTURE);
    }

    /**
     * 准备序列值迁移所需的资源和状态。
     */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.dbwriter.SequenceTransferScope prepareSequenceTransfer(
            cn.iocoder.yudao.framework.dbswitch.core.model.SequenceDependencyGraph graph,
            java.util.List<cn.iocoder.yudao.framework.dbswitch.dbwriter.SequenceTransferScope.TablePlan> tables,
            boolean replace, boolean truncate, boolean failIfExists,
            cn.iocoder.yudao.framework.dbswitch.dbwriter.SequenceTransferScope.Mode mode) {
        if (getDatabaseType() != ProductTypeEnum.POSTGRESQL) {
            throw new UnsupportedOperationException("Shared sequences require verified PostgreSQL catalog semantics");
        }
        return PostgresSequenceTransferScope.open(getDataSource(), graph, tables, replace, truncate, failIfExists,
                runtimeOptions.queryTimeoutSeconds(), mode);
    }

    /**
     * 拒绝当前实现未支持的 {@code planMigrationDependencies} 操作。
     */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.core.model.SequenceDependencyGraph planMigrationDependencies(
            List<cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.Relation> selectedRelations) {
        if (getDatabaseType() != ProductTypeEnum.POSTGRESQL) {
            throw new UnsupportedOperationException(
                    "Sequence dependency discovery requires the verified PostgreSQL product");
        }
        return PostgresConnectionDeadline.isBounded(getDataSource(), runtimeOptions.queryTimeoutSeconds())
                ? PostgresSequenceDependencies.readBounded(getDataSource(), selectedRelations,
                runtimeOptions.queryTimeoutSeconds())
                : PostgresSequenceDependencies.read(getDataSource(), selectedRelations,
                runtimeOptions.queryTimeoutSeconds());
    }

    /**
     * 创建并打开按键推进的读取游标。
     */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadSession openCursor(
            cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest request) {
        return PostgresCursorReader.open(getDataSource(), request, runtimeOptions, getFetchSize());
    }

    /**
     * 返回当前实现对应的数据库产品类型。
     */
    @Override
    public ProductTypeEnum getDatabaseType() {
        return ProductTypeEnum.POSTGRESQL;
    }

    /**
     * 按指定模式、表及查询范围读取表数据。
     */
    @Override
    public StatementResultSet queryTableData(String sql) {
        return exactTime(super.queryTableData(sql));
    }

    /**
     * 按指定模式、表及查询范围读取表数据。
     */
    @Override
    public StatementResultSet queryTableData(String sql, List<String> fields, List<String> orders) {
        sql = "select " + fields(fields) + " from (" + sql + ") t order by " + fields(orders) + " asc ";
        return exactTime(this.selectTableData(sql, this.fetchSize));
    }

    /**
     * 按指定模式、表及查询范围读取表数据。
     */
    @Override
    public StatementResultSet queryTableData(String sql, List<String> fields) {
        sql = "select " + fields(fields) + " from (" + sql + ") t";
        return exactTime(this.selectTableData(sql, this.fetchSize));
    }

    /**
     * 生成查询指定字段的select查询SQL语句。
     */
    @Override
    public String getSelectTableSql(String schemaName, String tableName, List<String> fields) {
        return "select " + fields(fields) + " from " + table(schemaName, tableName) + " ";
    }

    /**
     * 获取指定schema下表的按主键有序的结果集。
     */
    @Override
    public StatementResultSet queryTableData(String schemaName, String tableName, List<String> fields,
            List<String> orders) {
        String sql = getSelectTableSql(schemaName, tableName, fields) + "order by " + fields(orders) + " asc ";
        return exactTime(this.selectTableData(sql, this.fetchSize));
    }

    /**
     * 获取指定schema下表的结果集。
     */
    @Override
    public StatementResultSet queryTableData(String schemaName, String tableName,
            List<String> fields) {
        String sql = getSelectTableSql(schemaName, tableName, fields);
        return exactTime(this.selectTableData(sql, this.fetchSize));
    }

    /**
     * 清除指定表的所有数据。
     */
    @Override
    public void truncateTableData(String schemaName, String tableName) {
        String sql = "TRUNCATE TABLE " + table(schemaName, tableName) + " RESTART IDENTITY ";
        this.executeSql(sql);
    }

    /**
     * 删除指定物理表。
     */
    @Override
    public void dropTable(String schemaName, String tableName) {
        String sql = "DROP TABLE " + table(schemaName, tableName) + " cascade ";
        this.executeSql(sql);
    }

    /** 兼容子类保留自身尚未验证的普通读取契约。 */
    private StatementResultSet exactTime(StatementResultSet resource) {
        return getDatabaseType() == ProductTypeEnum.POSTGRESQL
                ? JdbcTimeValues.losslessMicrosecondTimeRead(resource) : resource;
    }

    /**
     * 返回字段集合。
     */
    private static String fields(List<String> names) {
        if (names == null || names.isEmpty()) {
            throw new IllegalArgumentException("Columns are required");
        }
        return names.stream().map(PostgreSqlUtils::quoteIdentifier).collect(java.util.stream.Collectors.joining(","));
    }

    /**
     * 返回表。
     */
    private static String table(String schema, String name) {
        return PostgreSqlUtils.getFullyQualifiedTableName(schema, name, true);
    }
}
