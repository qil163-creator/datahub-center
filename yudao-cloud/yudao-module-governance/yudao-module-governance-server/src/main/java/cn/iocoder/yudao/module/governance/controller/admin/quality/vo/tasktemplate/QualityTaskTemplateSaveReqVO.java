package cn.iocoder.yudao.module.governance.controller.admin.quality.vo.tasktemplate;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "管理后台 - 任务模板新增/修改 Request VO")
@Data
public class QualityTaskTemplateSaveReqVO {

    @Schema(description = "编号")
    private Long id;

    @Schema(description = "模板名称")
    @NotBlank(message = "模板名称不能为空")
    @Size(max = 128, message = "模板名称长度不能超过 128")
    private String templateName;

    @Schema(description = "模板编码")
    @Size(max = 128)
    private String templateCode;

    @Schema(description = "数据源连接ID")
    @NotNull(message = "数据源不能为空")
    private Long datasourceConnId;

    @Schema(description = "规则来源：RULE_SET/SPECIFY_RULE")
    private String ruleSourceType;

    @Schema(description = "规则集编号")
    private Long ruleSetId;

    @Schema(description = "指定规则的规则ID集合（逗号分隔）")
    @Size(max = 512)
    private String ruleIds;

    @Schema(description = "表范围类型：DATASOURCE/TABLE_MATCH/SPECIFY_TABLE/LAYER")
    private String tableScopeType;

    @Schema(description = "表名匹配模式")
    @Size(max = 256)
    private String tableMatch;

    @Schema(description = "指定表的表ID集合（逗号分隔）")
    @Size(max = 512)
    private String tableIds;

    @Schema(description = "分层")
    @Size(max = 32)
    private String layer;

    @Schema(description = "字段选择策略")
    @Size(max = 64)
    private String fieldSelectStrategy;

    @Schema(description = "调度类型")
    private String scheduleType;

    @Schema(description = "cron表达式")
    @Size(max = 128)
    private String cronExpression;

    @Schema(description = "同步模式：FULL全量")
    private String syncMode;

    @Schema(description = "生成任务数上限")
    private Integer taskLimit;

    @Schema(description = "机构标识字段")
    @Size(max = 64)
    private String orgField;

    @Schema(description = "通用分组字段")
    @Size(max = 64)
    private String groupField;

    @Schema(description = "状态：0-启用 1-停用")
    private Integer status;

    @Schema(description = "描述")
    @Size(max = 1024)
    private String description;

}
