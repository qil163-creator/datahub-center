package cn.iocoder.yudao.framework.dbswitch.data.entity;

import lombok.Data;
import cn.iocoder.yudao.framework.dbswitch.common.constant.ChangeDataSyncType;
import cn.iocoder.yudao.framework.dbswitch.common.type.CaseConvertEnum;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.type.SyncOptionEnum;

import java.util.Map;
import java.util.concurrent.TimeUnit;

@Data
public class TargetDataSourceProperties implements Cloneable {

    /** 仅限可信的本地编排；请求 JSON 不能安装检查点回调。 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @lombok.ToString.Exclude
    private transient Map<String, cn.iocoder.yudao.framework.dbswitch.data.cursor.CursorRunBinding> cursorRunBindings;

    /** 可信持久化恢复的归属，按最终目标模式和表路由，绝不从 JSON 接收。 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @lombok.ToString.Exclude
    private transient Map<String, cn.iocoder.yudao.framework.dbswitch.dbsynch.recovery.GroupRewriteBinding> groupRewriteBindings;

    /** 通过可信领域准入解析展开后的物理目标，仅保留在内存中。 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @lombok.ToString.Exclude
    private transient cn.iocoder.yudao.framework.dbswitch.dbsynch.recovery.GroupRewriteBindingProvider groupRewriteBindingProvider;

    /** 可信的整表恢复准入；驱动凭据和回调均不能来自 JSON。 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @lombok.ToString.Exclude
    private transient cn.iocoder.yudao.framework.dbswitch.dbsynch.recovery.TableRewriteBindingProvider tableRewriteBindingProvider;

    /**
     * 为当前分组重写目标生成持久化资源键。
     */
    public static String groupRewriteTargetKey(String schema, String table) {
        if (schema == null || table == null || table.isBlank() || schema.indexOf('\0') >= 0 || table.indexOf(
                '\0') >= 0) {
            throw new IllegalArgumentException("Invalid target recovery route");
        }
        return schema + '\0' + table;
    }

    /**
     * 解析当前分组重写所需的持久化绑定。
     */
    public cn.iocoder.yudao.framework.dbswitch.dbsynch.recovery.GroupRewriteBinding groupRewriteBinding(String schema,
            String table) {
        return groupRewriteBindings == null ? null : groupRewriteBindings.get(groupRewriteTargetKey(schema, table));
    }

    /** 每次运行不可变的批次限制；null 使用组件的有界默认值和运行时字节上限。 */
    private cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits batchLimits;

    /** 调用方提供的行回调的版本或配置摘要；此处不序列化回调。 */
    private cn.iocoder.yudao.framework.dbswitch.common.entity.RecordTransformDefinition transformDefinition;

    private Long databaseId;

    private Integer poolSize = 32;

    private ProductTypeEnum targetProductType;

    @lombok.ToString.Exclude
    private String url;

    private String driverClassName;

    /**
     * 驱动 jar 路径（文件或目录，目录含子目录递归；移植自 dbswitch 2.0.2）：
     * 配置后通过隔离 ClassLoader 动态加载驱动，避免与应用 classpath 中的驱动冲突；
     * 为空时沿用应用 classpath 中的驱动（历史行为）
     */
    private String driverPath;

    /** 部署配置标识，不是文件系统路径或上传的 JAR。 */
    private String driverProfileId;

    @lombok.ToString.Exclude
    private String username;

    @lombok.ToString.Exclude
    private String password;

    private Long connectionTimeout = TimeUnit.SECONDS.toMillis(120);

    private Long maxLifeTime = TimeUnit.MINUTES.toMillis(120);

    private String targetSchema = "";

    //sql接入时有此值
    private String targetTable;

    /** 破坏性替换必须显式配置。 */
    private Boolean targetDrop = Boolean.FALSE;

    private Boolean syncExist = Boolean.TRUE;

    private Boolean onlyCreate = Boolean.FALSE;

    /**
     * 是否同步索引信息，只有targetDrop为TRUE时生效
     */
    private Boolean indexCreate = Boolean.FALSE;

    /**
     * 是否同步默认值
     */
    private Boolean syncDefaultVal = Boolean.FALSE;

    /**
     * 表明前缀
     */
    private String tablePrefix;

    /**
     * 旧版大小写开关：同时作用于表名和字段名，仅在对应的 tableNameCase/columnNameCase 为 null 时生效
     */
    private Boolean lowercase = Boolean.FALSE;

    private Boolean uppercase = Boolean.FALSE;

    /**
     * 目标表名转换方式（移植自 dbswitch 2.0.2），在正则映射与表名前缀之后执行；
     * null 沿用 lowercase/uppercase，Hive 目标始终转为小写
     */
    private CaseConvertEnum tableNameCase;

