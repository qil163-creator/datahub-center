package cn.iocoder.yudao.module.dataforge.service.access.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Objects;
import java.util.Set;

/** JSONB 私有 Jackson 2 边界；旧定义必须先执行编号迁移，不在读取时静默兼容。 */
public final class AccessTaskConfigCodec {

    private static final int MAX_JSON_LENGTH = 1_000_000;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(com.fasterxml.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);

    /**
     * 限制访问任务配置工具的实例化，统一通过静态能力使用。
     */
    private AccessTaskConfigCodec() {
    }

    /**
     * 写入访问任务配置数据。
     *
     * @param config 处理配置
     * @return 写入访问任务配置数据文本
     */
    public static String write(AccessTaskConfig config) {
        validate(config);
        try {
            String json = MAPPER.writeValueAsString(config);
            if (json.length() > MAX_JSON_LENGTH) {
                throw invalid();
            }
            return json;
        } catch (java.io.IOException ignored) {
            throw invalid();
        }
    }

    /**
     * 读取访问任务配置。
     *
     * @param json JSON 内容
     * @return 访问任务配置
     */
    public static AccessTaskConfig read(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        if (json.length() > MAX_JSON_LENGTH) {
            throw invalid();
        }
        try {
            var tree = MAPPER.readTree(json);
            if (!tree.isObject() || !tree.path("schemaVersion").isIntegralNumber()
                    || tree.path("schemaVersion").intValue() != AccessTaskConfig.CURRENT_VERSION) {
                throw invalid();
            }
            // Jackson 的 String 反序列化器仍可能把数字转为名称；在转换前验证 typed 名单的真实 JSON 类型。
            for (var sourceNode : tree.path("source")) {
                validateHookJson(sourceNode);
                var mappings = sourceNode.path("configMap");
                if (mappings.isObject()) {
                    mappings.forEach(AccessTaskConfigCodec::validateTableMappingJson);
                }
                var names = sourceNode.get("sourceTableNames");
                if (names == null || names.isNull()) {
                    continue; // 可选 V1 扩展；旧定义继续采用旧版语义。
                }
                if (!names.isArray()) {
                    throw invalid();
                }
                for (var name : names) {
                    if (!name.isTextual()) {
                        throw invalid();
                    }
                }
            }
            validateHookJson(tree.path("target"));
            var distribution = tree.path("target").get("distributedKeys");
            if (distribution != null) {
                if (!distribution.isArray()) {
                    throw invalid();
                }
                for (var key : distribution) {
                    if (!key.isTextual()) {
                        throw invalid();
                    }
                }
            }
            AccessTaskConfig config = MAPPER.treeToValue(tree, AccessTaskConfig.class);
            validate(config);
            return config;
        } catch (java.io.IOException ignored) {
            throw invalid();
        }
    }

    /**
     * 复制访问任务配置。
     *
     * @param config 处理配置
     * @return 访问任务配置
     */
    public static AccessTaskConfig copy(AccessTaskConfig config) {
        return read(write(config));
    }

    /** SQL 投影使用全局列规则；持久定义和 HTTP 入口采用相同的业务模式约束。 */
    public static void requireSqlUnifiedMapping(String mapperType) {
        if (!"ALL".equals(mapperType)) {
            throw invalid();
        }
    }

