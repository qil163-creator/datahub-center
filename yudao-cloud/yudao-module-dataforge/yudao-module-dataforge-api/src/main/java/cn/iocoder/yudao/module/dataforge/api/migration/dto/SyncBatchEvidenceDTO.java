package cn.iocoder.yudao.module.dataforge.api.migration.dto;

import java.util.List;

/**
 * 不含连接秘密的执行批次事实。批次完成不是整改数据已到达/被读到的证明。
 * source/targetRevision 是配置修订，不是数据版本；planHash 只指向冻结请求配置，不是动态增量水位。
 * manifestComplete 仅表示引擎已返回的表级结果清单未被截断且内部计数一致，不表示全量覆盖或全部成功。
 * 消费方仍需检查失败、运行终态、实际对象、整改提交边界及同次读取的目标可见性，不能据此单独验收。
 */
public record SyncBatchEvidenceDTO(Integer schemaVersion, Long runId, Integer attempt,
                                   Long sourceDatabaseId, Long targetDatabaseId, String sourceRevision,
                                   String targetRevision,
                                   String sourceProduct, String targetProduct, String sourceSchema, String targetSchema,
                                   String planHash, String mode, String termination, boolean manifestComplete,
                                   Long totalTables, Long successfulTables, Long failedTables, List<Table> tables) {

    /**
     * 创建同步批次证据实例。
     *
     * @param schemaVersion 结构版本
     * @param runId 运行编号
     * @param attempt 当前部署尝试
     * @param sourceDatabaseId 来源数据源编号
     * @param targetDatabaseId 目标数据源编号
     * @param sourceRevision 来源修订号
     * @param targetRevision 目标修订号
     * @param sourceProduct 来源产品
     * @param targetProduct 目标产品
     * @param sourceSchema 来源结构
     * @param targetSchema 目标结构
     * @param planHash 计划摘要
     * @param mode 处理模式
     * @param termination 终止
     * @param manifestComplete 清单完整
     * @param totalTables 总量表
     * @param successfulTables 成功表
     * @param failedTables 失败表
     * @param tables 表
     */
    public SyncBatchEvidenceDTO {
        tables = tables == null ? List.of() : List.copyOf(tables);
    }

    public record Table(String sourceSchema, String sourceTable, String targetSchema, String targetTable,
                        Long rows, boolean successful, SyncTableTransferDTO transfer) {

        /**
         * 创建表实例。
         *
         * @param sourceSchema 来源结构
         * @param sourceTable 来源表
         * @param targetSchema 目标结构
         * @param targetTable 目标表
         * @param rows 待处理的行数据
         * @param successful 成功
         */
        public Table(String sourceSchema, String sourceTable, String targetSchema, String targetTable,
                Long rows, boolean successful) {
            this(sourceSchema, sourceTable, targetSchema, targetTable, rows, successful, null);
        }
    }
}
