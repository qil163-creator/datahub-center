package cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.backfill;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

@Schema(description = "管理后台 - 数据开发补数据发起 Request VO")
@Data
public class DevBackfillCreateReqVO {

    @Schema(description = "开发任务编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1024")
    @NotNull(message = "任务编号不能为空")
    private Long taskId;

    @Schema(description = "起始业务日期（含）", requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-10-01")
    @NotNull(message = "起始业务日期不能为空")
    private LocalDate startDate;

    @Schema(description = "截止业务日期（含），不晚于今天", requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-10-31")
    @NotNull(message = "截止业务日期不能为空")
    private LocalDate endDate;

    @Schema(description = "日期顺序：ASC 从早到晚，DESC 从晚到早", example = "ASC")
    @Pattern(regexp = "ASC|DESC", message = "日期顺序只能是 ASC 或 DESC")
    private String dateOrder;

    @Schema(description = "失败策略：STOP 失败即停止，CONTINUE 继续后续日期", example = "STOP")
    @Pattern(regexp = "STOP|CONTINUE", message = "失败策略只能是 STOP 或 CONTINUE")
    private String failurePolicy;

    @Schema(description = "备注", example = "上游口径修正后重算 10 月")
    @Size(max = 255, message = "备注不能超过 255 个字符")
    private String remark;

    @Schema(description = "请求标识（幂等）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "请求标识不能为空")
    @Pattern(regexp = "[A-Za-z0-9_-]{8,100}", message = "请求标识格式不正确")
    private String requestId;

}
