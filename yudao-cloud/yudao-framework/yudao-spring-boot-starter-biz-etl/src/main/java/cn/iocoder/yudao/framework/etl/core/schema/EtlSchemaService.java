package cn.iocoder.yudao.framework.etl.core.schema;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.dbswitch.core.lineage.LineageTransform;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.service.IMetaDataByJdbcService;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.etl.core.connection.EtlApiPort;
import cn.iocoder.yudao.framework.etl.core.connection.EtlConnectionInfo;
import cn.iocoder.yudao.framework.etl.core.connection.EtlConnectionResolver;
import cn.iocoder.yudao.framework.etl.core.connection.EtlTableReference;
import cn.iocoder.yudao.framework.etl.core.executor.builtin.*;
import cn.iocoder.yudao.framework.etl.core.executor.support.EtlFieldRules;
import cn.iocoder.yudao.framework.etl.core.model.EtlDag;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.sql.Types;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 字段契约推导：沿拓扑序推导每个节点的输入/输出字段，供设计器字段下拉与连线标签使用
 *
 * @author Li
 */
@Slf4j
public class EtlSchemaService {

    private final DbSwitchRuntime databaseRuntime;

    /**
     * 校验构造参数并初始化 {@code EtlSchemaService}。
     */
    public EtlSchemaService(DbSwitchRuntime databaseRuntime) {
        this.databaseRuntime = Objects.requireNonNull(databaseRuntime, "databaseRuntime");
    }

    /**
     * 推导字段契约（推导失败的节点记录到 errors，不中断整体）
     *
     * @param apiPort API 数据端口（可空，Rest API 节点推导需要）
     */
    public EtlSchemaResult resolve(EtlDag dag, EtlConnectionResolver connectionResolver, EtlApiPort apiPort) {
        return resolve(dag, connectionResolver, apiPort, Map.of());
    }

    /** 调用方可提供已经授权并冻结的来源元数据，复用相同转换推导而不重复读取来源。 */
    public EtlSchemaResult resolve(EtlDag dag, EtlConnectionResolver connectionResolver, EtlApiPort apiPort,
            Map<String, List<EtlFieldMeta>> sourceFields) {
        return resolve(dag, connectionResolver, apiPort, sourceFields, false);
    }

    /**
     * 追踪字段来源：复用字段契约推导的逐组件规则，额外记录每个字段来自哪些输入节点的哪些字段。
     * <p>
     * 结果只描述画布内的追溯关系，供血缘等消费方映射到物理表；不改变 {@link #resolve} 的结果和序列化形状。
     * 脚本、表达式计算等无法在设计期证明来源的组件，其输出字段标记为不完整。
     *
     * @param sourceFields 调用方已授权并冻结的输入节点字段（可空），与 {@link #resolve} 语义相同
     */
    public EtlFieldOriginResult traceOrigins(EtlDag dag, EtlConnectionResolver connectionResolver, EtlApiPort apiPort,
            Map<String, List<EtlFieldMeta>> sourceFields) {
        EtlSchemaResult schema = resolve(dag, connectionResolver, apiPort,
                sourceFields == null ? Map.of() : sourceFields, true);
        Map<String, EtlFieldOriginResult.NodeTrace> nodes = new LinkedHashMap<>();
        schema.getNodes().forEach((no, node) -> nodes.put(no, new EtlFieldOriginResult.NodeTrace(
                traced(node.getInputFields()), traced(node.getOutputFields()))));
        return new EtlFieldOriginResult(nodes, schema.getErrors());
    }

    /**
     * 转换为追踪字段。
     */
    private static List<EtlFieldOriginResult.TracedField> traced(List<EtlFieldMeta> fields) {
        return fields.stream().map(field -> new EtlFieldOriginResult.TracedField(field.getName(), field.origins(),
                !field.originsUnknown())).toList();
    }

