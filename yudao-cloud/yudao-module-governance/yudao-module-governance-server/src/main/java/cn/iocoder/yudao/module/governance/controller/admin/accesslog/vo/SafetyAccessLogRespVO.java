package cn.iocoder.yudao.module.governance.controller.admin.accesslog.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 敏感数据访问审计 Response VO")
@Data
public class SafetyAccessLogRespVO {

    @Schema(description = "日志编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long id;

    @Schema(description = "访问用户编号")
    private Long userId;

    @Schema(description = "访问用户名称")
    private String userName;

    @Schema(description = "调用应用编号")
    private Long appId;

    @Schema(description = "访问类型：API / STATIC_TASK", requiredMode = Schema.RequiredMode.REQUIRED)
    private String accessType;

    @Schema(description = "资源名称", requiredMode = Schema.RequiredMode.REQUIRED)
    private String resourceName;

    @Schema(description = "数据源编号")
    private Long databaseId;

    @Schema(description = "表名")
    private String tableName;

    @Schema(description = "涉及的敏感字段（逗号分隔）")
    private String sensitiveColumns;

    @Schema(description = "敏感字段数")
    private Integer columnCount;

    @Schema(description = "是否已脱敏：1-已脱敏 0-明文豁免", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer maskedFlag;

    @Schema(description = "命中的白名单编号")
    private Long whitelistId;

    @Schema(description = "调用方 IP")
    private String callerIp;

    @Schema(description = "访问时间", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime accessTime;

}
