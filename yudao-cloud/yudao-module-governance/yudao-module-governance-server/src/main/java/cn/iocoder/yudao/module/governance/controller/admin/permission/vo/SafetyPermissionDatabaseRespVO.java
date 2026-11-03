package cn.iocoder.yudao.module.governance.controller.admin.permission.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 数据权限数据源 Response VO")
@Data
public class SafetyPermissionDatabaseRespVO {

    @Schema(description = "数据源编号", example = "1")
    private Long id;

    @Schema(description = "数据源名称", example = "中台库")
    private String name;

    @Schema(description = "库用途：1-业务库 2-中台库", example = "2")
    private Integer usageType;

    @Schema(description = "产品类型", example = "POSTGRESQL")
    private String productType;

}
