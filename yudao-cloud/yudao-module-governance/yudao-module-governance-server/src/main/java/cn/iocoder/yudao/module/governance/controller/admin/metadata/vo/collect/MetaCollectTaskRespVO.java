package cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.collect;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 元数据采集任务 Response VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 元数据采集任务 Response VO")
@Data
public class MetaCollectTaskRespVO {

    private String host;

    private String port;

    private String username;

    private Integer collectType;

    private String executeStrategy;

    private String filePath;

    private String datasourceName;

    private String ownerName;

    private String creatorName;

    private String creatorPhone;

    private String updaterName;

    private String updaterPhone;

    private LocalDateTime updateTime;

    private Integer runStatus;

    @Schema(description = "任务编号", example = "1")
    private Long id;

    @Schema(description = "任务名称", example = "数据中台应用库（ADS）")
    private String name;

    @Schema(description = "dataforge 数据源编号", example = "1")
    private Long datasourceId;

    @Schema(description = "来源系统编号", example = "1")
    private Long sourceSystemId;

    @Schema(description = "来源系统名称", example = "山洪灾害监测预警系统")
    private String sourceSystemName;

    @Schema(description = "数据库类型", example = "DORIS")
    private String dbType;

    @Schema(description = "采集范围：1-整个数据源 2-自定义", example = "1")
    private Integer scopeType;

    @Schema(description = "自定义范围 JSON")
    private String scopeConfig;

    @Schema(description = "黑名单规则 JSON")
    private String blacklist;

    @Schema(description = "调度周期 cron 表达式", example = "0 0 2 * * ?")
    private String cron;

    @Schema(description = "调度器：XXL_JOB / DOLPHINSCHEDULER", example = "XXL_JOB")
    private String scheduler;

    @Schema(description = "任务上下线：0-下线 1-上线", example = "1")
    private Integer enabled;

    @Schema(description = "调度启停：0-停止 1-启动", example = "1")
    private Integer scheduleEnabled;

    @Schema(description = "责任人用户编号", example = "1")
    private Long ownerUserId;

    @Schema(description = "责任人电话", example = "13800000000")
    private String ownerPhone;

    @Schema(description = "责任部门编号", example = "100")
    private Long ownerDeptId;

    @Schema(description = "最近运行时间")
    private LocalDateTime lastRunTime;

    @Schema(description = "下次运行时间")
    private LocalDateTime nextRunTime;

    @Schema(description = "最近实例编号", example = "1")
    private Long lastInstanceId;

    @Schema(description = "描述")
    private String description;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
