package cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.util.OceanbaseUtils;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.AbstractDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.IDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.PrepareStatementResultSet;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.StatementResultSet;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * OceanBase 操作器：按兼容模式委托 MySQL 或 Oracle 操作器。
 */
public class OceanbaseDatabaseOperator extends AbstractDatabaseOperator implements IDatabaseOperator {

    private final IDatabaseOperator delegate;

    private final boolean mysqlMode;

    /**
     * 初始化 {@code OceanbaseDatabaseOperator}及其父类状态。
     */
    public OceanbaseDatabaseOperator(DataSource dataSource) {
        super(dataSource);
        this.mysqlMode = OceanbaseUtils.isOceanBaseUseMysqlMode(dataSource);
        this.delegate = mysqlMode
                ? new MysqlDatabaseOperator(dataSource)
                : new OracleDatabaseOperator(dataSource);
    }

    /**
     * 返回当前实现对应的数据库产品类型。
     */
    @Override
    public ProductTypeEnum getDatabaseType() {
        return ProductTypeEnum.OCEANBASE;
    }

    /**
     * 设置当前操作使用的配置，并检查配置阶段仍允许修改。
     */
    @Override
    public void configure(cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions options) {
        super.configure(options);
        delegate.configure(options);
    }

    /**
     * 按当前兼容模式包装结果集，保留时间值的精确语义。
     */
    private StatementResultSet lossless(StatementResultSet result) {
        return mysqlMode ? cn.iocoder.yudao.framework.dbswitch.dbcommon.database.JdbcTimeValues.losslessDurationRead(
                result) : result;
    }

    /** 每个模式在快照连接上重新验证自身的服务器、驱动及事务配置。 */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadSession openCursor(
            cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest request) {
        if (!mysqlMode) {
            return OracleCursorReader.open(dataSource, request, runtimeOptions, getFetchSize(),
                    cn.iocoder.yudao.framework.dbswitch.product.oceanbase.OceanbaseOracleReplayProfile.INSTANCE);
        }
        return OceanbaseCursorReader.open(dataSource, request, runtimeOptions, getFetchSize());
    }

    /**
     * 获取读取(fetch)数据的批次大小。
     */
    @Override
    public int getFetchSize() {
        return delegate.getFetchSize();
    }

    /**
     * 设置读取(fetch)数据的批次大小。
     */
    @Override
    public void setFetchSize(int size) {
        delegate.setFetchSize(size);
    }

    /**
     * 按指定模式、表及查询范围读取表数据。
     */
    @Override
    public StatementResultSet queryTableData(String sql, List<String> fields, List<String> orders) {
        return lossless(delegate.queryTableData(sql, fields, orders));
    }

    /**
     * 按指定模式、表及查询范围读取表数据。
     */
    @Override
    public StatementResultSet queryTableData(String sql) {
        return lossless(delegate.queryTableData(sql));
    }

    /**
     * 按指定模式、表及查询范围读取表数据。
     */
    @Override
    public StatementResultSet queryTableData(String sql, List<String> fields) {
        return lossless(delegate.queryTableData(sql, fields));
    }

    /**
     * 生成查询指定字段的select查询SQL语句。
     */
    @Override
    public String getSelectTableSql(String schemaName, String tableName, List<String> fields) {
        return delegate.getSelectTableSql(schemaName, tableName, fields);
    }

    /**
     * 获取指定schema下表的按主键有序的结果集。
     */
    @Override
    public StatementResultSet queryTableData(String schemaName, String tableName, List<String> fields,
            List<String> orders) {
        return lossless(delegate.queryTableData(schemaName, tableName, fields, orders));
    }

    /**
     * 获取指定schema下表的结果集。
     */
    @Override
    public StatementResultSet queryTableData(String schemaName, String tableName, List<String> fields) {
        return lossless(delegate.queryTableData(schemaName, tableName, fields));
    }

    /**
     * 清除指定表的所有数据。
     */
    @Override
    public void truncateTableData(String schemaName, String tableName) {
        delegate.truncateTableData(schemaName, tableName);
    }

    /**
     * 删除指定物理表。
     */
    @Override
    public void dropTable(String schemaName, String tableName) {
        delegate.dropTable(schemaName, tableName);
    }

    /**
     * 按增量边界读取表中的新增或变更数据。
     */
    @Override
    public PrepareStatementResultSet queryIncreaseTableData(String sourceSchemaName, String sourceTableName,
            List<String> queryFieldColumn, ColumnDescription increaseColumn,
            String startVal, String endVal) {
        return delegate.queryIncreaseTableData(sourceSchemaName, sourceTableName, queryFieldColumn,
                increaseColumn, startVal, endVal);
    }

    /**
     * 按增量边界读取表中的新增或变更数据。
     */
    @Override
    public PrepareStatementResultSet queryIncreaseTableData(String sql, List<String> queryFieldColumn,
            ColumnDescription increaseColumn, String startVal, String endVal) {
        return delegate.queryIncreaseTableData(sql, queryFieldColumn, increaseColumn, startVal, endVal);
    }

    /**
     * 返回当前字段是否属于主键。
     */
    @Override
    public Boolean getPkExist(String targetSchemaName, String targetTableName, Map<String, Object> pkVal)
            throws SQLException {
        return delegate.getPkExist(targetSchemaName, targetTableName, pkVal);
    }
}
