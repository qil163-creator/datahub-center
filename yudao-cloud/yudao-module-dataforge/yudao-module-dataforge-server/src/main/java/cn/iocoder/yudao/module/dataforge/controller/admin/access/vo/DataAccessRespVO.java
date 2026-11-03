package cn.iocoder.yudao.module.dataforge.controller.admin.access.vo;

import cn.iocoder.yudao.module.dataforge.service.access.config.AccessTableMapping;
import cn.iocoder.yudao.module.dataforge.service.access.config.AccessNameMapping;
import cn.iocoder.yudao.module.dataforge.service.access.config.AccessTypeMappingAcknowledgement;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Schema(description = "管理后台 - 数据同步任务 Response VO")
@Data
public class DataAccessRespVO {

    @Schema(description = "配置结构版本，当前为 1")
    private Integer schemaVersion = 1;

    @Schema(description = "当前任务逐列有损映射决策；旧任务默认空")
    private List<AccessTypeMappingAcknowledgement> typeMappingAcknowledgements = List.of();

    @Schema(description = "源端在整个 Identity 迁移期间停止写入、DDL 与发号的显式声明")
    private boolean identitySourceQuiesced;

    @Schema(description = "保留源 Identity 值并校准目标后续发号")
    private boolean preserveIdentityValues;

    @Schema(description = "目标及其发号会话由本任务独占的显式声明")
    private boolean exclusiveTargetOwnership;

    @Schema(description = "主键id", requiredMode = Schema.RequiredMode.REQUIRED, example = "1024")
    private Long id;

