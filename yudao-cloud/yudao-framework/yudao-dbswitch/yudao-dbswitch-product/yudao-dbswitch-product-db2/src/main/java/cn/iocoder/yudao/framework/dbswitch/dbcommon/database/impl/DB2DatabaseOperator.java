package cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.AbstractDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.IDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.StatementResultSet;
import cn.iocoder.yudao.framework.dbswitch.product.db2.Db2CursorMetadata;

import javax.sql.DataSource;
import java.util.List;

/**
 * DB2数据库实现类
 *
 * @author jrl
 */
public class DB2DatabaseOperator extends AbstractDatabaseOperator implements IDatabaseOperator {

    /**
     * 初始化 {@code DB2DatabaseOperator}及其父类状态。
     */
    public DB2DatabaseOperator(DataSource dataSource) {
        super(dataSource);
    }

    /**
     * 返回当前实现对应的数据库产品类型。
     */
    @Override
    public ProductTypeEnum getDatabaseType() {
        return ProductTypeEnum.DB2;
    }

    /**
     * 按指定模式、表及查询范围读取表数据。
     */
    @Override
    public StatementResultSet queryTableData(String sql, List<String> fields, List<String> orders) {
        sql = "select " + Db2CursorMetadata.fields(fields) + " from (" + sql + ") t order by "
                + Db2CursorMetadata.fields(orders) + " asc";
        return this.selectTableData(sql, this.fetchSize);
    }

    /**
     * 按指定模式、表及查询范围读取表数据。
     */
    @Override
    public StatementResultSet queryTableData(String sql, List<String> fields) {
        sql = "select " + Db2CursorMetadata.fields(fields) + " from (" + sql + ") t";
        return this.selectTableData(sql, this.fetchSize);
    }

    /**
     * 生成查询指定字段的select查询SQL语句。
     */
    @Override
    public String getSelectTableSql(String schemaName, String tableName, List<String> fields) {
        return "select " + Db2CursorMetadata.fields(fields) + " from " + Db2CursorMetadata.relation(schemaName,
                tableName);
    }

    /**
     * 获取指定schema下表的按主键有序的结果集。
     */
    @Override
    public StatementResultSet queryTableData(String schemaName, String tableName, List<String> fields,
            List<String> orders) {
        String sql = getSelectTableSql(schemaName, tableName, fields) + " order by " + Db2CursorMetadata.fields(
                orders) + " asc";
        return this.selectTableData(sql, this.fetchSize);
    }

    /**
     * 获取指定schema下表的结果集。
     */
    @Override
    public StatementResultSet queryTableData(String schemaName, String tableName,
            List<String> fields) {
        String sql = getSelectTableSql(schemaName, tableName, fields);
        return this.selectTableData(sql, this.fetchSize);
    }

    /**
     * 清除指定表的所有数据。
     */
    @Override
    public void truncateTableData(String schemaName, String tableName) {
        String sql = "TRUNCATE TABLE " + Db2CursorMetadata.relation(schemaName, tableName) + " immediate";
        this.executeSql(sql);
    }

    /**
     * 删除指定物理表。
     */
    @Override
    public void dropTable(String schemaName, String tableName) {
        String sql = "DROP TABLE " + Db2CursorMetadata.relation(schemaName, tableName);
        this.executeSql(sql);
    }

    /** DB2 12.1.5.0 负责其 WITH RR、限制取数的键集游标；强类型游标准入由具体产品负责。 */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadSession openCursor(
            cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest request) {
        return Db2CursorReader.open(dataSource, request, runtimeOptions, fetchSize);
    }

}
