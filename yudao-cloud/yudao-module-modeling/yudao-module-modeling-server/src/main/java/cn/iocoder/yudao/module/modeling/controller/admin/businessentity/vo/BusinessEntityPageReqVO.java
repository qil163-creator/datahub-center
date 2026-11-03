package cn.iocoder.yudao.module.modeling.controller.admin.businessentity.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class BusinessEntityPageReqVO extends PageParam {

    @Positive
    private Long dataDomainId;

    @Positive
    private Long subjectDomainId;

    @Size(max = 64)
    private String keyword;

    @Pattern(regexp = "OBJECT|ACTIVITY")
    private String entityType;

    @Pattern(regexp = "NORMAL|HIERARCHICAL|EVENT|PROCESS")
    private String entitySubtype;

    @Size(max = 4)
    private java.util.List<@NotNull @Pattern(regexp = "NORMAL|HIERARCHICAL|EVENT|PROCESS") String> entitySubtypes;

    @Min(0)
    @Max(1)
    private Integer status;

    @Positive
    private Long leaderUserId;

    @Pattern(regexp = "asc|desc")
    private String sortOrder = "desc";
}
