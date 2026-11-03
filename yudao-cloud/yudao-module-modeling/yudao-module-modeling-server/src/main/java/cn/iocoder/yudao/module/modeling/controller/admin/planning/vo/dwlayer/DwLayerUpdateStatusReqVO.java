package cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.dwlayer;

import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.common.validation.InEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "管理后台 - 数仓分层更新状态 Request VO")
@Data
public class DwLayerUpdateStatusReqVO {

    @Schema(description = "主键", requiredMode = Schema.RequiredMode.REQUIRED, example = "1024")
    @NotNull(message = "主键不能为空")
    private Long id;

    @Schema(description = "状态：0停用 1启用", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    @NotNull(message = "状态不能为空")
    @InEnum(value = CommonStatusEnum.class, message = "状态必须是 {value}")
    private Integer status;

}
