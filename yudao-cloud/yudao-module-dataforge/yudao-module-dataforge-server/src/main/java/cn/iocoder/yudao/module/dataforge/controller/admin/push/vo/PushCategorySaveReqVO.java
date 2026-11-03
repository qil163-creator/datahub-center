package cn.iocoder.yudao.module.dataforge.controller.admin.push.vo;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class PushCategorySaveReqVO {
    @Positive
    private Long id;
    @NotNull @Min(0)
    private Long parentId;
    @NotBlank @Size(max = 128)
    private String name;
    @NotNull @Min(0) @Max(100000)
    private Integer sort = 0;
    @NotNull @Min(0) @Max(1)
    private Integer status = 0;
    @Size(max = 500)
    private String description;
}
