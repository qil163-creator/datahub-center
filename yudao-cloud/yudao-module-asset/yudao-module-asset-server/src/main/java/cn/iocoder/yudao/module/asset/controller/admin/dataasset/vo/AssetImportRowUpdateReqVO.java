package cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.Map;

@Data
public class AssetImportRowUpdateReqVO {

    @NotNull
    private Long id;

    @NotNull
    private Long rowId;

    @NotNull
    @Min(0)
    private Integer revision;

    @NotNull
    @Size(min = 1, max = 2000)
    private Map<@NotBlank @Size(max = 256) String, @Size(max = 65536) String> values;
}
