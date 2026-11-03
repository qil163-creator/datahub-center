package cn.iocoder.yudao.module.dataforge.api.migration.dto;

import java.util.List;

/**
 * 有界诊断事实，不是到数证明。只含已授权目标表，不返回源表、其他目标、配置、日志或凭据。
 * windowTruncated/unscopedRunsPresent=false 也不证明没有其他写入者或更早批次。
 */
public record SyncBatchObservationDTO(Integer schemaVersion, Long databaseId, String configurationRevision,
                                      String product, String schema, String table, String tableReference,
                                      boolean windowTruncated, boolean unscopedRunsPresent, List<Batch> batches) {

    /**
     * 创建同步批次观测实例。
     *
     * @param schemaVersion 结构版本
     * @param databaseId 数据源编号
     * @param configurationRevision 连接配置修订号
     * @param product 产品
     * @param schema 结构
     * @param table 表
     * @param tableReference 表引用
     * @param windowTruncated 窗口截断
     * @param unscopedRunsPresent 未限定范围运行展示
     * @param batches 批次
     */
    public SyncBatchObservationDTO {
        batches = batches == null ? List.of() : List.copyOf(batches);
    }

    public record Batch(Long runId, Integer attempt, Long rowVersion, Integer runStatus,
                        boolean occupiesSlot, boolean reviewRequired, boolean stopRequested,
                        String effectState, String targetRevision, String mode, String termination,
                        boolean manifestComplete, boolean tableSuccessful, Long rows, Long startPosition,
                        SyncTableTransferDTO transfer) {

        /**
         * 创建批次实例。
         *
         * @param runId 运行编号
         * @param attempt 当前部署尝试
         * @param rowVersion 行版本
         * @param runStatus 运行状态
         * @param occupiesSlot 占用占位
         * @param reviewRequired 复核必需
         * @param stopRequested 停止已请求
         * @param effectState 副作用状态
         * @param targetRevision 目标修订号
         * @param mode 处理模式
         * @param termination 终止
         * @param manifestComplete 清单完整
         * @param tableSuccessful 表成功
         * @param rows 待处理的行数据
         * @param startPosition 开始位置
         */
        public Batch(Long runId, Integer attempt, Long rowVersion, Integer runStatus,
                boolean occupiesSlot, boolean reviewRequired, boolean stopRequested,
                String effectState, String targetRevision, String mode, String termination,
                boolean manifestComplete, boolean tableSuccessful, Long rows, Long startPosition) {
            this(runId, attempt, rowVersion, runStatus, occupiesSlot, reviewRequired, stopRequested,
                    effectState, targetRevision, mode, termination, manifestComplete, tableSuccessful, rows,
                    startPosition, null);
        }

        /** 兼容旧调用方和旧 JSON；缺少顺序不得推导为边界后的新批次。 */
        public Batch(Long runId, Integer attempt, Long rowVersion, Integer runStatus,
                boolean occupiesSlot, boolean reviewRequired, boolean stopRequested,
                String effectState, String targetRevision, String mode, String termination,
                boolean manifestComplete, boolean tableSuccessful, Long rows) {
            this(runId, attempt, rowVersion, runStatus, occupiesSlot, reviewRequired, stopRequested,
                    effectState, targetRevision, mode, termination, manifestComplete, tableSuccessful, rows, null);
        }
    }
}
