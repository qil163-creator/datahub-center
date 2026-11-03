package cn.iocoder.yudao.module.governance.controller.admin.master.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

@Data
@Schema(description = "主数据派发未知结果核查；证据引用受控工单，不提交连接凭据或业务载荷")
public class MasterDistributeResolveReqVO {

    @NotNull
    @Positive
    private Long recordId;

    @NotBlank
    @Pattern(regexp = "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
    private String requestId;

    @NotBlank
    @Pattern(regexp = "NO_EFFECT|COMPENSATED")
    @Schema(description = "全部未生效或已完整补偿；不能表示派发成功")
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
    @Schema(description = "旧工作者、远端请求及异步消费均已终止的证据编号；租约到期不是证明")
    private String stoppedEvidenceRef;

    @NotBlank
    @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:/#-]{0,159}")
    @Schema(description = "全部输出未生效或已完整补偿的证据编号")
    private String outputEvidenceRef;
}
