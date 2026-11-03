package cn.iocoder.yudao.module.governance.dal.dataobject.quality;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 数据治理-数据质量-检测结果明细 DO
 *
 * 规则级执行明细（任务实例下的每条规则 × 检查对象结果）
 *
 * @author Li
 */
@TableName("dh_governance_quality_audit_log")
@KeySequence("dh_governance_quality_audit_log_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QualityAuditLogDO extends BaseDO {

    /** 结果类型 - 通过 */
    public static final String RESULT_PASS = "PASS";

    /** 结果类型 - 未通过 */
    public static final String RESULT_FAIL = "FAIL";

    /** 结果类型 - 执行出错 */
    public static final String RESULT_ERROR = "ERROR";

    /** 结果类型 - 无样本跳过，不能当作合格 */
    public static final String RESULT_SKIPPED = "SKIPPED";

    /** 结果类型 - 未执行 */
    public static final String RESULT_NOT_RUN = "NOT_RUN";

    @TableId
    private Long id;

    /** 任务实例ID */
    private Long instanceId;

    /** 任务ID */
    private Long taskId;

    /** 规则ID */
    private Long ruleId;

    /** 任务专属评测编号。 */
    private Long evaluationId;

    /** 规则名称 */
    private String ruleName;

    /** 规则类型 */
    private String ruleType;

    /** 检查对象ID */
    private Long checkObjectId;

    /** 检测表 */
    private String tableName;

    /** 检测字段 */
    private String checkField;

    /** 分组 */
    private String groupName;

    /** 检测时间 */
    private LocalDateTime checkTime;

    /** 总记录数 */
    private Long totalCount;

    /** 失败数 */
    private Long failCount;

    /** 通过率 */
    private BigDecimal passRate;

    /** 结果类型：PASS通过/FAIL未通过 */
    private String resultType;

    /** 指标值 */
    private String indexValue;

    /** 检测耗时（毫秒） */
    private Long costMs;

    /** 执行SQL */
    private String resultSql;

    /** 错误详情 */
    private String errorDetail;

    /** 实际读取的非敏感绑定、时点及执行 SQL 摘要；旧记录为空，不推断回填。 */
    private String readEvidence;

}
