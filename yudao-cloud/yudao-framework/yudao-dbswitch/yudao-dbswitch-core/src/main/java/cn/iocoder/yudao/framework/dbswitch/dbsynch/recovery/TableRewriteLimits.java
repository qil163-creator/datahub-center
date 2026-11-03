package cn.iocoder.yudao.framework.dbswitch.dbsynch.recovery;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;

import java.util.Objects;

/** 显式整表恢复容量，独立于 JDBC 批次大小。前后两个映像均计入。 */
public record TableRewriteLimits(BatchLimits page, int maxRows, int maxPages, long maxMaterialBytes) {

    public static final long MAX_MATERIAL_BYTES = 256L * 1024 * 1024;

    /**
     * 校验构造参数并初始化 {@code TableRewriteLimits}。
     */
    public TableRewriteLimits {
        Objects.requireNonNull(page);
        if (maxRows < 1 || maxRows > 10_000_000 || maxPages < 1 || maxPages > 65536
                || page.maxBatchBytes() < 64 || page.maxBatchBytes() > 64L * 1024 * 1024
                || maxMaterialBytes < page.maxBatchBytes() || maxMaterialBytes > MAX_MATERIAL_BYTES) {
            throw new IllegalArgumentException("Invalid table recovery capacity");
        }
    }
}
