package cn.iocoder.yudao.module.modeling.controller.admin.businesscondition.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.*;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "管理后台 - 业务限定分页请求")
public class BusinessConditionPageReqVO extends PageParam {

    @Size(max = 100)
    private String keyword;

    @Pattern(regexp = "DRAFT|ACTIVE")
    private String status;

    @Positive
    private Long dataDomainId;

    @Positive
    private Long subjectDomainId;

    @Positive
    private Long sourceModelId;

    @Pattern(regexp = "BASIC|COMPOSITE")
    private String configType;

    @Size(max = 100)
    private String dataFreshness;
}
