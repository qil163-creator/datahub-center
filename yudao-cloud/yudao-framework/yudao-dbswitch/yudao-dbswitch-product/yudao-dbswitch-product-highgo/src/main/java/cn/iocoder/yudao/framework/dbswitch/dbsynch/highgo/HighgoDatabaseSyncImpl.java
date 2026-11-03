package cn.iocoder.yudao.framework.dbswitch.dbsynch.highgo;

import cn.iocoder.yudao.framework.dbswitch.dbsynch.pgsql.PostgresqlDatabaseSyncImpl;

import javax.sql.DataSource;

/**
 * HighGo 数据库 DML 同步实现。
 */
public class HighgoDatabaseSyncImpl extends PostgresqlDatabaseSyncImpl {

    /**
     * 初始化 {@code HighgoDatabaseSyncImpl}及其父类状态。
     */
    public HighgoDatabaseSyncImpl(DataSource ds) {
        super(ds);
    }
}
