package cn.iocoder.yudao.framework.etl.script.flink;

import cn.iocoder.yudao.framework.etl.core.script.*;
import org.apache.flink.api.common.RuntimeExecutionMode;
import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.api.common.typeinfo.PrimitiveArrayTypeInfo;
import org.apache.flink.runtime.state.StateInitializationContext;
import org.apache.flink.runtime.state.StateSnapshotContext;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.operators.AbstractStreamOperator;
import org.apache.flink.streaming.api.operators.BoundedOneInput;
import org.apache.flink.streaming.api.operators.OneInputStreamOperator;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.table.api.*;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.apache.flink.table.runtime.typeutils.ExternalTypeInfo;
import org.apache.flink.table.types.DataType;
import org.apache.flink.types.Row;
import org.apache.flink.types.RowKind;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** 可信 Flink stage 只处理行与 mTLS RPC；源码、原始认证材料和用户类不进入引擎 JVM。 */
public final class EtlNativeScriptMain {
    private EtlNativeScriptMain() { }
    public static void main(String[] arguments) throws Exception {
        var program = EtlNativeScriptProgram.fromArguments(arguments);
        if (!"FLINK".equals(program.engine())) throw invalid("Flink launcher不能执行其他引擎的程序");
        var environment = StreamExecutionEnvironment.getExecutionEnvironment();
        environment.setRuntimeMode(program.streaming() ? RuntimeExecutionMode.STREAMING : RuntimeExecutionMode.BATCH);
        environment.setParallelism(program.parallelism());
        if (program.streaming() && environment.getCheckpointConfig().getCheckpointInterval() <= 0) environment.enableCheckpointing(60000);
        var tables = StreamTableEnvironment.create(environment);
        String run = UUID.randomUUID().toString();
        var contexts = new LinkedHashMap<String, String>(); EtlScriptBrokerClient.Binding identity = null;
        for (var initializer : program.initializers()) {
            try (var client = new EtlScriptBrokerClient(initializer.binding(), run, "initializer-" + initializer.id(), null)) {
                var result = client.execute(0, List.of());
                if (!result.rows().isEmpty() || result.authenticationContext().isEmpty()) throw invalid("认证初始化返回了无效受管上下文");
                contexts.put(initializer.tokenKey(), result.authenticationContext()); if (identity == null) identity = initializer.binding();
            }
        }
        try (var scope = identity == null ? null : EtlScriptAuthenticatedHttp.openRuntimeContext(new EtlScriptAuthenticatedHttp.Context(identity, run, contexts))) {
        for (String initialization : program.initialization()) tables.executeSql(initialization);
        for (var stage : program.stages()) {
            Table input = tables.sqlQuery(stage.inputSql()); validateInputSchema(input, stage.inputFields());
            DataStream<Row> stream = tables.toDataStream(input, rowType(stage.inputFields()));
            DataStream<Row> result = stage(stream, stage, run, program.streaming());
            tables.createTemporaryView(stage.view(), result, Schema.newBuilder().fromRowDataType(rowType(stage.outputFields())).build());
        }
        var statements = tables.createStatementSet();
        for (String insert : program.inserts()) statements.addInsertSql(insert);
        TableResult result = statements.execute();
        result.getJobClient().ifPresent(client -> System.out.println("Job ID: " + client.getJobID()));
        result.await();
        System.out.println("DATAHUB_NATIVE_SCRIPT_COMPLETED");
        }
    }

    /** 不增加 shuffle：用户可见分区顺序完全来自输入 SQL 的实际计划。 */
    public static DataStream<Row> stage(DataStream<Row> input, EtlNativeScriptProgram.Stage stage, String run, boolean streaming) {
        return input.transform("managed-script-" + stage.id(), ExternalTypeInfo.of(rowType(stage.outputFields())), new BatchOperator(stage, run, streaming))
                .setParallelism(input.getParallelism())
                .uid("managed-script-" + stage.id());
    }