    /**
     * 推导字段契约；track 为 true 时为无上游的输入节点打上原始来源，规则沿途传递。
     */
    private EtlSchemaResult resolve(EtlDag dag, EtlConnectionResolver connectionResolver, EtlApiPort apiPort,
            Map<String, List<EtlFieldMeta>> sourceFields, boolean track) {
        EtlSchemaResult result = new EtlSchemaResult();
        List<String> sorted;
        try {
            sorted = dag.topologicalSort();
        } catch (Exception ex) {
            result.getErrors().add(ex.getMessage());
            return result;
        }
        for (String no : sorted) {
            EtlNodeDef node = dag.getNode(no);
            EtlSchemaResult.NodeSchema schema = new EtlSchemaResult.NodeSchema();
            result.getNodes().put(no, schema);
            try {
                schema.setInputFields(resolveInput(dag, result, node));
                schema.setOutputFields(sourceFields.containsKey(no) ? copyFields(sourceFields.get(no))
                        : resolveOutput(node, schema.getInputFields(), connectionResolver, apiPort));
                if (track && dag.getDataUpstream(no) == null && dag.getDataUpstreamList(no).isEmpty()) {
                    schema.getOutputFields().forEach(field -> field.originAt(no));
                }
            } catch (Exception ex) {
                log.warn("[resolve][节点({}) 字段推导失败：{}]", node.getName(), ex.getMessage());
                result.getErrors().add(String.format("节点【%s】字段推导失败：%s",
                        node.getName(), StrUtil.maxLength(ex.getMessage(), 200)));
            }
        }
        return result;
    }

    /**
     * 输入字段 = 数据上游的输出字段；多输入节点取全部上游的并集
     */
    private List<EtlFieldMeta> resolveInput(EtlDag dag, EtlSchemaResult result, EtlNodeDef node) {
        if (node.isMultiInput()) {
            Map<String, EtlFieldMeta> union = new LinkedHashMap<>();
            List<List<EtlFieldMeta>> sources = new ArrayList<>();
            for (String upstreamNo : dag.getDataUpstreamList(node.getNo())) {
                EtlSchemaResult.NodeSchema upstream = result.getNodes().get(upstreamNo);
                if (upstream != null) {
                    sources.add(upstream.getOutputFields());
                    upstream.getOutputFields().forEach(field -> union.merge(field.getName(), field.copy(),
                            EtlSchemaService::commonMetadata));
                }
            }
            union.replaceAll((name, field) -> sources.stream().anyMatch(source -> source.stream()
                    .noneMatch(value -> name.equals(value.getName()))) ? new EtlFieldMeta(name,
                    field.getDataType()).tracedLike(field) : field);
            return new ArrayList<>(union.values());
        }
        String upstreamNo = dag.getDataUpstream(node.getNo());
        if (upstreamNo != null && result.getNodes().containsKey(upstreamNo)) {
            return copyFields(result.getNodes().get(upstreamNo).getOutputFields());
        }
        return new ArrayList<>();
    }

