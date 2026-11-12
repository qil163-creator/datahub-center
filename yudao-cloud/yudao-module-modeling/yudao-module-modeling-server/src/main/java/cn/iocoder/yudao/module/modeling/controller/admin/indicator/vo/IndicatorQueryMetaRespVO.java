package cn.iocoder.yudao.module.modeling.controller.admin.indicator.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "管理后台 - 指标查询可选维度")
public class IndicatorQueryMetaRespVO {

    @Schema(description = "全部参与计算的基础指标的来源物理表共同拥有的字段")
    private List<IndicatorSourceFieldRespVO> dimensions;

    @Schema(description = "是否全部基础指标都配置了统计时间字段，可按时间范围与粒度查询")
    private Boolean timeSupported;

    @Schema(description = "派生指标的统计周期（去重）；查询粒度必须与之一致")
    private List<String> derivedPeriods;
}
