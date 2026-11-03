package cn.iocoder.yudao.module.modeling.controller.admin.model.vo.materialized;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 发布选择器仅暴露连接展示信息，不沿用携带凭据的 RPC DTO。 */
@Data
@Schema(description = "管理后台 - 模型发布数据源选项")
public class ModelMaterializedDatabaseRespVO {

    private Long id;

    private String name;

    private Long databaseType;

    private String databaseTypeName;

    private String categoryCode;

    private String productType;

    private String iconPath;

    @Schema(description = "脱敏主机地址，不包含账号、密码和连接参数")
    private String databaseAddress;

    private String schema;
}