    /**
     * 解析输出。
     */
    private List<EtlFieldMeta> resolveOutput(EtlNodeDef node, List<EtlFieldMeta> inputFields,
            EtlConnectionResolver connectionResolver, EtlApiPort apiPort) {
        JSONObject config = JSONUtil.parseObj(StrUtil.blankToDefault(node.getNodeJson(), "{}"));
        return switch (StrUtil.nullToEmpty(node.getToolCode())) {
            case "boundary-input", "boundary-output", "boundary-bridge" -> {
                List<EtlFieldMeta> fields = copyFields(inputFields);
                var declared = config.getJSONArray("fields");
                if (declared != null) {
                    for (var contract : EtlFieldContract.parseFields(declared)) {
                        EtlFieldMeta existing = fields.stream()
                                .filter(field -> Objects.equals(field.getName(), contract.name())).findFirst().orElse(null);
                        fields.removeIf(field -> Objects.equals(field.getName(), contract.name()));
                        fields.add(contract.metadata().tracedLike(existing));
                    }
                }
                yield fields;
            }
            case ReadDbExecutor.TOOL_CODE -> readDatabaseFields(config, connectionResolver);
            case RestApiExecutor.TOOL_CODE -> resolveRestApi(config, apiPort);
            case ReadKafkaExecutor.TOOL_CODE -> resolveReadKafka();
            // Excel / CSV 列头运行时确定，设计期无法推导
            case ReadExcelExecutor.TOOL_CODE, ReadCsvExecutor.TOOL_CODE,
                 ReadJsonExecutor.TOOL_CODE -> List.of();
            case "read-gzip-csv" -> List.of();
            case ColumnMapExecutor.TOOL_CODE -> resolveColumnMap(config, inputFields);
            case DataCleanExecutor.TOOL_CODE -> resolveDataClean(config, inputFields);
            case FieldDeriveExecutor.TOOL_CODE -> resolveFieldDerive(config, inputFields);
            case EtlFieldRules.STRING_OPERATION, EtlFieldRules.STRING_REPLACE, EtlFieldRules.SET_FIELD_VALUE ->
                    resolveFieldRules(node.getToolCode(), config, inputFields);
            case AddConstantExecutor.TOOL_CODE -> resolveAddConstant(config, inputFields);
            case RowColumnExecutor.TOOL_CODE -> resolveRowColumn(config, inputFields);
            case FieldSplitExecutor.TOOL_CODE -> resolveFieldSplit(config, inputFields);
            case IdGeneratorExecutor.TOOL_CODE -> resolveAppendField(config, inputFields, "targetField", "String", false);
            case CalculatorExecutor.TOOL_CODE -> resolveCalculator(config, inputFields);
            case ValueMapExecutor.TOOL_CODE, NumberRangeExecutor.TOOL_CODE ->
                    resolveAppendField(config, inputFields, "targetField", "String", true);
            case QualityCheckExecutor.TOOL_CODE -> resolveQualityFields(config, inputFields);
            case SplitToRowsExecutor.TOOL_CODE -> resolveAppendField(config, inputFields, "targetField", "String", true);
            case SortRecordsExecutor.TOOL_CODE -> copyFields(inputFields);
            // 脚本和复杂合并可改值、补空或展开结构；已有字段名称/类型只作提示，不能沿用新属性证据。
            // 脚本可任意改写字段值，来源无法在设计期证明。
            case JavaScriptExecutor.TOOL_CODE, JsScriptExecutor.TOOL_CODE -> unknownOrigins(unknownProperties(inputFields));
            case DataEncryptExecutor.TOOL_CODE -> resolveCryptoFields(config, inputFields, "encryptFields", true);
            case DataDecryptExecutor.TOOL_CODE -> resolveCryptoFields(config, inputFields, "decryptFields", false);
            case DataDesensitizeExecutor.TOOL_CODE -> resolveCryptoFields(config, inputFields, "desensitizeFields", true);
            case DataFilterExecutor.TOOL_CODE, DataConditionExecutor.TOOL_CODE -> copyFields(inputFields);
            case DataMergeExecutor.TOOL_CODE, SortMergeExecutor.TOOL_CODE,
                 MultiJoinExecutor.TOOL_CODE, CartesianProductExecutor.TOOL_CODE,
                 FlattenRowExecutor.TOOL_CODE -> unknownProperties(inputFields);
            case GenerateRecordsExecutor.TOOL_CODE, RandomRecordsExecutor.TOOL_CODE -> resolveGeneratedFields(config);
            // 输出/脚本节点无数据输出
            default -> List.of();
        };
    }

