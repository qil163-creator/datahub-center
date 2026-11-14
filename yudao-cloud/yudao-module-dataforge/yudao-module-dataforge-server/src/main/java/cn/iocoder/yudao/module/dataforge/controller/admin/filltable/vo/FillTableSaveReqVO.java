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

    @Schema(description = "合并策略：REPLACE_SCOPE-范围替换（默认） APPEND-追加 UPSERT-更新插入", example = "REPLACE_SCOPE")
    @jakarta.validation.constraints.Pattern(regexp = "^(|REPLACE_SCOPE|APPEND|UPSERT)$", message = "合并策略不合法")
    private String mergeStrategy;

    @Schema(description = "UPSERT 业务键字段码（英文逗号分隔，需为必填字段）", example = "student_no")
    @jakarta.validation.constraints.Size(max = 500, message = "业务键配置过长")
    private String businessKeyFields;

    @Schema(description = "录入页面冻结的前几列（0~10，0 表示不冻结）", example = "2")
    @jakarta.validation.constraints.Min(value = 0, message = "冻结列数不能小于 0")
    @jakarta.validation.constraints.Max(value = 10, message = "冻结列数不能大于 10")
    private Integer freezeColumnCount;

    @Schema(description = "多级汇总方式：DETAIL-明细汇集 GROUP-分组合计（默认明细汇集）", example = "DETAIL")
    @jakarta.validation.constraints.Pattern(regexp = "^(|DETAIL|GROUP)$", message = "多级汇总方式不合法")
    private String rollupMode;

    @Schema(description = "分组合计的分组字段码，英文逗号分隔", example = "region,category")
    @jakarta.validation.constraints.Size(max = 1000, message = "分组字段配置过长")
    private String rollupGroupFields;

}
