package cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "管理后台 - 数据资产编目 Request VO")
@Data
public class DataAssetRegisterReqVO {

    @Schema(description = "已发布的表元数据编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "表元数据编号不能为空")
    private Long metaTableId;

    @Schema(description = "资产名称；为空时按业务名称、注释、表名依次生成")
    @Size(max = 256, message = "资产名称不能超过 256 个字符")
    private String assetName;

    @Schema(description = "资产类目编号")
    private Long categoryId;

    @Schema(description = "所属主题域编号，由服务端核验可见范围与名称")
    private Long subjectDomainId;

    @Valid
    private AssetRegistrationConfigVO registrationConfig;

    @Size(max = 2000, message = "资产说明不能超过 2000 个字符")
    private String description;

    @Size(max = 512, message = "备注不能超过 512 个字符")
    private String remark;

}
