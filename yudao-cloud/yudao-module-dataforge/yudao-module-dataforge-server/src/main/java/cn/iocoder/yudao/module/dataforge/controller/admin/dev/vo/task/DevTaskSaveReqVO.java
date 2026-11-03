package cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.task;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 开发任务新增/修改 Request VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 开发任务新增/修改 Request VO")
@Data
public class DevTaskSaveReqVO {

    private Long executionUserId;

    @Schema(description = "成果质量任务编号（选填）", example = "1")
    private Long qualityTaskId;

    @Schema(description = "上游任务编号列表")
    @jakarta.validation.constraints.Size(max = 100)
    private java.util.List<@jakarta.validation.constraints.NotNull Long> dependencyTaskIds;

    @Schema(description = "任务定义乐观锁修订号", example = "3")
    private Integer expectedRevision;

    @Schema(description = "任务调度 Cron；完整保存后即可用于定时运行")
    @Size(max = 100)
    private String cronExpression;


    @Schema(description = "主键", example = "1")
    private Long id;

    @Schema(description = "目录编号", example = "1")
    private Long dirId;

    @Schema(description = "任务名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "ods_水位日汇总")
    @NotBlank(message = "任务名称不能为空")
    @Size(max = 100, message = "任务名称不能超过 100 个字符")
    private String name;

    @Schema(description = "任务维护负责人，与执行授权身份相互独立")
    @jakarta.validation.constraints.Positive
    private Long ownerId;

    @Schema(description = "调度控制面；当前仅支持 DOLPHIN_SCHEDULER")
    @jakarta.validation.constraints.Pattern(regexp = "DOLPHIN_SCHEDULER")
    private String scheduler;

    @Schema(description = "未绑定连接时选定的数据库产品；绑定后由受管数据源带出")
    @jakarta.validation.constraints.Pattern(regexp = "[A-Z][A-Z0-9_]{0,31}")
    private String dbType;

    @jakarta.validation.Valid
    private cn.iocoder.yudao.module.dataforge.model.dev.DevExecutionConfig executionConfig;

    @Schema(description = "任务类型", example = "SQL")
    private String taskType;

    @jakarta.validation.Valid
    @Schema(description = "引擎程序配置；空值保留历史 SQL 执行路径")
    private cn.iocoder.yudao.module.dataforge.model.dev.DevProgramConfig programConfig;

    @Schema(description = "数据源编号", example = "1")
    private Long databaseId;

    @Schema(description = "任务脚本内容")
    @Size(max = 100000)
    private String content;

    @Schema(description = "执行超时（秒）", example = "300")
    private Integer timeoutSeconds;

    @Schema(description = "查询结果最大返回行数", example = "1000")
    @jakarta.validation.constraints.Min(1)
    @jakarta.validation.constraints.Max(10000)
    private Integer maxRows;

    @Schema(description = "DolphinScheduler 数据源编号（集群任务）", example = "1")
    private Integer dsDatasourceId;

    @Schema(description = "DolphinScheduler 数据源类型（集群任务）", example = "SPARK")
    @Size(max = 32, message = "数据源类型不能超过 32 个字符")
    private String dsDatasourceType;

    @Schema(description = "任务描述")
    @Size(max = 500, message = "任务描述不能超过 500 个字符")
    private String description;

    @Schema(description = "版本备注（保存脚本时记录）", example = "增加日期过滤")
    @Size(max = 200, message = "版本备注不能超过 200 个字符")
    private String versionRemark;

}
