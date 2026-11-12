package cn.iocoder.yudao.module.governance.dal.dataobject.security;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * 数据安全-异常访问告警。只记录统计值与规则，不记录访问到的数据。
 */
@TableName("dh_governance_safety_access_alert")
@KeySequence("dh_governance_safety_access_alert_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class SafetyAccessAlertDO extends BaseDO {

    /** 规则：窗口内敏感访问次数过多。 */
    public static final String RULE_VOLUME = "VOLUME";
    /** 规则：窗口内豁免明文访问过多。 */
    public static final String RULE_PLAINTEXT = "PLAINTEXT";
    /** 规则：非工作时段访问敏感数据。 */
    public static final String RULE_OFF_HOURS = "OFF_HOURS";
    /** 规则：窗口内访问的表过多。 */
    public static final String RULE_TABLE_SPREAD = "TABLE_SPREAD";
    /** 状态：待处理。 */
    public static final String STATUS_OPEN = "OPEN";
    /** 状态：已确认风险。 */
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    /** 状态：已忽略（误报）。 */
    public static final String STATUS_IGNORED = "IGNORED";

    @TableId
    private Long id;
    /** 去重键：规则 + 用户 + 窗口开始，重复识别不重复告警 */
    private String dedupeKey;
    /** 规则编码 */
    private String ruleCode;
    /** 访问用户 */
    private Long userId;
    /** 访问用户名称快照 */
    private String userName;
    /** 窗口开始 */
    private LocalDateTime windowStart;
    /** 窗口结束 */
    private LocalDateTime windowEnd;
    /** 统计值 */
    private Integer metricValue;
    /** 阈值 */
    private Integer threshold;
    /** 状态 */
    private String status;
    /** 处理人 */
    private Long handlerId;
    /** 处理时间 */
    private LocalDateTime handleTime;
    /** 处理说明 */
    private String handleRemark;
    /** 乐观锁修订号 */
    private Integer revision;
}
