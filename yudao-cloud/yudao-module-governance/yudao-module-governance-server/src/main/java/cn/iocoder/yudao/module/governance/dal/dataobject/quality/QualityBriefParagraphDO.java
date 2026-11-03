package cn.iocoder.yudao.module.governance.dal.dataobject.quality;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 数据治理-数据质量-简报模板段落 DO
 *
 * @author Li
 */
@TableName("dh_governance_quality_brief_paragraph")
@KeySequence("dh_governance_quality_brief_paragraph_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QualityBriefParagraphDO extends BaseDO {

    /** 段落类型 - 正文 */
    public static final String TYPE_TEXT = "TEXT";

    /** 段落类型 - 附表 */
    public static final String TYPE_TABLE = "TABLE";

    @TableId
    private Long id;

    /** 模板ID */
    private Long templateId;

    /** 段落类型：TEXT正文/TABLE附表 */
    private String paraType;

    /** 排序码 */
    private Integer sortNo;

    /** 标题 */
    private String title;

    /** 内容模板（含变量 ${totalOrg} 等） */
    private String contentText;

    /** 关联规则集ID */
    private Long ruleSetId;

}
