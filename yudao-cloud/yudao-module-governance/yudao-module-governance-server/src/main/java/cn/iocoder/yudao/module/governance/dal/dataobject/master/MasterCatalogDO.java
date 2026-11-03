package cn.iocoder.yudao.module.governance.dal.dataobject.master;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 主数据目录 DO
 *
 * 参考 srt-cloud 的 data_governance_master_data_catalog：
 * 多级目录树组织主数据主题，主数据类型的叶子节点下挂主数据模型
 *
 * @author Li
 */
@TableName("dh_governance_master_catalog")
@KeySequence("dh_governance_master_catalog_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MasterCatalogDO extends BaseDO {

    public static final Long PARENT_ID_ROOT = 0L;

    /** 普通目录（可建子节点） */
    public static final int TYPE_DIR = 0;

    /** 主数据节点（下挂主数据模型） */
    public static final int TYPE_MASTER = 1;

    /**
     * 主键
     */
    @TableId
    private Long id;

    /**
     * 父级编号，0 表示根节点
     */
    private Long parentId;

    /**
     * 节点类型：0-普通目录 1-主数据
     */
    private Integer type;

    /**
     * 节点名称
     */
    private String name;

    /**
     * 同级排序号
     */
    private Integer orderNo;

    /**
     * 描述
     */
    private String description;

    /**
     * 完整路径（各级名称以 / 拼接）
     */
    private String path;

    /**
     * 所属机构编号
     */
    private Long orgId;

}
