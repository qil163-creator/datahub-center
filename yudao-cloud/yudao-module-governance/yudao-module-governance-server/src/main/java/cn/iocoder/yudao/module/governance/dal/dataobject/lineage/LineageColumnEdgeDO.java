package cn.iocoder.yudao.module.governance.dal.dataobject.lineage;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 数据血缘-字段级边。派生数据：按来源整体替换，只经 Mapper 显式 {@code @Delete} 物理删除，不走逻辑删除。
 */
@TableName("dh_governance_lineage_column_edge")
@KeySequence("dh_governance_lineage_column_edge_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class LineageColumnEdgeDO extends BaseDO {

    @TableId
    private Long id;

    /** 所属上报来源编号 */
    private Long sourceRefId;

    /** 上游节点编号 */
    private Long upstreamNodeId;

    /** 上游字段名（小写规范化） */
    private String upstreamColumn;

    /** 下游节点编号 */
    private Long downstreamNodeId;

    /** 下游字段名（小写规范化） */
    private String downstreamColumn;

    /** 变换大类 */
    private String transformType;

    /** 变换子类 */
    private String transformSubtype;

    /** 是否存在歧义 */
    private Boolean ambiguous;

    /** 是否经过遮蔽处理 */
    private Boolean masking;

    /** 所在语句序号 */
    private Integer statementIndex;

}
