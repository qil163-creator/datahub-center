package cn.iocoder.yudao.module.asset.dal.dataobject.term;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 数据资产-业务术语与资产字段绑定 DO（一个字段只允许绑定一个术语）
 */
@TableName("dh_asset_term_column_rel")
@KeySequence("dh_asset_term_column_rel_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssetTermColumnRelDO extends BaseDO {

    @TableId
    private Long id;

    /** 治理业务术语编号。 */
    private Long standardTermId;

    private Long assetId;

    private Long metaColumnId;

    /**
     * 字段名称快照
     */
    private String columnName;

    private String remark;

}
