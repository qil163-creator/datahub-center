package cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "工单通知结果人工核验")
public class QualityWorkOrderNotifyReviewReqVO {

    @NotNull
    private Long id;

    @NotNull
    private Integer expectedResolutionVersion;

    @NotBlank
    @Size(max = 64)
    private String requestId;

    @NotBlank
    @Pattern(regexp = "DELIVERED|NOT_DELIVERED")
    private String outcome;

    @NotBlank
    @Size(max = 1000)
    private String reason;

    /** 操作人手工登记的系统消息编号，仅作人工参考，服务端不据此宣称系统已核验。 */
    @Positive
    private Long operatorMessageId;
}