    /**
     * 根据数据库读取节点配置解析输出字段结构；调用方负责授权连接并限制 SQL 形状。
     */
    public List<EtlFieldMeta> readDatabaseFields(JSONObject config, EtlConnectionResolver connectionResolver) {
        Long databaseId = config.getLong("databaseId");
        if (databaseId == null) {
            return List.of();
        }
        List<ColumnDescription> columns;
        try {
            EtlConnectionInfo conn = connectionResolver.resolve(databaseId);
            IMetaDataByJdbcService metaDataService = databaseRuntime.jdbcMetadata(
                    conn.getProductType(), conn.getDriverProfileId());
            if (config.getInt("ifSql", 0) == 1) {
                String sql = config.getStr("sql");
                if (StrUtil.isBlank(sql)) {
                    return List.of();
                }
                columns = metaDataService.querySqlColumnMeta(
                        conn.getJdbcUrl(), conn.getUsername(), conn.getPassword(), sql);
            } else {
                String tableName = config.getStr("tableName");
                if (StrUtil.isBlank(tableName)) {
                    return List.of();
                }
                var table = EtlTableReference.resolve(conn, tableName, config.getStr("schemaName"));
                columns = metaDataService.queryTableSchema(
                        conn.getJdbcUrl(), conn.getUsername(), conn.getPassword(),
                        table.schema(), table.table(), 10_000).columns();
            }
        } catch (RuntimeException failure) {
            // 厂商或驱动异常可能含有 SQL、JDBC URL 或凭据。不得将其
            // 消息或原因传入设计响应，也不得传入下方的通用节点警告。
            log.warn("[resolveReadDb][数据库字段元数据读取失败 databaseId={}, failureType={}]",
                    databaseId, failure.getClass().getSimpleName());
            throw new IllegalStateException("数据库字段元数据读取失败，请核对授权、连接与受支持的查询配置");
        }
        List<EtlFieldMeta> fields = new ArrayList<>();
        for (ColumnDescription column : columns) {
            String type = column.getFieldTypeName();
            var field = new EtlFieldMeta(column.getFieldName(), StrUtil.isBlank(type) || "-".equals(type) ? null : type);
            // JDBC 旧版布尔值和默认精度不能作为证据。SQL 结果没有原生源契约。
            if (config.getInt("ifSql", 0) != 1) {
                var evidence = column.getMetadataEvidence();
                field.setNullable(value(evidence.nullable()));
                field.setLength(value(evidence.characterLength()));
                // 整数或浮点数的 numeric_precision 可能表示二进制位数。仅导出精确十进制精度。
                if (column.getFieldType() == Types.NUMERIC || column.getFieldType() == Types.DECIMAL) {
                    field.setPrecision(value(evidence.numericPrecision()));
                    field.setScale(value(evidence.numericScale()));
                }
            }
            fields.add(field);
        }
        return fields;
    }

    /**
     * 解析质量字段集合。
     */
    private List<EtlFieldMeta> resolveQualityFields(JSONObject config, List<EtlFieldMeta> input) {
        List<EtlFieldMeta> result = new ArrayList<>(input);
        String status = config.getStr("statusField", "_quality_status");
        if (result.stream().anyMatch(f -> status.equals(f.getName()))) {
            throw new IllegalStateException("状态字段与已有字段冲突：" + status);
        }
        result.add(new EtlFieldMeta(status, "String"));
        return result;
    }

    /**
     * 解析剩余部分API。
     */
    private List<EtlFieldMeta> resolveRestApi(JSONObject config, EtlApiPort apiPort) {
        JSONObject apiConfig = config.getJSONObject("apiConfig");
        if (apiConfig == null || apiPort == null) {
            return List.of();
        }
        List<EtlFieldMeta> columns = apiPort.getColumns(apiConfig);
        return columns == null ? List.of() : columns;
    }

    /**
     * 解析读取Kafka。
     */
    private List<EtlFieldMeta> resolveReadKafka() {
        List<EtlFieldMeta> fields = new ArrayList<>();
        fields.add(new EtlFieldMeta("_key", "String"));
        fields.add(new EtlFieldMeta("_topic", "String"));
        fields.add(new EtlFieldMeta("_partition", "Integer"));
        fields.add(new EtlFieldMeta("_offset", "Long"));
        fields.add(new EtlFieldMeta("_timestamp", "Long"));
        fields.add(new EtlFieldMeta("value", "String"));
        return fields;
    }

