package cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo;

import cn.iocoder.yudao.module.asset.controller.admin.assettag.vo.AssetTagRespVO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "管理后台 - 数据资产 Response VO")
@Data
public class DataAssetRespVO {

    private Long id;

    private String assetCode;

    private String catalogItemUrn;

    private String assetName;

    private Integer assetType;

    private Long categoryId;

    private String categoryName;

    private Long metaTableId;

    private String resourceUrn;

    private Long publishedSnapshotId;

    private String publishedVersion;

    private Long datasourceId;

    private String sourceSystemName;

    private String dbName;

    private String schemaName;

    private String tableName;

    private String tableType;

    private String layer;

    private Long subjectDomainId;

    private String subjectDomainName;

    private Long rowCount;

    private Integer fieldCount;

    private Long storageSize;

    private BigDecimal healthScore;

    private String description;

    private AssetRegistrationConfigVO registrationConfig;

    private Integer status;

    private LocalDateTime publishTime;

    private String publisher;

    private Long techLeaderUserId;

    private String techLeaderPhone;

    private Long businessLeaderUserId;

    private String businessLeaderPhone;

    private Long ownerDeptId;

    private Long viewCount;

    private Integer shareAttribute;

    private Integer shareAttr;

    private String shareCondition;

    private String updateCycle;

    private String businessPurpose;

    private String contactName;

    private String contactPhone;

    private String remark;

    private String shareDenyReason;

    private Long providerDeptId;

    private Long sourceDeptId;

    private String dutyBasis;

    private String updateRule;

    private Long catalogRevision;

    private Long publishedCatalogVersionId;

    private Integer techAvailable;

    private String externalCode;

    private String sourceRecordId;

    private String fileName;

    private String fileType;

    private String filePath;

    private String mapDisplayScore;

    private Boolean retirementRequested;

    private String stopSupplyReason;

    private LocalDateTime stopSupplyTime;

    private Long pendingGrantRevocations;

    private String publicationNotice;

    private String creator;

    private String creatorName;

    private String updater;

    private String updaterName;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private List<AssetTagRespVO> tags;

    private Integer qualityTaskCount;

    @Schema(description = "技术负责人姓名")
    private String techLeaderName;

    @Schema(description = "业务负责人姓名")
    private String businessLeaderName;

    @Schema(description = "数据源（数据连接）名称")
    private String datasourceName;

    @Schema(description = "数据源产品类型")
    private String datasourceType;

    @Schema(description = "数据源主机/IP，由 JDBC 连接串解析")
    private String datasourceHost;

    @Schema(description = "当前元数据定版版本")
    private String currentMetadataVersion;

    @Schema(description = "当前元数据发布状态")
    private Integer currentMetadataPublishStatus;

    @Schema(description = "当前元数据定版是否与资产引用版本不同")
    private Boolean metadataDrifted;

}
