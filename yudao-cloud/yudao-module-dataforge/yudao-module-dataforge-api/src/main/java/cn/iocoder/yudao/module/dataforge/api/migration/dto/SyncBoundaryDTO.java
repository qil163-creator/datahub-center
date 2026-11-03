package cn.iocoder.yudao.module.dataforge.api.migration.dto;

/** 与同步实际启动共用目标事务顺序；不代表业务数据版本、提交成功或读路由可见。 */
public record SyncBoundaryDTO(Integer schemaVersion, String requestKey, Long databaseId,
                              String configurationRevision, String product, String schema, String table,
                              Long position) {

}

