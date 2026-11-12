package cn.iocoder.yudao.module.governance.dal.dataobject.lineage;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 字段级血缘图中的 (节点, 字段) 键；非表实体，仅作查询参数与投影结果。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LineageColumnKey {

    private Long nodeId;

    /** 小写规范化后的字段名 */
    private String columnName;

}
