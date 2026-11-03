package cn.iocoder.yudao.module.governance.dal.dataobject.quality;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.math.BigDecimal;

/**
 * 数据治理-数据质量-机构评分 DO
 *
 * 机构维度得分率 + 排名
 *
 * @author Li
 */
@TableName("dh_governance_quality_org_score")
@KeySequence("dh_governance_quality_org_score_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QualityOrgScoreDO extends BaseDO {

    @TableId
    private Long id;

    /** 规则集ID */
    private Long ruleSetId;

    /** 周期开始（YYYYMM） */
    private String periodStart;

    /** 周期结束（YYYYMM） */
    private String periodEnd;

    /** 机构ID */
    private Long orgId;

    /** 机构名称 */
    private String orgName;

    /** 机构编码 */
    private String orgCode;

    /** 得分率（%） */
    private BigDecimal scoreRate;

    /** 达标规则数 */
    private Long passRuleCount;

    /** 总规则数 */
    private Long totalRuleCount;

    /** 区域排名 */
    private Integer regionRank;

}
