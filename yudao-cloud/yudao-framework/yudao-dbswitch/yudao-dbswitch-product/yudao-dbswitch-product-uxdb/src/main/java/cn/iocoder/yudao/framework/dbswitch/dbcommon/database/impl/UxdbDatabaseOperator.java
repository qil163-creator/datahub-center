package cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadSession;

import javax.sql.DataSource;

/**
 * UXDB 鏁版嵁搴撴搷浣滃櫒锛屽鐢?PostgreSQL 璇诲彇/DDL 鏂硅█锛涙父鏍囦娇鐢?UXDB 鑷繁鐨勫揩鐓т笌 keyset 瀹炵幇銆? */
public class UxdbDatabaseOperator extends PostgreSqlDatabaseOperator {

    /**
     * 初始化 {@code UxdbDatabaseOperator}及其父类状态。
     */
    public UxdbDatabaseOperator(DataSource dataSource) {
        super(dataSource);
    }

    /**
     * 创建并打开按键推进的读取游标。
     */
    @Override
    public CursorReadSession openCursor(CursorReadRequest request) {
        return UxdbCursorReader.open(dataSource, request, runtimeOptions, fetchSize);
    }

    /**
     * 返回当前实现对应的数据库产品类型。
     */
    @Override
    public ProductTypeEnum getDatabaseType() {
        return ProductTypeEnum.UXDB;
    }
}
