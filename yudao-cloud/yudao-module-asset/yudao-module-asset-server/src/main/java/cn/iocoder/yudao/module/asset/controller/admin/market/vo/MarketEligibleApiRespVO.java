package cn.iocoder.yudao.module.asset.controller.admin.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 当前资产可申请开通的 API")
@Data
public class MarketEligibleApiRespVO {

    private Long id;

    private String name;

    private String path;

    private String version;

    private String apiType;

    private String configType;

}
