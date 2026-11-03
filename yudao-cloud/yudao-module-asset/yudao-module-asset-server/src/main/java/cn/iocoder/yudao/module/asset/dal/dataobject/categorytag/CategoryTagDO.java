package cn.iocoder.yudao.module.asset.dal.dataobject.categorytag;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 数据资产-标签类目 DO（树形）
 */
@TableName("dh_asset_category_tag")
@KeySequence("dh_asset_category_tag_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CategoryTagDO extends BaseDO {

    public static final Long PARENT_ID_ROOT = 0L;

    @TableId
    private Long id;

    private Long parentId;

    private String categoryName;

    private String categoryCode;

    private Integer sort;

    /**
     * 状态：0-启用 1-停用
     */
    private Integer status;

    private String remark;

    private String sourceRecordId;

}
