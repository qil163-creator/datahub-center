package cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "管理后台 - 资产地图分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class DataAssetMapPageReqVO extends DataAssetPageReqVO {

    @Schema(description = "数据板块编号，匹配资产登记配置")
    private Long dataDomainId;
}
