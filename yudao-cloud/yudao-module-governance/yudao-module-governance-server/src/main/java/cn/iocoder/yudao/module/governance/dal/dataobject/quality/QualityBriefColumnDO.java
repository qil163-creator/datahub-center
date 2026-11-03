package cn.iocoder.yudao.module.governance.dal.dataobject.quality;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 数据治理-数据质量-简报模板附列 DO
 *
 * @author Li
 */
@TableName("dh_governance_quality_brief_column")
@KeySequence("dh_governance_quality_brief_column_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QualityBriefColumnDO extends BaseDO {

    @TableId
    private Long id;

    /** 段落ID */
    private Long paragraphId;

    /** 字段名 */
    private String fieldName;

    /** 列标题 */
    private String colTitle;

    /** 数据字段 */
    private String dataField;

    /** 格式 */
    private String format;

    /** 排序 */
    private Integer sortNo;

}
