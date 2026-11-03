package cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.object;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 表元数据批量认责 Request VO（仅覆盖传入的负责人字段，逐表留痕）
 *
 * @author Li
 */
@Schema(description = "管理后台 - 表元数据批量认责 Request VO")
@Data
public class MetaBatchOwnerReqVO {

    @Schema(description = "表元数据编号列表", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "请选择要认责的表")
    private List<Long> ids;

    @Schema(description = "技术负责人用户编号", example = "1")
    private Long techLeaderUserId;

    @Schema(description = "技术负责人电话")
    private String techLeaderPhone;

    @Schema(description = "业务负责人用户编号", example = "2")
    private Long businessLeaderUserId;

    @Schema(description = "业务负责人电话")
    private String businessLeaderPhone;

    @Schema(description = "责任部门编号", example = "103")
    private Long ownerDeptId;

}
