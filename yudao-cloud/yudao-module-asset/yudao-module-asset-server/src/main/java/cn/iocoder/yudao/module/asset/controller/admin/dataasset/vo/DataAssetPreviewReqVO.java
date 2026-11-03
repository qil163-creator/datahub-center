package cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Schema(description = "管理后台 - 数据资产带条件只读预览 Request VO")
@Data
public class DataAssetPreviewReqVO {

    @NotNull(message = "资产编号不能为空")
    private Long id;

    @Schema(description = "预览行数，1-100，默认 20")
    private Integer limit;

    @jakarta.validation.constraints.Min(1)
    @jakarta.validation.constraints.Max(1000000)
    private Integer pageNo;

    @jakarta.validation.constraints.Min(1)
    @jakarta.validation.constraints.Max(100)
    private Integer pageSize;

    @Valid
    @Size(max = 10, message = "过滤条件最多 10 条")
    private List<Filter> filters;

    @Schema(description = "过滤条件（AND 连接）")
    @Data
    public static class Filter {

        @NotBlank(message = "过滤字段不能为空")
        private String column;

        @Schema(description = "eq/ne/gt/ge/lt/le/like/isNull/notNull")
        @NotBlank(message = "操作符不能为空")
        private String operator;

        private String value;

    }

}
