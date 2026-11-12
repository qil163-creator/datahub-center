package cn.iocoder.yudao.module.governance.dal.dataobject.lineage;

import lombok.Data;

/**
 * 影响分析逐层展开时的投影：被触达的下游 (节点[, 字段]) 及触达它的来源；非表实体。
 */
@Data
public class LineageEdgeHit {

    private Long nodeId;

    /** 字段名；表级展开时为空 */
    private String columnName;

    private Long sourceRefId;

}
