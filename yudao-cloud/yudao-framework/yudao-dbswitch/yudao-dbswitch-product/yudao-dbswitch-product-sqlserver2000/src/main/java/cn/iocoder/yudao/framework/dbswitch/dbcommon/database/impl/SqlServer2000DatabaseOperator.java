package cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.LockedKeysetCursor;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadSession;
import cn.iocoder.yudao.framework.dbswitch.product.sqlserver2000.SqlServer2000LockedCursorProfile;

import javax.sql.DataSource;

/** 保留 SQL Server 2000 的普通读取，同时提供已独立验证的加锁游标。 */
public final class SqlServer2000DatabaseOperator extends SqlServerDatabaseOperator {

    /**
     * 初始化 {@code SqlServer2000DatabaseOperator}及其父类状态。
     */
    public SqlServer2000DatabaseOperator(DataSource source) {
        super(source, ProductTypeEnum.SQLSERVER2000);
    }

    /**
     * 创建并打开按键推进的读取游标。
     */
    @Override
    public CursorReadSession openCursor(CursorReadRequest request) {
        return LockedKeysetCursor.open(dataSource, request, new SqlServer2000LockedCursorProfile(),
                Math.min(120, runtimeOptions.queryTimeoutSeconds()), fetchSize);
    }
}
