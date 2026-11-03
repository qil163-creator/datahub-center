package cn.iocoder.yudao.module.asset.dal.dataobject.assettag;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

@TableName("dh_asset_tag_rel")
@KeySequence("dh_asset_tag_rel_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssetTagRelDO extends BaseDO {

    @TableId
    private Long id;

    private Long assetId;

    private Long tagId;

    private String remark;

    private Integer revision;

    private String sourceRecordKey;

}