    /**
     * 校验访问任务配置输入及状态。
     *
     * @param config 处理配置
     */
    public static void validate(AccessTaskConfig config) {
        if (config == null || !Objects.equals(config.getSchemaVersion(), AccessTaskConfig.CURRENT_VERSION)
                || config.getSource() == null || config.getSource().size() != 1
                || config.getSource().getFirst() == null || config.getTarget() == null) {
            throw invalid();
        }
        var source = config.getSource().getFirst();
        var target = config.getTarget();
        var distribution = target.getDistributedKeys();
        if (distribution == null || distribution.size() > 1024
                || distribution.stream().anyMatch(key -> key == null || key.isBlank() || key.indexOf('\0') >= 0)
                || new java.util.HashSet<>(distribution).size() != distribution.size()
                || !distribution.isEmpty() && target.isChangeDataSync()) {
            throw invalid();
        }
        validateHook(source.getBeforeSqlScripts());
        validateHook(source.getAfterSqlScripts());
        validateHook(target.getBeforeSqlScripts());
        validateHook(target.getAfterSqlScripts());
        if (source.getSourceTableNames() != null) {
            if (!Integer.valueOf(1).equals(source.getSourceType())) {
                throw invalid();
            }
            String activeCsv = Integer.valueOf(1).equals(source.getIncludeOrExclude())
                    ? source.getSourceIncludes() : source.getSourceExcludes();
            if (activeCsv != null && !activeCsv.isEmpty()) {
                throw invalid();
            }
            try {
                cn.iocoder.yudao.framework.dbswitch.data.config.SourceTableSelection.requireExactNames(
                        source.getSourceTableNames());
            } catch (IllegalArgumentException ignored) {
                throw invalid();
            }
        }
        try {
            var decisions = AccessTaskConfigMapper.toRuntimeAcknowledgements(config.getTypeMappingAcknowledgements());
            if (!decisions.isEmpty() && (!Integer.valueOf(1).equals(source.getSourceType())
                    || target.isChangeDataSync() && "INCREASE_COLUMN".equals(target.getChangeDataSyncType()))) {
                throw invalid();
            }
        } catch (IllegalArgumentException | NullPointerException ignored) {
            throw invalid();
        }
        if (source.getSourceType() == null || source.getIncludeOrExclude() == null
                || source.getMapperType() == null || source.getTableReadMode() == null
                || source.getCursorBootstrapAllowed() == null || source.getIdentitySourceQuiesced() == null || target.getTargetSyncOption() == null
                || !Set.of(1, 2).contains(source.getSourceType()) || !Set.of(0, 1)
                .contains(source.getIncludeOrExclude())
                || source.getFetchSize() == null || source.getFetchSize() < 1 || source.getFetchSize() > 100_000
                || !Set.of("ALL", "SINGLE").contains(source.getMapperType())
                || !Set.of("INCLUDE_DESCENDANTS", "ONLY").contains(source.getTableReadMode())
                || target.isLowercase() && target.isUppercase()) {
            throw invalid();
        }
        if (!nameCase(target.getTableNameCase()) || !nameCase(target.getColumnNameCase())) {
            throw invalid();
        }
        if (source.getSourceType() == 2) {
            requireSqlUnifiedMapping(source.getMapperType());
            if (blank(source.getSourceSql()) || blank(target.getTargetTable())) {
                throw invalid();
            }
            // 保存接口禁止删除 SQL 投影列；旧 JSONB、运行快照和直接 runtime 转换同样不能绕过。
            if (source.getRegexColumnMapper() != null && source.getRegexColumnMapper().stream().anyMatch(mapping ->
                    mapping == null || cn.hutool.core.util.StrUtil.isBlank(mapping.getToValue()))) {
                throw invalid();
            }
        }
        if (source.getCursorWatermarkContract() != null
                && !"MONOTONIC_WATERMARK_V1".equals(source.getCursorWatermarkContract())) {
            throw invalid();
        }
        if (source.getCursorBootstrapAllowed() && blank(source.getCursorWatermarkContract())) {
            throw invalid();
        }
        if (target.isPreserveIdentityValues() != target.isExclusiveTargetOwnership()
                || target.isPreserveIdentityValues() && target.isChangeDataSync()) {
            throw invalid();
        }
        if (target.getWriteMode() != null && !Set.of("CREATE_NEW", "STRICT_COPY")
                .contains(target.getWriteMode())) {
            throw invalid();
        }
        if (!Set.of("INSERT_UPDATE_DELETE", "INSERT_UPDATE", "INSERT_DELETE", "UPDATE_DELETE", "INSERT", "UPDATE",
                        "DELETE")
                .contains(target.getTargetSyncOption())) {
            throw invalid();
        }
        if (target.isChangeDataSync() && !Set.of("PK_ORDER_COMPARE", "INCREASE_COLUMN").contains(
                Objects.toString(target.getChangeDataSyncType(), ""))) {
            throw invalid();
        }
        validateMappings(source.getRegexTableMapper());
        validateMappings(source.getRegexColumnMapper());
        if (source.getConfigMap() != null) {
            if (source.getConfigMap().size() > 10_000) {
                throw invalid();
            }
            source.getConfigMap().forEach((table, mapping) -> {
                if (blank(table) || mapping == null) {
                    throw invalid();
                }
                validateTableMapping(mapping, source.getSourceType());
                validateMappings(mapping.getRegexTableMapper());
                validateMappings(mapping.getRegexColumnMapper());
            });
        }
    }

