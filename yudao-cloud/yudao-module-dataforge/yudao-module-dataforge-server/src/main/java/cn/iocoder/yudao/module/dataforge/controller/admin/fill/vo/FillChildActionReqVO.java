package cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 在线填报-对下级实例的操作 Request VO（审核、退回、撤销下发、调整截止时间）
 *
 * @author Li
 */
@Schema(description = "管理后台 - 对下级实例的操作 Request VO")
@Data
public class FillChildActionReqVO {

    @Schema(description = "下级实例编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "21")
    @NotNull(message = "下级实例不能为空")
    private Long childWorkItemId;

    @Schema(description = "审核结论：true-通过 false-驳回（仅审核使用）", example = "true")
    private Boolean approve;

    @Schema(description = "原因或审核意见（驳回、退回时必填）", example = "合计与明细不一致")
    @Size(max = 200, message = "原因不能超过 200 个字符")
    private String reason;

    @Schema(description = "新的截止时间（仅调整截止时间使用，不能晚于本单位截止时间）")
    private LocalDateTime deadlineTime;

}