    /**
     * 解析列映射。
     */
    private List<EtlFieldMeta> resolveColumnMap(JSONObject config, List<EtlFieldMeta> inputFields) {
        JSONArray mappers = config.getJSONArray("regexColumnMapper");
        List<EtlFieldMeta> fields = new ArrayList<>();
        for (EtlFieldMeta input : inputFields) {
            String name = input.getName();
            boolean removed = false;
            if (mappers != null) {
                for (Object item : mappers) {
                    JSONObject mapper = (JSONObject) item;
                    String fromPattern = mapper.getStr("fromPattern");
                    if (StrUtil.isBlank(fromPattern)) {
                        continue;
                    }
                    try {
                        Pattern pattern = Pattern.compile(fromPattern);
                        if (!pattern.matcher(name).find()) {
                            continue;
                        }
                        String toValue = mapper.getStr("toValue");
                        if (StrUtil.isBlank(toValue)) {
                            removed = true;
                        } else {
                            name = pattern.matcher(name).replaceAll(toValue);
                        }
                    } catch (PatternSyntaxException ex) {
                        throw new IllegalStateException(
                                String.format("正则表达式【%s】不合法", fromPattern));
                    }
                    break;
                }
            }
            if (!removed) {
                fields.add(input.renamed(name));
            }
        }
        return fields;
    }

    /**
     * 数据清洗：删除字段调整结构，填默认值或格式化使目标字段的旧属性证据失效。
     */
    private List<EtlFieldMeta> resolveDataClean(JSONObject config, List<EtlFieldMeta> inputFields) {
        if ("GOVERNANCE".equalsIgnoreCase(config.getStr("executionMode"))) {
            // 治理清洗规则可将数值变成字符串或 BigInteger；设计期拿不到冻结规则详情，
            // 因此对声明为目标的字段丢弃旧类型，避免下游误用过时的字段契约。
            var changedFields = new java.util.HashSet<String>();
            JSONArray rules = config.getJSONArray("governanceRules");
            if (rules != null) {
                for (Object item : rules) {
                    if (!(item instanceof JSONObject rule)) {
                        continue;
                    }
                    JSONObject ruleConfig = rule.getJSONObject("config");
                    JSONArray columns = ruleConfig == null ? null : ruleConfig.getJSONArray("columns");
                    if (columns != null) {
                        for (Object column : columns) {
                            if (column instanceof String name && StrUtil.isNotBlank(name)) {
                                changedFields.add(name);
                            }
                        }
                    }
                }
            }
            List<EtlFieldMeta> fields = new ArrayList<>(inputFields.size());
            for (EtlFieldMeta field : inputFields) {
                fields.add(changedFields.contains(field.getName())
                        ? new EtlFieldMeta(field.getName(), null).derivedFrom(field, LineageTransform.TRANSFORMATION, false)
                        : field.copy());
            }
            return fields;
        }
        List<EtlFieldMeta> fields = copyFields(inputFields);
        if (config.getBool("enableFieldRemoval", false)) {
            List<String> toRemove = StrUtil.split(config.getStr("fieldsToRemove", ""), ',', true, true);
            fields.removeIf(field -> toRemove.contains(field.getName()));
        }
        var changed = new java.util.HashSet<String>();
        if (config.getBool("enableNullHandling", false)
                && !"REMOVE".equalsIgnoreCase(config.getStr("nullHandlingStrategy", "REMOVE"))) {
            collectFieldNames(config.getJSONArray("defaultValues"), changed);
        }
        if (config.getBool("enableFormatting", false)) {
            collectFieldNames(config.getJSONArray("formatRules"), changed);
        }
        fields.replaceAll(field -> changed.contains(field.getName())
                ? new EtlFieldMeta(field.getName(), null).derivedFrom(field, LineageTransform.TRANSFORMATION, false)
                : field);
        if (config.getBool("enableNullHandling", false)
                && !"REMOVE".equalsIgnoreCase(config.getStr("nullHandlingStrategy", "REMOVE"))) {
            var defaults = new java.util.HashSet<String>();
            collectFieldNames(config.getJSONArray("defaultValues"), defaults);
            defaults.stream().filter(name -> fields.stream().noneMatch(field -> name.equals(field.getName())))
                    .sorted().forEach(name -> fields.add(new EtlFieldMeta(name, null)));
        }
        return fields;
    }

