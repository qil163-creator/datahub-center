package cn.iocoder.yudao.module.dataforge.controller.admin.driver.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "管理后台 - 数据源驱动管理新增/修改 Request VO")
@Data
public class DatasourceDriverSaveReqVO {

    @Schema(description = "驱动编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "16128")
    private Long id;

    @Schema(description = "驱动编码", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "驱动编码不能为空")
    private String driverCode;

    @Schema(description = "驱动名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "赵六")
    @NotEmpty(message = "驱动名称不能为空")
    private String driverName;

    @Schema(description = "数据库产品类型：MYSQL、POSTGRESQL、ORACLE、HIVE、DORIS、CLICKHOUSE   字典数据", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotEmpty(message = "数据库产品类型：MYSQL、POSTGRESQL、ORACLE、HIVE、DORIS、CLICKHOUSE   字典数据不能为空")
    private String productType;

    @Schema(description = "驱动来源：BUILTIN 内置驱动，UPLOAD 上传驱动    字典数据", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "驱动来源：BUILTIN 内置驱动，UPLOAD 上传驱动    字典数据不能为空")
    private String driverSource;

    @Schema(description = "驱动类名", requiredMode = Schema.RequiredMode.REQUIRED, example = "李四")
    @NotEmpty(message = "驱动类名不能为空")
    private String driverClassName;

    @Schema(description = "JDBC URL 模板", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "JDBC URL 模板不能为空")
    private String jdbcUrlTemplate;

    @Schema(description = "默认连接测试 SQL")
    private String testSql;

    @Schema(description = "连接图标路径")
    private String icon;

    @Schema(description = "是否有 schema 概念：1有 0无", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "是否有 schema 概念：1有 0无不能为空")
    private Short supportSchema;

    @Schema(description = "上传驱动 JAR 文件名称（内置为空）", example = "王五")
    private String jarFileName;

    @Schema(description = "上传驱动 JAR 文件路径（内置为空，走 classpath）")
    private String jarFilePath;

    @Schema(description = "上传驱动 JAR 文件 MD5")
    private String jarFileMd5;

    @Schema(description = "上传驱动 JAR 文件大小")
    private Double jarFileSize;

    @Schema(description = "排序", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "排序不能为空")
    private Integer sort;

    @Schema(description = "状态", requiredMode = Schema.RequiredMode.REQUIRED, example = "2")
    @NotNull(message = "状态不能为空")
    private Short status;

    @Schema(description = "备注", example = "你说的对")
    private String remark;

}
