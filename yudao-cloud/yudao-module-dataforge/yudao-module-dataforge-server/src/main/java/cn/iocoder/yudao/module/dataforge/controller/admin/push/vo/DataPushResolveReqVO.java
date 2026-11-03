package cn.iocoder.yudao.module.dataforge.controller.admin.push.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

@Data
@Schema(description = "推送未知结果人工核查；证据编号引用受控工单，不上传凭据或业务载荷")
public class DataPushResolveReqVO {

    @NotNull
    @Positive
    private Long recordId;

    @NotBlank
    @Pattern(regexp = "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
    private String requestId;

    @NotBlank
    @Pattern(regexp = "NO_EFFECT|COMPENSATED")
    @Schema(description = "NO_EFFECT：确认未生效；COMPENSATED：已完整补偿；不能表示发送成功")
    private String outcome;

    @NotBlank
    @Size(max = 500)
    private String reason;

    @NotNull
    @AssertTrue
    private Boolean confirmedStopped;

    @NotNull
    @AssertTrue
    private Boolean outputReviewed;

    @NotBlank
    @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:/#-]{0,159}")
    @Schema(description = "工作者与远端请求均已终止的受控证据编号；超时、租约到期不构成证据")
    private String stoppedEvidenceRef;

    @NotBlank
    @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:/#-]{0,159}")
    @Schema(description = "关联本运行全部批次、远端异步消费及补偿范围的核查证据编号")
    private String outputEvidenceRef;
}
