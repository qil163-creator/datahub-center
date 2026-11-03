package cn.iocoder.yudao.framework.dbswitch.dbsynch.opengauss;

import cn.iocoder.yudao.framework.dbswitch.dbsynch.pgsql.PostgresqlDatabaseSyncImpl;

import javax.sql.DataSource;

/**
 * openGauss 数据库 DML 同步实现。
 */
public class OpengaussDatabaseSyncImpl extends PostgresqlDatabaseSyncImpl {

    /**
     * 初始化 {@code OpengaussDatabaseSyncImpl}及其父类状态。
     */
    public OpengaussDatabaseSyncImpl(DataSource ds) {
        super(ds);
    }
}
