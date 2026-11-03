package cn.iocoder.yudao.module.asset.dal.dataobject.dataasset;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dh_asset_import_row")
@KeySequence("dh_asset_import_row_seq")
public class AssetImportRowDO extends BaseDO {

    @TableId
    private Long id;

    private Long assetId;

    private Integer ordinal;

    private String rowJson;

    private Integer revision;
}
