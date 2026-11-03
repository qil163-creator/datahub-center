package cn.iocoder.yudao.module.governance.controller.admin.accesslog.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

import static cn.iocoder.yudao.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

@Schema(description = "管理后台 - 敏感数据访问审计分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class SafetyAccessLogPageReqVO extends PageParam {

    @Schema(description = "访问用户编号", example = "1")
    private Long userId;

    @Schema(description = "访问用户名称（模糊）", example = "admin")
    private String userName;

    @Schema(description = "访问类型：API / STATIC_TASK / QUERY / DEV / EXPLORE / ACCESS", example = "API")
    private String accessType;

    @Schema(description = "资源名称（模糊）", example = "用户查询")
    private String resourceName;

    @Schema(description = "表名（模糊）", example = "sys_user")
    private String tableName;

    @Schema(description = "是否已脱敏：1-已脱敏 0-明文豁免", example = "1")
    private Integer maskedFlag;

    @Schema(description = "访问时间区间")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    private LocalDateTime[] accessTime;

}
