package cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadSession;

import javax.sql.DataSource;

/**
 * HighGo 数据库操作器，复用 PostgreSQL 读取/DDL 方言；游标使用 HighGo 自己的快照与 keyset 实现。
 */
public class HighgoDatabaseOperator extends PostgreSqlDatabaseOperator {

    /**
     * 初始化 {@code HighgoDatabaseOperator}及其父类状态。
     */
    public HighgoDatabaseOperator(DataSource dataSource) {
        super(dataSource);
    }

    /**
     * 创建并打开按键推进的读取游标。
     */
    @Override
    public CursorReadSession openCursor(CursorReadRequest request) {
        return HighgoCursorReader.open(dataSource, request, runtimeOptions, fetchSize);
    }

    /**
     * 返回当前实现对应的数据库产品类型。
     */
    @Override
    public ProductTypeEnum getDatabaseType() {
        return ProductTypeEnum.HIGHGO;
    }
}
