package cn.iocoder.yudao.module.governance.controller.admin.quality.vo.issue;

import cn.idev.excel.annotation.ExcelIgnore;
import cn.idev.excel.annotation.ExcelProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 问题数据 Response VO")
@Data
public class QualityIssueDataRespVO {

    @Schema(description = "编号")
    @ExcelProperty("编号")
    private Long id;

    @Schema(description = "任务实例编号")
    @ExcelIgnore
    private Long instanceId;

    @Schema(description = "检测结果编号")
    @ExcelIgnore
    private Long auditLogId;

    @Schema(description = "任务编号")
    @ExcelIgnore
    private Long taskId;

    @Schema(description = "任务名称")
    @ExcelProperty("任务名称")
    private String taskName;

    @Schema(description = "规则编号")
    @ExcelIgnore
    private Long ruleId;

    @Schema(description = "规则名称")
    @ExcelProperty("规则名称")
    private String ruleName;

    @Schema(description = "检查对象编号")
    @ExcelIgnore
    private Long objectId;

    @Schema(description = "检测表")
    @ExcelProperty("检测表")
    private String tableName;

    @Schema(description = "检测字段")
    @ExcelProperty("检测字段")
    private String checkField;

    @Schema(description = "机构编号")
    @ExcelProperty("机构编号")
    private Long orgId;

    @Schema(description = "地区编码")
    @ExcelProperty("机构/地区编码")
    private String regionCode;

    @Schema(description = "统计口径：ORG/ALL")
    @ExcelProperty("统计口径")
    private String statScope;

    @Schema(description = "问题行数")
    @ExcelProperty("问题行数")
    private Long issueCount;

    @Schema(description = "主键留存值")
    @ExcelIgnore
    private String pkValue;

    @Schema(description = "定位SQL（可重放）")
    @ExcelProperty("定位SQL")
    private String locationSql;

    @Schema(description = "处理状态：UNPROCESSED/PROCESSED")
    @ExcelProperty("处理状态")
    private String issueStatus;

    @Schema(description = "检出时间")
    @ExcelProperty("检出时间")
    private LocalDateTime checkTime;

}
