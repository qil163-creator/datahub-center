package cn.iocoder.yudao.module.governance.controller.admin.security.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理后台 - 异常访问告警 Response VO
 */
@Schema(description = "管理后台 - 异常访问告警 Response VO")
@Data
public class SafetyAccessAlertRespVO {

    @Schema(description = "编号", example = "1")
    private Long id;

    @Schema(description = "规则编码", example = "VOLUME")
    private String ruleCode;

    @Schema(description = "访问用户编号", example = "1")
    private Long userId;

    @Schema(description = "访问用户名称", example = "张三")
    private String userName;

    @Schema(description = "窗口开始")
    private LocalDateTime windowStart;

    @Schema(description = "窗口结束")
    private LocalDateTime windowEnd;

    @Schema(description = "统计值", example = "320")
    private Integer metricValue;

    @Schema(description = "阈值", example = "200")
    private Integer threshold;

    @Schema(description = "状态：OPEN/CONFIRMED/IGNORED", example = "OPEN")
    private String status;

    @Schema(description = "处理人编号")
    private Long handlerId;

    @Schema(description = "处理时间")
    private LocalDateTime handleTime;

    @Schema(description = "处理说明")
    private String handleRemark;

    @Schema(description = "修订号", example = "0")
    private Integer revision;

    @Schema(description = "识别时间")
    private LocalDateTime createTime;
}
