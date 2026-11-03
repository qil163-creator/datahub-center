package cn.iocoder.yudao.module.asset.dal.dataobject.dataasset;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

@TableName("dh_asset_etl_batch_binding")
@KeySequence("dh_asset_etl_batch_binding_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class AssetEtlBatchBindingDO extends BaseDO {

    @TableId
    private Long id;

    private Long assetId;

    private Integer revision;

    private Long batchId;

    private Long recordId;

    private String payloadHash;

    private Long databaseId;

    private String schemaName;

    private String tableName;

    private Long actorId;

    private String reason;
}
