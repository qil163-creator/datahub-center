package cn.iocoder.yudao.module.governance.controller.admin.permission.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Schema(description = "管理后台 - 数据权限配置保存 Request VO")
@Data
public class SafetyPermissionSaveReqVO {

    @Schema(description = "数据源编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "数据源编号不能为空")
    private Long databaseId;

    @Schema(description = "表名", requiredMode = Schema.RequiredMode.REQUIRED, example = "ods_user")
    @NotBlank(message = "表名不能为空")
    private String tableName;

    @NotBlank(message = "请选择已核验的物理模式")
    private String schemaName;

    @NotBlank(message = "精确表引用不能为空")
    private String tableReference;

    @Schema(description = "响应中的历史未绑定标识；保存不能以此开启旧裸表匹配")
    private Boolean legacyUnbound;

    @Schema(description = "角色权限配置列表", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "角色权限配置列表不能为空")
    @Valid
    private List<RolePermissionConfig> rolePermissionConfigs;

    @Schema(description = "角色权限配置")
    @Data
    public static class RolePermissionConfig {

        @Schema(description = "角色编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
        @NotNull(message = "角色编号不能为空")
        private Long roleId;

        @Schema(description = "角色名称", example = "数据分析师")
        private String roleName;

        @Schema(description = "历史未绑定表策略编号，仅用于显式核验绑定")
        private Long legacyTablePermissionId;

        private List<Long> legacyFieldPermissionIds;

        @Schema(description = "表权限")
        @Valid
        private TablePermission tablePermission;

        @Schema(description = "字段权限列表")
        @Valid
        private List<FieldPermission> fieldPermissions;
    }

    @Schema(description = "表权限")
    @Data
    public static class TablePermission {

        @Schema(description = "表权限：0-禁止访问 1-只读 2-读写", requiredMode = Schema.RequiredMode.REQUIRED, example = "2")
        @NotNull(message = "表权限类型不能为空")
        @Min(value = 0, message = "表权限类型不合法")
        @Max(value = 2, message = "表权限类型不合法")
        private Integer permissionType;

        @Schema(description = "数据分级编号", example = "1")
        private Long securityLevelId;

        @Valid
        @Schema(description = "独立 INSERT/UPDATE/DELETE 写授权；null 撤销写权限")
        private cn.iocoder.yudao.module.governance.api.permission.dto.SafetyWritePolicyDTO writePolicy;

        @jakarta.validation.constraints.Size(max = 500)
        @Schema(description = "新增、修改或撤销独立写授权时必填变更依据；响应不回填历史理由")
        private String writeChangeReason;
    }

    @Schema(description = "字段权限")
    @Data
    public static class FieldPermission {

        @Schema(description = "字段名", requiredMode = Schema.RequiredMode.REQUIRED, example = "mobile")
        @NotBlank(message = "字段名不能为空")
        private String fieldName;

        @Schema(description = "字段类型", example = "varchar")
        private String fieldType;

        @Schema(description = "字段说明", example = "手机号")
        private String fieldComment;

        @Schema(description = "字段权限：0-禁止访问 1-明文访问 2-脱敏访问", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
        @NotNull(message = "字段权限类型不能为空")
        @Min(value = 0, message = "字段权限类型不合法")
        @Max(value = 2, message = "字段权限类型不合法")
        private Integer permissionType;

        @Schema(description = "脱敏规则编号", example = "10")
        private Long maskingRuleId;

        @Schema(description = "数据分级编号", example = "1")
        private Long securityLevelId;
    }

}