    public static DataType rowType(List<EtlNativeScriptProgram.Field> fields) {
        return DataTypes.ROW(fields.stream().map(field -> DataTypes.FIELD(field.name(), scalarType(field))).toArray(DataTypes.Field[]::new));
    }
    private static DataType scalarType(EtlNativeScriptProgram.Field field) {
        String sql = field.sqlType();
        DataType type = switch (sql) {
            case "STRING" -> DataTypes.STRING(); case "BOOLEAN" -> DataTypes.BOOLEAN();
            case "TINYINT" -> DataTypes.TINYINT(); case "SMALLINT" -> DataTypes.SMALLINT();
            case "INT" -> DataTypes.INT(); case "BIGINT" -> DataTypes.BIGINT();
            case "FLOAT" -> DataTypes.FLOAT(); case "DOUBLE" -> DataTypes.DOUBLE(); case "DATE" -> DataTypes.DATE();
            default -> {
                if (sql.startsWith("DECIMAL(")) { var shape = sql.substring(8, sql.length() - 1).split(","); yield DataTypes.DECIMAL(Integer.parseInt(shape[0]), Integer.parseInt(shape[1])); }
                if (sql.startsWith("TIMESTAMP(")) yield DataTypes.TIMESTAMP(Integer.parseInt(sql.substring(10, 11)));
                throw invalid("脚本标量类型无效");
            }
        };
        return Boolean.FALSE.equals(field.nullable()) ? type.notNull() : type;
    }
    private static void validateInputSchema(Table table, List<EtlNativeScriptProgram.Field> fields) {
        var schema = table.getResolvedSchema();
        if (!schema.getColumnNames().equals(fields.stream().map(EtlNativeScriptProgram.Field::name).toList())) throw invalid("脚本输入字段数量或顺序与已发布合同不一致");
        for (int i = 0; i < fields.size(); i++) if (!schema.getColumnDataTypes().get(i).getLogicalType().copy(true).equals(scalarType(fields.get(i)).getLogicalType().copy(true)))
            throw invalid("脚本输入字段类型与已发布合同不一致");
    }

    public static final class BatchOperator extends AbstractStreamOperator<Row> implements OneInputStreamOperator<Row, Row>, BoundedOneInput {
        private static final long serialVersionUID = 1L;
        private final EtlNativeScriptProgram.Stage stage;
        private final String originalRun, scope;
        private final boolean streaming;
        private transient ListState<byte[]> state;
        private transient EtlNativeScriptState.Snapshot restored;
        private transient ArrayList<Map<String, Object>> buffer;
        private transient NavigableMap<Long, Long> checkpoints;
        private transient volatile EtlScriptBrokerClient client;
        private transient ScheduledFuture<?> timer;
        private transient Thread shutdown;
        private transient AtomicBoolean cleaning;
        private transient volatile boolean finished, inputEnded, inFlight, cancelRequired;
        private transient String run, partition, session, checkpointScope;
        private transient int bufferedBytes;
        private transient long nextSequence, timerDeadline;

