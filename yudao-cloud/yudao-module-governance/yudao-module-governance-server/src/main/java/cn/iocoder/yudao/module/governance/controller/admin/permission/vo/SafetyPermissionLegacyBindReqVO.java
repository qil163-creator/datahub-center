package cn.iocoder.yudao.module.governance.controller.admin.permission.vo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 管理员明确核验历史策略目标；只改变绑定，不改变原禁止、脱敏、行条件等策略。 */
@Data
public class SafetyPermissionLegacyBindReqVO {

    @NotNull
    private Long databaseId;

    @NotNull
    private Long roleId;

    @NotBlank
    @Size(max = 256)
    private String legacyTableName;

    @NotBlank
    @Size(max = 256)
    private String schemaName;

    @NotBlank
    @Size(max = 256)
    private String tableName;

    @NotBlank
    @Size(max = 2048)
    private String tableReference;

    @NotBlank
    @Size(max = 500)
    private String note;
}
