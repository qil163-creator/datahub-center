package cn.iocoder.yudao.module.governance.dal.dataobject.quality;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 数据治理-数据质量-问题数据 DO
 *
 * 定位式留存：只存「定位SQL + 主键值」，不复制源数据，查看时实时回源
 *
 * @author Li
 */
@TableName("dh_governance_quality_issue_data")
@KeySequence("dh_governance_quality_issue_data_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QualityIssueDataDO extends BaseDO {

    /** 处理状态 - 未处理 */
    public static final String STATUS_UNPROCESSED = "UNPROCESSED";

    /** 处理状态 - 已处理 */
    public static final String STATUS_PROCESSED = "PROCESSED";

    /** 统计口径 - 按机构 */
    public static final String SCOPE_ORG = "ORG";

    /** 统计口径 - 全部合计 */
    public static final String SCOPE_ALL = "ALL";

    @TableId
    private Long id;

    /** 任务实例ID */
    private Long instanceId;

    /** 检测结果ID */
    private Long auditLogId;

    /** 任务ID */
    private Long taskId;

    /** 规则ID */
    private Long ruleId;

    /** 检查对象ID */
    private Long objectId;

    /** 检测表 */
    private String tableName;

    /** 检测字段 */
    private String checkField;

    /** 机构ID */
    private Long orgId;

    /** 地区编码 */
    private String regionCode;

    /** 统计口径：ORG按机构/ALL全部合计 */
    private String statScope;

    /** 问题行数 */
    private Long issueCount;

    /** 主键留存值 */
    private String pkValue;

    /** 定位SQL（可重放） */
    private String locationSql;

    /** 处理状态：UNPROCESSED未处理/PROCESSED已处理 */
    private String issueStatus;

    /** 检出时间 */
    private LocalDateTime checkTime;

}
