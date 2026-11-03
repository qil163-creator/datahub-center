package cn.iocoder.yudao.module.asset.dal.dataobject.dataasset;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 外部目录迁入的独立本地快照，不代表原数据源连接或共享授权。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dh_asset_import_dataset")
public class AssetImportDatasetDO extends BaseDO {

    @TableId
    private Long assetId;

    private String sourceAssetId;

    private String sourceName;

    private String sourceType;

    private Long sourceDataCount;

    private Long importedRowCount;

    private Boolean complete;

    private String columnsJson;

    private String fileInfoJson;

    private String qualityJson;

    private String lineageJson;

    private Integer revision;
}
