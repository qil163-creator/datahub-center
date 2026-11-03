package cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo;

import cn.iocoder.yudao.module.dataforge.api.etl.dto.EtlTargetBatchDescribeRespDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "管理后台 - 资产冻结批次关联及实时准入摘要")
public class AssetEtlBatchBindingRespVO {

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

    private EtlTargetBatchDescribeRespDTO batch;
}
