package cn.iocoder.yudao.module.supply.controller.admin.app.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "管理后台 - ETL 授权审计历史；按编号倒序，不代表当前有效授权")
public record SupplyEtlGrantAuditPageRespVO(List<Item> list, Long nextBeforeId, boolean hasMore) {

    @Data
    @Schema(description = "ETL 授权审计事件")
    public static class Item {

        private Long id;

        private Long grantId;

        private Long appId;

        private Long apiId;

        private Long actorId;

        private Long revision;

        private String action;

        private Snapshot before;

        private Snapshot after;

        private String reason;

        private String traceId;

        private String result;

        private LocalDateTime createTime;
    }

    @Data
    @Schema(description = "授权事件的批准与控制摘要，不含密钥或数据内容")
    public static class Snapshot {

        private String sourceType;

        private String sourceId;

        private Long permissionUserId;

        private String approvedContractHash;

        private String purpose;

        private String apiVersion;

        private Integer grantStatus;

        private Boolean aggregateDisabled;

        private Long grantRevision;

        private Long aggregateRevision;

        private LocalDateTime validFrom;

        private LocalDateTime validTo;

        private Integer foreverFlag;

        private Integer applicationStatus;
    }
}
