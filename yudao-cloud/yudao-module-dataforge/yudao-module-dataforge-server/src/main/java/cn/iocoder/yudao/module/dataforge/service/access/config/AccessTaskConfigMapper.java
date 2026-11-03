package cn.iocoder.yudao.module.dataforge.service.access.config;

import cn.iocoder.yudao.framework.dbswitch.common.entity.MapperConfig;
import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.CaseConvertEnum;
import cn.iocoder.yudao.framework.dbswitch.common.type.SyncOptionEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan;
import cn.iocoder.yudao.framework.dbswitch.core.model.DorisCreateTableOptions;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LossyMappingAcknowledgement;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.entity.TargetDataSourceProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** 业务定义到组件执行请求的唯一适配边界；每次调用都生成独立对象。 */
public final class AccessTaskConfigMapper {

    /**
     * 限制访问任务配置工具的实例化，统一通过静态能力使用。
     */
    private AccessTaskConfigMapper() {
    }

    /**
     * 转换为运行时。
     *
     * @param config 处理配置
     * @param targetDatabaseId 目标数据源编号
     * @return 迁移请求
     */
    public static MigrationRequest toRuntime(AccessTaskConfig config, Long targetDatabaseId) {
        AccessTaskConfigCodec.validate(config);
        var saved = config.getSource().getFirst();
        var source = new SourceDataSourceProperties();
        source.setSourceType(saved.getSourceType());
        source.setSourceSql(saved.getSourceSql());
        source.setBeforeSqlScripts(saved.getBeforeSqlScripts());
        source.setAfterSqlScripts(saved.getAfterSqlScripts());
        source.setFetchSize(saved.getFetchSize());
        source.setIncludeOrExclude(saved.getIncludeOrExclude());
        source.setSourceIncludes(saved.getSourceIncludes());
        source.setSourceExcludes(saved.getSourceExcludes());
        source.setSourceTableNames(
                saved.getSourceTableNames() == null ? null : List.copyOf(saved.getSourceTableNames()));
        source.setTableReadMode(TableReadPlan.ReadMode.valueOf(saved.getTableReadMode()));
        source.setMapperType(saved.getMapperType());
        source.setSourcePrimaryKeys(strings(saved.getSourcePrimaryKeys()));
        source.setIncreaseColumnName(saved.getIncreaseColumnName());
        source.setTimestampColumn(saved.getTimestampColumn());
        source.setRegexTableMapper(toRuntimeMappings(saved.getRegexTableMapper()));
        source.setRegexColumnMapper(toRuntimeMappings(saved.getRegexColumnMapper()));
        source.setCursorWatermarkContract(saved.getCursorWatermarkContract());
        source.setCursorBootstrapAllowed(saved.getCursorBootstrapAllowed());
        source.setIdentitySourceQuiesced(saved.getIdentitySourceQuiesced());
        var mappings = new LinkedHashMap<String, MapperConfig>();
        if (saved.getConfigMap() != null) {
            saved.getConfigMap().forEach((table, value) -> {
                var mapping = new MapperConfig();
                mapping.setTargetTableName(value.getTargetTableName());
                mapping.setTargetTableComment(value.getTargetTableComment());
                mapping.setDorisOptions(toRuntimeDorisOptions(value.getDorisOptions()));
                mapping.setRegexTableMapper(toRuntimeMappings(value.getRegexTableMapper()));
                mapping.setRegexColumnMapper(toRuntimeMappings(value.getRegexColumnMapper()));
                mapping.setSourcePrimaryKeys(strings(value.getSourcePrimaryKeys()));
                mapping.setIncreaseColumnName(value.getIncreaseColumnName());
                mapping.setTimestampColumn(value.getTimestampColumn());
                mappings.put(table, mapping);
            });
        }
        source.setConfigMap(mappings);
        var savedTarget = config.getTarget();
        var target = new TargetDataSourceProperties();
        target.setDatabaseId(targetDatabaseId);
        target.setBeforeSqlScripts(savedTarget.getBeforeSqlScripts());
        target.setAfterSqlScripts(savedTarget.getAfterSqlScripts());
        target.setTargetTable(savedTarget.getTargetTable());
        target.setTargetDrop(savedTarget.isTargetDrop());
        target.setSyncExist(savedTarget.isSyncExist());
        target.setOnlyCreate(savedTarget.isOnlyCreate());
        target.setIndexCreate(savedTarget.isIndexCreate());
        target.setSyncDefaultVal(savedTarget.isSyncDefaultVal());
        target.setTablePrefix(savedTarget.getTablePrefix());
        target.setLowercase(savedTarget.isLowercase());
        target.setUppercase(savedTarget.isUppercase());
        target.setTableNameCase(nameCase(savedTarget.getTableNameCase()));
        target.setColumnNameCase(nameCase(savedTarget.getColumnNameCase()));
        target.setDistributedKeys(savedTarget.getDistributedKeys());
        target.setCreateTableAutoIncrement(savedTarget.isCreateTableAutoIncrement());
        target.setPreserveIdentityValues(savedTarget.isPreserveIdentityValues());
        target.setExclusiveTargetOwnership(savedTarget.isExclusiveTargetOwnership());
        target.setChangeDataSync(savedTarget.isChangeDataSync());
        target.setChangeDataSyncType(savedTarget.getChangeDataSyncType());
        target.setTargetTruncateData(savedTarget.isTargetTruncateData());
        target.setWriteMode(savedTarget.getWriteMode());
        target.setFailOnFieldReadError(savedTarget.isFailOnFieldReadError());
        target.setTargetSyncOption(SyncOptionEnum.valueOf(savedTarget.getTargetSyncOption()));
        var runtime = new MigrationRequest();
        runtime.setSource(new ArrayList<>(List.of(source)));
        runtime.setTarget(target);
        runtime.setTypeMappingAcknowledgements(toRuntimeAcknowledgements(config.getTypeMappingAcknowledgements()));
        return runtime;
    }

