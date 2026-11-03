package cn.iocoder.yudao.module.asset.controller.admin.standardassociation.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Schema(description = "管理后台 - 标准关联资产响应")
public class StandardAssociationRespVO {

    private Long id;

    private String assetName;

    private String description;

    private String tableName;

    private List<String> columnNames;

    private String creatorName;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
