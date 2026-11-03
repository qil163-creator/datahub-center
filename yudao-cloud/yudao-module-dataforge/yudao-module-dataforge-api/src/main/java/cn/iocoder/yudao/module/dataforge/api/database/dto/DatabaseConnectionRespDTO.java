package cn.iocoder.yudao.module.dataforge.api.database.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 数据源连接信息 响应 DTO
 *
 * 供其它模块（如数据治理的主数据）通过 RPC 获取数据源实例的连接信息，
 * 列表查询时不返回密码，仅按编号取单条时才携带解密后的密码
 *
 * @author Li
 */
@Schema(description = "RPC 服务 - 数据源连接信息 Response DTO")
@Data
public class DatabaseConnectionRespDTO {

    @Schema(description = "数据源编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long id;

    @Schema(description = "连接名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "中台库")
    private String name;

    @Schema(description = "连接类型模板编号；同一数据库产品可对应多个模板", example = "3")
    private Long databaseType;

    @Schema(description = "连接类型名称", example = "Oracle SID")
    private String databaseTypeName;

    @Schema(description = "连接分类", example = "RELATIONAL")
    private String categoryCode;

    @Schema(description = "用途分类（1 业务库 2 中台库）", example = "2")
    private Integer usageType;

    @Schema(description = "dbswitch 产品类型编码", example = "POSTGRESQL")
    private String productType;

    @Schema(description = "连接图标路径", example = "/static/db/postgresql.png")
    private String iconPath;

    @Schema(description = "JDBC 连接串（已拼接扩展参数）", example = "jdbc:postgresql://127.0.0.1:5432/demo")
    private String jdbcUrl;

    @Schema(description = "连接地址（主机:端口），不含账号、密码和库名", example = "127.0.0.1:5432")
    private String host;

    @Schema(description = "用户名", example = "root")
    private String username;

    @Schema(description = "密码（已解密，仅按编号查询时返回）")
    private String password;

    @Schema(description = "模式名", example = "public")
    private String schema;

    @Schema(description = "数据源配置修订标识（服务端更新时间的无损字符串）；缺失时不能证明历史绑定一致")
    private String configurationRevision;

}
