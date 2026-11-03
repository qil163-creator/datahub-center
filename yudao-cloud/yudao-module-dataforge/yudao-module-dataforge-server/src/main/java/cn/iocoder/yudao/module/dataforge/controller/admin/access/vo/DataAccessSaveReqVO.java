package cn.iocoder.yudao.module.dataforge.controller.admin.access.vo;

import cn.iocoder.yudao.module.dataforge.service.access.config.AccessTableMapping;
import cn.iocoder.yudao.module.dataforge.service.access.config.AccessNameMapping;
import cn.iocoder.yudao.module.dataforge.service.access.config.AccessTaskConfig;
import cn.iocoder.yudao.module.dataforge.service.access.config.AccessTypeMappingAcknowledgement;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Schema(description = "管理后台 - 数据同步任务新增/修改 Request VO")
@Data
public class DataAccessSaveReqVO {

    @Schema(description = "配置结构版本，当前为 1")
    private Integer schemaVersion = 1;

    @Schema(description = "按最终物理目标表/列逐项确认预览中的有损原因；默认空，不接受不支持映射")
    @Valid
    @NotNull
    @Size(max = 10_000)
    private List<AccessTypeMappingAcknowledgement> typeMappingAcknowledgements = List.of();

    @Schema(description = "显式确认源端在整个 Identity 迁移期间停止写入、DDL 与发号；默认不声明")
    private boolean identitySourceQuiesced;

    @Schema(description = "显式保留源 Identity 值并校准目标后续发号；需配合目标独占声明")
    private boolean preserveIdentityValues;

    @Schema(description = "显式确认目标及持有序列缓存的其他写入会话已退出，任务期间由本任务独占")
    private boolean exclusiveTargetOwnership;

    @Schema(description = "主键id", example = "1024")
    private Long id;

