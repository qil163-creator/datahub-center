package cn.iocoder.yudao.module.governance.controller.admin.standardcodeset.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;
import cn.iocoder.yudao.framework.common.pojo.PageParam;

@Data
@EqualsAndHashCode(callSuper = true)
public class StandardCodeMappingPageReqVO extends PageParam {

    @jakarta.validation.constraints.NotNull
    private Integer codeSetId;

    private String originalValue;

    @jakarta.validation.constraints.Pattern(regexp = "id|createTime")
    private String sortField = "createTime";

    @jakarta.validation.constraints.Pattern(regexp = "asc|desc")
    private String sortOrder = "desc";
}
