package cn.iocoder.yudao.module.governance.controller.admin.qualityrule.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 稽查规则独立维度 Response VO")
@Data
public class AuditRuleDimensionRespVO {

    @Schema(description = "稽查维度编号", example = "1")
    private Long id;

    @Schema(description = "稽查维度名称", example = "一致性")
    private String name;

    @Schema(description = "稽查维度编码", example = "D01")
    private String code;

    @Schema(description = "状态：0-启用 1-停用", example = "0")
    private Short status;

    @Schema(description = "显示顺序", example = "0")
    private Integer sort;

    @Schema(description = "备注")
    private String remark;
}
