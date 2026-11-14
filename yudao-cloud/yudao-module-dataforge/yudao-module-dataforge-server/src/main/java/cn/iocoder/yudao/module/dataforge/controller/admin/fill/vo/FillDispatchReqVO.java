package cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 在线填报-向下级下发 Request VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 向下级下发 Request VO")
@Data
public class FillDispatchReqVO {

    @Schema(description = "下发的下级组织", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "请选择要下发的下级组织")
    @Size(max = 200, message = "一次最多下发 200 个组织")
    @Valid
    private List<Org> orgs;

    @Schema(description = "是否保存为后续期次的默认下发名单（覆盖本单位原有的默认名单）", example = "true")
    private Boolean saveAsDefault;

    @Schema(description = "管理后台 - 下发组织")
    @Data
    public static class Org {

        @Schema(description = "下级组织（部门）编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "103")
        @NotNull(message = "下级组织不能为空")
        private Long reportingOrgId;

        @Schema(description = "填报人用户编号（须属于该下级组织或其下属部门）", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty(message = "每个下级组织至少指定一名填报人")
        @Size(max = 20, message = "每个下级组织最多 20 名填报人")
        private List<Long> assigneeUserIds;

        @Schema(description = "截止时间（不能晚于本单位截止时间）", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "截止时间不能为空")
        private LocalDateTime deadlineTime;
    }

}
