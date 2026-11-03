package cn.iocoder.yudao.module.dataforge.controller.admin.database.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 数据源精简信息 Response VO")
@Data
public class DatabaseSimpleRespVO {

    @Schema(description = "主键id", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long id;

    @Schema(description = "连接名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "人口库")
    private String name;

    @Schema(description = "用途分类：1-业务库 2-中台库", example = "1")
    private Integer usageType;

    @Schema(description = "连接类型", example = "1")
    private Long databaseType;

    @Schema(description = "连接类型名称", example = "PostgreSQL")
    private String databaseTypeName;

    @Schema(description = "连接分类", example = "RELATIONAL")
    private String categoryCode;

    @Schema(description = "主机地址", example = "127.0.0.1")
    private String databaseIp;

    @Schema(description = "端口", example = "5432")
    private String databasePort;

    @Schema(description = "连接地址（主机:端口），不含账号和库名", example = "127.0.0.1:5432")
    private String host;

    @Schema(description = "当前部署注册的数据库产品标识")
    private String productId;

    @Schema(description = "组件声明的可恢复游标目标产品；空集合表示尚无可用方向，执行前仍须验证驱动、结构和键语义")
    private java.util.List<String> cursorTargets = java.util.List.of();

    @Schema(description = "连接图标路径", example = "/static/db/postgresql.png")
    private String iconPath;

    @Schema(description = "schema")
    private String databaseSchema;

}
