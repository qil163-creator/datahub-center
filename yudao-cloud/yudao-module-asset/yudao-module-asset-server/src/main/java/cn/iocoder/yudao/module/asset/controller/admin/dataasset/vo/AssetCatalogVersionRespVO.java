package cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "政务目录业务版本")
@Data
public class AssetCatalogVersionRespVO {

    private Long id;

    private Long assetId;

    private Integer versionNo;

    private String contentHash;

    private String contentJson;

    private Long techSnapshotId;

    private Integer status;

    private Long submitUserId;

    private LocalDateTime submitTime;

    private Long checkUserId;

    private LocalDateTime checkTime;

    private String checkNote;

    private LocalDateTime publishTime;

    private LocalDateTime createTime;

}
