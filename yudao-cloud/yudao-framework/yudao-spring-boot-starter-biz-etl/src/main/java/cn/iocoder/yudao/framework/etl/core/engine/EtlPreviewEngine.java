package cn.iocoder.yudao.framework.etl.core.engine;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.etl.core.connection.EtlRowPolicyPort;
import cn.iocoder.yudao.framework.etl.core.connection.EtlReadPolicyPort;
import cn.iocoder.yudao.framework.etl.core.connection.EtlConnectionResolver;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.etl.core.executor.EtlExecutorRegistry;
import cn.iocoder.yudao.framework.etl.core.model.*;
import cn.iocoder.yudao.framework.etl.core.schema.EtlFieldMeta;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunResult;
import cn.iocoder.yudao.framework.etl.core.validation.EtlValidationResult;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;

/** 有界来源采样后执行转换；数据库读取必须由调用方提供授权和脱敏策略。 */
public final class EtlPreviewEngine {

    static final long MAX_BYTES = 10L * 1024 * 1024;

    static final long MAX_ROW_BYTES = 256L * 1024;

    /**
     * 预览白名单：只放行可证明无副作用的节点，前端 preview-dialog.vue 的 previewTools 必须与此保持一致。
     * 纯内存转换节点均已逐个核对源码：不含 java.io / java.net / JDBC / 脚本引擎 / Runtime.exec。
     * 明确不放行：data-encrypt、data-decrypt（依赖密钥配置，且解密会在预览中暴露明文）、所有 read-* / write-*、
     * insert-db、managed-db-output、rest-api、Kafka、脚本节点、sub-pipeline、boundary-input、boundary-output；
     * read-db 整表采样仅在调用方提供 {@link DatabaseSource} 时另行放行。
     */
    private static final Set<String> ALLOWED = Set.of(
            // 生成来源
            "generate-records", "random-records",
            // 受控治理节点（经 EtlRowPolicyPort 执行）与子流程桥接
            "quality-check", "boundary-bridge",
            // 纯内存行级转换
            "add-constant", "value-map", "flatten-row", "sort-records",
            "column-map", "data-filter", "data-clean", "data-desensitize", "data-condition",
            "field-derive", "field-split", "calculator", "id-generator", "number-range",
            "split-to-rows", "row-column",
            // 纯内存多输入节点：预览中各来源最多 limit+1 行，结果放大超出 maxRows 时显式失败
            "data-merge", "sort-merge", "multi-join", "cartesian-product");

    private static final String UNSUPPORTED_MESSAGE = "预览仅支持生成来源、授权的 PostgreSQL 整表采样、受控字典与质量节点，"
            + "以及纯内存转换（常量、值映射、扁平化、排序、字段映射/拆分/派生、过滤、清洗、脱敏、条件分流、计算、ID 生成、"
            + "数值范围、列转多行、行列转换、合并、排序合并、多 JOIN、笛卡尔积）；不支持 SQL、脚本、加解密或读写外部系统的节点";

