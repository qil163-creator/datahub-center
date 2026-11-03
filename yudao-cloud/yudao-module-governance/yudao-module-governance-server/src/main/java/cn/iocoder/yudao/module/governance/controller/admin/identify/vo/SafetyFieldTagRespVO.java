package cn.iocoder.yudao.module.governance.controller.admin.identify.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 敏感字段打标 Response VO")
@Data
public class SafetyFieldTagRespVO {

    @Schema(description = "打标编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long id;

    @Schema(description = "数据源编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long databaseId;

    @Schema(description = "表名", requiredMode = Schema.RequiredMode.REQUIRED)
    private String tableName;

    @Schema(description = "字段名", requiredMode = Schema.RequiredMode.REQUIRED)
    private String columnName;

    @Schema(description = "数据分类编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long categoryId;

    @Schema(description = "数据分类名称")
    private String categoryName;

    @Schema(description = "数据分级编号")
    private Long levelId;

    @Schema(description = "数据分级名称")
    private String levelName;

    @Schema(description = "数据分级颜色")
    private String levelColor;

    @Schema(description = "命中的识别规则编号")
    private Long identifyRuleId;

    @Schema(description = "扫描记录编号")
    private Long scanRunId;

    @Schema(description = "打标来源：AUTO / MANUAL", requiredMode = Schema.RequiredMode.REQUIRED)
    private String source;

    @Schema(description = "打标状态：PENDING / CONFIRMED / REJECTED", requiredMode = Schema.RequiredMode.REQUIRED)
    private String tagStatus;

    @Schema(description = "命中说明")
    private String hitDesc;

    @Schema(description = "确认/驳回时间")
    private LocalDateTime confirmTime;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
