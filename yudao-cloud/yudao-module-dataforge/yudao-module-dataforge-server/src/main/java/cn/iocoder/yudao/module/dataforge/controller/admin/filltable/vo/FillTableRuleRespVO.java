package cn.iocoder.yudao.module.dataforge.controller.admin.filltable.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 数据填报-填报表校验规则 Response VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 填报表校验规则 Response VO")
@Data
public class FillTableRuleRespVO {

    @Schema(description = "编号", example = "1")
    private Long id;

    @Schema(description = "填报表编号", example = "1")
    private Long tableId;

    @Schema(description = "规则名称", example = "分项之和等于合计")
    private String name;

    @Schema(description = "范围：ROW-行级 TABLE-表级 PERIOD-跨期", example = "ROW")
    private String scope;

    @Schema(description = "规则表达式", example = "total = item_a + item_b")
    private String expression;

    @Schema(description = "违反时的提示语", example = "合计应等于各分项之和")
    private String message;

    @Schema(description = "级别：1-错误 2-警告", example = "1")
    private Integer severity;

    @Schema(description = "问题定位字段码", example = "total")
    private String fieldCode;

    @Schema(description = "排序", example = "0")
    private Integer sort;

    @Schema(description = "是否启用：0-否 1-是", example = "1")
    private Integer enabled;

}
