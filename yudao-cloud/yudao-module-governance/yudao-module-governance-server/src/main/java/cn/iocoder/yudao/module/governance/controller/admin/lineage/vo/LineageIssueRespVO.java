package cn.iocoder.yudao.module.governance.controller.admin.lineage.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 血缘解析问题 Response VO")
@Data
public class LineageIssueRespVO {

    @Schema(description = "问题编码")
    private String code;

    @Schema(description = "问题描述")
    private String message;

    @Schema(description = "所在语句序号（可空）")
    private Integer statementIndex;

}
