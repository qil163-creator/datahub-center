package cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.object;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "管理后台 - 元数据状态变更")
public class MetaTableStateReqVO {

    @NotNull
    @Schema(description = "表编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;

    @NotNull
    @Min(0)
    @Schema(description = "读取时的状态修订号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer stateRevision;
}
