package cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo;

import cn.iocoder.yudao.module.asset.controller.admin.quality.vo.AssetQualityTaskRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketResourceRespVO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "集市消费者详情，不含数据源主机等管理秘密")
@Data
public class MarketConsumerDetailRespVO {

    private MarketResourceRespVO asset;

    private List<DataAssetColumnRespVO> columns;

    private List<AssetQualityTaskRespVO> qualityTasks;

}