    @Schema(description = "任务名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "人口库同步")
    @NotEmpty(message = "任务名称不能为空")
    private String taskName;

    @Schema(description = "描述", example = "同步公安人口基础表")
    private String description;

    @Schema(description = "单位id", example = "100")
    private Long orgId;

    @Schema(description = "同步引擎类型：1-离线同步(DBSwitch) 2-实时同步(Seatunnel) 3-API 接入", example = "1")
    private Integer engineType;

    @Schema(description = "源端类型：1-指定表 2-SQL（离线同步必填）", example = "1")
    private Integer sourceType;

    @Schema(description = "源端数据源id（离线同步必填）", example = "1")
    private Long sourceDatabaseId;

    @Schema(description = "目的端数据源id（离线同步必填）", example = "2")
    private Long targetDatabaseId;

    @Schema(description = "接入方式：1-入中台库 2-自定义接入（离线同步必填）", example = "1")
    private Integer accessMode;

    @Schema(description = "任务类型：1-实时同步 2-一次性全量同步 3-一次全量周期增量", requiredMode = Schema.RequiredMode.REQUIRED, example = "2")
    @NotNull(message = "任务类型不能为空")
    private Integer taskType;

    @Schema(description = "cron 表达式（周期任务必填）", example = "0 0 2 * * ?")
    private String cron;

    @Schema(description = "包含或排除所选表：1-包含 0-排除", example = "1")
    private Integer includeOrExclude;

    @Schema(description = "源端选择的表")
    private List<String> sourceSelectedTables;

    @Schema(description = "只创建表结构，不同步数据")
    private boolean targetOnlyCreate;

    @Schema(description = "同步已存在的表")
    private boolean targetSyncExit;

    @Schema(description = "同步前是否删除重建目的表")
    private boolean targetDropTable;

    @Schema(description = "同步前是否清空目的表数据")
    private boolean targetTruncateData;

    @Schema(description = "开启增量变更同步")
    private boolean targetDataSync;

    @Schema(description = "增量变更类型：PK_ORDER_COMPARE-主键排序比对 INCREASE_COLUMN-增量字段", example = "INCREASE_COLUMN")
    private String changeDataSyncType;

    @Schema(description = "显式写入约束：CREATE_NEW / STRICT_COPY；未设置时使用任务的已有目标选项")
    private String writeMode;

    @Schema(description = "字段读取失败时立即终止；STRICT_COPY 始终启用")
    private boolean failOnFieldReadError;

    @Schema(description = "主键比对允许操作：INSERT / UPDATE / DELETE 及其下划线组合")
    private String targetSyncOption = "INSERT_UPDATE_DELETE";

    @Schema(description = "同步索引")
    private boolean targetIndexCreate;

    @Schema(description = "同步默认值")
    private boolean syncDefaultVal;

    @Schema(description = "表名字段名转小写（旧版开关，仅在未设置对应转换方式时生效）")
    private boolean targetLowerCase;

    @Schema(description = "表名字段名转大写（旧版开关，仅在未设置对应转换方式时生效）")
    private boolean targetUpperCase;

    @Schema(description = "表名转换：NONE / UPPER / LOWER / SNAKE（驼峰转下划线）/ CAMEL（下划线转驼峰）；为空时沿用旧版开关", example = "SNAKE")
    @Pattern(regexp = AccessTaskConfig.NAME_CASE_VALUES, message = "表名转换方式不合法")
    private String targetTableNameCase;

    @Schema(description = "字段名转换：NONE / UPPER / LOWER / SNAKE（驼峰转下划线）/ CAMEL（下划线转驼峰）；为空时沿用旧版开关", example = "SNAKE")
    @Pattern(regexp = AccessTaskConfig.NAME_CASE_VALUES, message = "字段名转换方式不合法")
    private String targetColumnNameCase;

    @Schema(description = "Greenplum 全量自动建表的分布列，使用映射后的物理列名；为空时沿用主键分布")
    @NotNull
    @Size(max = 1024)
    private List<@jakarta.validation.constraints.NotBlank String> targetDistributedKeys = List.of();

    @Schema(description = "建表时主键自增")
    private boolean targetAutoIncrement;

    @Schema(description = "批处理量", example = "5000")
    private Integer batchSize;

    @Schema(description = "映射类型：ALL-统一映射 SINGLE-单表单独映射", example = "ALL")
    private String mapperType;

    @Schema(description = "表名映射（统一映射时生效）")
    private List<AccessNameMapping> tableNameMapper;

    @Schema(description = "字段名映射（统一映射时生效）")
    private List<AccessNameMapping> columnNameMapper;

    @Schema(description = "单表映射配置（单独映射时生效，key 为源表名）")
    private Map<String, AccessTableMapping> configMap;

    @Schema(description = "源端 SQL（SQL 接入时必填）", example = "select * from t_user where status = 1")
    private String sourceSql;

    @Schema(description = "源端同步前脚本；可选，仅受限 SELECT 逐条治理重写授权，DML/DDL 拒绝")
    @Size(max = AccessTaskConfig.MAX_HOOK_SQL_LENGTH)
    private String sourceBeforeSqlScripts;

    @Schema(description = "源端同步后脚本；可选，仅受限 SELECT 逐条治理重写授权，DML/DDL 拒绝")
    @Size(max = AccessTaskConfig.MAX_HOOK_SQL_LENGTH)
    private String sourceAfterSqlScripts;

    @Schema(description = "目标端同步前脚本；可选，仅受限 SELECT 逐条治理重写授权，DML/DDL 拒绝")
    @Size(max = AccessTaskConfig.MAX_HOOK_SQL_LENGTH)
    private String targetBeforeSqlScripts;

    @Schema(description = "目标端同步后脚本；可选，仅受限 SELECT 逐条治理重写授权，DML/DDL 拒绝")
    @Size(max = AccessTaskConfig.MAX_HOOK_SQL_LENGTH)
    private String targetAfterSqlScripts;

    @Schema(description = "目标表名（SQL 接入时必填）", example = "ods_t_user")
    private String targetTable;

    @Schema(description = "唯一键列表（SQL 接入时使用）")
    private List<String> sourcePrimaryKeys;

    @Schema(description = "增量字段（统一映射时生效）", example = "update_time")
    private String increaseColumnName;

    @Schema(description = "游标源契约，仅支持 MONOTONIC_WATERMARK_V1")
    private String cursorWatermarkContract;

    @Schema(description = "显式允许在没有可信检查点时从头启动游标，默认关闭")
    private Boolean cursorBootstrapAllowed = false;

    @Schema(description = "读取父表时包含子代或仅本表：INCLUDE_DESCENDANTS / ONLY")
    private String tableReadMode = "INCLUDE_DESCENDANTS";

    @Schema(description = "时间戳字段（时序数据库使用）", example = "ts")
    private String timestampColumn;

    @Schema(description = "SeaTunnel 原生作业配置，按所选 JSON/HOCON 格式原文提交")
    @Size(max = 1_048_576)
    @lombok.ToString.Exclude
    private String seatunnelConfig;

    @Schema(description = "SeaTunnel 配置格式：json / hocon", example = "json")
    private String configFormat;

    @Schema(description = "API 接入映射（API 接入必填）")
    private List<@Valid @NotNull ApiAccessMappingVO> apiMappings;

    @Schema(description = "备注")
    private String remark;

}
