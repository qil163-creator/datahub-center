package cn.iocoder.yudao.module.governance.dal.dataobject.categorydataquality;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 数据质量类目管理 DO
 *
 * @author Li
 */
@TableName("dh_governance_category_data_quality")
@KeySequence("dh_foundation_category_data_quality_seq")
// 用于 Oracle、PostgreSQL、Kingbase、DB2、H2 数据库的主键自增。如果是 MySQL 等数据库，可不写。
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CategoryDataQualityDO extends BaseDO {

    public static final Long PARENT_ID_ROOT = 0L;

    /**
     * 主键
     */
    @TableId
    private Long id;

    /**
     * 父级ID
     */
    private Long parentId;

    /**
     * 类目名称
     */
    private String categoryName;

    /**
     * 层级编码
     */
    private String categoryCode;

    /**
     * 排序
     */
    private Integer sort;

    /**
     * 状态
     *
     * 枚举 {@link TODO common_status 对应的类}
     */
    private Short status;

    /**
     * 备注
     */
    private String remark;


}
