package cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo;

import lombok.Data;

import java.util.Map;

@Data
public class AssetImportSnapshotRespVO {

    private String sourceAssetId;

    private String sourceName;

    private String sourceType;

    private Long sourceDataCount;

    private Long importedRowCount;

    private Boolean complete;

    private Map<String, Object> fileInfo;

    private Map<String, Object> quality;

    private Map<String, Object> lineage;
}
