package cn.iocoder.yudao.framework.etl.spark;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.etl.core.executor.support.EtlManagedOdps;
import cn.iocoder.yudao.framework.etl.core.executor.support.EtlManagedSecrets;
import cn.iocoder.yudao.framework.etl.core.executor.support.EtlNativeRowGuard;
import com.aliyun.odps.tunnel.TableTunnel;
import com.aliyun.odps.tunnel.io.CompressOption;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.connector.catalog.*;
import org.apache.spark.sql.connector.expressions.Transform;
import org.apache.spark.sql.connector.read.ScanBuilder;
import org.apache.spark.sql.connector.write.*;
import org.apache.spark.sql.types.StructType;
import org.apache.spark.sql.util.CaseInsensitiveStringMap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Spark Driver 唯一上传会话，分片仅暂存 block；所有分片成功后才调用 Tunnel commit。 */
public final class EtlOdpsProvider implements TableProvider {
    @Override public StructType inferSchema(CaseInsensitiveStringMap options) { throw new IllegalArgumentException("ODPS 需要完整字段合同"); }
    @Override public boolean supportsExternalMetadata() { return true; }
    @Override public Table getTable(StructType schema, Transform[] transforms, Map<String, String> input) {
        String encoded = input.get("plan"); if (schema == null || schema.isEmpty() || encoded == null || encoded.length() > 1048576) throw new IllegalArgumentException("ODPS 输出合同缺失或超限");
        return new Sink(schema, encoded);
    }
    private record Sink(StructType schema, String encoded) implements Table, SupportsRead, SupportsWrite {
        @Override public String name() { return "受管 ODPS Tunnel 批处理输出"; }
        @Override public Set<TableCapability> capabilities() { return Set.of(TableCapability.BATCH_READ, TableCapability.BATCH_WRITE); }
        @Override public ScanBuilder newScanBuilder(CaseInsensitiveStringMap options) { throw new IllegalArgumentException("ODPS 输出视图不用于读取"); }
        @Override public WriteBuilder newWriteBuilder(LogicalWriteInfo info) {
            if (SparkSession.active().sparkContext().getConf().getBoolean("spark.speculation", false)) throw new IllegalArgumentException("ODPS 上传工作环境必须关闭推测执行");
            return new WriteBuilder() {
                @Override public BatchWrite buildForBatch() {
                    JSONObject plan = decode(encoded); var session = EtlManagedOdps.upload(plan, EtlManagedSecrets::readFromEnvironment, null);
                    return new BatchWrite() {
                        @Override public DataWriterFactory createBatchWriterFactory(PhysicalWriteInfo partitions) {
                            if (partitions.numPartitions() > 20000) throw new IllegalArgumentException("ODPS 单会话分片数不能超过20000");
                            return new Factory(info.schema(), encoded, session.getId());
                        }
                        @Override public void commit(WriterCommitMessage[] messages) {
                            try {
                                var blocks = new LinkedHashSet<Long>(); for (var message : messages) if (!(message instanceof Part part) || !blocks.add(part.block())) throw new IllegalArgumentException();
                                session.commit(blocks.toArray(Long[]::new));
                            } catch (Exception failure) { throw new IllegalStateException("ODPS 上传最终提交未完整确认，请核查会话状态后再重试"); }
                        }
                        // 未完成会话不会提交可见表数据，由官方会话 TTL 清理暂存块；提交应答丢失仍需管理员核查。
                        @Override public void abort(WriterCommitMessage[] messages) { }
                    };
                }
            };
        }
    }
    private record Part(long block) implements WriterCommitMessage { }
    private record Factory(StructType schema, String encoded, String session) implements DataWriterFactory {
        @Override public DataWriter<InternalRow> createWriter(int partitionId, long taskId) { return new Writer(schema, encoded, session, partitionId, taskId); }
    }
    private static final class Writer implements DataWriter<InternalRow> {
        private final StructType schema; private final JSONObject plan; private final TableTunnel.UploadSession session;
        private final com.aliyun.odps.tunnel.io.TunnelRecordWriter writer; private final long block; private int pending; private boolean closed;
        Writer(StructType schema, String encoded, String id, int partition, long attempt) {
            try {
                if (partition < 0 || partition >= 20000 || attempt < 0) throw new IllegalArgumentException();
                this.schema = schema; plan = decode(encoded); block = partition;
                session = EtlManagedOdps.upload(plan, EtlManagedSecrets::readFromEnvironment, id);
                var opened = session.openRecordWriter(block, new CompressOption(CompressOption.CompressAlgorithm.ODPS_RAW, 0, 0), Math.addExact(attempt, 1));
                if (!(opened instanceof com.aliyun.odps.tunnel.io.TunnelRecordWriter nativeWriter)) { opened.close(); throw new IllegalArgumentException(); }
                writer = nativeWriter;
            } catch (Exception failure) { throw new IllegalStateException("ODPS 暂存分片不可用"); }
        }
        @Override public void write(InternalRow row) throws IOException {
            try {
                var values = new LinkedHashMap<String, String>();
                for (int index = 0; index < schema.size(); index++) values.put(schema.fields()[index].name(), EtlSparkRowValues.format(row, schema.fields()[index], index, Map.of()));
                EtlNativeRowGuard.validate(JSONUtil.toJsonStr(values), plan); var record = session.newRecord();
                for (var field : values.entrySet()) {
                    if (field.getValue() == null && !session.getSchema().getColumn(field.getKey()).isNullable()) throw new IllegalArgumentException();
                    record.set(field.getKey(), EtlManagedOdps.value(plan.getJSONObject("columns").getStr(field.getKey()), field.getValue()));
                }
                writer.write(record); if (++pending >= plan.getInt("batchSize")) { writer.flush(); pending = 0; }
            } catch (Exception failure) { throw new IOException("ODPS 分片字段不满足声明类型或上传未确认"); }
        }
        private void finish() throws IOException {
            if (!closed) { closed = true; try { writer.close(); } catch (Exception failure) { throw new IOException("ODPS 分片上传未完整确认"); } }
        }
        @Override public WriterCommitMessage commit() throws IOException { finish(); return new Part(block); }
        @Override public void abort() throws IOException { finish(); }
        @Override public void close() throws IOException { finish(); }
    }
    private static JSONObject decode(String encoded) { return JSONUtil.parseObj(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8)); }
}
