package cn.iocoder.yudao.module.dataforge.service.access.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.ToString;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DataForge 拥有的版本化持久定义。数据源引用属于任务，连接材料只在执行时解析。
 * 不继承组件属性，不保存驱动路径、凭据、执行回调、观测元数据或运行水位。
 */
@Data
@ToString(onlyExplicitlyIncluded = true)
public class AccessTaskConfig {

    public static final int CURRENT_VERSION = 1;

    /** 与 DB Switch 生命周期脚本的总字符上限一致。 */
    public static final int MAX_HOOK_SQL_LENGTH = 100_000;

    /** 表名/字段名转换方式白名单（版本 1 可选扩展），HTTP 入口与持久定义共用。 */
    public static final String NAME_CASE_VALUES = "NONE|UPPER|LOWER|SNAKE|CAMEL";

    private Integer schemaVersion = CURRENT_VERSION;

    private List<Source> source = new ArrayList<>();

    private Target target = new Target();

    /** 可选的逐列确认；版本 1 的旧定义缺省为空，仍拒绝有损映射。 */
    private List<AccessTypeMappingAcknowledgement> typeMappingAcknowledgements = new ArrayList<>();

    @Data
    @ToString(onlyExplicitlyIncluded = true)
    public static class Source {

        private Integer sourceType = 1;

        private String sourceSql;

        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String beforeSqlScripts;

        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String afterSqlScripts;

        private Integer fetchSize = 5000;

        private Integer includeOrExclude = 1;

        private String sourceIncludes = "";

        private String sourceExcludes = "";

        /** 可选 V1 扩展；null 表示旧版 CSV，否则原样保留物理名称。 */
        private List<String> sourceTableNames;

        private String tableReadMode = "INCLUDE_DESCENDANTS";

        private String mapperType = "ALL";

        private List<String> sourcePrimaryKeys = new ArrayList<>();

        private String increaseColumnName;

        private String timestampColumn;

        private List<AccessNameMapping> regexTableMapper = new ArrayList<>();

        private List<AccessNameMapping> regexColumnMapper = new ArrayList<>();

        private Map<String, AccessTableMapping> configMap = new LinkedHashMap<>();

        private String cursorWatermarkContract;

        private Boolean cursorBootstrapAllowed = false;

        /** 由操作人员显式声明源端 DML、DDL 及标识分配保持静止。 */
        private Boolean identitySourceQuiesced = false;
    }

    @Data
    @ToString(onlyExplicitlyIncluded = true)
    public static class Target {

        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String beforeSqlScripts;

        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String afterSqlScripts;

        private String targetTable;

        private boolean targetDrop;

        private boolean syncExist = true;

        private boolean onlyCreate;

        private boolean indexCreate;

        private boolean syncDefaultVal;

        private String tablePrefix;

        private boolean lowercase;

        private boolean uppercase;

        /** 可选 V1 扩展；null 时沿用旧版表名转小写、转大写标志。 */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String tableNameCase;

        /** 可选 V1 扩展；null 时沿用旧版列名转小写、转大写标志。 */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String columnNameCase;

        /** 可选 V1 扩展：最终物理 Greenplum 分布列；为空时保留数据库产品默认值。 */
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        private List<String> distributedKeys = List.of();

        private boolean createTableAutoIncrement;

        private boolean preserveIdentityValues;

        private boolean exclusiveTargetOwnership;

        private boolean changeDataSync;

        private String changeDataSyncType;

        private boolean targetTruncateData;

        private String writeMode;

        private boolean failOnFieldReadError;

        private String targetSyncOption = "INSERT_UPDATE_DELETE";
    }
}
