package cn.iocoder.yudao.module.dataforge.controller.admin.filltable.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "管理后台 - 填报表新增/修改 Request VO")
@Data
public class FillTableSaveReqVO {

    @Schema(description = "主键id", example = "1024")
    private Long id;

    @Schema(description = "物理表英文名", requiredMode = Schema.RequiredMode.REQUIRED, example = "t_population")
    @NotEmpty(message = "表英文名不能为空")
    private String tableEngName;

    @Schema(description = "表中文名", requiredMode = Schema.RequiredMode.REQUIRED, example = "人口信息表")
    @NotEmpty(message = "表中文名不能为空")
    private String tableCnName;

    @Schema(description = "描述")
    private String description;

    @Schema(description = "目标数据源id", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "目标数据源不能为空")
    private Long databaseId;

    @Schema(description = "单位id", example = "100")
    private Long orgId;

}
