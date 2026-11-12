package cn.iocoder.yudao.module.governance.controller.admin.profile.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "管理后台 - 发起数据探查 Request VO")
@Data
public class ProfileRunCreateReqVO {

    @Schema(description = "元数据表编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1024")
    @NotNull(message = "元数据表编号不能为空")
    private Long metaTableId;

}
