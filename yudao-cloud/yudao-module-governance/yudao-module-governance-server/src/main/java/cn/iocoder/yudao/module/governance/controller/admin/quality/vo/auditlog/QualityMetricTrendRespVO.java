package cn.iocoder.yudao.module.governance.controller.admin.quality.vo.auditlog;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表级指标趋势中的一个点（一次检测结果）。数值以十进制字符串传输，前端不得转为浮点后再计算。
 */
@Schema(description = "管理后台 - 数据质量表级指标趋势点 Response VO")
@Data
public class QualityMetricTrendRespVO {

    @Schema(description = "检测结果编号")
    private Long auditLogId;

    @Schema(description = "检测时间")
    private LocalDateTime checkTime;

    @Schema(description = "业务日期 yyyy-MM-dd（替换 ${bizdate} 所用日期），旧记录可能为空", example = "2026-11-15")
    private String bizDate;

    @Schema(description = "指标值（如表行数），十进制字符串", example = "1200")
    private String value;

    @Schema(description = "本次预期区间下限（含），十进制字符串，为空表示不限", example = "700")
    private String lower;

    @Schema(description = "本次预期区间上限（含），十进制字符串，为空表示不限", example = "1500")
    private String upper;

    @Schema(description = "结果类型：PASS/FAIL/SKIPPED")
    private String resultType;

}
