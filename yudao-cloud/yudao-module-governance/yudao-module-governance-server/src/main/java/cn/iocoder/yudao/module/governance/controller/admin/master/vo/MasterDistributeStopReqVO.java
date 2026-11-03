package cn.iocoder.yudao.module.governance.controller.admin.master.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "管理后台 - 停止指定主数据派发运行，不改变发布及排期")
@Data
public class MasterDistributeStopReqVO {

    @Schema(description = "执行记录编号，不能使用任务编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long recordId;

    @Schema(description = "停止原因，禁止填写凭据或业务载荷", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 500)
    private String reason;
}
