package cn.iocoder.yudao.module.asset.api.sensitive.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AssetSensitiveFactRespDTO {

    private Long id;

    private String sourceRecordId;

    private Long assetId;

    private String assetName;

    private String assetDescription;

    private String tableName;

    private String columnName;

    private Long categoryId;

    private Long ruleId;

    private String levelName;

    private String levelColor;

    private Integer status;

    private Integer revision;

    private String creatorName;

    private String updaterName;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
