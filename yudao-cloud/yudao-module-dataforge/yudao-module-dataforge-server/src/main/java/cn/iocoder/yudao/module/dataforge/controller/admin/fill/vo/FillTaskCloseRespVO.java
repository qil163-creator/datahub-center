package cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 在线填报-结束填报 Response VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 结束填报 Response VO")
@Data
public class FillTaskCloseRespVO {

    @Schema(description = "被取消的未提交组织数（待填报/填报中/整改中）", example = "3")
    private Integer cancelledCount;

    @Schema(description = "撤销进行中更正、恢复为已合并的组织数", example = "1")
    private Integer restoredCount;

    @Schema(description = "仍在审批或合并中的组织数；全部完成后任务自动结束", example = "2")
    private Integer inFlightCount;

    @Schema(description = "结束后的任务状态：1-进行中（仍有审批/合并中的组织） 2-已完成 3-已取消（无任何组织合并）", example = "2")
    private Integer taskStatus;

}
