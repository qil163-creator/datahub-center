package cn.iocoder.yudao.module.modeling.controller.admin.indicator.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.*;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "管理后台 - 指标模型分页请求")
public class IndicatorModelPageReqVO extends PageParam {

    @Size(max = 100)
    private String keyword;

    @Pattern(regexp = "ATOMIC|DERIVED|COMPOSITE")
    private String indicatorType;

    @Pattern(regexp = "DRAFT|ACTIVE")
    private String status;

    private Long dataDomainId;

    private Long subjectDomainId;

    private Long sourceModelId;

    private Long baseIndicatorId;
}