    /**
     * portCounts 统计目标节点每个输出端口的全部样本行数（如质量节点 pass/warn/reject、条件分流分支），
     * rowPorts 与 rows 按下标一一对应，只覆盖展示行。
     */
    public record Result(boolean success, List<EtlFieldMeta> columns, List<Map<String, Object>> rows,
                         long inputRows, long outputRows, List<EtlValidationResult.Issue> issues, boolean truncated,
                         Map<String, Long> portCounts, List<String> rowPorts, String previewId,
                         List<List<RowViolation>> rowViolations, Budget budget, long targetInputRows,
                         List<InputSample> inputSamples, String comparisonMode, List<MaskedField> maskedFields) {

        /** 空集合兜底，保证序列化形状稳定。 */
        public Result {
            maskedFields = maskedFields == null ? List.of() : List.copyOf(maskedFields);
        }

        /** 不带脱敏标注的完整构造器，供组件内部与既有消费方使用。 */
        public Result(boolean success, List<EtlFieldMeta> columns, List<Map<String, Object>> rows,
                long inputRows, long outputRows, List<EtlValidationResult.Issue> issues, boolean truncated,
                Map<String, Long> portCounts, List<String> rowPorts, String previewId,
                List<List<RowViolation>> rowViolations, Budget budget, long targetInputRows,
                List<InputSample> inputSamples, String comparisonMode) {
            this(success, columns, rows, inputRows, outputRows, issues, truncated, portCounts, rowPorts, previewId,
                    rowViolations, budget, targetInputRows, inputSamples, comparisonMode, List.of());
        }

        /**
         * 用调用方处理后的行替换展示数据（例如按数据安全策略脱敏），其余统计与诊断保持不变。
         *
         * @param maskedRows    替换后的目标节点行，条数必须与原结果一致
         * @param maskedSamples 替换后的输入样本，个数与顺序必须与原结果一致
         * @param masked        被处理的字段标注
         */
        public Result withMaskedRows(List<Map<String, Object>> maskedRows, List<InputSample> maskedSamples,
                                     List<MaskedField> masked) {
            if (maskedRows.size() != rows.size() || maskedSamples.size() != inputSamples.size()) {
                throw new IllegalArgumentException("替换后的行数与原预览结果不一致");
            }
            return new Result(success, columns, maskedRows, inputRows, outputRows, issues, truncated, portCounts,
                    rowPorts, previewId, rowViolations, budget, targetInputRows, maskedSamples, comparisonMode, masked);
        }

        /** 保留原有完整响应构造器，供组件消费方使用。 */
        public Result(boolean success, List<EtlFieldMeta> columns, List<Map<String, Object>> rows,
                long inputRows, long outputRows, List<EtlValidationResult.Issue> issues, boolean truncated,
                Map<String, Long> portCounts, List<String> rowPorts, String previewId,
                List<List<RowViolation>> rowViolations, Budget budget) {
            this(success, columns, rows, inputRows, outputRows, issues, truncated, portCounts, rowPorts,
                    previewId, rowViolations, budget, 0, List.of(), "INDEPENDENT_SAMPLES");
        }

        /**
         * 使用给定参数初始化 {@code Result}。
         */
        public Result(boolean success, List<EtlFieldMeta> columns, List<Map<String, Object>> rows,
                long inputRows, long outputRows, List<EtlValidationResult.Issue> issues, boolean truncated,
                Map<String, Long> portCounts, List<String> rowPorts) {
            this(success, columns, rows, inputRows, outputRows, issues, truncated, portCounts, rowPorts,
                    UUID.randomUUID().toString(), List.of(), null);
        }

        /**
         * 使用给定参数初始化 {@code Result}。
         */
        public Result(boolean success, List<EtlFieldMeta> columns, List<Map<String, Object>> rows,
                long inputRows, long outputRows, List<EtlValidationResult.Issue> issues, boolean truncated) {
            this(success, columns, rows, inputRows, outputRows, issues, truncated, Map.of(), List.of());
        }
    }

    /**
     * 传入 DATA 边的独立采样流，已完成源授权和脱敏。
     * rowCount 统计本次有界预览执行观察到的行数，绝非源数据的总行数。
     * 输入与输出不具有位置对应身份：过滤、排序、展开和连接会改变其关系。
     */
    public record InputSample(String sourceNodeNo, String sourceNodeName, String sourceOutputPort,
                              String inputPort, List<EtlFieldMeta> columns, List<Map<String, Object>> rows,
                              long rowCount, boolean truncated) {

    }

    /**
     * 被调用方处理（如脱敏）的字段，只含字段名与原因，不含原值。
     *
     * @param sourceNodeNo 字段所在的输入样本节点；为空表示目标节点的输出
     * @param field        字段名
     * @param reason       原因编码，由调用方定义
     * @param categoryNames 命中的分类名称
     */
    public record MaskedField(String sourceNodeNo, String field, String reason, List<String> categoryNames) {

        /** 复制为不可变集合。 */
        public MaskedField {
            categoryNames = categoryNames == null ? List.of() : List.copyOf(categoryNames);
        }
    }

    /** 只返回规则元信息，不返回可能含原始字段值的规则 message。 */
    public record RowViolation(String ruleCode, String field, String severity, String code) {

    }

    /** 估算资源计量，不代表 JVM 的实际堆占用；累计传输行包含每层转换。 */
    public record Budget(long maxRows, long maxBytes, long maxRowBytes, long usedRows, long usedBytes,
                         long peakBufferedBytes, long responseBytes, int maxResponseRows, long timeoutSeconds) {

    }

    /** scope 是可信且经过认证的配额所有者，绝不能来自客户端提交的节点或配置值。 */
    public record DatabaseSource(DbSwitchRuntime runtime, EtlConnectionResolver connections,
                                 EtlReadPolicyPort policy, String scope, UnaryOperator<Runnable> taskWrapper) {

        /**
         * 校验构造参数并初始化 {@code DatabaseSource}。
         */
        public DatabaseSource {
            Objects.requireNonNull(runtime);
            Objects.requireNonNull(connections);
            Objects.requireNonNull(policy);
            Objects.requireNonNull(taskWrapper);
            if (scope == null || scope.isBlank()) {
                throw new IllegalArgumentException("Preview quota scope is required");
            }
        }
    }

