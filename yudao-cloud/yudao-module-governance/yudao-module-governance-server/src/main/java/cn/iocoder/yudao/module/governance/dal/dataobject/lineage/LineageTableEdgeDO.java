package cn.iocoder.yudao.module.governance.dal.dataobject.lineage;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 数据血缘-表级边。派生数据：按来源整体替换，只经 Mapper 显式 {@code @Delete} 物理删除，不走逻辑删除。
 */
@TableName("dh_governance_lineage_table_edge")
@KeySequence("dh_governance_lineage_table_edge_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class LineageTableEdgeDO extends BaseDO {

    @TableId
    private Long id;

    /** 所属上报来源编号 */
    private Long sourceRefId;

    /** 上游节点编号 */
    private Long upstreamNodeId;

    /** 下游节点编号 */
    private Long downstreamNodeId;

    /** 边来源，取值见 LineageEdgeOriginEnum */
    private String origin;

    /** 所在语句序号 */
    private Integer statementIndex;

}
