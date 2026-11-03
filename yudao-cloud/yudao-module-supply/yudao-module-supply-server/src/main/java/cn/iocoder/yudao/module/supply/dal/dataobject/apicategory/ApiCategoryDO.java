package cn.iocoder.yudao.module.supply.dal.dataobject.apicategory;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 数据服务-服务分类 DO（树形）
 *
 * @author Li
 */
@TableName("dh_supply_api_category")
@KeySequence("dh_supply_api_category_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiCategoryDO extends BaseDO {

    public static final Long PARENT_ID_ROOT = 0L;

    /**
     * 主键
     */
    @TableId
    private Long id;

    /**
     * 父级编号，0 表示顶级节点
     */
    private Long parentId;

    /**
     * 数据服务类目名称
     */
    private String name;

    /**
     * 状态：0-启用 1-停用
     */
    private Integer status;

    /**
     * 排序
     */
    private Integer sort;

    /**
     * 描述
     */
    private String description;

    /**
     * 备注
     */
    private String remark;

}