    /**
     * 字段派生器：输入字段 + 派生的新字段
     */
    private List<EtlFieldMeta> resolveFieldDerive(JSONObject config, List<EtlFieldMeta> inputFields) {
        Map<String, EtlFieldMeta> fields = new LinkedHashMap<>();
        inputFields.forEach(field -> fields.put(field.getName(), field));
        String targetField = config.getStr("targetField");
        if (StrUtil.isNotBlank(targetField)) {
            // 派生表达式的引用字段在设计期无法可靠解析，来源标记为不完整。
            fields.put(targetField, new EtlFieldMeta(targetField, "String").markOriginsUnknown());
        }
        return new ArrayList<>(fields.values());
    }

    /** 字符串变换清除数值和时区属性；原样复制继承来源字段的全部已知契约。 */
    private List<EtlFieldMeta> resolveFieldRules(String code, JSONObject config, List<EtlFieldMeta> inputFields) {
        var plan = EtlFieldRules.compile(code, config);
        Map<String, EtlFieldMeta> original = new LinkedHashMap<>();
        inputFields.forEach(field -> original.put(field.getName(), field));
        Map<String, EtlFieldMeta> result = new LinkedHashMap<>();
        inputFields.forEach(field -> result.put(field.getName(), field.copy()));
        for (String required : plan.requiredFields()) {
            if (!original.containsKey(required)) throw new IllegalStateException("上游字段不存在：" + required);
        }
        for (var projection : plan.projections()) {
            EtlFieldMeta source = original.get(projection.sourceField());
            if (source == null) throw new IllegalStateException("上游字段不存在：" + projection.sourceField());
            EtlFieldMeta target = projection.textResult()
                    ? new EtlFieldMeta(projection.targetField(), "String", source.getNullable(), null, null, null)
                    : source.renamed(projection.targetField());
            target.setSourceName(source.getSourceName());
            // 设置字段值为原样复制；字符串处理和替换会改变取值。
            target.derivedFrom(source, EtlFieldRules.SET_FIELD_VALUE.equals(code)
                    ? LineageTransform.IDENTITY : LineageTransform.TRANSFORMATION, false);
            result.put(target.getName(), target);
        }
        return new ArrayList<>(result.values());
    }

    /**
     * 增加常量：输入字段 + 常量字段（按声明类型）
     */
    private List<EtlFieldMeta> resolveAddConstant(JSONObject config, List<EtlFieldMeta> inputFields) {
        Map<String, EtlFieldMeta> fields = new LinkedHashMap<>();
        inputFields.forEach(field -> fields.put(field.getName(), field));
        JSONArray array = config.getJSONArray("fields");
        if (array != null) {
            for (Object item : array) {
                JSONObject field = (JSONObject) item;
                String name = field.getStr("name");
                if (StrUtil.isNotBlank(name)) {
                    fields.put(name, new EtlFieldMeta(name,
                            StrUtil.blankToDefault(field.getStr("fieldType"), "String")));
                }
            }
        }
        return new ArrayList<>(fields.values());
    }

    /**
     * 行列转换：列转行 = 保留列 + 键列/值列；行转列 = 分组字段 + 映射生成的新列
     */
    private List<EtlFieldMeta> resolveRowColumn(JSONObject config, List<EtlFieldMeta> inputFields) {
        String mode = StrUtil.blankToDefault(config.getStr("mode"), "COL2ROW").toUpperCase();
        List<EtlFieldMeta> fields = new ArrayList<>();
        if ("ROW2COL".equals(mode)) {
            JSONArray groupFields = config.getJSONArray("groupFields");
            if (groupFields != null) {
                for (Object item : groupFields) {
                    String name = String.valueOf(item);
                    EtlFieldMeta input = inputFields.stream()
                            .filter(field -> name.equals(field.getName()))
                            .findFirst().orElse(null);
                    fields.add(input == null ? new EtlFieldMeta(name, "String") : input);
                }
            }
            JSONArray mappings = config.getJSONArray("mappings");
            if (mappings != null) {
                for (Object item : mappings) {
                    String fieldName = ((JSONObject) item).getStr("fieldName");
                    if (StrUtil.isNotBlank(fieldName)) {
                        // 行转列的新列取值依赖运行期数据，来源标记为不完整。
                        fields.add(new EtlFieldMeta(fieldName, "String").markOriginsUnknown());
                    }
                }
            }
            return fields;
        }
        JSONArray unpivotArray = config.getJSONArray("unpivotFields");
        List<String> unpivotFields = new ArrayList<>();
        if (unpivotArray != null) {
            unpivotArray.forEach(item -> unpivotFields.add(String.valueOf(item)));
        }
        inputFields.stream()
                .filter(field -> !unpivotFields.contains(field.getName()))
                .forEach(fields::add);
        fields.add(new EtlFieldMeta(
                StrUtil.blankToDefault(config.getStr("keyField"), "key"), "String"));
        fields.add(new EtlFieldMeta(
                StrUtil.blankToDefault(config.getStr("valueField"), "value"), "String")
                .derivedFrom(inputFields.stream().filter(field -> unpivotFields.contains(field.getName())).toList(),
                        LineageTransform.TRANSFORMATION, false));
        return fields;
    }

