package cn.iocoder.yudao.module.asset.controller.admin.standardassociation.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "管理后台 - 标准关联资产分页请求")
public class StandardAssociationPageReqVO extends PageParam {

    @NotNull
    @Pattern(regexp = "element|codeset")
    @Schema(description = "标准种类", requiredMode = Schema.RequiredMode.REQUIRED)
    private String kind;

    @NotNull
    @Positive
    @Schema(description = "数据元或代码表编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer id;
}
