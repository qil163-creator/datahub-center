package cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 在线填报-可下发的下级组织 Response VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 可下发的下级组织 Response VO")
@Data
public class FillDispatchCandidateRespVO {

    @Schema(description = "部门编号", example = "103")
    private Long deptId;

    @Schema(description = "部门名称", example = "下属一科")
    private String name;

    @Schema(description = "上级部门编号", example = "100")
    private Long parentId;

    @Schema(description = "是否已在本期任务中（已下发或由发起者直接下发）", example = "false")
    private Boolean inTask;

    @Schema(description = "默认下发名单中的填报人")
    private List<Assignee> defaultAssignees;

    @Schema(description = "默认下发名单中的截止提前小时数；不在默认名单中时为空", example = "24")
    private Integer defaultOffsetHours;

    @Schema(description = "管理后台 - 默认填报人")
    @Data
    public static class Assignee {

        @Schema(description = "用户编号", example = "7")
        private Long userId;

        @Schema(description = "用户名称", example = "张三")
        private String userName;
    }

}
