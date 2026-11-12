package cn.iocoder.yudao.module.modeling.controller.admin.indicator.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

@Data
@Schema(description = "管理后台 - 复合指标公式校验请求")
public class IndicatorFormulaValidateReqVO {

    @Schema(description = "正在编辑的复合指标编号，新建时为空")
    @Positive
    private Long id;

    @Schema(description = "正在编辑的复合指标编码，用于检测自引用")
    @Size(max = 100)
    private String code;

    @Schema(description = "所属数据域", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long dataDomainId;

    @Schema(description = "公式", requiredMode = Schema.RequiredMode.REQUIRED, example = "{gmv} / {order_cnt} * 100")
    @NotBlank
    @Size(max = 2000)
    private String formula;
}
