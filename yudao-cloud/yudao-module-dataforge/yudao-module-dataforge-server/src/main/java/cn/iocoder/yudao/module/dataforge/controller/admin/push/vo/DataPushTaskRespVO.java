package cn.iocoder.yudao.module.dataforge.controller.admin.push.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 数据推送任务 Response VO")
@Data
public class DataPushTaskRespVO {

    @Schema(description = "主键")
    private Long id;

    @Schema(description = "推送配置编号")
    private Long pushConfigId;

    @Schema(description = "推送配置名称")
    private String pushConfigName;

    @Schema(description = "通道类型")
    private Integer channelType;

    @Schema(description = "任务名称")
    private String name;

    @Schema(description = "推送类目编号")
    private Long categoryId;

    @Schema(description = "源数据源编号")
    private Long sourceDatabaseId;

    @Schema(description = "源数据源名称")
    private String sourceDatabaseName;

    @Schema(description = "推送类型")
    private Integer pushType;

    @Schema(description = "源表名")
    private String tableName;

    @Schema(description = "自定义 SELECT")
    private String sqlText;

    @Schema(description = "对象名")
    private String objectName;

    @Schema(description = "推送方式：1-全量 2-增量")
    private Integer pushMode;

    @Schema(description = "增量字段")
    private String incrementColumn;

    @Schema(description = "首次增量起始水位（不含）")
    private String initStartVal;

    @Schema(description = "每批行数")
    private Integer batchSize;

    @Schema(description = "调度类型")
    private Integer taskType;

    @Schema(description = "Cron")
    private String cron;

    @Schema(description = "发布状态")
    private Integer status;

    @Schema(description = "下次执行时间")
    private LocalDateTime nextRunTime;

    @Schema(description = "发布时间")
    private LocalDateTime releaseTime;

    @Schema(description = "发布人编号")
    private Long releaseUserId;

    @Schema(description = "发布人")
    private String releaseUserName;

    @Schema(description = "说明")
    private String description;

    @Schema(description = "所属机构编号")
    private Long orgId;

    @Schema(description = "所属机构名称")
    private String orgName;

    @Schema(description = "是否正在运行")
    private Boolean running;

    @Schema(description = "持久占位状态：0-无占位 1-发送前等待 2-已进入发送 5-结果未知；未知禁止重跑")
    private Integer blockingRunStatus;

    @Schema(description = "最近一次运行状态")
    private Integer lastRunStatus;

    @Schema(description = "最近一次开始时间")
    private LocalDateTime lastStartTime;

    @Schema(description = "最近一次结束时间")
    private LocalDateTime lastEndTime;

    @Schema(description = "最近一次推出行数")
    private Long lastDataCount;

    @Schema(description = "最近一次错误摘要")
    private String lastErrorInfo;

    @Schema(description = "当前增量水位上界")
    private String watermarkEndVal;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
