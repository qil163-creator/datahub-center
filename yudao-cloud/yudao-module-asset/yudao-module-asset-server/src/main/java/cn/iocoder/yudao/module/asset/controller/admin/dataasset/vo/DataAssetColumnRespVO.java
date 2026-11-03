package cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo;

import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataColumnRespDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 资产字段：元数据技术属性 + 资产侧业务语义（术语）+ 标准信息（数据元）
 */
@Schema(description = "管理后台 - 数据资产字段 Response VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class DataAssetColumnRespVO extends MetadataColumnRespDTO {

    @Schema(description = "已绑定的治理业务术语编号")
    private Long termId;

    @Schema(description = "已绑定的业务术语名称")
    private String termName;

}
