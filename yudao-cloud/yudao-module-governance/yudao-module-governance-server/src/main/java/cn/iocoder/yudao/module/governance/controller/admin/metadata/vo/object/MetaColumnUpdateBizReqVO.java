package cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.object;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 字段元数据业务/管理属性维护 Request VO
 *
 * 仅允许维护平台侧属性（采集永不覆盖）；每处修改记录 change_source=手工 的变更明细
 *
 * @author Li
 */
@Schema(description = "管理后台 - 字段元数据业务属性维护 Request VO")
@Data
public class MetaColumnUpdateBizReqVO {

    @Schema(description = "字段元数据编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "字段元数据编号不能为空")
    private Long id;

    @Schema(description = "读取详情时的修订号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "修订号不能为空，请重新加载详情")
    @Min(value = 0, message = "修订号不能小于零")
    private Integer revision;

    @Schema(description = "平台注释", example = "流域编码（按 SL 249 标准）")
    private String customComment;

    @Schema(description = "业务名称/中文名称", example = "流域编码")
    private String businessName;

    @Schema(description = "业务定义")
    private String businessDefinition;

    @Schema(description = "度量单位", example = "米")
    private String measuringUnit;

    @Schema(description = "标准数据元编号（关联标准，置空表示解除）", example = "1")
    private Integer standardElementId;

    @Schema(description = "安全分级编号", example = "2")
    private Long safetyLevelId;

    @Schema(description = "敏感/加密标注：0-否 1-是", example = "0")
    private Integer sensitiveFlag;

    @Schema(description = "备注")
    private String remark;

}
