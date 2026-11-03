package cn.iocoder.yudao.module.asset.controller.admin.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Schema(description = "管理后台 - 本人已批准资产详情；只读历史不恢复授权")
@Data
@EqualsAndHashCode(callSuper = true)
public class MarketMyAssetDetailRespVO extends MarketMyAssetRespVO {

    private String description;

    private String assetVersion;

    private String purpose;

    private String dutyMatter;

    private String legalBasis;

    private List<String> approvedColumns;

    private List<MarketCheckProcessRespVO> processes;
}
