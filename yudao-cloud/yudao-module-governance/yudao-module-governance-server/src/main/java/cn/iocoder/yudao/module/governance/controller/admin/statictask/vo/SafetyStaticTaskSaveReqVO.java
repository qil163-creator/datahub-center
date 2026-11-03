package cn.iocoder.yudao.module.governance.controller.admin.statictask.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 静态脱敏任务新增/修改 Request VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 静态脱敏任务新增/修改 Request VO")
@Data
public class SafetyStaticTaskSaveReqVO {

    @Schema(description = "任务编号", example = "1024")
    private Long id;

    @Schema(description = "任务名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "用户表脱敏到测试库")
    @NotBlank(message = "任务名称不能为空")
    private String taskName;

    @Schema(description = "源数据源编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "源数据源不能为空")
    private Long sourceDatabaseId;

    @Schema(description = "源表名", requiredMode = Schema.RequiredMode.REQUIRED, example = "member_user")
    @NotBlank(message = "源表名不能为空")
    private String sourceTableName;

    @Schema(description = "目标数据源编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "2")
    @NotNull(message = "目标数据源不能为空")
    private Long targetDatabaseId;

    @Schema(description = "目标表名", requiredMode = Schema.RequiredMode.REQUIRED, example = "member_user")
    @NotBlank(message = "目标表名不能为空")
    private String targetTableName;

    @Schema(description = "写入模式：OVERWRITE-全量覆盖、APPEND-追加", requiredMode = Schema.RequiredMode.REQUIRED, example = "OVERWRITE")
    @NotBlank(message = "写入模式不能为空")
    private String writeMode;

    @Schema(description = "批次大小，默认 1000", example = "1000")
    private Integer batchSize;

    @Schema(description = "字段脱敏配置", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "至少配置一个脱敏字段")
    @Valid
    private List<@NotNull(message = "脱敏字段配置不能为空") SafetyStaticTaskFieldRuleVO> fieldRules;

    @Schema(description = "定时执行 Cron 表达式，为空表示仅手动执行", example = "0 0 2 * * ?")
    private String cronExpression;

    @Schema(description = "状态：0-上线、1-下线", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    @NotNull(message = "状态不能为空")
    private Integer status;

    @Schema(description = "任务描述", example = "每天凌晨同步生产用户表到测试库并脱敏")
    private String description;

}
