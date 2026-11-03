package cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.datadomain;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import cn.iocoder.yudao.framework.common.validation.InEnum;
import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;

@Schema(description = "管理后台 - 数据板块状态更新 Request VO")
@Data
public class DataDomainUpdateStatusReqVO {

    @Schema(description = "主键", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "编号不能为空")
    private Long id;

    @Schema(description = "状态：0-启用 1-停用", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    @NotNull(message = "状态不能为空")
    @InEnum(value = CommonStatusEnum.class, message = "状态必须是 {value}")
    private Integer status;

}
