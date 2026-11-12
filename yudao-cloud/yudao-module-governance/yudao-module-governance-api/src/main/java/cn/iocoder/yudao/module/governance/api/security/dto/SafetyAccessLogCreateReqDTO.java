package cn.iocoder.yudao.module.governance.api.security.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 敏感数据访问审计日志 创建 DTO
 *
 * @author Li
 */
@Schema(description = "RPC 服务 - 敏感数据访问审计日志创建 DTO")
@Data
public class SafetyAccessLogCreateReqDTO {

    /** 访问类型：数据服务 API */
    public static final String ACCESS_TYPE_API = "API";

    /** 访问类型：静态脱敏任务 */
    public static final String ACCESS_TYPE_STATIC_TASK = "STATIC_TASK";

    /** 访问类型：数据查询工作台 */
    public static final String ACCESS_TYPE_QUERY = "QUERY";

    /** 访问类型：数据开发调试 */
    public static final String ACCESS_TYPE_DEV = "DEV";

    /** 访问类型：数据探查 */
    public static final String ACCESS_TYPE_EXPLORE = "EXPLORE";

    /** 访问类型：数据接入同步 */
    public static final String ACCESS_TYPE_ACCESS = "ACCESS";

    /** 访问类型：数据导出 */
    public static final String ACCESS_TYPE_EXPORT = "EXPORT";

    /** 访问类型：敏感识别扫描读取原值样本（不脱敏，结果不返回给用户） */
    public static final String ACCESS_TYPE_SCAN = "SCAN";

    @Schema(description = "访问用户编号（管理端用户；网关应用调用时为空）")
    private Long userId;

    @Schema(description = "访问用户名称")
    private String userName;

    @Schema(description = "调用应用编号（数据服务网关调用时记录）")
    private Long appId;

    @Schema(description = "访问类型：API / STATIC_TASK / QUERY / DEV / EXPLORE / ACCESS / EXPORT / SCAN", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "访问类型不能为空")
    private String accessType;

    @Schema(description = "资源名称（API 名称 / 任务名称）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "资源名称不能为空")
    private String resourceName;

    @Schema(description = "数据源编号")
    private Long databaseId;

    @Schema(description = "表名")
    private String tableName;

    @Schema(description = "涉及的敏感字段（逗号分隔）")
    private String sensitiveColumns;

    @Schema(description = "敏感字段数")
    private Integer columnCount;

    @Schema(description = "是否已脱敏：1-已脱敏 0-明文（白名单豁免）")
    private Integer maskedFlag;

    @Schema(description = "命中的白名单编号（豁免时记录）")
    private Long whitelistId;

    @Schema(description = "调用方 IP")
    private String callerIp;

    @Schema(description = "访问时间（空则取当前时间）")
    private LocalDateTime accessTime;

}
