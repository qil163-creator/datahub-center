package cn.iocoder.yudao.module.supply.controller.admin.dataapi.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "管理后台 - API 回滚发布 Request VO")
@Data
public class DataApiRollbackReqVO {

    @Schema(description = "API 编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "API 编号不能为空")
    private Long id;

    @Schema(description = "目标快照编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "发布快照不能为空")
    private Long releaseId;

    @Schema(description = "期望的行版本，用于并发保护")
    @NotNull(message = "请刷新后提交当前版本")
    @jakarta.validation.constraints.Min(0)
    private Integer expectedRowVersion;

}
