package cn.iocoder.yudao.module.asset.controller.admin.sensitive.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "管理后台 - 敏感清单记录")
public class AssetSensitiveRespVO {

    private Long id;

    private String sourceRecordId;

    private Long assetId;

    private String assetName;

    private String assetDescription;

    private String tableName;

    private Long columnId;

    private String columnName;

    private Long categoryId;

    private Long ruleId;

    private String classificationName;

    private String levelName;

    private String levelColor;

    private String ruleName;

    private String description;

    private Integer status;

    private Integer revision;

    private String sourceCreatorName;

    private String sourceUpdaterName;

    private String creatorName;

    private String updaterName;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
