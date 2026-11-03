package cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;

@Data
public class AssetGenericRegisterReqVO {

    @NotNull
    @Min(2)
    @Max(3)
    private Integer assetType;

    @Valid
    @NotEmpty
    @Size(max = 200)
    private List<Item> items;

    @Data
    public static class Item {

        @NotNull
        @Positive
        private Long candidateId;

        @NotBlank
        @Size(max = 256)
        private String assetName;

        private Long categoryId;

        private Long subjectDomainId;

        @Size(max = 2000)
        private String description;

        @Valid
        private AssetRegistrationConfigVO registrationConfig;
    }
}
