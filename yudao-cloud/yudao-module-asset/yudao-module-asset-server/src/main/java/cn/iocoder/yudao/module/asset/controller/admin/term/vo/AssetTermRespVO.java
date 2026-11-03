package cn.iocoder.yudao.module.asset.controller.admin.term.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 供资产字段引用的治理业务术语 Response VO")
@Data
public class AssetTermRespVO {

    @Schema(description = "治理业务术语编号")
    private Long id;

    private String name;

    private String description;

    private Integer status;
}
