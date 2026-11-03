package cn.iocoder.yudao.module.dataforge.controller.admin.database.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "管理后台 - 数据源中心--数据库信息新增/修改 Request VO")
@Data
public class DatabaseSaveReqVO {

    @Schema(description = "主键id", requiredMode = Schema.RequiredMode.REQUIRED, example = "24164")
    private Long id;

    @Schema(description = "连接名称", example = "赵六")
    private String name;

    @Schema(description = "连接类型", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "连接类型不能为空")
    private Integer databaseType;

    @Schema(description = "主机ip地址；SQLite 文件连接可为空")
    @Size(max = 253, message = "主机地址不能超过253个字符")
    private String databaseIp;

    @Schema(description = "端口；SQLite 文件连接可为空")
    @Size(max = 10, message = "端口不能超过10个字符")
    private String databasePort;

    @Schema(description = "库名", example = "赵六")
    private String databaseName;

    @Schema(description = "schema")
    private String databaseSchema;

    @Schema(description = "状态", example = "0")
    private Integer status;

    @Schema(description = "用途分类：1-业务库 2-中台库", example = "1")
    private Integer usageType;

    @Schema(description = "用户名", example = "芋艿")
    @Size(max = 512, message = "账号或组织名称不能超过512个字符")
    private String userName;

    @Schema(description = "密码")
    private String password;

    @Schema(description = "jdbcUrl", example = "https://www.iocoder.cn")
    private String jdbcUrl;

    @Schema(description = "单位id", example = "18943")
    private Long orgId;

    @Schema(description = "扩展配置")
    @Size(max = 16384, message = "扩展配置不能超过16384个字符")
    private String extendConfig;

    @Schema(description = "密码解密密钥")
    private String secretKey;

    @Schema(description = "排序")
    private Integer sort;

    @Schema(description = "备注", example = "随便")
    private String remark;

}
