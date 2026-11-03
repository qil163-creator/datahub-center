package cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 在线填报-调整填报人 Request VO（发起者/授权管理员）
 *
 * @author Li
 */
@Schema(description = "管理后台 - 调整填报人 Request VO")
@Data
public class FillWorkItemAssigneeUpdateReqVO {

    @Schema(description = "工作项编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "工作项编号不能为空")
    private Long workItemId;

    @Schema(description = "新增填报人用户编号列表", example = "[1003]")
    private List<Long> addUserIds;

    @Schema(description = "移除填报人用户编号列表", example = "[1001]")
    private List<Long> removeUserIds;

    @Schema(description = "原因说明", example = "原填报人休假，重新分配")
    private String reason;

}
