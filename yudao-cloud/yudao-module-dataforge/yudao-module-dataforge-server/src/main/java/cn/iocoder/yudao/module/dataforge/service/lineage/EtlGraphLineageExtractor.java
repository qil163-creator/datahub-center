package cn.iocoder.yudao.module.dataforge.service.lineage;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.dbswitch.core.lineage.LineageColumnSource;
import cn.iocoder.yudao.framework.dbswitch.core.lineage.LineageTable;
import cn.iocoder.yudao.framework.dbswitch.core.lineage.LineageTransform;
import cn.iocoder.yudao.framework.dbswitch.core.lineage.QueryLineageResult;
import cn.iocoder.yudao.framework.dbswitch.core.lineage.SqlLineageAnalyzer;
import cn.iocoder.yudao.framework.dbswitch.core.lineage.SqlLineageRequest;
import cn.iocoder.yudao.framework.dbswitch.core.lineage.SqlLineageStatus;
import cn.iocoder.yudao.framework.etl.core.connection.EtlConnectionResolver;
import cn.iocoder.yudao.framework.etl.core.model.EtlDag;
import cn.iocoder.yudao.framework.etl.core.model.EtlEdgeDef;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.schema.EtlFieldMeta;
import cn.iocoder.yudao.framework.etl.core.schema.EtlFieldOrigin;
import cn.iocoder.yudao.framework.etl.core.schema.EtlFieldOriginResult;
import cn.iocoder.yudao.framework.etl.core.schema.EtlSchemaService;
import cn.iocoder.yudao.module.governance.api.lineage.dto.LineageNodeRefDTO;
import cn.iocoder.yudao.module.governance.enums.lineage.LineageExternalKindEnum;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 从可视化 ETL 或集成任务的画布提取血缘：输入节点 → 输出节点的表级血缘，以及沿组件传递的字段级血缘。
 * <p>
 * 字段来源复用 ETL 组件的 {@link EtlSchemaService#traceOrigins} 规则；数据库输入的字段取自 governance
 * 已采集的元数据，SQL 输入由 {@link SqlLineageAnalyzer#analyzeQuery} 解析。全程不连接源库：
 * 传给组件的连接解析器只会抛错。外部数据集（Kafka、文件、API 等）只从不含凭据的配置键取标识，
 * 取不到时退回"组件:节点名"，不同流程读同一外部数据集时可能无法合并为同一节点。
 */
@Component
public class EtlGraphLineageExtractor {

    private static final Set<String> OUTPUT_DB = Set.of("insert-db", "managed-db-output");
    private static final Set<String> GENERATED = Set.of("generate-records", "random-records", "boundary-input",
            "boundary-bridge");
    /** 不含凭据、可作为外部数据集标识的配置键，按优先级尝试。 */
    private static final List<String> EXTERNAL_KEYS = List.of("topic", "topics", "collection", "fileResourceId",
            "fileId", "filePath", "path", "objectName", "tableName");
    /** 设计期不连接任何数据源。 */
    private static final EtlConnectionResolver NO_CONNECTION = databaseId -> {
        throw new IllegalStateException("血缘分析不连接数据源");
    };

    @Resource
    private EtlSchemaService schemas;
    @Resource
    private LineageDatasourceResolver datasources;
    @Resource
    private LineageCatalogProvider catalogs;

    /**
     * 输入数据集：节点引用、提供给字段推导的冻结字段（null 表示交给组件规则推导），以及字段到物理来源的映射。
     */
    private record Input(List<LineageNodeRefDTO> nodes, List<EtlFieldMeta> fields,
                         Function<String, List<Source>> sources) {

        /**
         * 按字段名映射的输入。
         */
        static Input mapped(List<LineageNodeRefDTO> nodes, List<EtlFieldMeta> fields, Map<String, List<Source>> byKey) {
            return new Input(nodes, fields, name -> byKey.getOrDefault(key(name), List.of()));
        }
    }

    /** 字段的一个物理来源。 */
    private record Source(LineageNodeRefDTO node, String column, LineageTransform transform, boolean ambiguous,
                          boolean masking) {
    }

    /**
     * 提取画布血缘写入构建器。
     */
    public void extract(List<EtlNodeDef> nodes, List<EtlEdgeDef> edges, LineageReportBuilder builder) {
        if (nodes.isEmpty()) {
            return;
        }
        EtlDag dag;
        try {
            dag = EtlDag.buildLenient(nodes, edges);
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            builder.status(SqlLineageStatus.FAILED).issue("GRAPH_INVALID", "流程结构无效，无法解析血缘", null);
            return;
        }
        Map<String, Input> inputs = inputs(dag, builder);
        Map<String, List<EtlFieldMeta>> sourceFields = new LinkedHashMap<>();
        inputs.forEach((no, input) -> {
            if (input.fields() != null) {
                sourceFields.put(no, input.fields());
            }
        });
        EtlFieldOriginResult trace = schemas.traceOrigins(dag, NO_CONNECTION, null, sourceFields);
        if (!trace.errors().isEmpty()) {
            builder.degrade("ETL_SCHEMA_INCOMPLETE", "部分节点字段推导失败，字段级血缘可能不完整");
        }
        boolean incomplete = false;
        boolean hasOutput = false;
        for (EtlNodeDef node : dag.getNodes()) {
            LineageNodeRefDTO target = output(node);
            if (target == null) {
                continue;
            }
            hasOutput = true;
            for (String root : roots(dag, node.getNo())) {
                Input input = inputs.get(root);
                if (input != null) {
                    input.nodes().forEach(upstream -> builder.tableEdge(upstream, target,
                            LineageReportBuilder.parsedOrigin(), null));
                }
            }
            EtlFieldOriginResult.NodeTrace written = trace.nodes().get(node.getNo());
            if (written == null) {
                continue;
            }
            Map<String, String> mapping = fieldMappings(node);
            for (EtlFieldOriginResult.TracedField field : written.inputs()) {
                String column = mapping.isEmpty() ? field.name() : mapping.get(field.name());
                if (column == null) {
                    continue;
                }
                incomplete = incomplete || !field.complete();
                for (EtlFieldOrigin origin : field.origins()) {
                    Input input = inputs.get(origin.nodeNo());
                    if (input == null) {
                        continue;
                    }
                    for (Source source : input.sources().apply(origin.field())) {
                        builder.columnEdge(source.node(), source.column(), target, column,
                                LineageTransform.compose(origin.transform(), source.transform()),
                                source.ambiguous(), source.masking() || origin.masking(), null);
                    }
                }
            }
        }
        if (incomplete) {
            builder.degrade("ETL_ORIGIN_INCOMPLETE", "流程经过脚本或表达式计算组件，部分字段来源无法追溯");
        }
        if (!hasOutput) {
            builder.issue("NO_OUTPUT", "流程没有写入类输出节点，无表间数据流", null);
        }
    }

    // ==================== 字段追溯（供预览脱敏） ====================

    /**
     * 物理字段来源。
     *
     * @param masking 经过哈希、计数等不暴露原值的变换
     */
    public record PhysicalColumn(Long datasourceId, String schemaName, String tableName, String column,
                                 boolean masking) {
    }

    /**
     * 字段追溯结果。
     *
     * @param sources  物理来源；外部数据集（Kafka、文件等）不计入
     * @param complete false 表示来源无法完整追溯（脚本/表达式组件、SQL 无法解析、来源有歧义等）
     */
    public record TracedField(List<PhysicalColumn> sources, boolean complete) {
    }

    /**
     * 画布字段追溯：各节点输出（输出节点为写入）字段到物理列的映射，以及画布读取的全部物理表。
     *
     * @param fields 节点编号 → 字段名（小写不敏感时以原名为键）→ 追溯结果
     * @param tables 读取的物理表，作为无法追溯字段的候选
     */
    public record FieldTrace(Map<String, Map<String, TracedField>> fields, List<LineageNodeRefDTO> tables) {

        /**
         * 节点字段的追溯结果；节点或字段不存在时视为无法追溯。
         */
        public TracedField of(String nodeNo, String field) {
            Map<String, TracedField> node = fields.get(nodeNo);
            TracedField traced = node == null ? null : node.get(field);
            return traced == null ? new TracedField(List.of(), false) : traced;
        }
    }

    /**
     * 追溯画布每个节点的字段来源，复用血缘的输入解析与组件推导规则。不连接源库，元数据取自已采集结果。
     */
    public FieldTrace traceFields(List<EtlNodeDef> nodes, List<EtlEdgeDef> edges) {
        if (nodes.isEmpty()) {
            return new FieldTrace(Map.of(), List.of());
        }
        EtlDag dag = EtlDag.buildLenient(nodes, edges);
        // 降级信息只用于血缘上报；这里只关心来源本身。
        LineageReportBuilder scratch = new LineageReportBuilder("ETL_PIPELINE", 0L, "preview", 0L, 0);
        Map<String, Input> inputs = inputs(dag, scratch);
        Map<String, List<EtlFieldMeta>> sourceFields = new LinkedHashMap<>();
        inputs.forEach((no, input) -> {
            if (input.fields() != null) {
                sourceFields.put(no, input.fields());
            }
        });
        EtlFieldOriginResult trace = schemas.traceOrigins(dag, NO_CONNECTION, null, sourceFields);
        List<LineageNodeRefDTO> tables = new ArrayList<>();
        inputs.values().forEach(input -> input.nodes().stream()
                .filter(ref -> ref.getDatasourceId() != null && ref.getTableName() != null)
                .filter(ref -> !tables.contains(ref)).forEach(tables::add));
        Map<String, Map<String, TracedField>> fields = new LinkedHashMap<>();
        trace.nodes().forEach((no, node) -> {
            List<EtlFieldOriginResult.TracedField> written = node.outputs().isEmpty() ? node.inputs() : node.outputs();
            Map<String, TracedField> byName = new LinkedHashMap<>();
            for (EtlFieldOriginResult.TracedField field : written) {
                byName.put(field.name(), physical(field, inputs));
            }
            // 无上游的输入节点：字段直接来自其物理来源。
            Input own = inputs.get(no);
            if (own != null && node.outputs().isEmpty() && node.inputs().isEmpty() && own.fields() != null) {
                own.fields().forEach(field -> byName.put(field.getName(),
                        new TracedField(columns(own.sources().apply(field.getName())), true)));
            }
            fields.put(no, byName);
        });
        return new FieldTrace(fields, tables);
    }

    /**
     * 把组件追溯的输入来源映射到物理列。
     */
    private static TracedField physical(EtlFieldOriginResult.TracedField field, Map<String, Input> inputs) {
        boolean complete = field.complete();
        List<PhysicalColumn> result = new ArrayList<>();
        for (EtlFieldOrigin origin : field.origins()) {
            Input input = inputs.get(origin.nodeNo());
            if (input == null) {
                // 来源输入节点无法解析（如 SQL 不在支持矩阵）：不能当作确定来源。
                complete = false;
                continue;
            }
            for (Source source : input.sources().apply(origin.field())) {
                complete = complete && !source.ambiguous() && !"*".equals(source.column());
                LineageNodeRefDTO ref = source.node();
                if (ref.getDatasourceId() != null && ref.getTableName() != null) {
                    result.add(new PhysicalColumn(ref.getDatasourceId(), ref.getSchemaName(), ref.getTableName(),
                            source.column(), source.masking() || origin.masking()));
                }
            }
        }
        return new TracedField(result, complete);
    }

    /**
     * 输入节点字段的物理列。
     */
    private static List<PhysicalColumn> columns(List<Source> sources) {
        return sources.stream().filter(source -> source.node().getDatasourceId() != null
                        && source.node().getTableName() != null)
                .map(source -> new PhysicalColumn(source.node().getDatasourceId(), source.node().getSchemaName(),
                        source.node().getTableName(), source.column(), source.masking())).toList();
    }

    // ==================== 输入 ====================

    /**
     * 解析全部无上游的输入节点。数据库表输入按数据源批量预取已采集字段。
     */
    private Map<String, Input> inputs(EtlDag dag, LineageReportBuilder builder) {
        Map<String, Input> inputs = new LinkedHashMap<>();
        Map<Long, Map<String, LineageTable>> tablesBySource = new LinkedHashMap<>();
        Map<Long, LineageDatasourceResolver.Datasource> cache = new HashMap<>();
        for (EtlNodeDef node : dag.getNodes()) {
            if (!dag.getDataUpstreamList(node.getNo()).isEmpty() || output(node) != null
                    || GENERATED.contains(StrUtil.nullToEmpty(node.getToolCode()))) {
                continue;
            }
            JSONObject config = config(node);
            if ("read-db".equals(node.getToolCode())) {
                Long databaseId = config.getLong("databaseId");
                var datasource = cache.computeIfAbsent(databaseId == null ? -1L : databaseId,
                        ignored -> datasources.resolve(databaseId));
                if (datasource == null) {
                    builder.degrade("DATASOURCE_MISSING", "输入节点【" + node.getName() + "】未绑定可用数据源");
                    continue;
                }
                if (config.getInt("ifSql", 0) == 1) {
                    Input input = sqlInput(node, config, datasource, builder);
                    if (input != null) {
                        inputs.put(node.getNo(), input);
                    }
                    continue;
                }
                String tableName = config.getStr("tableName");
                if (StrUtil.isBlank(tableName)) {
                    continue;
                }
                LineageTable table = new LineageTable(StrUtil.blankToDefault(config.getStr("schemaName"),
                        datasource.defaultSchema()), tableName);
                tablesBySource.computeIfAbsent(datasource.id(), ignored -> new LinkedHashMap<>())
                        .put(node.getNo(), table);
                continue;
            }
            inputs.put(node.getNo(), externalInput(node, config));
        }
        for (Map.Entry<Long, Map<String, LineageTable>> entry : tablesBySource.entrySet()) {
            Long datasourceId = entry.getKey();
            var prefetched = catalogs.prefetch(datasourceId, entry.getValue().values());
            if (prefetched.failed()) {
                builder.degrade("METADATA_UNAVAILABLE", "读取已采集元数据失败，字段级血缘可能不完整");
            }
            for (Map.Entry<String, LineageTable> tableEntry : entry.getValue().entrySet()) {
                EtlNodeDef node = dag.getNode(tableEntry.getKey());
                LineageTable table = tableEntry.getValue();
                LineageNodeRefDTO ref = LineageReportBuilder.table(datasourceId, table);
                List<String> columns = prefetched.catalog().columns(table).orElse(null);
                if (columns == null) {
                    builder.degrade("METADATA_MISSING", "表 " + table.displayName() + " 未采集元数据，只保留表级血缘");
                    columns = List.of();
                }
                List<String> selected = selected(config(node), columns);
                List<EtlFieldMeta> fields = new ArrayList<>();
                Map<String, List<Source>> sources = new HashMap<>();
                for (String column : selected) {
                    fields.add(new EtlFieldMeta(column, null));
                    sources.put(key(column), List.of(new Source(ref, column, LineageTransform.IDENTITY, false, false)));
                }
                inputs.put(tableEntry.getKey(), Input.mapped(List.of(ref), fields, sources));
            }
        }
        return inputs;
    }

    /**
     * SQL 输入：两遍分析，第二遍带已采集表结构以展开 *。
     */
    private Input sqlInput(EtlNodeDef node, JSONObject config, LineageDatasourceResolver.Datasource datasource,
                           LineageReportBuilder builder) {
        String sql = config.getStr("sql");
        if (StrUtil.isBlank(sql) || datasource.product() == null || !SqlLineageAnalyzer.supports(datasource.product())) {
            builder.degrade("SQL_INPUT_UNSUPPORTED", "输入节点【" + node.getName() + "】的 SQL 无法解析血缘");
            return null;
        }
        SqlLineageRequest request = SqlLineageRequest.of(datasource.product(), datasource.defaultSchema(), sql);
        QueryLineageResult result = SqlLineageAnalyzer.analyzeQuery(request);
        if (!result.readTables().isEmpty()) {
            var prefetched = catalogs.prefetch(datasource.id(), result.readTables());
            result = SqlLineageAnalyzer.analyzeQuery(request.withCatalog(prefetched.catalog()));
        }
        if (result.status() == SqlLineageStatus.FAILED) {
            builder.degrade("SQL_INPUT_UNSUPPORTED", "输入节点【" + node.getName() + "】的 SQL 无法解析血缘");
            return null;
        }
        if (result.status() == SqlLineageStatus.PARTIAL) {
            builder.status(SqlLineageStatus.PARTIAL);
        }
        result.issues().forEach(issue -> builder.issue(issue.code(), issue.message(), null));
        List<LineageNodeRefDTO> nodes = result.readTables().stream()
                .map(table -> LineageReportBuilder.table(datasource.id(), table)).toList();
        List<EtlFieldMeta> fields = new ArrayList<>();
        Map<String, List<Source>> sources = new HashMap<>();
        for (QueryLineageResult.Column column : result.columns()) {
            if (column.name() == null || column.wildcard()) {
                continue;
            }
            fields.add(new EtlFieldMeta(column.name(), null));
            List<Source> mapped = new ArrayList<>();
            for (LineageColumnSource source : column.sources()) {
                mapped.add(new Source(LineageReportBuilder.table(datasource.id(), source.table()), source.column(),
                        source.transform(), source.ambiguous(), source.masking()));
            }
            sources.put(key(column.name()), mapped);
        }
        return Input.mapped(nodes, fields, sources);
    }

    /**
     * 外部数据集输入：字段名由组件规则推导，每个字段以外部数据集的同名字段为来源。
     */
    private Input externalInput(EtlNodeDef node, JSONObject config) {
        LineageNodeRefDTO ref = external(node, config);
        return new Input(List.of(ref), null,
                name -> List.of(new Source(ref, name, LineageTransform.IDENTITY, false, false)));
    }

    // ==================== 输出 ====================

    /**
     * 输出节点的目标数据集；非输出节点返回 null。
     */
    private LineageNodeRefDTO output(EtlNodeDef node) {
        String code = StrUtil.nullToEmpty(node.getToolCode());
        boolean isOutput = OUTPUT_DB.contains(code) || code.startsWith("write-")
                || Integer.valueOf(EtlNodeDef.TOOL_TYPE_OUTPUT).equals(node.getToolType());
        if (!isOutput) {
            return null;
        }
        JSONObject config = config(node);
        Long databaseId = config.getLong("databaseId");
        String tableName = config.getStr("tableName");
        if (databaseId != null && StrUtil.isNotBlank(tableName)) {
            var datasource = datasources.resolve(databaseId);
            String schema = StrUtil.blankToDefault(config.getStr("schemaName"),
                    datasource == null ? null : datasource.defaultSchema());
            return LineageReportBuilder.table(databaseId, new LineageTable(schema, tableName));
        }
        return external(node, config);
    }

    /**
     * 输出节点的字段映射（来源字段 → 目标字段）；未配置时为空，表示按同名写入。
     */
    private static Map<String, String> fieldMappings(EtlNodeDef node) {
        JSONArray mappings = config(node).getJSONArray("fieldMappings");
        Map<String, String> result = new HashMap<>();
        if (mappings == null) {
            return result;
        }
        for (Object item : mappings) {
            if (item instanceof JSONObject mapping && mapping.getBool("enabled", true)
                    && StrUtil.isNotBlank(mapping.getStr("source")) && StrUtil.isNotBlank(mapping.getStr("target"))) {
                result.put(mapping.getStr("source"), mapping.getStr("target"));
            }
        }
        return result;
    }

    // ==================== 工具 ====================

    /**
     * 外部数据集节点：只取不含凭据的配置键；URL 去掉用户信息与查询串。
     */
    private static LineageNodeRefDTO external(EtlNodeDef node, JSONObject config) {
        String code = StrUtil.nullToEmpty(node.getToolCode());
        String kind = code.contains("kafka") || code.contains("rabbit") || code.contains("rocket")
                ? LineageExternalKindEnum.KAFKA.name()
                : code.contains("hdfs") || code.contains("hive") ? LineageExternalKindEnum.HDFS.name()
                : code.contains("mongo") ? LineageExternalKindEnum.MONGODB.name()
                : code.contains("api") || code.contains("webservice") || code.contains("http")
                ? LineageExternalKindEnum.API.name()
                : code.contains("csv") || code.contains("excel") || code.contains("json") || code.contains("text")
                || code.contains("ftp") || code.contains("file") ? LineageExternalKindEnum.FILE.name()
                : LineageExternalKindEnum.OTHER.name();
        String identity = null;
        if (LineageExternalKindEnum.API.name().equals(kind)) {
            JSONObject api = config.getJSONObject("apiConfig");
            identity = sanitizeUrl(api == null ? config.getStr("url") : api.getStr("url"));
        }
        if (identity == null) {
            for (String candidate : EXTERNAL_KEYS) {
                Object value = config.get(candidate);
                if (value != null && StrUtil.isNotBlank(String.valueOf(value))) {
                    identity = candidate + "=" + value;
                    break;
                }
            }
        }
        String externalKey = code + ":" + (identity == null ? StrUtil.blankToDefault(node.getName(), node.getNo())
                : identity);
        LineageNodeRefDTO ref = LineageNodeRefDTO.external(kind, StrUtil.maxLength(externalKey, 500));
        ref.setDisplayName(StrUtil.maxLength(StrUtil.blankToDefault(node.getName(), externalKey), 250));
        return ref;
    }

    /**
     * 去掉 URL 的用户信息、查询串与片段；无法解析时返回 null。
     */
    static String sanitizeUrl(String url) {
        if (StrUtil.isBlank(url)) {
            return null;
        }
        try {
            URI uri = URI.create(url.trim());
            if (uri.getHost() == null) {
                return null;
            }
            return (uri.getScheme() == null ? "" : uri.getScheme() + "://") + uri.getHost()
                    + (uri.getPort() > 0 ? ":" + uri.getPort() : "") + StrUtil.nullToEmpty(uri.getPath());
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    /**
     * 回溯输出节点的全部根输入节点。
     */
    private static Set<String> roots(EtlDag dag, String no) {
        Set<String> roots = new LinkedHashSet<>();
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(dag.getDataUpstreamList(no));
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (!seen.add(current)) {
                continue;
            }
            List<String> upstream = dag.getDataUpstreamList(current);
            if (upstream.isEmpty()) {
                roots.add(current);
            } else {
                queue.addAll(upstream);
            }
        }
        return roots;
    }

    /**
     * 按 selectedColumns 过滤输入字段；未配置时为全部字段。
     */
    private static List<String> selected(JSONObject config, List<String> columns) {
        JSONArray selected = config.getJSONArray("selectedColumns");
        if (selected == null || selected.isEmpty()) {
            return columns;
        }
        Set<String> wanted = new HashSet<>();
        selected.forEach(item -> wanted.add(key(String.valueOf(item))));
        return columns.stream().filter(column -> wanted.contains(key(column))).toList();
    }

    /**
     * 节点配置。
     */
    private static JSONObject config(EtlNodeDef node) {
        try {
            return JSONUtil.parseObj(StrUtil.blankToDefault(node.getNodeJson(), "{}"));
        } catch (RuntimeException malformed) {
            return new JSONObject();
        }
    }

    /**
     * 大小写不敏感的字段键。
     */
    private static String key(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }
}
