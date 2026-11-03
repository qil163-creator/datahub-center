package cn.iocoder.yudao.module.asset.controller.admin.assettag.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 资产标签 Response VO")
@Data
public class AssetTagRespVO {

    private Long id;

    private Integer revision;

    private String sourceRecordId;

    private String sourceCreatorName;

    private String sourceUpdaterName;

    private Integer sourceSortOrder;

    private LocalDateTime sourceCreateTime;

    private LocalDateTime sourceUpdateTime;

    private String name;

    private String category;

    private Long categoryId;

    private String categoryName;

    private String color;

    private String nearSynonyms;

    private String synonyms;

    private String description;

    private Integer status;

    private String remark;

    @Schema(description = "已打标的资产数量")
    private Long assetCount;

    private String creator;

    private String creatorName;

    private String updater;

    private String updaterName;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

}
