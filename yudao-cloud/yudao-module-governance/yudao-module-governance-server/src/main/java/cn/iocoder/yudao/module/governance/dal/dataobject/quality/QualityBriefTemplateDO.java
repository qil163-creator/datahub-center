package cn.iocoder.yudao.module.governance.dal.dataobject.quality;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 数据治理-数据质量-质检简报模板 DO
 *
 * 正文段落 + 附表配置 + 可用变量插值
 *
 * @author Li
 */
@TableName("dh_governance_quality_brief_template")
@KeySequence("dh_governance_quality_brief_template_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QualityBriefTemplateDO extends BaseDO {

    /** 状态 - 启用 */
    public static final Integer STATUS_ENABLE = 0;

    /** 状态 - 停用 */
    public static final Integer STATUS_DISABLE = 1;

    @TableId
    private Long id;

    /** 模板名称 */
    private String templateName;

    /** 模板编码 */
    private String templateCode;

    /** 评估周期：DAY/WEEK/MONTH */
    private String assessCycle;

    /** 评估维度：按机构 */
    private String assessDim;

    /** 排序 */
    private Integer sortNo;

    /** 状态：0-启用 1-停用 */
    private Integer status;

    /** 描述 */
    private String description;

}
