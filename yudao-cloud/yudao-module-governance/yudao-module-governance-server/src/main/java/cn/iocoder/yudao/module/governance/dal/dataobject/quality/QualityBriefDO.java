package cn.iocoder.yudao.module.governance.dal.dataobject.quality;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 数据治理-数据质量-质检简报 DO
 *
 * 生成的简报实例
 *
 * @author Li
 */
@TableName("dh_governance_quality_brief")
@KeySequence("dh_governance_quality_brief_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QualityBriefDO extends BaseDO {

    /** 简报状态 - 草稿 */
    public static final String STATUS_DRAFT = "DRAFT";

    /** 简报状态 - 已发布 */
    public static final String STATUS_PUBLISHED = "PUBLISHED";

    @TableId
    private Long id;

    /** 简报标题 */
    private String briefTitle;

    /** 期号 */
    private String issueNo;

    /** 评估周期 */
    private String assessCycle;

    /** 评估开始（YYYYMM） */
    private String assessStart;

    /** 评估结束（YYYYMM） */
    private String assessEnd;

    /** 模板ID */
    private Long templateId;

    /** 简报状态：DRAFT草稿/PUBLISHED已发布 */
    private String briefStatus;

    /** 简报内容JSON（渲染后） */
    private String briefContent;

    /** 生成时间 */
    private LocalDateTime generateTime;

    /** 生成人 */
    private String generator;

}
