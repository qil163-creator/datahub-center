package cn.iocoder.yudao.module.governance.dal.dataobject.standardterm;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 数据标准--业务术语 DO
 *
 * <p>业务术语表（Glossary）：统一业务口径的名词定义，
 * 支持别名、负责人（Owner）、数据管家（Steward）与关联数据元。</p>
 *
 * @author Li
 */
@TableName("dh_governance_standard_term")
@KeySequence("dh_governance_standard_term_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StandardTermDO extends BaseDO {

    /**
     * 主键
     */
    @TableId
    private Long id;

    /**
     * 术语名称（全局唯一）
     */
    private String name;

    /**
     * 英文名称
     */
    private String engName;

    /**
     * 别名/同义词，逗号分隔
     */
    private String alias;

    /**
     * 业务定义
     */
    private String definition;

    /**
     * 所属类目，关联 dh_governance_category_datameta.id
     */
    private Long categoryId;

    /**
     * 业务术语类目编号。
     */
    private Long termCategoryId;

    /**
     * 关联标准数据元编号
     */
    private Integer relatedElementId;

    /**
     * 负责人（Owner）用户编号
     */
    private Long ownerUserId;

    /**
     * 数据管家（Steward）用户编号
     */
    private Long stewardUserId;

    /**
     * 状态
     *
     * 字典 common_status
     */
    private Short status;

    /**
     * 备注
     */
    private String remark;

}
