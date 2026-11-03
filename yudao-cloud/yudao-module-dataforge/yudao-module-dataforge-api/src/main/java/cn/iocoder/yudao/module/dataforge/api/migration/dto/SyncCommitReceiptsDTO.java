package cn.iocoder.yudao.module.dataforge.api.migration.dto;

import java.util.List;

/** 诊断用原生事务回执，缺少恢复代际、原生对象/范围和读取绑定，不能直接将复检改为 PASS。 */
public record SyncCommitReceiptsDTO(Integer schemaVersion, Long totalBatches, Long witnessedBatches,
                                    Boolean truncated, List<Entry> receipts, Coverage coverage) {

    /**
     * 创建同步提交回执实例。
     *
     * @param schemaVersion 结构版本
     * @param totalBatches 总量批次
     * @param witnessedBatches 已观测批次
     * @param truncated 截断
     * @param receipts 回执
     */
    public SyncCommitReceiptsDTO(Integer schemaVersion, Long totalBatches, Long witnessedBatches,
            Boolean truncated, List<Entry> receipts) {
        this(schemaVersion, totalBatches, witnessedBatches, truncated, receipts, null);
    }

    /**
     * 创建同步提交回执实例。
     *
     * @param schemaVersion 结构版本
     * @param totalBatches 总量批次
     * @param witnessedBatches 已观测批次
     * @param truncated 截断
     * @param receipts 回执
     * @param coverage 覆盖范围
     */
    public SyncCommitReceiptsDTO {
        if (receipts != null) {
            receipts = List.copyOf(receipts);
        }
    }

    public record Entry(Long batch, Integer schemaVersion, String protocol, String transactionId,
                        String routeFingerprint, String relationFingerprint) {

        /**
         * 创建条目实例。
         *
         * @param batch 目标批次信息
         * @param schemaVersion 结构版本
         * @param protocol 协议
         * @param transactionId 事务编号
         * @param routeFingerprint 路由指纹
         */
        public Entry(Long batch, Integer schemaVersion, String protocol, String transactionId,
                String routeFingerprint) {
            this(batch, schemaVersion, protocol, transactionId, routeFingerprint, null);
        }
    }

    /** 所有已确认批次的有界摘要；truncated 仍只描述样本清单，不因存在摘要改写。 */
    public record Coverage(Integer schemaVersion, String protocol, Long batchCount, Long acknowledgedRows,
                           String firstTransactionId, String lastTransactionId, String routeFingerprint, String digest,
                           String relationFingerprint) {

        /**
         * 创建覆盖范围实例。
         *
         * @param schemaVersion 结构版本
         * @param protocol 协议
         * @param batchCount 批次数量
         * @param acknowledgedRows 已确认行
         * @param firstTransactionId 首个事务编号
         * @param lastTransactionId 最后事务编号
         * @param routeFingerprint 路由指纹
         * @param digest 摘要
         */
        public Coverage(Integer schemaVersion, String protocol, Long batchCount, Long acknowledgedRows,
                String firstTransactionId, String lastTransactionId, String routeFingerprint, String digest) {
            this(schemaVersion, protocol, batchCount, acknowledgedRows, firstTransactionId, lastTransactionId,
                    routeFingerprint, digest, null);
        }
    }
}
