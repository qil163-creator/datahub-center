package cn.iocoder.yudao.module.asset.dal.dataobject.dataasset;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dh_asset_import_row_log")
@KeySequence("dh_asset_import_row_log_seq")
public class AssetImportRowLogDO extends BaseDO {

    @TableId
    private Long id;

    private Long assetId;

    private Long rowId;

    private Integer revision;

    private String beforeJson;

    private String afterJson;
}
