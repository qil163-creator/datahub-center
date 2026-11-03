package cn.iocoder.yudao.module.governance.controller.admin.whitelist.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class SafetyWhitelistWorkbenchPageReqVO extends PageParam {

    @Size(max = 50)
    private String name;

    @Positive
    private Long categoryId;

    @Min(0)
    @Max(1)
    private Short status;

    @Pattern(regexp = "id|createTime")
    private String sortField;

    @Pattern(regexp = "asc|desc")
    private String sortOrder;
}
