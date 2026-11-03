package cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "管理后台 - 问题工单状态动作 Request VO")
@Data
public class QualityWorkOrderCommandReqVO {

    @NotNull
    private Long id;

    @NotNull
    private Integer expectedVersion;

    @NotBlank
    @Size(max = 64)
    private String requestId;

    @Size(max = 1024)
    private String reason;

    private Long handlingDeptId;

    private Long ownerUserId;

    private Long confirmerUserId;

    private String assignBasis;

    @Pattern(regexp = "SOURCE_FIX|PLATFORM_REPAIR|EXPLAIN|EXCEPTION", message = "整改类型不合法")
    private String submissionType;

    @Size(max = 2000)
    private String content;

    @Size(max = 512)
    private String evidenceRef;

    private Long repairOrderId;

    private List<Long> issueIds;

    @Size(max = 512)
    private String exceptionScope;

    private LocalDateTime exceptionExpireAt;

    private Long submissionId;

    /** VERIFY_PASS / RETURN / ACCEPT_EXPLAIN / ACCEPT_EXCEPTION */
    @Pattern(regexp = "VERIFY_PASS|RETURN|ACCEPT_EXPLAIN|ACCEPT_EXCEPTION", message = "验收动作不合法")
    private String confirmAction;

    private LocalDateTime newDeadline;

    @Size(max = 256)
    private String deadlineBasis;

}