        public BatchOperator(EtlNativeScriptProgram.Stage stage, String run, boolean streaming) {
            this.stage = Objects.requireNonNull(stage); this.originalRun = Objects.requireNonNull(run); this.streaming = streaming;
            if (streaming == stage.partitionBatch()) throw invalid("脚本窗口与任务模式不一致");
            try { scope = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stage.toString().getBytes(StandardCharsets.UTF_8))); }
            catch (Exception failure) { throw invalid("脚本阶段身份不可用"); }
        }
        @Override public void initializeState(StateInitializationContext context) throws Exception {
            super.initializeState(context);
            state = context.getOperatorStateStore().getListState(new ListStateDescriptor<>("datahub-script-window-v1", PrimitiveArrayTypeInfo.BYTE_PRIMITIVE_ARRAY_TYPE_INFO));
            if (context.isRestored()) {
                var values = state.get().iterator();
                if (!values.hasNext()) throw invalid("脚本恢复缺少明确的分区状态");
                restored = EtlNativeScriptState.decode(values.next());
                if (values.hasNext()) throw invalid("脚本检查点不支持隐式合并或重分配分区");
            }
        }
        @Override public void open() throws Exception {
            super.open(); cleaning = new AtomicBoolean(); buffer = new ArrayList<>(); checkpoints = new TreeMap<>(); bufferedBytes = Integer.BYTES;
            partition = stage.id() + "-" + getRuntimeContext().getIndexOfThisSubtask(); run = originalRun; session = "";
            checkpointScope = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((scope + ":" + getRuntimeContext().getNumberOfParallelSubtasks()).getBytes(StandardCharsets.US_ASCII)));
            if (streaming && !getRuntimeContext().isCheckpointingEnabled()) throw invalid("持续脚本必须启用检查点");
            if (restored != null) {
                if (!checkpointScope.equals(restored.scope()) || !partition.equals(restored.partition()) || restored.rows().size() > stage.maximumRows()
                        || stage.partitionBatch() && restored.timerDeadline() != 0) throw invalid("脚本检查点不属于当前阶段、分区或批次合同");
                run = restored.run(); session = restored.session(); nextSequence = restored.nextSequence(); timerDeadline = restored.timerDeadline();
                buffer.addAll(restored.rows()); bufferedBytes = EtlNativeScriptState.batchBytes(buffer);
                for (var row : buffer) EtlNativeScriptValues.output(row, stage.inputFields());
                if (!session.isEmpty()) client = new EtlScriptBrokerClient(stage.binding(), run, partition, session);
            }
            shutdown = new Thread(this::stopClient, "etl-script-stop-" + partition); Runtime.getRuntime().addShutdownHook(shutdown);
            if (!stage.partitionBatch() && !buffer.isEmpty()) scheduleTimer();
        }
        @Override public void processElement(StreamRecord<Row> record) throws Exception {
            if (finished || record.getValue().getKind() != RowKind.INSERT || record.getValue().getArity() != stage.inputFields().size()) throw invalid("脚本只接受已声明的 INSERT 数据行");
            if (buffer.size() >= stage.maximumRows()) throw invalid("完整分区超过脚本最大行数，不能暗中拆分整批语义");
            var values = new ArrayList<Object>(record.getValue().getArity());
            for (int i = 0; i < record.getValue().getArity(); i++) values.add(record.getValue().getField(i));
            var row = EtlNativeScriptValues.input(values, stage.inputFields()); int bytes = EtlNativeScriptState.rowBytes(row);
            if (bufferedBytes > EtlNativeScriptState.MAX_BATCH_BYTES - bytes) throw invalid("脚本批次超过明确的字节预算");
            buffer.add(row); bufferedBytes += bytes;
            if (!stage.partitionBatch()) {
                if (buffer.size() == 1) scheduleTimer();
                if (buffer.size() == stage.maximumRows()) flush(false);
            }
        }
        private void scheduleTimer() {
            if (timerDeadline == 0) timerDeadline = Math.addExact(getProcessingTimeService().getCurrentProcessingTime(), stage.flushMillis());
            long expected = timerDeadline;
            timer = getProcessingTimeService().registerTimer(Math.max(timerDeadline, getProcessingTimeService().getCurrentProcessingTime()), timestamp -> {
                if (!finished && timerDeadline == expected && !buffer.isEmpty()) flush(false);
            });
        }
        private void flush(boolean emptyPartition) throws Exception {
            if (finished || buffer.isEmpty() && !emptyPartition) return;
            if (nextSequence == Long.MAX_VALUE) throw invalid("脚本批次序号耗尽");
            if (timer != null) timer.cancel(false); timer = null;
            boolean confirmedResponse = false;
            try {
                if (client == null) { client = new EtlScriptBrokerClient(stage.binding(), run, partition, session.isEmpty() ? null : session); session = client.sessionReference(); }
                inFlight = true;
                var response = client.execute(nextSequence, List.copyOf(buffer));
                if (!response.authenticationContext().isEmpty()) throw invalid("Flink 数据脚本不能返回认证上下文");
                var rows = new ArrayList<Row>(response.rows().size());
                for (var row : response.rows()) rows.add(Row.ofKind(RowKind.INSERT, EtlNativeScriptValues.output(row, stage.outputFields()).toArray()));
                nextSequence++; buffer.clear(); bufferedBytes = Integer.BYTES; timerDeadline = 0; confirmedResponse = true;
                for (var row : rows) output.collect(new StreamRecord<>(row));
            } catch (Exception failure) {
                // 响应已完整确认后的下游失败允许从已提交检查点重放缓存；未知执行结果必须取消。
                if (!confirmedResponse) cancelRequired = true;
                throw failure;
            }
            finally { inFlight = false; }
        }
        @Override public void endInput() throws Exception { flush(stage.partitionBatch()); inputEnded = true; finished = true; }
        @Override public void snapshotState(StateSnapshotContext context) throws Exception {
            super.snapshotState(context);
            state.update(List.of(EtlNativeScriptState.encode(new EtlNativeScriptState.Snapshot(checkpointScope, partition, run, session, nextSequence, timerDeadline, buffer))));
            checkpoints.put(context.getCheckpointId(), nextSequence);
            while (checkpoints.size() > 64) checkpoints.pollFirstEntry();
        }
        @Override public void notifyCheckpointComplete(long checkpoint) throws Exception {
            super.notifyCheckpointComplete(checkpoint);
            var completed = checkpoints.floorEntry(checkpoint);
            if (completed != null && client != null && !cancelRequired) client.acknowledge(completed.getValue());
            checkpoints.headMap(checkpoint, true).clear();
        }
        @Override public void notifyCheckpointAborted(long checkpoint) throws Exception { super.notifyCheckpointAborted(checkpoint); checkpoints.remove(checkpoint); }
        private void stopClient() {
            if (cleaning == null || !cleaning.compareAndSet(false, true) || client == null) return;
            boolean interrupted = Thread.interrupted();
            // 流作业空闲停止后保留 broker 会话至 TTL，以便从检查点继续；CLOSE 会永久禁止新批次。
            try { if (inFlight || cancelRequired) client.cancel(); else if (inputEnded) client.close(); }
            finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
        @Override public void close() throws Exception {
            finished = true; if (timer != null) timer.cancel(false);
            try { stopClient(); }
            finally {
                if (shutdown != null) try { Runtime.getRuntime().removeShutdownHook(shutdown); } catch (IllegalStateException ignored) { }
                super.close();
            }
        }
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
