package cn.iocoder.yudao.module.asset.controller.admin.sensitive.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.*;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "管理后台 - 敏感清单查询")
public class AssetSensitivePageReqVO extends PageParam {

    @Size(max = 200)
    private String keyword;

    @Pattern(regexp = "(?:dir|category):[1-9][0-9]*")
    private String categoryKey;

    @Min(0)
    @Max(1)
    private Integer status;

    @Positive
    private Long assetId;

    @Positive
    private Long ruleId;

    @Pattern(regexp = "id|createTime")
    private String sortField = "id";

    @Pattern(regexp = "asc|desc")
    private String sortOrder = "desc";
}
