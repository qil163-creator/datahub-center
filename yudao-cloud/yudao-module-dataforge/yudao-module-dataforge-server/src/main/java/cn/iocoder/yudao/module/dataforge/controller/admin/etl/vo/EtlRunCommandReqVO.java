package cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.util.Map;

@Data
@Schema(description = "ETL 稳定执行命令")
public class EtlRunCommandReqVO {

    @NotNull
    private Long pipelineId;

    @NotBlank
    @Size(max = 128)
    private String requestId;

    /** 客户端读取参数定义时的版本，覆盖参数时必填；旧无覆盖请求继续兼容。 */
    @Min(0)
    private Integer expectedDefinitionRevision;

    /** 运行值始终以字符串传输，保留大整数与精确小数。 */
    @Size(max = 100)
    @JsonDeserialize(contentUsing = StrictJsonStringDeserializer.class)
    private Map<@Pattern(regexp = "[A-Za-z_][A-Za-z0-9_]{0,63}") String,
            @NotNull @Size(max = 4096) String> parameters;
}
