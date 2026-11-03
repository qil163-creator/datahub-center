package cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo;

import cn.iocoder.yudao.module.asset.controller.admin.quality.vo.AssetQualityTaskRespVO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "管理后台 - 数据资产详情 Response VO")
@Data
public class DataAssetDetailRespVO {

    private DataAssetRespVO asset;

    private List<DataAssetColumnRespVO> columns;

    private List<AssetQualityTaskRespVO> qualityTasks;

    private AssetImportSnapshotRespVO importedSnapshot;

}
