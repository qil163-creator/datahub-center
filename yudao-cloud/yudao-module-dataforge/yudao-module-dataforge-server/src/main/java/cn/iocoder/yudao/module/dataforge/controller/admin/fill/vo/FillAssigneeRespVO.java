package cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 在线填报-填报人 Response VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 填报人 Response VO")
@Data
public class FillAssigneeRespVO {

    @Schema(description = "用户编号", example = "1001")
    private Long userId;

    @Schema(description = "用户昵称", example = "李四")
    private String userName;

    @Schema(description = "所属部门编号", example = "101")
    private Long deptId;

    @Schema(description = "状态：1-有效 0-已失效", example = "1")
    private Integer state;

    @Schema(description = "来源：1-计划默认 2-发起者指定 3-转派接收 4-管理员重新分配", example = "1")
    private Integer source;

}
