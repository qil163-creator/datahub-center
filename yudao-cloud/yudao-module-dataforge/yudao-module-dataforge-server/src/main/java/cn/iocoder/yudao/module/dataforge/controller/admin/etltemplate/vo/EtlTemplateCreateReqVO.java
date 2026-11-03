package cn.iocoder.yudao.module.dataforge.controller.admin.etltemplate.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Schema(description = "管理后台 - 创建不可变 ETL 团队图片段模板")
public class EtlTemplateCreateReqVO {

    @NotBlank
    @Size(max = 100)
    private String name;

    @Size(max = 500)
    private String description;

    @NotBlank
    @Pattern(regexp = "[a-zA-Z0-9_-]{16,64}")
    private String requestId;

    @NotEmpty
    @Size(max = 32)
    private List<Map<String, Object>> nodes;

    @NotNull
    @Size(max = 128)
    private List<Map<String, Object>> edges;
}