    /**
     * 转换为运行时确认回执。
     *
     * @param decisions 结论
     * @return 转换为运行时确认回执结果集合
     */
    public static List<LossyMappingAcknowledgement> toRuntimeAcknowledgements(
            List<AccessTypeMappingAcknowledgement> decisions) {
        if (decisions == null || decisions.size() > 10_000) {
            throw new IllegalArgumentException("类型映射决策范围无效");
        }
        var result = decisions.stream().map(value -> new LossyMappingAcknowledgement(
                ProductTypeEnum.valueOf(value.targetProduct()), value.targetSchema(), value.targetTable(),
                value.column(), value.mappingFingerprint(), java.util.Set.copyOf(value.lossCodes()))).toList();
        LossyMappingAcknowledgement.requireUnique(result);
        return result;
    }

    /**
     * 转换为运行时映射。
     *
     * @param mappings 映射
     * @return 转换为运行时映射结果集合
     */
    public static List<PatternMapper> toRuntimeMappings(List<AccessNameMapping> mappings) {
        var result = new ArrayList<PatternMapper>();
        if (mappings != null) {
            mappings.forEach(value -> result.add(new PatternMapper(value.getFromPattern(), value.getToValue())));
        }
        return result;
    }

    /** 业务 JSON 与组件不可变建表参数的唯一映射，同时复用组件的输入校验。 */
    public static DorisCreateTableOptions toRuntimeDorisOptions(AccessDorisOptions options) {
        if (options == null) {
            return null;
        }
        var partition = options.partition();
        return new DorisCreateTableOptions(
                partition == null ? null : new DorisCreateTableOptions.Partition(
                        partition.kind(), partition.columns(), partition.partitions() == null ? null
                        : partition.partitions().stream().map(entry ->
                        new DorisCreateTableOptions.PartitionEntry(
                                entry.name(), entry.values())).toList()),
                options.distributionType(), options.distributionColumns(), options.bucketMode(),
                options.bucketCount(), options.replicationNum());
    }

    /**
     * 获取访问任务配置的字符串。
     *
     * @param values 字段值集合
     * @return 访问任务配置的字符串结果集合
     */
    private static List<String> strings(List<String> values) {
        return values == null ? new ArrayList<>() : new ArrayList<>(values);
    }

    /**
     * 获取访问任务配置的名称大小写。
     *
     * @param value 待处理的值
     * @return 大小写转换
     */
    private static CaseConvertEnum nameCase(String value) {
        return value == null ? null : CaseConvertEnum.valueOf(value);
    }
}
