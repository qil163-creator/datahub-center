package cn.iocoder.yudao.module.asset.dal.dataobject.dataasset;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

import java.time.LocalDateTime;

@TableName("dh_asset_catalog_link_operation")
@KeySequence("dh_asset_catalog_link_operation_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class AssetCatalogLinkOperationDO extends BaseDO {

    @TableId
    private Long id;

    private String operationId;

    private Long assetId;

    private Long tableId;

    private String sourceKey;

    private Long sourceRevision;

    private Boolean linked;

    private String requestHash;

    private String status;

    private String outcome;

    private Integer attempts;

    private String claimToken;

    private LocalDateTime leaseUntil;

    private LocalDateTime nextRetryTime;

    private String lastError;

    private String dispositionReason;
}
