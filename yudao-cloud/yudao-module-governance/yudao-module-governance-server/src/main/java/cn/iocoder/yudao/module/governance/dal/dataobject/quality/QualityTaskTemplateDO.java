package cn.iocoder.yudao.module.governance.dal.dataobject.quality;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 数据治理-数据质量-任务模板 DO
 *
 * 模板 = 规则集 + 表范围 + 字段选择策略 + 调度，同步后物化为实体任务
 *
 * @author Li
 */
@TableName("dh_governance_quality_task_template")
@KeySequence("dh_governance_quality_task_template_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QualityTaskTemplateDO extends BaseDO {

    /** 状态 - 启用 */
    public static final Integer STATUS_ENABLE = 0;

    /** 状态 - 停用 */
    public static final Integer STATUS_DISABLE = 1;

    /** 规则来源 - 规则集 */
    public static final String RULE_SOURCE_RULE_SET = "RULE_SET";

    /** 规则来源 - 指定规则 */
    public static final String RULE_SOURCE_SPECIFY = "SPECIFY_RULE";

    /** 表范围 - 整个数据源 */
    public static final String SCOPE_DATASOURCE = "DATASOURCE";

    /** 表范围 - 表名匹配 */
    public static final String SCOPE_TABLE_MATCH = "TABLE_MATCH";

    /** 表范围 - 指定表 */
    public static final String SCOPE_SPECIFY_TABLE = "SPECIFY_TABLE";

    /** 表范围 - 按分层 */
    public static final String SCOPE_LAYER = "LAYER";

    @TableId
    private Long id;

    /** 模板名称 */
    private String templateName;

    /** 模板编码 */
    private String templateCode;

    /** 数据源连接ID */
    private Long datasourceConnId;

    /** 规则来源：RULE_SET规则集/SPECIFY_RULE指定规则 */
    private String ruleSourceType;

    /** 规则集ID */
    private Long ruleSetId;

    /** 指定规则的规则ID集合（逗号分隔） */
    private String ruleIds;

    /** 表范围类型：DATASOURCE/TABLE_MATCH/SPECIFY_TABLE/LAYER */
    private String tableScopeType;

    /** 表名匹配模式 */
    private String tableMatch;

    /** 指定表的表ID集合（逗号分隔） */
    private String tableIds;

    /** 分层（表范围类型为LAYER时生效） */
    private String layer;

    /** 字段选择策略 */
    private String fieldSelectStrategy;

    /** 调度类型 */
    private String scheduleType;

    /** cron表达式 */
    private String cronExpression;

    /** 同步模式：FULL全量 */
    private String syncMode;

    /** 生成任务数上限 */
    private Integer taskLimit;

    /** 机构标识字段 */
    private String orgField;

    /** 通用分组字段 */
    private String groupField;

    /** 状态：0-启用 1-停用 */
    private Integer status;

    /** 描述 */
    private String description;

}
