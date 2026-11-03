package cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "基于人工未送达结论显式授权通知重试")
public class QualityWorkOrderNotifyRetryReqVO {

    @NotNull
    private Long id;

    @NotNull
    private Long decisionId;

    @NotNull
    private Integer expectedResolutionVersion;

    @NotBlank
    @Size(max = 64)
    private String requestId;

    @NotBlank
    @Size(max = 1000)
    private String reason;
}
