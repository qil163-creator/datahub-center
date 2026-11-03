package cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

@Data
@Schema(description = "管理后台 - ETL 环境保存请求")
public class EtlEnvironmentSaveReqVO {

    @Positive
    private Long id;

    @Min(0)
    private Integer revision;

    @NotBlank
    @Pattern(regexp = "[a-z][a-z0-9_-]{0,63}")
    private String code;

    @NotBlank
    @Size(max = 100)
    private String name;

    @NotNull
    private Boolean enabled;
}
