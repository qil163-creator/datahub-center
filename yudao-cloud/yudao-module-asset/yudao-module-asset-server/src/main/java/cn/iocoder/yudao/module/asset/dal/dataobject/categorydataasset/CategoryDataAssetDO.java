package cn.iocoder.yudao.module.asset.dal.dataobject.categorydataasset;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 数据资产类目管理 DO
 *
 * @author Li
 */
@TableName("dh_asset_category_data_asset")
@KeySequence("dh_asset_category_data_asset_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CategoryDataAssetDO extends BaseDO {

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
