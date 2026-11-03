package cn.iocoder.yudao.framework.dbswitch.dbsynch.kingbase;

import cn.iocoder.yudao.framework.dbswitch.dbsynch.pgsql.PostgresqlDatabaseSyncImpl;

import javax.sql.DataSource;

/**
 * kingbase8数据库DML同步实现类
 *
 * @author jrl
 */
public class KingbaseDatabaseSyncImpl extends PostgresqlDatabaseSyncImpl {

    /**
     * 初始化 {@code KingbaseDatabaseSyncImpl}及其父类状态。
     */
    public KingbaseDatabaseSyncImpl(DataSource ds) {
        super(ds);
    }

}
