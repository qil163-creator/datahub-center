package cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "管理后台 - 生成来源或 PostgreSQL 整表有界采样后执行转换；结果不是完整数据集")
public class EtlPreviewReqVO extends EtlSchemaReqVO {

    @NotBlank
    @Size(max = 128)
    private String targetNodeId;

    @NotNull
    @Min(1)
    @Max(100)
    private Integer limit = 100;
}
