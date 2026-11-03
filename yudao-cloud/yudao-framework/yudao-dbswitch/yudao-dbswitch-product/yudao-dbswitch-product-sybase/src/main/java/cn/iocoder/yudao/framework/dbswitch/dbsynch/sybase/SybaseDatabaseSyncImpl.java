package cn.iocoder.yudao.framework.dbswitch.dbsynch.sybase;


import cn.iocoder.yudao.framework.dbswitch.dbsynch.mssql.SqlServerDatabaseSyncImpl;

import javax.sql.DataSource;

/**
 * Sybase数据库DML同步实现类
 *
 * @author tang
 */
public class SybaseDatabaseSyncImpl extends SqlServerDatabaseSyncImpl {

    /**
     * 初始化 {@code SybaseDatabaseSyncImpl}及其父类状态。
     */
    public SybaseDatabaseSyncImpl(DataSource ds) {
        super(ds);
    }

}
