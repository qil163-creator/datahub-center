package cn.iocoder.yudao.module.governance.controller.admin.standardassociation.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
@Schema(description = "管理后台 - 标准落标字段检查请求")
public class StandardAssociationCheckReqVO {

    @NotNull
    @Pattern(regexp = "element|codeset")
    @Schema(description = "标准种类", requiredMode = Schema.RequiredMode.REQUIRED)
    private String kind;

    @NotNull
    @Positive
    @Schema(description = "数据元或代码表编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer id;
}
