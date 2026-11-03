package cn.iocoder.yudao.module.asset.controller.admin.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 续期申请：创建一条关联原申请的新申请，必须重新审批，不延长原批准。
 */
@Schema(description = "管理后台 - 续期申请")
@Data
public class MarketRenewalReqVO {

    @Schema(description = "原申请编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "原申请编号不能为空")
    private Long id;

    @Schema(description = "续期后的使用截止时间", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "续期截止时间不能为空")
    private LocalDateTime applyEndTime;

    /**
     * 一次表单提交固定的幂等身份：同一 operationId 的重复提交只产生一个续期申请。
     */
    @Schema(description = "续期请求幂等编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "续期请求编号不能为空")
    @Size(max = 128, message = "续期请求编号过长")
    private String operationId;

}
