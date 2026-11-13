package cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 在线填报-截止时间延期 Request VO（发起者/授权管理员）
 *
 * 不传 workItemIds 时延期任务下全部尚未提交（待填报/填报中/整改中）的组织实例，并同步任务截止时间；
 * 传入时只延期指定实例。延期只会推后截止时间，已晚于新截止时间的实例保持不变。
 *
 * @author Li
 */
@Schema(description = "管理后台 - 填报截止时间延期 Request VO")
@Data
public class FillTaskExtendReqVO {

    @Schema(description = "任务编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "任务编号不能为空")
    private Long taskId;

    @Schema(description = "指定延期的组织实例编号；为空表示任务下全部未提交实例", example = "[11, 12]")
    @Size(max = 1000, message = "单次最多延期 1000 个组织实例")
    private List<Long> workItemIds;

    @Schema(description = "新的截止时间（毫秒时间戳），必须晚于当前时间", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "新的截止时间不能为空")
    private LocalDateTime deadlineTime;

    @Schema(description = "延期原因", example = "部分单位系统维护，统一延期两天")
    @Size(max = 200, message = "延期原因不能超过 200 个字符")
    private String reason;

}
