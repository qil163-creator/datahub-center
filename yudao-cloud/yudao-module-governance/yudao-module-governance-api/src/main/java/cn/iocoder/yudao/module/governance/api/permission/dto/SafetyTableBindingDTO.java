package cn.iocoder.yudao.module.governance.api.permission.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 精确物理对象；引用必须经过数据源提供方解析，不代表已经授权。 */
@Data
@Schema(description = "RPC 服务 - 治理权限精确表身份")
public class SafetyTableBindingDTO {

    @NotBlank
    @Size(max = 256)
    private String schemaName;

    @NotBlank
    @Size(max = 256)
    private String tableName;

    @NotBlank
    @Size(max = 2048)
    private String tableReference;
}
