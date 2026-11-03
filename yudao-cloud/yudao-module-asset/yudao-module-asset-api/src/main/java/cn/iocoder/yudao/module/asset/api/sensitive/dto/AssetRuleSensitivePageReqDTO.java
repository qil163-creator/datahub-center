package cn.iocoder.yudao.module.asset.api.sensitive.dto;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import jakarta.validation.constraints.*;
import lombok.*;

@Data
@EqualsAndHashCode(callSuper = true)
public class AssetRuleSensitivePageReqDTO extends PageParam {

    @NotNull
    @Positive
    private Long ruleId;

    @Pattern(regexp = "id|createTime")
    private String sortField = "createTime";

    @Pattern(regexp = "asc|desc")
    private String sortOrder = "desc";
}
