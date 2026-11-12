package cn.iocoder.yudao.module.modeling.controller.admin.indicator.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "管理后台 - 复合指标公式校验结果")
public class IndicatorFormulaValidateRespVO {

    @Schema(description = "是否可保存")
    private Boolean valid;

    @Schema(description = "不可保存时的原因")
    private String message;

    @Schema(description = "引用替换为规范编码后的公式")
    private String normalizedFormula;

    @Schema(description = "直接引用的指标编码，按首次出现顺序")
    private List<String> references;

    @Schema(description = "依赖树（直接引用及其下级）")
    private List<IndicatorDependencyNodeRespVO> dependencies;
}
