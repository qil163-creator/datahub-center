package cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.object;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "管理后台 - 确认指定结构版本定版；业务信息以确认时为准")
public class MetaTableFinalizeReqVO extends MetaTableStateReqVO {

    @NotNull
    @Schema(description = "本表结构快照编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long snapshotId;
}
