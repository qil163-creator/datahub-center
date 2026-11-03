package cn.iocoder.yudao.module.governance.controller.admin.master.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 主数据派发任务 Response VO")
@Data
public class MasterDistributeRespVO {

    private Integer latestRunStatus;

    private LocalDateTime lastPushTime;

    private Long totalCount;

    private Long successCount;

    private Long failureCount;

    private Long unknownCount;

    private String creatorName;

    private String updaterName;

    private LocalDateTime updateTime;

    @Schema(description = "阻塞运行：0-无 1-等待 2-发送中 5-未知待核查")
    private Integer blockingRunStatus;

    @Schema(description = "主键", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long id;

    @Schema(description = "主数据模型编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long masterModelId;

    /** 主数据推送类目，历史任务可为空。 */
    private Long categoryId;

    @Schema(description = "模型名称（中文名）", example = "人口主数据")
    private String masterModelName;

    @Schema(description = "模型物理表名", example = "md_person")
    private String masterModelTableName;

    @Schema(description = "派发名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "人口主数据推送公安库")
    private String name;

    @Schema(description = "派发类型：1-数据库 2-API 3-消息队列", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Integer distributeType;

    @Schema(description = "调度类型：1-一次性 2-周期性", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Integer taskType;

    @Schema(description = "Cron 表达式", example = "0 0 2 * * ?")
    private String cron;

    @Schema(description = "下次执行时间")
    private LocalDateTime nextRunTime;

    @Schema(description = "发布状态：0-未发布 1-已发布", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    private Integer status;

    @Schema(description = "描述", example = "每天凌晨推送")
    private String description;

    @Schema(description = "最近发布时间（取消发布后保留）")
    private LocalDateTime releaseTime;

    @Schema(description = "最近发布人编号（取消发布后保留）", example = "1")
    private Long releaseUserId;

    @Schema(description = "最近发布人昵称", example = "管理员")
    private String releaseUserName;

    @Schema(description = "所属机构编号", example = "100")
    private Long orgId;

    @Schema(description = "所属机构名称", example = "大数据局")
    private String orgName;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "派发配置")
    private MasterDistributeConfigVO config;

}