    /**
     * 字段拆分：输入（默认移除来源字段）+ 拆分出的新字段
     */
    private List<EtlFieldMeta> resolveFieldSplit(JSONObject config, List<EtlFieldMeta> inputFields) {
        Map<String, EtlFieldMeta> fields = new LinkedHashMap<>();
        inputFields.forEach(field -> fields.put(field.getName(), field));
        EtlFieldMeta source = fields.get(config.getStr("sourceField"));
        if (!config.getBool("keepSource", false)) {
            fields.remove(config.getStr("sourceField"));
        }
        JSONArray outputs = config.getJSONArray("outputs");
        if (outputs != null) {
            for (Object item : outputs) {
                String name = ((JSONObject) item).getStr("name");
                if (StrUtil.isNotBlank(name)) {
                    fields.put(name, new EtlFieldMeta(name, "String")
                            .derivedFrom(source, LineageTransform.TRANSFORMATION, false));
                }
            }
        }
        return new ArrayList<>(fields.values());
    }

    /**
     * 计算器：输入 + 各规则的结果字段
     */
    private List<EtlFieldMeta> resolveCalculator(JSONObject config, List<EtlFieldMeta> inputFields) {
        Map<String, EtlFieldMeta> fields = new LinkedHashMap<>();
        inputFields.forEach(field -> fields.put(field.getName(), field));
        JSONArray rules = config.getJSONArray("rules");
        if (rules != null) {
            for (Object item : rules) {
                String name = ((JSONObject) item).getStr("targetField");
                if (StrUtil.isNotBlank(name)) {
                    // 计算表达式的引用字段在设计期无法可靠解析，来源标记为不完整。
                    fields.put(name, new EtlFieldMeta(name, "String").markOriginsUnknown());
                }
            }
        }
        return new ArrayList<>(fields.values());
    }

    /**
     * 追加单个目标字段（ID 生成器/值映射/数值范围/列拆分为多行，目标为空则覆盖原字段不变结构）
     *
     * @param fromSource 目标值是否由 sourceField 变换而来（ID 生成器为 false）
     */
    private List<EtlFieldMeta> resolveAppendField(JSONObject config, List<EtlFieldMeta> inputFields,
            String configKey, String dataType, boolean fromSource) {
        Map<String, EtlFieldMeta> fields = new LinkedHashMap<>();
        inputFields.forEach(field -> fields.put(field.getName(), field));
        EtlFieldMeta source = fromSource ? fields.get(config.getStr("sourceField")) : null;
        String targetField = StrUtil.blankToDefault(config.getStr(configKey), config.getStr("sourceField"));
        if (StrUtil.isNotBlank(targetField)) {
            fields.put(targetField, new EtlFieldMeta(targetField, dataType)
                    .derivedFrom(source, LineageTransform.TRANSFORMATION, false));
        }
        return new ArrayList<>(fields.values());
    }

    /**
     * 解析生成列字段集合。
     */
    private List<EtlFieldMeta> resolveGeneratedFields(JSONObject config) {
        List<EtlFieldMeta> fields = new ArrayList<>();
        JSONArray array = config.getJSONArray("fields");
        if (array != null) {
            for (Object item : array) {
                JSONObject field = (JSONObject) item;
                String name = field.getStr("name");
                if (StrUtil.isNotBlank(name)) {
                    fields.add(new EtlFieldMeta(name,
                            StrUtil.blankToDefault(field.getStr("type"), "String")));
                }
            }
        }
        return fields;
    }

