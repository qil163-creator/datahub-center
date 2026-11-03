package cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 在线填报-填报职责转派 Request VO（填报人本人发起）
 *
 * @author Li
 */
@Schema(description = "管理后台 - 填报转派 Request VO")
@Data
public class FillWorkItemTransferReqVO {

    @Schema(description = "工作项编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "工作项编号不能为空")
    private Long workItemId;

    @Schema(description = "接收人用户编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1002")
    @NotNull(message = "接收人不能为空")
    private Long targetUserId;

    @Schema(description = "转派原因", example = "本人出差，转派同科室王五")
    private String reason;

}