    /**
     * 目标字段名（含主键、时间戳字段）转换方式，在正则映射之后执行；
     * null 沿用 lowercase/uppercase，Hive 目标始终转为小写
     */
    private CaseConvertEnum columnNameCase;

    private Boolean createTableAutoIncrement = Boolean.FALSE;

    /** 保留受支持的原生自增标识和所属序列值，全部批次提交后再对齐。 */
    private Boolean preserveIdentityValues = Boolean.FALSE;

    /** 技术层独占契约：其他写入器及持有序列缓存的会话均已结束。 */
    private Boolean exclusiveTargetOwnership = Boolean.FALSE;

    /** 原生 DDL 技术契约：本次运行独占目标命名空间上的外部 DDL。 */
    private Boolean exclusiveTargetDdlOwnership = Boolean.FALSE;

    private Boolean writerEngineInsert = Boolean.TRUE;

    private Boolean changeDataSync = Boolean.FALSE;

    private String changeDataSyncType;

    /** 全量复制默认追加数据，清空目标必须显式配置。 */
    private Boolean targetTruncateData = Boolean.FALSE;

    /**
     * 显式写入模式。空值沿用配置控制，drop/truncate 默认关闭。
     * CREATE_NEW / STRICT_COPY 由新调用方显式传入，禁止隐式覆盖已有表。
     */
    private String writeMode;

    /**
     * 兼容旧输入的保留字段。执行时所有模式均在字段读取失败时终止，false 不再允许替换为 NULL。
     */
    private Boolean failOnFieldReadError = Boolean.TRUE;

    /**
     * 变化量同步时允许执行的操作组合（移植自 dbswitch 2.0.2），
     * 默认插入/更新/删除全部执行，与历史行为一致
     */
    private SyncOptionEnum targetSyncOption = SyncOptionEnum.INSERT_UPDATE_DELETE;

    /**
     * 自定义建表 DDL（移植自 dbswitch 2.0.2）：key 为目标表名，value 为完整建表语句；
     * 命中时优先于自动生成的 DDL（含索引语句也需自行包含在内）
     */
    private Map<String, String> customDdlMap;

    /** Greenplum 生成 CREATE 时的分布列，使用最终目标物理名称；为空时使用主键。 */
    private java.util.List<String> distributedKeys = java.util.List.of();

    /**
     * 设置分布式键集合。
     */
    public void setDistributedKeys(java.util.List<String> keys) {
        distributedKeys = keys == null ? java.util.List.of() : java.util.List.copyOf(keys);
    }

    /**
     * 任务开始前在目标库执行的 SQL 脚本（多条以分号分隔），移植自 dbswitch 2.0.2。
     * 典型用途：迁移前禁用索引/触发器
     */
    private String beforeSqlScripts;

    /**
     * 任务结束后在目标库执行的 SQL 脚本（多条以分号分隔），移植自 dbswitch 2.0.2。
     * 典型用途：迁移后重建索引、收集统计信息
     */
    private String afterSqlScripts;

    /**
     * 返回当前COPY。
     */
    public TargetDataSourceProperties copy() {
        if (!distributedKeys.isEmpty() && (targetProductType != ProductTypeEnum.GREENPLUM
                || Boolean.TRUE.equals(changeDataSync))) {
            throw new IllegalArgumentException("Custom distribution requires Greenplum generated full-table CREATE");
        }
        if (distributedKeys.size() > 1024 || distributedKeys.stream()
                .anyMatch(key -> key.isBlank() || key.indexOf('\0') >= 0)
                || new java.util.HashSet<>(distributedKeys).size() != distributedKeys.size()) {
            throw new IllegalArgumentException("Distribution keys must be distinct nonempty physical column names");
        }
        if (Boolean.TRUE.equals(preserveIdentityValues) != Boolean.TRUE.equals(exclusiveTargetOwnership)) {
            throw new IllegalArgumentException("Identity preservation requires explicit exclusive target ownership");
        }
        if (Boolean.TRUE.equals(
                preserveIdentityValues) && ((targetProductType != ProductTypeEnum.POSTGRESQL && targetProductType != ProductTypeEnum.SQLSERVER)
                || Boolean.TRUE.equals(changeDataSync))) {
            throw new IllegalArgumentException(
                    "Identity preservation requires a supported product's full-table migration");
        }
        try {
            var result = (TargetDataSourceProperties) super.clone();
            result.customDdlMap = customDdlMap == null ? null : Map.copyOf(customDdlMap);
            result.distributedKeys = java.util.List.copyOf(distributedKeys);
            // 保留可信回调的身份，仅改变映射表的归属。
            result.cursorRunBindings = cursorRunBindings == null ? null : Map.copyOf(cursorRunBindings);
            result.groupRewriteBindings = groupRewriteBindings == null ? null : Map.copyOf(groupRewriteBindings);
            return result;
        } catch (CloneNotSupportedException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
