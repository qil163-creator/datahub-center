package cn.iocoder.yudao.module.asset.controller.admin.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "管理后台 - 指定同部门申请的资产详情，不转授申请人的消费许可")
public class MarketDepartmentAssetDetailRespVO {

    private MarketDepartmentAssetRespVO asset;

    private MarketCheckDetailRespVO.ResourceSummary resource;

    private List<MarketCheckDetailRespVO.Column> columns = List.of();

    private MarketCheckDetailRespVO.Preview preview;

    private MarketCheckDetailRespVO.ApiDocument apiDocument;

    private Boolean canPreview = false;

    private Boolean canViewApiDocument = false;

    private Boolean canInvoke = false;
}
