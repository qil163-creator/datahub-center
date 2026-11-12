package cn.iocoder.yudao.module.modeling.controller.admin.indicator.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "管理后台 - 指标依赖树节点")
public class IndicatorDependencyNodeRespVO {

    private Long id;

    private String code;

    private String name;

    @Schema(description = "ATOMIC / DERIVED / COMPOSITE")
    private String indicatorType;

    private String status;

    @Schema(description = "复合指标公式")
    private String formula;

    @Schema(description = "派生指标统计周期")
    private String timePeriod;

    private List<IndicatorDependencyNodeRespVO> children;
}
