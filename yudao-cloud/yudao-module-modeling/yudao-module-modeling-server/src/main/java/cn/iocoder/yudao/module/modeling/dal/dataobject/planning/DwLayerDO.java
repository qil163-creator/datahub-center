package cn.iocoder.yudao.module.modeling.dal.dataobject.planning;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 数仓分层 DO
 *
 * @author Li
 */
@TableName("dh_modeling_dw_layer")
@KeySequence("dh_dw_layer_seq") // 用于 Oracle、PostgreSQL、Kingbase、DB2、H2 数据库的主键自增。如果是 MySQL 等数据库，可不写。
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DwLayerDO extends BaseDO {

    public static final Long PARENT_ID_ROOT = 0L;

    public static final String NODE_TYPE_CATEGORY = "CATEGORY";

    public static final String NODE_TYPE_LAYER = "LAYER";

    public static final Short IS_BUILTIN_YES = 1;

    /**
     * 主键
     */
    @TableId
    private Long id;

    /**
     * 父级ID，0表示一级分类节点
     */
    private Long parentId;

    /**
     * 祖级列表，如 0 或 0,1
     */
    private String ancestors;

    /**
     * 层级：1分类节点，2实际分层
     */
    private Short levelNo;

    /**
     * 分层名称
     */
    private String layerName;

    /**
     * 分层编码：ODS/DIM/DWD/DWS/ADS，分类节点为空
     */
    private String layerCode;

    /**
     * 节点类型：CATEGORY分类节点/LAYER实际分层节点
     */
    private String nodeType;

    /**
     * 默认表前缀，仅实际分层节点填写
     */
    private String tablePrefix;

    /**
     * 默认表类型：SOURCE贴源/DETAIL明细/DIMENSION维度/SUMMARY汇总/APPLICATION应用
     *
     * 枚举 {@link TODO default_table_type 对应的类}
     */
    private String defaultTableType;

    /**
     * 负责人ID
     */
    private Long ownerUserId;

    /**
     * 排序
     */
    private Integer sortOrder;

    /**
     * 是否内置：0否 1是，内置节点不允许删除
     *
     * 枚举 {@link TODO is_builtin 对应的类}
     */
    private Short isBuiltin;

    /**
     * 状态：0-启用 1-停用
     *
     * 枚举 {@link cn.iocoder.yudao.framework.common.enums.CommonStatusEnum}
     */
    private Short status;

    /**
     * 说明
     */
    private String description;

    /**
     * 备注
     */
    private String remark;


}
