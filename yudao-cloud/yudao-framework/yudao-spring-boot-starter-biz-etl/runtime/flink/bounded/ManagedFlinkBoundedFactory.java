package cn.iocoder.yudao.framework.etl.flink.bounded;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.etl.core.executor.support.EtlBoundedConnectorTransport;
import cn.iocoder.yudao.framework.etl.core.executor.support.EtlNativeRowGuard;
import cn.iocoder.yudao.framework.etl.core.script.EtlScriptAuthenticatedHttp;
import org.apache.flink.configuration.*;
import org.apache.flink.streaming.api.functions.source.RichSourceFunction;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;
import org.apache.flink.table.api.ValidationException;
import org.apache.flink.table.connector.ChangelogMode;
import org.apache.flink.table.connector.source.*;
import org.apache.flink.table.connector.sink.*;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.factories.*;
import org.apache.flink.table.types.logical.RowType;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** 单并发、真实有界的 Flink 表桥；外部认证和协议细节由执行端 SPI 各自校验。 */
public final class ManagedFlinkBoundedFactory implements DynamicTableSourceFactory, DynamicTableSinkFactory {
    private static final ConfigOption<String> KIND = ConfigOptions.key("kind").stringType().noDefaultValue();
    private static final ConfigOption<String> DIRECTION = ConfigOptions.key("direction").stringType().noDefaultValue();
    private static final ConfigOption<String> PLAN = ConfigOptions.key("plan").stringType().noDefaultValue();
    @Override public String factoryIdentifier() { return "datahub-bounded"; }
    @Override public Set<ConfigOption<?>> requiredOptions() { return Set.of(KIND, DIRECTION, PLAN); }
    @Override public Set<ConfigOption<?>> optionalOptions() { return Set.of(); }
    @Override public DynamicTableSource createDynamicTableSource(DynamicTableSourceFactory.Context context) {
        var helper = FactoryUtil.createTableFactoryHelper(this, context); helper.validate();
        String kind = helper.getOptions().get(KIND), encoded = helper.getOptions().get(PLAN);
        if (!"source".equals(helper.getOptions().get(DIRECTION))) throw new ValidationException("输出连接器不能用于读取");
        RowType shape = (RowType) context.getPhysicalRowDataType().getLogicalType(); validate(kind, encoded, shape);
        String runtime = null;
        var sourcePlan = decode(encoded);
        if (kind.equals("api") && sourcePlan.getJSONObject("headerContextRefs") != null && !sourcePlan.getJSONObject("headerContextRefs").isEmpty()) {
            runtime = EtlScriptAuthenticatedHttp.currentRuntimeContext();
            if (runtime == null || runtime.isBlank()) throw new ValidationException("接口认证变量尚未由当前离线脚本运行阶段授权");
        }
        final String authenticatedContext = runtime;
        return new ScanTableSource() {
            @Override public ChangelogMode getChangelogMode() { return ChangelogMode.insertOnly(); }
            @Override public ScanRuntimeProvider getScanRuntimeProvider(ScanContext ignored) { return SourceFunctionProvider.of(new BoundedSource(kind, encoded, shape, authenticatedContext), true); }
            @Override public DynamicTableSource copy() { return this; }
            @Override public String asSummaryString() { return "受管有界输入：" + kind; }
        };
    }
    @Override public DynamicTableSink createDynamicTableSink(DynamicTableSinkFactory.Context context) {
        var helper = FactoryUtil.createTableFactoryHelper(this, context); helper.validate();
        String kind = helper.getOptions().get(KIND), encoded = helper.getOptions().get(PLAN);
        if (!"sink".equals(helper.getOptions().get(DIRECTION))) throw new ValidationException("输入连接器不能用于输出");
        RowType shape = (RowType) context.getPhysicalRowDataType().getLogicalType(); validate(kind, encoded, shape);
        return new DynamicTableSink() {
            @Override public ChangelogMode getChangelogMode(ChangelogMode requested) { return ChangelogMode.insertOnly(); }
            @Override public SinkRuntimeProvider getSinkRuntimeProvider(Context ignored) { return SinkFunctionProvider.of(new BoundedSink(kind, encoded, shape), 1); }
            @Override public DynamicTableSink copy() { return this; }
            @Override public String asSummaryString() { return "受管有界输出：" + kind; }
        };
    }
    private static void validate(String kind, String encoded, RowType shape) {
        if (kind == null || !kind.matches("[a-z][a-z0-9-]{0,31}") || encoded == null || encoded.length() > 1_048_576 || shape.getFieldCount() < 1 || shape.getFieldCount() > 1024)
            throw new ValidationException("受管有界连接器计划或字段合同无效");
        var plan = decode(encoded);
        if (plan.containsKey("runtimeAuthenticatedContext")) throw new ValidationException("原始连接器计划不能指定内部认证运行上下文");
        var expected = plan.getJSONObject("resultTypes");
        if (expected == null || expected.size() != shape.getFieldCount() || !expected.keySet().equals(new LinkedHashSet<>(shape.getFieldNames())))
            throw new ValidationException("执行计划与物理表字段不一致");
        for (var field : shape.getFields()) {
            String sql = expected.getJSONObject(field.getName()).getStr("sqlType", "").toUpperCase(Locale.ROOT).replace(" ", "");
            var type = field.getType();
            boolean compatible = switch (type.getTypeRoot()) {
                case CHAR, VARCHAR -> sql.equals("STRING");
                case BOOLEAN -> sql.equals("BOOLEAN");
                case TINYINT -> sql.equals("TINYINT");
                case SMALLINT -> sql.equals("SMALLINT");
                case INTEGER -> sql.equals("INT") || sql.equals("INTEGER");
                case BIGINT -> sql.equals("BIGINT");
                case FLOAT -> sql.equals("FLOAT");
                case DOUBLE -> sql.equals("DOUBLE");
                case DATE -> sql.equals("DATE");
                case BINARY, VARBINARY -> sql.equals("BINARY") || sql.equals("BYTES");
                case DECIMAL -> sql.equals("DECIMAL(" + ((org.apache.flink.table.types.logical.DecimalType) type).getPrecision() + "," + ((org.apache.flink.table.types.logical.DecimalType) type).getScale() + ")");
                case TIMESTAMP_WITHOUT_TIME_ZONE -> sql.equals("TIMESTAMP") ? ((org.apache.flink.table.types.logical.TimestampType) type).getPrecision() == 6 : sql.equals("TIMESTAMP(" + ((org.apache.flink.table.types.logical.TimestampType) type).getPrecision() + ")");
                default -> false;
            };
            if (!compatible) throw new ValidationException("执行计划类型与 Flink 表物理类型不一致");
        }
        transport(kind);
    }
    private static JSONObject decode(String encoded) {
        try { return JSONUtil.parseObj(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8)); }
        catch (RuntimeException failure) { throw new ValidationException("受管有界连接器计划不是有效编码"); }
    }
    private static EtlBoundedConnectorTransport transport(String kind) {
        var found = new ArrayList<EtlBoundedConnectorTransport>();
        for (var candidate : ServiceLoader.load(EtlBoundedConnectorTransport.class, Thread.currentThread().getContextClassLoader())) if (candidate.kinds().contains(kind)) found.add(candidate);
        if (found.size() != 1) throw new ValidationException("工作节点连接器未安装或存在重复实现：" + kind);
        return found.get(0);
    }
    private static final class BoundedSource extends RichSourceFunction<RowData> {
        private final String kind, encoded, authenticatedContext; private final RowType shape;
        private volatile boolean running = true; private transient volatile Thread thread;
        BoundedSource(String kind, String encoded, RowType shape, String authenticatedContext) { this.kind = kind; this.encoded = encoded; this.shape = shape; this.authenticatedContext = authenticatedContext; }
        @Override public void run(SourceContext<RowData> context) throws Exception {
            thread = Thread.currentThread(); var plan = decode(encoded); if (authenticatedContext != null) plan.set("runtimeAuthenticatedContext", authenticatedContext); var converter = new ManagedFlinkRows(shape); long[] count = {0, 0};
            try {
                transport(kind).read(kind, plan, json -> {
                    if (!running || Thread.currentThread().isInterrupted()) throw new IllegalStateException("有界输入已取消");
                    count[0]++; count[1] += json.getBytes(StandardCharsets.UTF_8).length;
                    if (count[0] > 1_000_000 || count[1] > 67_108_864) throw new IllegalArgumentException("有界输入超过总行数或字节预算");
                    RowData row = converter.decode(EtlNativeRowGuard.validate(json, plan));
                    synchronized (context.getCheckpointLock()) { if (running) context.collect(row); }
                });
            } catch (Exception failure) { if (running) throw new IllegalStateException("受管有界输入失败：" + kind); }
            finally { thread = null; }
        }
        @Override public void cancel() { running = false; Thread current = thread; if (current != null) current.interrupt(); }
    }
    private static final class BoundedSink extends RichSinkFunction<RowData> {
        private final String kind, encoded; private final RowType shape;
        private transient EtlBoundedConnectorTransport.Writer writer; private transient ManagedFlinkRows converter; private transient JSONObject plan;
        private long count, bytes; private boolean finished;
        BoundedSink(String kind, String encoded, RowType shape) { this.kind = kind; this.encoded = encoded; this.shape = shape; }
        @Override public void open(Configuration ignored) throws Exception {
            plan = decode(encoded); converter = new ManagedFlinkRows(shape); writer = transport(kind).writer(kind, plan);
        }
        @Override public void invoke(RowData row, Context ignored) throws Exception {
            if (finished || Thread.currentThread().isInterrupted()) throw new IllegalStateException("有界输出已结束或取消");
            var values = converter.values(row); String json = JSONUtil.toJsonStr(values);
            if (++count > 1_000_000 || (bytes += json.getBytes(StandardCharsets.UTF_8).length) > 67_108_864) throw new IllegalArgumentException("有界输出超过总行数或字节预算");
            EtlNativeRowGuard.validate(json, plan); writer.write(values);
        }
        @Override public void finish() throws Exception { if (!finished) { writer.finish(); finished = true; } }
        @Override public void close() throws Exception { if (writer != null) writer.close(); }
    }
}
