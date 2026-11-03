package cn.iocoder.yudao.module.governance.controller.admin.statictask.vo;

import cn.idev.excel.annotation.ExcelIgnoreUnannotated;
import cn.idev.excel.annotation.ExcelProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 静态脱敏任务 Response VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 静态脱敏任务 Response VO")
@Data
@ExcelIgnoreUnannotated
public class SafetyStaticTaskRespVO {

    @Schema(description = "任务编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1024")
    @ExcelProperty("任务编号")
    private Long id;

    @Schema(description = "任务名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "用户表脱敏到测试库")
    @ExcelProperty("任务名称")
    private String taskName;

    @Schema(description = "源数据源编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long sourceDatabaseId;

    @Schema(description = "源表名", requiredMode = Schema.RequiredMode.REQUIRED, example = "member_user")
    @ExcelProperty("源表名")
    private String sourceTableName;

    @Schema(description = "目标数据源编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "2")
    private Long targetDatabaseId;

    @Schema(description = "目标表名", requiredMode = Schema.RequiredMode.REQUIRED, example = "member_user")
    @ExcelProperty("目标表名")
    private String targetTableName;

    @Schema(description = "写入模式：OVERWRITE-全量覆盖、APPEND-追加", requiredMode = Schema.RequiredMode.REQUIRED, example = "OVERWRITE")
    @ExcelProperty("写入模式")
    private String writeMode;

    @Schema(description = "批次大小", example = "1000")
    private Integer batchSize;

    @Schema(description = "字段脱敏配置")
    private List<SafetyStaticTaskFieldRuleVO> fieldRules;

    @Schema(description = "定时执行 Cron 表达式", example = "0 0 2 * * ?")
    @ExcelProperty("Cron 表达式")
    private String cronExpression;

    @Schema(description = "状态：0-上线、1-下线", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    @ExcelProperty("状态")
    private Integer status;

    @Schema(description = "任务描述")
    private String description;

    @Schema(description = "创建时间", requiredMode = Schema.RequiredMode.REQUIRED)
    @ExcelProperty("创建时间")
    private LocalDateTime createTime;

}
