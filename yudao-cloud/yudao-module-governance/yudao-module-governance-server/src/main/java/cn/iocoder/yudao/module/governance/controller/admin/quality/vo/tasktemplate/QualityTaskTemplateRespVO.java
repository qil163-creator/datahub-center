package cn.iocoder.yudao.module.governance.controller.admin.quality.vo.tasktemplate;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 任务模板 Response VO")
@Data
public class QualityTaskTemplateRespVO {

    @Schema(description = "编号")
    private Long id;

    @Schema(description = "模板名称")
    private String templateName;

    @Schema(description = "模板编码")
    private String templateCode;

    @Schema(description = "数据源连接ID")
    private Long datasourceConnId;

    @Schema(description = "规则来源：RULE_SET/SPECIFY_RULE")
    private String ruleSourceType;

    @Schema(description = "规则集编号")
    private Long ruleSetId;

    @Schema(description = "规则集名称")
    private String ruleSetName;

    @Schema(description = "指定规则的规则ID集合（逗号分隔）")
    private String ruleIds;

    @Schema(description = "表范围类型")
    private String tableScopeType;

    @Schema(description = "表名匹配模式")
    private String tableMatch;

    @Schema(description = "指定表的表ID集合")
    private String tableIds;

    @Schema(description = "分层")
    private String layer;

    @Schema(description = "字段选择策略")
    private String fieldSelectStrategy;

    @Schema(description = "调度类型")
    private String scheduleType;

    @Schema(description = "cron表达式")
    private String cronExpression;

    @Schema(description = "同步模式")
    private String syncMode;

    @Schema(description = "生成任务数上限")
    private Integer taskLimit;

    @Schema(description = "机构标识字段")
    private String orgField;

    @Schema(description = "通用分组字段")
    private String groupField;

    @Schema(description = "状态：0-启用 1-停用")
    private Integer status;

    @Schema(description = "描述")
    private String description;

    @Schema(description = "已生成任务数")
    private Long generatedTaskCount;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
