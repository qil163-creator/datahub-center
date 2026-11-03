package cn.iocoder.yudao.module.asset.controller.admin.sensitive.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "管理后台 - 敏感清单批量删除")
public class AssetSensitiveDeleteReqVO {

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
