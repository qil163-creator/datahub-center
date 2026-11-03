package cn.iocoder.yudao.module.governance.controller.admin.quality.vo.auditlog;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Schema(description = "管理后台 - 检测结果 Response VO")
@Data
public class QualityAuditLogRespVO {

    @Schema(description = "编号")
    private Long id;

    @Schema(description = "任务实例编号")
    private Long instanceId;

    @Schema(description = "任务编号")
    private Long taskId;

    @Schema(description = "任务名称")
    private String taskName;

    @Schema(description = "规则编号")
    private Long ruleId;

    @Schema(description = "规则名称")
    private String ruleName;

    @Schema(description = "规则类型")
    private String ruleType;

    @Schema(description = "检查对象编号")
    private Long checkObjectId;

    @Schema(description = "检测表")
    private String tableName;

    @Schema(description = "检测字段")
    private String checkField;

    @Schema(description = "分组")
    private String groupName;

    @Schema(description = "检测时间")
    private LocalDateTime checkTime;

    @Schema(description = "总记录数")
    private Long totalCount;

    @Schema(description = "失败数")
    private Long failCount;

    @Schema(description = "通过率")
    private BigDecimal passRate;

    @Schema(description = "结果类型：PASS/FAIL/ERROR")
    private String resultType;

    @Schema(description = "指标值")
    private String indexValue;

    @Schema(description = "检测耗时（毫秒）")
    private Long costMs;

    @Schema(description = "执行SQL")
    private String resultSql;

    @Schema(description = "错误详情")
    private String errorDetail;

}
