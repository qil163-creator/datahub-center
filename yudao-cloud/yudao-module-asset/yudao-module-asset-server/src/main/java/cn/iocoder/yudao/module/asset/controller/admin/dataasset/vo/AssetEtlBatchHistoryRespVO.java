package cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "管理后台 - 资产批次关联历史；历史记录不代表当前可消费")
public record AssetEtlBatchHistoryRespVO(List<Item> list, Integer nextBeforeRevision, boolean hasMore) {

    @Data
    @Schema(description = "资产批次关联修订")
    public static class Item {

        private Long id;

        private Long assetId;

        private Integer revision;

        private Long batchId;

        private Long recordId;

        private String payloadHash;

        private Long databaseId;

        private String schemaName;

        private String tableName;

        private Long actorId;

        private String reason;

        private LocalDateTime createTime;
    }
}
