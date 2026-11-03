package cn.iocoder.yudao.module.supply.controller.admin.app.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "管理后台 - 应用分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class SupplyAppPageReqVO extends PageParam {

    @Schema(description = "应用名称（模糊）", example = "灌区")
    private String name;

    @Schema(description = "应用类型：Web/App/小程序", example = "Web")
    private String appType;

    @Schema(description = "是否公开：0-非公开 1-公开", example = "1")
    private Integer publicFlag;

}
