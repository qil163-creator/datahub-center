package cn.iocoder.yudao.module.governance.api.metadata.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 表元数据 RPC DTO，面向资产编目等跨模块场景。
 */
@Schema(description = "RPC 服务 - 表元数据信息")
@Data
public class MetadataTableRespDTO {

    private Long id;

    private String resourceUrn;

    private Long dbId;

    private String dbName;

    private String schemaName;

    private Long datasourceId;

    private String dbType;

    private String sourceSystemName;

    private String tableName;

    private String tableType;

    private String sourceComment;

    private String customComment;

    private String businessName;

    private String businessDefinition;

    private String layer;

    private Integer columnCount;

    private Long rowCount;

    private Long storageSize;

    private Long subjectDomainId;

    private String subjectDomainName;

    private Long techLeaderUserId;

    private String techLeaderPhone;

    private Long businessLeaderUserId;

    private String businessLeaderPhone;

    private Long ownerDeptId;

    private Integer objectStatus;

    private Integer publishStatus;

    private String publishedVersion;

    private Long publishedSnapshotId;

    private LocalDateTime publishTime;

    private BigDecimal healthScore;

    private Integer catalogLinked;

    private String catalogItemUrn;

    private LocalDateTime createTime;

}
