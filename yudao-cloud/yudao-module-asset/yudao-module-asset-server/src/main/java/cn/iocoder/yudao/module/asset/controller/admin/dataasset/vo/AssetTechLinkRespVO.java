package cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "政务资源技术关联")
@Data
public class AssetTechLinkRespVO {

    private Long id;

    private Long assetId;

    private String providerModule;

    private String resourceUrn;

    private Long datasourceId;

    private String schemaName;

    private String objectName;

    private Long metaTableId;

    private Long snapshotId;

    private Integer primaryFlag;

    private LocalDateTime createTime;

}
