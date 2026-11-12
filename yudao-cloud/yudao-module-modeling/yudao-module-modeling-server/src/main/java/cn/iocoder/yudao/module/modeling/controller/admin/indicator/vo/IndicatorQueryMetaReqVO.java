package cn.iocoder.yudao.module.modeling.controller.admin.indicator.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "管理后台 - 指标查询可选维度请求")
public class IndicatorQueryMetaReqVO {

    @NotEmpty
    @Size(max = 10)
    private List<@NotNull @Positive Long> indicatorIds;
}
