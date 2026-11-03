package cn.iocoder.yudao.module.dataforge.controller.admin.filltable.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "管理后台 - 填报表字段新增/修改 Request VO")
@Data
public class FillTableColumnSaveReqVO {

    @Schema(description = "主键id", example = "1")
    private Long id;

    @Schema(description = "填报表id", requiredMode = Schema.RequiredMode.REQUIRED, example = "1024")
    @NotNull(message = "填报表不能为空")
    private Long tableId;

    @Schema(description = "物理字段名", requiredMode = Schema.RequiredMode.REQUIRED, example = "id_card")
    @NotEmpty(message = "字段名不能为空")
    private String name;

    @Schema(description = "中文名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "身份证号")
    @NotEmpty(message = "中文名称不能为空")
    private String cnName;

    @Schema(description = "物理列注释", example = "身份证号")
    private String commentStr;

    @Schema(description = "数据类型：1-小数 2-字符串 3-日期 5-整数 6-长整型 9-日期时间 10-时间",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "2")
    @NotNull(message = "数据类型不能为空")
    private Integer fieldType;

    @Schema(description = "录入类型：1-手工录入 2-选择", example = "1")
    private Integer fillType;

    @Schema(description = "选择值（英文逗号分隔）", example = "男,女")
    private String fillValues;

    @Schema(description = "录入是否隐藏：0-否 1-是", example = "0")
    private Integer fillHide;

    @Schema(description = "字段长度", example = "50")
    private Integer fieldLength;

    @Schema(description = "小数位数", example = "2")
    private Integer fieldScale;

    @Schema(description = "是否可为空：0-否 1-是", example = "1")
    private Integer nullable;

    @Schema(description = "是否查询条件：0-否 1-是", example = "0")
    private Integer ifQuery;

    @Schema(description = "是否主键：0-否 1-是", example = "0")
    private Integer pk;

    @Schema(description = "主键类型：1-UUID 2-自行录入", example = "1")
    private Integer pkType;

    @Schema(description = "字段顺序", example = "1")
    private Integer sort;

}