    /**
     * 加密/解密/脱敏：新字段追加，同字段覆盖时也清除原值属性证据。
     *
     * @param masking 输出是否不暴露原值（加密、脱敏为 true，解密为 false）
     */
    private List<EtlFieldMeta> resolveCryptoFields(JSONObject config, List<EtlFieldMeta> inputFields,
            String configKey, boolean masking) {
        Map<String, EtlFieldMeta> fields = new LinkedHashMap<>();
        inputFields.forEach(field -> fields.put(field.getName(), field));
        JSONArray array = config.getJSONArray(configKey);
        if (array != null) {
            for (Object item : array) {
                JSONObject field = (JSONObject) item;
                String fieldName = field.getStr("fieldName");
                String outputField = StrUtil.blankToDefault(field.getStr("outputFieldName"), fieldName);
                if (StrUtil.isBlank(fieldName)) {
                    continue;
                }
                EtlFieldMeta source = fields.get(fieldName);
                if (!outputField.equals(fieldName) && !field.getBool("keepOriginal", false)) {
                    fields.remove(fieldName);
                }
                fields.put(outputField, new EtlFieldMeta(outputField, "String")
                        .derivedFrom(source, LineageTransform.TRANSFORMATION, masking));
            }
        }
        return new ArrayList<>(fields.values());
    }

    /**
     * 返回值。
     */
    private static <T> T value(MetadataValue<T> fact) {
        return fact != null && fact.state() == MetadataValue.State.VALUE ? fact.value() : null;
    }

    /**
     * 复制字段集合。
     */
    private static List<EtlFieldMeta> copyFields(List<EtlFieldMeta> fields) {
        return new ArrayList<>(fields.stream().map(EtlFieldMeta::copy).toList());
    }

    /**
     * 根据当前输入构建并返回 {@code ArrayList}。
     */
    private static List<EtlFieldMeta> unknownProperties(List<EtlFieldMeta> fields) {
        // 属性证据失效，但同名字段的来源仍可追溯（合并、关联只组合字段，不改写取值）。
        return new ArrayList<>(
                fields.stream().map(field -> new EtlFieldMeta(field.getName(), field.getDataType(), null, null, null, null, null, field.getSourceName())
                        .tracedLike(field)).toList());
    }

    /**
     * 标记全部字段来源不完整（脚本类组件）。
     */
    private static List<EtlFieldMeta> unknownOrigins(List<EtlFieldMeta> fields) {
        fields.forEach(EtlFieldMeta::markOriginsUnknown);
        return fields;
    }

    /**
     * 根据当前输入构建并返回 {@code EtlFieldMeta}。
     */
    private static EtlFieldMeta commonMetadata(EtlFieldMeta left, EtlFieldMeta right) {
        return new EtlFieldMeta(left.getName(), common(left.getDataType(), right.getDataType()),
                common(left.getNullable(), right.getNullable()), common(left.getPrecision(), right.getPrecision()),
                common(left.getScale(), right.getScale()), common(left.getTimezone(), right.getTimezone()),
                common(left.getLength(), right.getLength()), mergeSource(left.getSourceName(), right.getSourceName()))
                .tracedLike(left).mergedWith(right);
    }

    private static String mergeSource(String left, String right) {
        if (left == null) return right;
        if (right == null || left.equals(right)) return left;
        return left + " | " + right;
    }

    /**
     * 构建当前请求或操作共享的参数部分。
     */
    private static <T> T common(T left, T right) {
        return Objects.equals(left, right) ? left : null;
    }

    /**
     * 收集字段名集合。
     */
    private static void collectFieldNames(JSONArray array, java.util.Set<String> names) {
        if (array == null) {
            return;
        }
        for (Object item : array) {
            if (item instanceof JSONObject field && StrUtil.isNotBlank(field.getStr("fieldName"))) {
                names.add(field.getStr("fieldName"));
            }
        }
    }

}
