package cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.NotNull;

import java.util.List;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "资产注册的业务配置，不修改物理表及其元数据")
public class AssetRegistrationConfigVO {

    @Positive
    private Long dataDomainId;

    @Size(max = 256)
    private String dataDomainName;

    @Positive
    private Long entityId;

    @Size(max = 32)
    private List<@NotNull @Positive Long> granularityEntityIds;

    @Size(max = 256)
    private String businessEnglishName;

    @Size(max = 256)
    private String businessEntity;

    @Pattern(regexp = "1|2|3", message = "数据时效无效")
    private String timeEffect;

    @Size(max = 2000)
    private String statGranularity;

    @Pattern(regexp = "LOWER|UPPER|MIXED", message = "表命名规范无效")
    private String namingCase;

    @Pattern(regexp = "ODS|DIM|DWD|DWS|ADS", message = "表类型无效")
    private String tableType;

    @Size(max = 64)
    private String layer;
}
