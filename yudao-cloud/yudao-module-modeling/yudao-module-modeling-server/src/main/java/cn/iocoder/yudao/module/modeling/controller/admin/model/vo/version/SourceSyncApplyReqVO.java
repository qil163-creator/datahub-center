package cn.iocoder.yudao.module.modeling.controller.admin.model.vo.version;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

@Schema(description = "管理后台 - 贴源表结构同步执行 Request VO")
@Data
public class SourceSyncApplyReqVO {

    @Schema(description = "逻辑模型编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "模型编号不能为空")
    private Long modelId;

    @Schema(description = "预览时的模型修订号", requiredMode = Schema.RequiredMode.REQUIRED, example = "8")
    @NotNull(message = "模型修订号不能为空")
    @PositiveOrZero(message = "模型修订号不能为负数")
    private Long expectedRevision;
}
