package cn.iocoder.yudao.module.governance.dal.dataobject.lineage;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 数据血缘-节点：库表或外部数据集，按规范化 nodeKey 全局去重；节点不删除，没有边的节点不参与检索。
 */
@TableName("dh_governance_lineage_node")
@KeySequence("dh_governance_lineage_node_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class LineageNodeDO extends BaseDO {

    @TableId
    private Long id;

    /** 规范化节点键，见 LineageNodeKeys */
    private String nodeKey;

    /** 节点类型，取值见 LineageNodeTypeEnum */
    private String nodeType;

    /** 数据源编号（TABLE） */
    private Long datasourceId;

    /** schema 名（TABLE，可空） */
    private String schemaName;

    /** 表名（TABLE） */
    private String tableName;

    /** 外部数据集种类（EXTERNAL） */
    private String externalKind;

    /** 外部数据集标识（EXTERNAL） */
    private String externalKey;

    /** 展示名 */
    private String displayName;

}
