package cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl;

/**
 * Kingbase8数据库实现类
 *
 * @author jrl
 */

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadSession;

import javax.sql.DataSource;

public class KingbaseDatabaseOperator extends PostgreSqlDatabaseOperator {

    /**
     * 初始化 {@code KingbaseDatabaseOperator}及其父类状态。
     */
    public KingbaseDatabaseOperator(DataSource dataSource) {
        super(dataSource);
    }

    /**
     * 创建并打开按键推进的读取游标。
     */
    @Override
    public CursorReadSession openCursor(CursorReadRequest request) {
        return KingbaseCursorReader.open(dataSource, request, runtimeOptions, fetchSize);
    }

    /**
     * 返回当前实现对应的数据库产品类型。
     */
    @Override
    public ProductTypeEnum getDatabaseType() {
        return ProductTypeEnum.KINGBASE;
    }
}
