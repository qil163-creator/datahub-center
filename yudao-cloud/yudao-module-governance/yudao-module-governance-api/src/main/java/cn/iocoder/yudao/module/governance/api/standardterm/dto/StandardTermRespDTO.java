package cn.iocoder.yudao.module.governance.api.standardterm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "RPC 服务 - 当前业务术语 Response DTO")
@Data
public class StandardTermRespDTO {

    @Schema(description = "术语编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;

    @Schema(description = "术语名称", requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;

    @Schema(description = "业务定义")
    private String definition;

    @Schema(description = "别名或同义词，逗号分隔")
    private String alias;

    @Schema(description = "状态：0 启用，1 停用", requiredMode = Schema.RequiredMode.REQUIRED)
    private Short status;

}