    @Schema(description = "任务名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "人口库同步")
    private String taskName;

    @Schema(description = "描述")
    private String description;

    @Schema(description = "单位id", example = "100")
    private Long orgId;

    @Schema(description = "单位名称", example = "市大数据局")
    private String orgName;

    @Schema(description = "发布人名称", example = "管理员")
    private String releaseUserName;

    @Schema(description = "同步引擎类型：1-离线同步(DBSwitch) 2-SeaTunnel 3-API 接入", example = "1")
    private Integer engineType;

    @Schema(description = "源端类型：1-指定表 2-SQL", example = "1")
    private Integer sourceType;

    @Schema(description = "源端数据源id", example = "1")
    private Long sourceDatabaseId;

    @Schema(description = "源端数据源名称", example = "公安人口库")
    private String sourceDatabaseName;

    @Schema(description = "目的端数据源id", example = "2")
    private Long targetDatabaseId;

    @Schema(description = "目的端数据源名称", example = "中台库")
    private String targetDatabaseName;

    @Schema(description = "接入方式：1-入中台库 2-自定义接入", example = "1")
    private Integer accessMode;

    @Schema(description = "任务类型：2-一次性全量同步 3-一次全量周期增量", example = "2")
    private Integer taskType;

    @Schema(description = "cron 表达式", example = "0 0 2 * * ?")
    private String cron;

    @Schema(description = "发布状态：0-未发布 1-已发布", example = "0")
    private Integer status;

    @Schema(description = "最新运行状态：1-等待中 2-运行中 3-正常结束 4-异常结束", example = "3")
    private Integer runStatus;

    @Schema(description = "包含或排除所选表：1-包含 0-排除", example = "1")
    private Integer includeOrExclude;

    @Schema(description = "源端选择的表")
    private List<String> sourceSelectedTables;

    @Schema(description = "只创建表结构，不同步数据")
    private Boolean targetOnlyCreate;

    @Schema(description = "同步已存在的表")
    private Boolean targetSyncExit;

    @Schema(description = "同步前是否删除重建目的表")
    private Boolean targetDropTable;

    @Schema(description = "同步前是否清空目的表数据")
    private Boolean targetTruncateData;

    @Schema(description = "开启增量变更同步")
    private Boolean targetDataSync;

    @Schema(description = "增量变更类型", example = "INCREASE_COLUMN")
    private String changeDataSyncType;

    @Schema(description = "显式写入约束：CREATE_NEW / STRICT_COPY；未设置时使用任务的已有目标选项")
    private String writeMode;

    @Schema(description = "字段读取失败时立即终止；STRICT_COPY 始终启用")
    private boolean failOnFieldReadError;

    @Schema(description = "主键比对允许操作：INSERT / UPDATE / DELETE 及其下划线组合")
    private String targetSyncOption = "INSERT_UPDATE_DELETE";

    @Schema(description = "同步索引")
    private Boolean targetIndexCreate;

    @Schema(description = "同步默认值")
    private Boolean syncDefaultVal;

    @Schema(description = "表名字段名转小写（旧版开关）")
    private Boolean targetLowerCase;

    @Schema(description = "表名字段名转大写（旧版开关）")
    private Boolean targetUpperCase;

    @Schema(description = "表名转换：NONE / UPPER / LOWER / SNAKE / CAMEL；旧任务未设置时为空，按旧版开关执行", example = "SNAKE")
    private String targetTableNameCase;

    @Schema(description = "字段名转换：NONE / UPPER / LOWER / SNAKE / CAMEL；旧任务未设置时为空，按旧版开关执行", example = "SNAKE")
    private String targetColumnNameCase;

    @Schema(description = "Greenplum 自动建表的分布列，使用最终物理列名；旧任务缺省为空")
    private List<String> targetDistributedKeys = List.of();

    @Schema(description = "建表时主键自增")
    private Boolean targetAutoIncrement;

    @Schema(description = "批处理量", example = "5000")
    private Integer batchSize;

    @Schema(description = "映射类型：ALL-统一映射 SINGLE-单表单独映射", example = "ALL")
    private String mapperType;

    @Schema(description = "表名映射")
    private List<AccessNameMapping> tableNameMapper;

    @Schema(description = "字段名映射")
    private List<AccessNameMapping> columnNameMapper;

    @Schema(description = "单表映射配置")
    private Map<String, AccessTableMapping> configMap;

    @Schema(description = "源端 SQL")
    private String sourceSql;

    @Schema(description = "源端同步前脚本；执行前逐条治理授权")
    private String sourceBeforeSqlScripts;

    @Schema(description = "源端同步后脚本；执行前逐条治理授权")
    private String sourceAfterSqlScripts;

    @Schema(description = "目标端同步前脚本；执行前逐条治理授权")
    private String targetBeforeSqlScripts;

    @Schema(description = "目标端同步后脚本；执行前逐条治理授权")
    private String targetAfterSqlScripts;

    @Schema(description = "目标表名")
    private String targetTable;

    @Schema(description = "唯一键列表")
    private List<String> sourcePrimaryKeys;

    @Schema(description = "增量字段")
    private String increaseColumnName;

    @Schema(description = "游标源契约，仅支持 MONOTONIC_WATERMARK_V1")
    private String cursorWatermarkContract;

    @Schema(description = "显式允许在没有可信检查点时从头启动游标，默认关闭")
    private Boolean cursorBootstrapAllowed = false;

    @Schema(description = "读取父表时包含子代或仅本表：INCLUDE_DESCENDANTS / ONLY")
    private String tableReadMode = "INCLUDE_DESCENDANTS";

    @Schema(description = "时间戳字段")
    private String timestampColumn;

    @Schema(description = "SeaTunnel 原生作业配置，供有任务查询权限的用户编辑回显")
    @lombok.ToString.Exclude
    private String seatunnelConfig;

    @Schema(description = "Seatunnel 配置格式：json / hocon")
    private String configFormat;

    @Schema(description = "API 接入映射")
    private List<ApiAccessMappingVO> apiMappings;

    @Schema(description = "最近开始时间")
    private LocalDateTime startTime;

    @Schema(description = "最近结束时间")
    private LocalDateTime endTime;

    @Schema(description = "发布时间")
    private LocalDateTime releaseTime;

    @Schema(description = "发布人id")
    private Long releaseUserId;

    @Schema(description = "下次执行时间")
    private LocalDateTime nextRunTime;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "创建人名称", example = "管理员")
    private String creatorName;

}
