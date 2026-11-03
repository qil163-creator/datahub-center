package cn.iocoder.yudao.module.governance.controller.admin.master.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "管理后台 - 主数据派发任务保存 Request VO")
@Data
public class MasterDistributeSaveReqVO {

    @Schema(description = "主键", example = "1")
    private Long id;

    @Schema(description = "API 推送启用状态：0-禁用 1-启用；启用不立即发送")
    @jakarta.validation.constraints.Min(0)
    @jakarta.validation.constraints.Max(1)
    private Integer status;

    @Schema(description = "主数据模型编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "主数据模型不能为空")
    private Long masterModelId;

    /** 主数据推送类目，历史任务可为空。 */
    private Long categoryId;

    @Schema(description = "派发名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "人口主数据推送公安库")
    @NotBlank(message = "派发名称不能为空")
    @Size(max = 50, message = "派发名称不能超过 50 个字符")
    private String name;

    @Schema(description = "派发类型：1-数据库 2-API 3-消息队列", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "派发类型不能为空")
    private Integer distributeType;

    @Schema(description = "调度类型：1-一次性 2-周期性", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "调度类型不能为空")
    private Integer taskType;

    @Schema(description = "Cron 表达式（周期任务必填）", example = "0 0 2 * * ?")
    private String cron;

    @Schema(description = "描述", example = "每天凌晨推送")
    @Size(max = 256, message = "描述不能超过 256 个字符")
    private String description;

    @Schema(description = "所属机构编号", example = "100")
    private Long orgId;

    @Schema(description = "派发配置", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "派发配置不能为空")
    @Valid
    private MasterDistributeConfigVO config;

}
