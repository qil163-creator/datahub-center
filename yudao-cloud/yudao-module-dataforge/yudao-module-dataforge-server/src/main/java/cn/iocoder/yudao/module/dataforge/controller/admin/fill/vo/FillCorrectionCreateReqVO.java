package cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 在线填报-发起数据更正 Request VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 发起数据更正 Request VO")
@Data
public class FillCorrectionCreateReqVO {

    @Schema(description = "原提交快照编号（须为该实例最新已合并快照）", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "原提交快照编号不能为空")
    private Long submissionId;

    @Schema(description = "更正原因", requiredMode = Schema.RequiredMode.REQUIRED, example = "8 月数据统计口径错误，需重报")
    @NotEmpty(message = "更正原因不能为空")
    private String reason;

}
