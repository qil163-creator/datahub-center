package cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;

import javax.sql.DataSource;
import java.util.List;

import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.JdbcTimeValues;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.StatementResultSet;

/**
 * openGauss 数据库操作器，复用 PostgreSQL 读取/DDL 方言（DROP TABLE CASCADE）。
 */
public class OpengaussDatabaseOperator extends PostgreSqlDatabaseOperator {

    /**
     * 初始化 {@code OpengaussDatabaseOperator}及其父类状态。
     */
    public OpengaussDatabaseOperator(DataSource dataSource) {
        super(dataSource);
    }

    /**
     * 返回当前实现对应的数据库产品类型。
     */
    @Override
    public ProductTypeEnum getDatabaseType() {
        return ProductTypeEnum.OPENGAUSS;
    }

    /**
     * 创建并打开按键推进的读取游标。
     */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadSession openCursor(
            cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest request) {
        return OpengaussCursorReader.open(dataSource, request, runtimeOptions, fetchSize);
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
