package cn.iocoder.yudao.module.asset.controller.admin.assettag.vo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;

@Data
public class AssetTagDeleteListReqVO {

    @NotEmpty
    @Size(max = 100)
    private List<@NotNull @Valid Item> items;

    @Data
    public static class Item {

        @NotNull
        @Positive
        private Long id;

        @NotNull
        @Min(0)
        private Integer revision;
    }
}