    /**
     * 校验表映射。
     *
     * @param mapping 映射
     * @param sourceType 来源类型
     */
    private static void validateTableMapping(AccessTableMapping mapping, Integer sourceType) {
        if (mapping.getTargetTableName() != null || mapping.getTargetTableComment() != null || mapping.getDorisOptions() != null) {
            if (!Integer.valueOf(1).equals(sourceType)) {
                throw invalid();
            }
        }
        if (mapping.getTargetTableName() != null) {
            if (mapping.getTargetTableName().isBlank()) {
                throw invalid();
            }
            try {
                cn.iocoder.yudao.framework.dbswitch.data.config.SourceTableSelection.requireExactName(
                        mapping.getTargetTableName());
            } catch (IllegalArgumentException ignored) {
                throw invalid();
            }
        }
        String comment = mapping.getTargetTableComment();
        if (comment != null && (comment.length() > 65_536 || comment.indexOf('\0') >= 0)) {
            throw invalid();
        }
        try {
            AccessTaskConfigMapper.toRuntimeDorisOptions(mapping.getDorisOptions());
        } catch (IllegalArgumentException | NullPointerException ignored) {
            throw invalid();
        }
    }

    /** Jackson String 仍可能接受数字；物理名称和分区值必须保留真实字符串线协议。 */
    private static void validateTableMappingJson(com.fasterxml.jackson.databind.JsonNode mapping) {
        requireTextIfPresent(mapping, "targetTableName", "targetTableComment");
        var options = mapping.get("dorisOptions");
        if (options == null || options.isNull()) {
            return;
        }
        if (!options.isObject()) {
            throw invalid();
        }
        requireTextIfPresent(options, "distributionType", "bucketMode");
        requireTextArray(options.get("distributionColumns"));
        var partition = options.get("partition");
        if (partition == null || partition.isNull()) {
            return;
        }
        if (!partition.isObject()) {
            throw invalid();
        }
        requireTextIfPresent(partition, "kind");
        requireTextArray(partition.get("columns"));
        var entries = partition.get("partitions");
        if (entries == null || !entries.isArray()) {
            throw invalid();
        }
        for (var entry : entries) {
            requireTextIfPresent(entry, "name");
            var tuples = entry.get("values");
            if (tuples == null || !tuples.isArray()) {
                throw invalid();
            }
            for (var tuple : tuples) {
                requireTextArray(tuple);
            }
        }
    }

    /**
     * 校验文本条件展示。
     *
     * @param node 节点
     * @param fields 字段定义
     */
    private static void requireTextIfPresent(com.fasterxml.jackson.databind.JsonNode node, String... fields) {
        for (String field : fields) {
            var value = node.get(field);
            if (value != null && !value.isNull() && !value.isTextual()) {
                throw invalid();
            }
        }
    }

    /**
     * 校验文本数组。
     *
     * @param values 字段值集合
     */
    private static void requireTextArray(com.fasterxml.jackson.databind.JsonNode values) {
        if (values == null || !values.isArray()) {
            throw invalid();
        }
        for (var value : values) {
            if (!value.isTextual()) {
                throw invalid();
            }
        }
    }

    /**
     * 校验映射。
     *
     * @param mappings 映射
     */
    private static void validateMappings(java.util.List<AccessNameMapping> mappings) {
        if (mappings == null) {
            return;
        }
        if (mappings.size() > 1000) {
            throw invalid();
        }
        for (var mapping : mappings) {
            if (mapping == null || mapping.getFromPattern() == null || mapping.getFromPattern().length() > 1024
                    || mapping.getToValue() == null || mapping.getToValue().length() > 1024) {
                throw invalid();
            }
            try {
                java.util.regex.Pattern.compile(mapping.getFromPattern());
            } catch (java.util.regex.PatternSyntaxException ignored) {
                throw invalid();
            }
        }
    }

    /**
     * 判断文本是否为空或只包含空白字符。
     *
     * @param value 待处理的值
     * @return 处理是否成功
     */
    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** null 表示沿用旧的大小写开关。 */
    private static boolean nameCase(String value) {
        return value == null || value.matches(AccessTaskConfig.NAME_CASE_VALUES);
    }

    /**
     * 校验钩子JSON。
     *
     * @param node 节点
     */
    private static void validateHookJson(com.fasterxml.jackson.databind.JsonNode node) {
        for (String name : java.util.List.of("beforeSqlScripts", "afterSqlScripts")) {
            var value = node.get(name);
            if (value != null && !value.isNull() && !value.isTextual()) {
                throw invalid();
            }
        }
    }

    /**
     * 校验钩子。
     *
     * @param value 待处理的值
     */
    private static void validateHook(String value) {
        if (value != null && (value.length() > AccessTaskConfig.MAX_HOOK_SQL_LENGTH || value.indexOf('\0') >= 0)) {
            throw invalid();
        }
    }

    /**
     * 构造当前配置或输入不合法时使用的异常。
     *
     * @return 非法参数异常
     */
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("数据接入配置不符合版本 1 白名单；旧数据须先执行配置迁移");
    }
}
