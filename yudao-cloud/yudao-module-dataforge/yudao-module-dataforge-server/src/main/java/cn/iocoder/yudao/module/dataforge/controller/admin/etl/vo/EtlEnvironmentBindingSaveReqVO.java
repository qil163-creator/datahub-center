package cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

@Data
@Schema(description = "管理后台 - ETL 环境连接绑定保存请求")
public class EtlEnvironmentBindingSaveReqVO {

    @Positive
    private Long id;

    @Min(0)
    private Integer revision;

    @NotNull
    @Positive
    private Long environmentId;

    @NotBlank
    @Pattern(regexp = "[A-Za-z][A-Za-z0-9_.-]{0,63}")
    private String logicalKey;

    @NotNull
    @Positive
    private Long databaseId;

    @NotBlank
    @Size(max = 128)
    @Pattern(regexp = "[^\\p{Cntrl}]+")
    private String schemaName;
}
