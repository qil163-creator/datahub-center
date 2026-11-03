package cn.iocoder.yudao.framework.etl.spark;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.etl.core.executor.support.EtlBoundedConnectorTransport;
import cn.iocoder.yudao.framework.etl.core.executor.support.EtlNativeRowGuard;
import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.catalyst.expressions.GenericInternalRow;
import org.apache.spark.sql.connector.catalog.*;
import org.apache.spark.sql.connector.distributions.*;
import org.apache.spark.sql.connector.expressions.*;
import org.apache.spark.sql.connector.read.*;
import org.apache.spark.sql.connector.write.*;
import org.apache.spark.sql.types.*;
import org.apache.spark.sql.util.CaseInsensitiveStringMap;
import org.apache.spark.unsafe.types.UTF8String;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** 批任务专属V2表：单个有限读取分区，输出强制单分区并在完整验证后确认发布。 */
public final class EtlBoundedMessageProvider implements TableProvider {
    @Override public boolean supportsExternalMetadata() { return true; }
    @Override public StructType inferSchema(CaseInsensitiveStringMap ignored) { throw new IllegalArgumentException("受管消息需要明确字段合同"); }
    @Override public Table getTable(StructType schema, Transform[] transforms, Map<String, String> options) {
        String encoded = options.get("datahub.plan"), kind = options.get("datahub.kind"), direction = options.get("datahub.direction");
        if (schema == null || schema.isEmpty() || schema.size() > 1024 || encoded == null || encoded.length() > 1_048_576
                || !Set.of("kafka", "rabbitmq", "rocketmq", "tdengine").contains(kind) || !Set.of("source", "sink").contains(direction)) throw new IllegalArgumentException("消息表计划无效");
        var plan = decode(encoded); var fields = plan.getJSONObject("resultTypes");
        if (fields == null || !fields.keySet().equals(new LinkedHashSet<>(Arrays.asList(schema.fieldNames())))) throw new IllegalArgumentException("消息表字段与执行合同不一致");
        for (var field : schema.fields()) if (!DataType.fromDDL(fields.getJSONObject(field.name()).getStr("sqlType")).equals(field.dataType())) throw new IllegalArgumentException("消息表物理类型与声明合同不一致");
        transport(kind); return new MessageTable(schema, encoded, kind, direction);
    }
    private static JSONObject decode(String encoded) { return JSONUtil.parseObj(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8)); }
    private static EtlBoundedConnectorTransport transport(String kind) {
        var matches = new ArrayList<EtlBoundedConnectorTransport>();
        for (var candidate : ServiceLoader.load(EtlBoundedConnectorTransport.class, Thread.currentThread().getContextClassLoader())) if (candidate.kinds().contains(kind)) matches.add(candidate);
        if (matches.size() != 1) throw new IllegalStateException("消息工作端未安装或存在重复协议实现：" + kind);
        return matches.get(0);
    }
    private record MessageTable(StructType schema, String encoded, String kind, String direction) implements Table, SupportsRead, SupportsWrite {
        @Override public String name() { return "受管有界消息：" + kind; }
        @Override public Set<TableCapability> capabilities() { return direction.equals("sink") ? Set.of(TableCapability.BATCH_READ, TableCapability.BATCH_WRITE) : Set.of(TableCapability.BATCH_READ); }
        @Override public ScanBuilder newScanBuilder(CaseInsensitiveStringMap ignored) {
            if (!direction.equals("source")) throw new IllegalArgumentException("消息输出表不能作为输入读取");
            return () -> new Scan() {
                @Override public StructType readSchema() { return schema; }
                @Override public Batch toBatch() { return new Batch() {
                    @Override public InputPartition[] planInputPartitions() { return new InputPartition[]{new Part(encoded, kind)}; }
                    @Override public PartitionReaderFactory createReaderFactory() { return new ReaderFactory(schema); }
                }; }
            };
        }
        @Override public WriteBuilder newWriteBuilder(LogicalWriteInfo info) {
            if (!direction.equals("sink")) throw new IllegalArgumentException("消息输入表不能作为输出写入");
            return new WriteBuilder() { @Override public Write build() { return new RequiresDistributionAndOrdering() {
                @Override public Distribution requiredDistribution() { return Distributions.clustered(new Expression[]{Expressions.column(schema.fieldNames()[0])}); }
                @Override public int requiredNumPartitions() { return 1; }
                @Override public SortOrder[] requiredOrdering() { return new SortOrder[0]; }
                @Override public BatchWrite toBatch() { return new BatchWrite() {
                    @Override public DataWriterFactory createBatchWriterFactory(PhysicalWriteInfo physical) { if (physical.numPartitions() != 1) throw new IllegalStateException("消息有界输出必须保持单个完整分区"); return new WriterFactory(schema, encoded, kind); }
                    @Override public void commit(WriterCommitMessage[] messages) { if (messages == null || messages.length != 1) throw new IllegalStateException("消息有界输出缺少唯一提交结果"); }
                    @Override public void abort(WriterCommitMessage[] messages) { }
                }; }
            }; } };
        }
    }
    private record Part(String encoded, String kind) implements InputPartition { }
    private record ReaderFactory(StructType schema) implements PartitionReaderFactory {
        @Override public PartitionReader<InternalRow> createReader(InputPartition partition) {
            var part = (Part) partition; var plan = decode(part.encoded()); var rows = new ArrayList<String>(); long[] bytes = {0};
            try { transport(part.kind()).read(part.kind(), plan, json -> { if (Thread.currentThread().isInterrupted() || rows.size() >= 1_000_000 || (bytes[0] += json.getBytes(StandardCharsets.UTF_8).length) > 67_108_864) throw new IllegalArgumentException("消息有限分区超过预算或已取消"); rows.add(EtlNativeRowGuard.validate(json, plan)); }); }
            catch (Exception failure) { rows.clear(); throw new IllegalStateException("受管消息有界读取失败：" + part.kind()); }
            return new PartitionReader<>() {
                private int index = -1; private InternalRow current;
                @Override public boolean next() { if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("消息读取已取消"); if (++index >= rows.size()) { current = null; return false; } current = row(JSONUtil.parseObj(rows.get(index)), schema); return true; }
                @Override public InternalRow get() { if (current == null) throw new IllegalStateException("没有当前消息行"); return current; }
                @Override public void close() { current = null; rows.clear(); }
            };
        }
    }
    private static InternalRow row(JSONObject values, StructType schema) {
        Object[] result = new Object[schema.size()];
        for (int i = 0; i < schema.size(); i++) {
            var field = schema.fields()[i]; Object value = values.get(field.name());
            if (JSONUtil.isNull(value)) { if (!field.nullable()) throw new IllegalArgumentException("消息字段不允许空值"); continue; }
            String text = value.toString(); DataType type = field.dataType();
            if (type.equals(DataTypes.StringType)) result[i] = UTF8String.fromString(text);
            else if (type.equals(DataTypes.BooleanType)) { if (!Set.of("true", "false").contains(text.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("消息布尔字段无效"); result[i] = Boolean.valueOf(text); }
            else if (type.equals(DataTypes.LongType)) result[i] = new BigDecimal(text).longValueExact();
            else if (type.equals(DataTypes.IntegerType)) result[i] = new BigDecimal(text).intValueExact();
            else if (type.equals(DataTypes.ShortType)) result[i] = new BigDecimal(text).shortValueExact();
            else if (type.equals(DataTypes.ByteType)) result[i] = new BigDecimal(text).byteValueExact();
            else if (type.equals(DataTypes.DoubleType)) { double number = Double.parseDouble(text); if (!Double.isFinite(number)) throw new IllegalArgumentException("消息浮点超出范围"); result[i] = number; }
            else if (type.equals(DataTypes.FloatType)) { float number = Float.parseFloat(text); if (!Float.isFinite(number) || number == 0 && new BigDecimal(text).signum() != 0) throw new IllegalArgumentException("消息浮点超出范围"); result[i] = number; }
            else if (type instanceof DecimalType decimal) { var parsed = new BigDecimal(text).setScale(decimal.scale(), java.math.RoundingMode.UNNECESSARY); if (parsed.precision() > decimal.precision()) throw new IllegalArgumentException("消息精确小数超出范围"); result[i] = Decimal.apply(parsed, decimal.precision(), decimal.scale()); }
            else if (type.equals(DataTypes.DateType)) result[i] = Math.toIntExact(LocalDate.parse(text).toEpochDay());
            else if (type.equals(DataTypes.TimestampType)) { Instant instant; try { instant = OffsetDateTime.parse(text.replace(' ', 'T')).toInstant(); } catch (java.time.format.DateTimeParseException ignored) { instant = LocalDateTime.parse(text.replace(' ', 'T')).toInstant(ZoneOffset.UTC); } if (instant.getNano() % 1000 != 0) throw new IllegalArgumentException("Spark消息时标不能无损容纳纳秒精度"); result[i] = Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000), instant.getNano() / 1000); }
            else throw new IllegalArgumentException("消息字段类型未适配");
        }
        return new GenericInternalRow(result);
    }
    private record Committed() implements WriterCommitMessage { }
    private record WriterFactory(StructType schema, String encoded, String kind) implements DataWriterFactory {
        @Override public DataWriter<InternalRow> createWriter(int partition, long attempt) {
            if (partition != 0) throw new IllegalStateException("消息输出必须单分区执行"); var plan = decode(encoded);
            final EtlBoundedConnectorTransport.Writer writer;
            try { writer = transport(kind).writer(kind, plan); } catch (Exception failure) { throw new IllegalStateException("消息输出初始化失败"); }
            return new DataWriter<>() {
                private long rows, bytes; private boolean closed, committed;
                @Override public void write(InternalRow value) throws IOException {
                    if (closed || committed || Thread.currentThread().isInterrupted()) throw new IOException("消息输出已经结束或取消");
                    var fields = new LinkedHashMap<String, String>(); for (int i = 0; i < schema.size(); i++) fields.put(schema.fields()[i].name(), EtlSparkRowValues.format(value, schema.fields()[i], i, Map.of()));
                    String json = JSONUtil.toJsonStr(fields); if (++rows > 1_000_000 || (bytes += json.getBytes(StandardCharsets.UTF_8).length) > 67_108_864) throw new IOException("消息完整输出分区超过预算");
                    EtlNativeRowGuard.validate(json, plan); try { writer.write(fields); } catch (Exception failure) { throw new IOException("消息输出行校验或暂存失败"); }
                }
                @Override public WriterCommitMessage commit() throws IOException { if (closed || committed) throw new IOException("消息输出不能重复提交"); try { writer.finish(); committed = true; return new Committed(); } catch (Exception failure) { throw new IOException("消息确认发布失败，重试采用至少一次语义"); } }
                @Override public void abort() throws IOException { close(); }
                @Override public void close() throws IOException { if (!closed) { closed = true; try { writer.close(); } catch (Exception failure) { throw new IOException("消息输出清理失败"); } } }
            };
        }
    }
}
