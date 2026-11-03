package cn.iocoder.yudao.framework.dbswitch.dbsynch.uxdb;

import cn.iocoder.yudao.framework.dbswitch.dbsynch.pgsql.PostgresqlDatabaseSyncImpl;

import javax.sql.DataSource;

/**
 * UXDB 数据库 DML 同步实现。
 */
public class UxdbDatabaseSyncImpl extends PostgresqlDatabaseSyncImpl {

    /**
     * 初始化 {@code UxdbDatabaseSyncImpl}及其父类状态。
     */
    public UxdbDatabaseSyncImpl(DataSource ds) {
        super(ds);
    }
}
