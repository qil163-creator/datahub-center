package cn.iocoder.yudao.framework.dbswitch.data.domain;

/**
 * 执行资源的终止证据，不代表数据已回滚或迁移业务成功。
 */
public enum MigrationTermination {
    RUNNING,
    CONFIRMED,
    UNCONFIRMED
}
