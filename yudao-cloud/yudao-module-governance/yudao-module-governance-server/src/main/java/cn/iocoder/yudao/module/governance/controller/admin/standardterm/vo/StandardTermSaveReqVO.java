package cn.iocoder.yudao.module.governance.controller.admin.standardterm.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "管理后台 - 业务术语新增/修改 Request VO")
@Data
public class StandardTermSaveReqVO {

    @Schema(description = "主键", example = "1")
    private Long id;

    @Schema(description = "术语名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "客户")
    @NotBlank(message = "术语名称不能为空")
    private String name;

    @Schema(description = "英文名称", example = "customer")
    private String engName;

    @Schema(description = "别名/同义词，逗号分隔", example = "顾客,买家")
    private String alias;

    @Schema(description = "业务定义", requiredMode = Schema.RequiredMode.REQUIRED, example = "与本企业发生交易的自然人或法人")
    @NotBlank(message = "业务定义不能为空")
    private String definition;

    @Schema(description = "业务术语类目编号", example = "1")
    private Long termCategoryId;

    @JsonIgnore
    private boolean termCategorySpecified;

    @JsonSetter("termCategoryId")
    public void setTermCategoryId(Long termCategoryId) {
        this.termCategoryId = termCategoryId;
        this.termCategorySpecified = true;
    }

    @Schema(description = "所属类目编号", example = "1")
    private Long categoryId;

    @Schema(description = "关联标准数据元编号", example = "1024")
    private Integer relatedElementId;

    @Schema(description = "负责人用户编号", example = "1")
    private Long ownerUserId;

    @Schema(description = "数据管家用户编号", example = "2")
    private Long stewardUserId;

    @Schema(description = "状态", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    @NotNull(message = "状态不能为空")
    private Short status;

    @Schema(description = "备注")
    private String remark;

}
