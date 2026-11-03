package cn.iocoder.yudao.module.modeling.controller.admin.conceptmodel.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class ConceptModelPageReqVO extends PageParam {

    @Size(max = 100)
    private String keyword;

    @Positive
    private Long dataDomainId;

    @Positive
    private Long subjectDomainId;

    @Pattern(regexp = "DRAFT|ACTIVE")
    private String status;
}