    /**
     * 在当前数量和资源限制内运行预览并返回样本结果。
     */
    public Result preview(List<EtlNodeDef> allNodes, List<EtlEdgeDef> allEdges, String target, int limit,
            Map<String, String> params, EtlRowPolicyPort policies) {
        return preview(allNodes, allEdges, target, limit, params, policies, null);
    }

    /**
     * 在当前数量和资源限制内运行预览并返回样本结果。
     */
    public Result preview(List<EtlNodeDef> allNodes, List<EtlEdgeDef> allEdges, String target, int limit,
            Map<String, String> params, EtlRowPolicyPort policies, DatabaseSource database) {
        return preview(allNodes, allEdges, target, limit, params, policies, database, null);
    }

    /**
     * 在当前数量和资源限制内运行预览并返回样本结果。
     */
    public Result preview(List<EtlNodeDef> allNodes, List<EtlEdgeDef> allEdges, String target, int limit,
            Map<String, String> params, EtlRowPolicyPort policies, DatabaseSource database,
            cn.iocoder.yudao.framework.etl.core.runtime.EtlExecutionControl control) {
        if (control != null && control.stopRequested()) {
            return error("预览已请求取消");
        }
        if (limit < 1 || limit > 100 || allNodes == null || allNodes.size() > 32) {
            return error("预览最多展示 100 行且流程不超过 32 个节点");
        }
        Map<String, EtlNodeDef> byId = new LinkedHashMap<>();
        for (EtlNodeDef n : allNodes) {
            if (byId.put(n.getNo(), n) != null) {
                return error("预览存在重复节点编号");
            }
        }
        if (!byId.containsKey(target)) {
            return error("请选择要预览的节点");
        }
        List<EtlEdgeDef> sourceEdges = allEdges == null ? List.of() : allEdges;
        Set<String> selected = new LinkedHashSet<>();
        Deque<String> pending = new ArrayDeque<>();
        pending.add(target);
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            if (!selected.add(current)) {
                continue;
            }
            for (EtlEdgeDef e : sourceEdges) {
                if (Objects.equals(e.getTargetNo(), current)) {
                    pending.add(e.getSourceNo());
                }
            }
        }
        List<EtlNodeDef> nodes = new ArrayList<>();
        AtomicBoolean sourceTruncated = new AtomicBoolean();
        for (String id : selected) {
            EtlNodeDef n = byId.get(id);
            if (n == null || (!ALLOWED.contains(n.getToolCode()) && !(database != null && "read-db".equals(
                    n.getToolCode())))) {
                return error(UNSUPPORTED_MESSAGE);
            }
            EtlNodeDef copy = new EtlNodeDef();
            copy.setNo(n.getNo());
            copy.setName(n.getName());
            copy.setToolCode(n.getToolCode());
            copy.setToolType(n.getToolType());
            copy.setOverTimeMinutes(1);
            String resolved = new cn.iocoder.yudao.framework.etl.core.variable.EtlVariableContext(params).resolveJson(
                    n.getNodeJson() == null ? "{}" : n.getNodeJson());
            var config = JSONUtil.parseObj(resolved);
            if ("read-db".equals(n.getToolCode()) && config.getInt("ifSql", 0) != 0) {
                return error("数据库预览仅支持整表采样，不支持自定义 SQL");
            }
            if (Set.of("generate-records", "random-records").contains(n.getToolCode())) {
                int count = config.getInt("count", 1);
                if (count < 0 || count > 1_000_000) {
                    return error("记录数量无效");
                }
                if (count > limit) {
                    sourceTruncated.set(true);
                }
                config.set("count", Math.min(count, limit + 1));
            }
            copy.setNodeJson(config.toString());
            nodes.add(copy);
        }
        List<EtlEdgeDef> edges = sourceEdges.stream()
                .filter(e -> selected.contains(e.getSourceNo()) && selected.contains(e.getTargetNo())).toList();
        EtlDag dag;
        try {
            dag = EtlDag.build(nodes, edges);
        } catch (RuntimeException ex) {
            return error(ex.getMessage());
        }
        AtomicLong input = new AtomicLong(), output = new AtomicLong();
        AtomicLong responseBytes = new AtomicLong();
        List<Map<String, Object>> rows = Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean captureClosed = new AtomicBoolean();
        // 使用已验证 DAG 的端口顺序，包括旧版边顺序回退。单个总输入
        // 上限在各边之间公平分配，避免快速数据源占满所有输入位置。
        List<InputCapture> inputs = new ArrayList<>();
        List<String> upstreams = dag.getDataUpstreamList(target);
        for (int i = 0; i < upstreams.size(); i++) {
            String upstream = upstreams.get(i);
            EtlEdgeDef edge = edges.stream().filter(e -> upstream.equals(e.getSourceNo())
                    && target.equals(e.getTargetNo()) && !e.isControl()).findFirst().orElseThrow();
            int quota = limit / upstreams.size() + (i < limit % upstreams.size() ? 1 : 0);
            inputs.add(new InputCapture(byId.get(upstream), edge, quota));
        }
        Map<String, Long> portCounts = new LinkedHashMap<>();
        List<String> rowPorts = new ArrayList<>();
        List<List<RowViolation>> rowViolations = new ArrayList<>();
        String previewId = UUID.randomUUID().toString();
        var registry = new EtlExecutorRegistry();
        if (database != null) {
            AtomicLong sourceBytes = new AtomicLong();
            registry.register("read-db", (node, context) -> new EtlPreviewReadDbExecutor(
                    node, context, database, limit, sourceTruncated, sourceBytes, control));
        }
        var options = EtlRunOptions.builder()
                .executionControl(control)
                .params(Map.of()).maxNodes(32).timeoutSeconds(30).maxRows(10_000)
                .maxBytes(MAX_BYTES).maxRowBytes(MAX_ROW_BYTES).rowPolicyPort(policies)
                .taskWrapper(database == null ? null : database.taskWrapper())
                .rowObserver(new cn.iocoder.yudao.framework.etl.core.runtime.EtlRowObserver() {
                    private List<RowViolation> pendingViolations = List.of();

                    private long pendingBytes;

                    /**
                     * 按目标节点收集并限制当前预览中的质量问题结果。
                     */
                    @Override
                    public void onQualityResult(String id, List<EtlRowPolicyPort.Violation> violations) {
                        if (!Objects.equals(id, target)) {
                            return;
                        }
                        if (violations.size() > 100) {
                            throw new IllegalArgumentException("预览单行违规规则超过限制");
                        }
                        List<RowViolation> safe = new ArrayList<>();
                        long bytes = 0;
                        for (var violation : violations) {
                            for (String value : Arrays.asList(violation.ruleCode(), violation.field(),
                                    violation.severity(), violation.code())) {
                                if (value != null && value.length() > 256) {
                                    throw new IllegalArgumentException("预览规则元信息超过限制");
                                }
                                bytes += value == null ? 16 : 64L + value.length() * 2L;
                            }
                            safe.add(new RowViolation(violation.ruleCode(), violation.field(), violation.severity(),
                                    violation.code()));
                        }
                        synchronized (rows) {
                            if (captureClosed.get()) {
                                return;
                            }
                            pendingViolations = List.copyOf(safe);
                            pendingBytes = bytes;
                        }
                    }

                    /**
                     * 接收当前数据行并更新处理进度或输出。
                     */
                    @Override
                    public void onRow(String id, String port, Map<String, Object> row) {
                        String outputPort = port == null ? EtlEdgeDef.PORT_MAIN : port;
                        synchronized (rows) {
                            if (captureClosed.get()) {
                                return;
                            }
                            if (Objects.equals(byId.get(id).getToolType(), EtlNodeDef.TOOL_TYPE_INPUT)) {
                                input.incrementAndGet();
                            }
                            for (var sample : inputs) {
                                if (sample.matches(id, outputPort)) {
                                    sample.count++;
                                    if (sample.rows.size() < sample.limit) {
                                        var wire = cn.iocoder.yudao.framework.etl.core.executor.support.EtlJsonValues.wireRow(
                                                row);
                                        accountResponseRow(wire, 0, responseBytes);
                                        sample.rows.add(wire);
                                    }
                                }
                            }
                            if (Objects.equals(id, target)) {
                                output.incrementAndGet();
                                portCounts.merge(outputPort, 1L, Long::sum);
                                if (rows.size() < limit) {
                                    var wire = cn.iocoder.yudao.framework.etl.core.executor.support.EtlJsonValues.wireRow(
                                            row);
                                    accountResponseRow(wire, pendingBytes, responseBytes);
                                    rows.add(wire);
                                    rowPorts.add(outputPort);
                                    rowViolations.add(pendingViolations);
                                }
                            }
                        }
                    }
                }).build();
        EtlRunResult result;
        try (var engine = database == null ? new EtlPipelineEngine(registry) : new EtlPipelineEngine(registry,
                database.runtime())) {
            result = engine.execute(dag, options);
        }
        synchronized (rows) {
            // 延迟响应的协作取消或未经确认的工作进程终止，绝不能保留新样本。
            captureClosed.set(true);
            Budget budget = new Budget(options.getMaxRows(), options.getMaxBytes(), options.getMaxRowBytes(),
                    result.getTransferredRows(), result.getTransferredBytes(), result.getPeakBufferedBytes(),
                    responseBytes.get(), 2 * limit, options.getTimeoutSeconds());
            if (!result.isSuccess() || control != null && control.stopRequested()) {
                rows.clear();
                inputs.forEach(sample -> sample.rows.clear());
                return failed(target, input.get(), output.get(), previewId, budget,
                        database == null ? result.getFirstError() : "预览失败：源权限、配置、读取限额或执行超时，请检查所选节点及其上游");
            }
            List<InputSample> samples = inputs.stream().map(InputCapture::snapshot).toList();
            long targetInput = inputs.stream().mapToLong(sample -> sample.count).sum();
            Result response = new Result(true, columns(rows), List.copyOf(rows), input.get(), output.get(), List.of(),
                    sourceTruncated.get() || output.get() > limit,
                    Collections.unmodifiableMap(new LinkedHashMap<>(portCounts)), List.copyOf(rowPorts), previewId,
                    List.copyOf(rowViolations), budget, targetInput, samples, "INDEPENDENT_SAMPLES");
            // 样本、字段名、源标签、端口元数据和预算本身共用响应
            // 大小上限。最终传输检查弥补只估算行数据时对超宽行或元数据的遗漏。
            if (cn.iocoder.yudao.framework.etl.core.executor.support.EtlJsonValues.write(response)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES) {
                rows.clear();
                inputs.forEach(sample -> sample.rows.clear());
                return failed(target, input.get(), output.get(), previewId, budget,
                        "Preview response byte budget exceeded");
            }
            return response;
        }
    }

    /**
     * 按序列化后的 UTF-8 大小累计预览响应字节预算。
     */
    private static void accountResponseRow(Map<String, Object> wire, long extraBytes, AtomicLong bytes) {
        long serialized = cn.iocoder.yudao.framework.etl.core.executor.support.EtlJsonValues.write(wire)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        long size = Math.max(serialized,
                cn.iocoder.yudao.framework.etl.core.runtime.EtlRunContext.estimateRowBytes(wire)) + extraBytes;
        if (size > MAX_ROW_BYTES || bytes.addAndGet(size) > MAX_BYTES) {
            throw new IllegalArgumentException("Preview response byte budget exceeded");
        }
    }

    /**
     * 读取或组装当前操作所使用的列定义。
     */
    private static List<EtlFieldMeta> columns(List<Map<String, Object>> rows) {
        Set<String> keys = new LinkedHashSet<>();
        rows.forEach(row -> keys.addAll(row.keySet()));
        return keys.stream().map(key -> new EtlFieldMeta(key, "Object")).toList();
    }

    /**
     * 记录当前批次或任务的失败状态。
     */
    private static Result failed(String target, long input, long output, String previewId, Budget budget,
            String message) {
        return new Result(false, List.of(), List.of(), input, output,
                List.of(new EtlValidationResult.Issue(target, "preview", "ERROR", "PREVIEW_FAILED", message)), false,
                Map.of(), List.of(), previewId, List.of(), budget, 0, List.of(), "INDEPENDENT_SAMPLES");
    }

    private static final class InputCapture {

        private final EtlNodeDef source;

        private final EtlEdgeDef edge;

        private final int limit;

        private final List<Map<String, Object>> rows = new ArrayList<>();

        private long count;

        /**
         * 初始化 {@code InputCapture}。
         */
        private InputCapture(EtlNodeDef source, EtlEdgeDef edge, int limit) {
            this.source = source;
            this.edge = edge;
            this.limit = limit;
        }

        /**
         * 返回当前匹配结果。
         */
        private boolean matches(String id, String port) {
            return source.getNo().equals(id) && edge.resolveOutputPort().equals(port);
        }

        /**
         * 返回快照。
         */
        private InputSample snapshot() {
            String name = source.getName();
            if (name != null && name.length() > 256) {
                name = name.substring(0, 256);
            }
            String inputPort = edge.getInputPort();
            if (inputPort == null || inputPort.isBlank()) {
                inputPort = "in";
            }
            return new InputSample(source.getNo(), name, edge.resolveOutputPort(), inputPort,
                    columns(rows), List.copyOf(rows), count, count > rows.size());
        }
    }

    /**
     * 返回错误。
     */
    private Result error(String message) {
        return new Result(false, List.of(), List.of(), 0, 0,
                List.of(new EtlValidationResult.Issue(null, "preview", "ERROR", "PREVIEW_UNSUPPORTED", message)),
                false);
    }
}
