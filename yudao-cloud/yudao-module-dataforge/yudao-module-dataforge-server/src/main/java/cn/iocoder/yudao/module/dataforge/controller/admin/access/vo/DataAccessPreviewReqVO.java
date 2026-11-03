package cn.iocoder.yudao.module.dataforge.controller.admin.access.vo;

import cn.iocoder.yudao.module.dataforge.service.access.config.AccessTableMapping;
import cn.iocoder.yudao.module.dataforge.service.access.config.AccessNameMapping;
import cn.iocoder.yudao.module.dataforge.service.access.config.AccessTaskConfig;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Schema(description = "管理后台 - 数据同步任务名称映射预览 Request VO")
@Data
public class DataAccessPreviewReqVO {

    @Schema(description = "配置结构版本，当前为 1")
    private Integer schemaVersion = 1;

    @Schema(description = "源端数据源id", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "源端数据源不能为空")
    private Long sourceDatabaseId;

    @Schema(description = "可选受管理目标；提供时按实际目标产品、schema 和权限预览")
    private Long targetDatabaseId;

    @jakarta.validation.constraints.Size(max = 1000)
    @Schema(description = "显式源主键；空列表使用源库实际主键")
    private List<String> sourcePrimaryKeys = List.of();

    @Schema(description = "是否保留源默认值，与实际同步配置一致")
    private boolean syncDefaultVal;

    @Schema(description = "源端类型：1-指定表 2-SQL", example = "1")
    private Integer sourceType;

    @Schema(description = "源端 SQL（SQL 接入时必填）")
    private String sourceSql;

    @Schema(description = "包含或排除所选表：1-包含 0-排除", example = "1")
    private Integer includeOrExclude;

    @Schema(description = "源端选择的表")
    private List<String> sourceSelectedTables;

    @Schema(description = "接入方式：1-入中台库 2-自定义接入（入中台库时目标表自动加 ods_ 前缀）", example = "1")
    private Integer accessMode;

    @Schema(description = "映射类型：ALL-统一映射 SINGLE-单表单独映射", example = "ALL")
    private String mapperType;

    @Schema(description = "表名映射")
    private List<AccessNameMapping> tableNameMapper;

    @Schema(description = "字段名映射")
    private List<AccessNameMapping> columnNameMapper;

    @Schema(description = "单表映射配置（单独映射时生效，key 为源表名）")
    private Map<String, AccessTableMapping> configMap;

    @Schema(description = "表名字段名转小写（旧版开关，仅在未设置对应转换方式时生效）")
    private boolean targetLowerCase;

    @Schema(description = "表名字段名转大写（旧版开关，仅在未设置对应转换方式时生效）")
    private boolean targetUpperCase;

    @Schema(description = "表名转换：NONE / UPPER / LOWER / SNAKE / CAMEL；为空时沿用旧版开关", example = "SNAKE")
    @Pattern(regexp = AccessTaskConfig.NAME_CASE_VALUES, message = "表名转换方式不合法")
    private String targetTableNameCase;

    @Schema(description = "字段名转换：NONE / UPPER / LOWER / SNAKE / CAMEL；为空时沿用旧版开关", example = "SNAKE")
    @Pattern(regexp = AccessTaskConfig.NAME_CASE_VALUES, message = "字段名转换方式不合法")
    private String targetColumnNameCase;

    @Schema(description = "预览字段映射的表名（字段映射预览时必填）", example = "t_user")
    private String previewTableName;

}
