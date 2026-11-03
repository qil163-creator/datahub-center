package cn.iocoder.yudao.module.asset.controller.admin.market.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class MarketTopicRespVO {

    private Long id;

    private String name;

    private Long assetCount;
}
