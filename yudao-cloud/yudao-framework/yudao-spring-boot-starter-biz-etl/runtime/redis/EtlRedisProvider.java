package cn.iocoder.yudao.framework.etl.spark;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.etl.core.executor.support.EtlManagedRedis;
import cn.iocoder.yudao.framework.etl.core.executor.support.EtlManagedSecrets;
import cn.iocoder.yudao.framework.etl.core.executor.support.EtlNativeRowGuard;
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

/** Spark 原生 Redis 批量输出；只有公开计划被序列化，密码不进入任务、闭包或 SQL。 */
public final class EtlRedisProvider implements TableProvider {
    @Override public StructType inferSchema(CaseInsensitiveStringMap options) { throw new IllegalArgumentException("Redis 输出必须声明完整字段合同"); }
    @Override public boolean supportsExternalMetadata() { return true; }
    @Override public Table getTable(StructType schema, Transform[] transforms, Map<String, String> input) {
        String encoded = input.get("plan");
        if (schema == null || schema.isEmpty() || encoded == null || encoded.length() > 1048576) throw new IllegalArgumentException("Redis 输出合同缺失或超限");
        return new Sink(schema, encoded);
    }
    private record Sink(StructType schema, String encoded) implements Table, SupportsRead, SupportsWrite {
        @Override public String name() { return "受管 Redis 批量输出"; }
        @Override public Set<TableCapability> capabilities() { return Set.of(TableCapability.BATCH_READ, TableCapability.BATCH_WRITE); }
        @Override public ScanBuilder newScanBuilder(CaseInsensitiveStringMap options) { throw new IllegalArgumentException("Redis 输出视图不能直接读取"); }
        @Override public WriteBuilder newWriteBuilder(LogicalWriteInfo info) {
            return new WriteBuilder() {
                @Override public BatchWrite buildForBatch() {
                    return new BatchWrite() {
                        @Override public DataWriterFactory createBatchWriterFactory(PhysicalWriteInfo ignored) { return new Factory(info.schema(), encoded); }
                        @Override public void commit(WriterCommitMessage[] messages) { }
                        @Override public void abort(WriterCommitMessage[] messages) { }
                    };
                }
            };
        }
    }
    private record Factory(StructType schema, String encoded) implements DataWriterFactory {
        @Override public DataWriter<InternalRow> createWriter(int partitionId, long taskId) { return new Writer(schema, encoded); }
    }
    private enum Complete implements WriterCommitMessage { INSTANCE }
    private static final class Writer implements DataWriter<InternalRow> {
        private final StructType schema; private final JSONObject plan; private final redis.clients.jedis.Jedis connection;
        private final redis.clients.jedis.Pipeline pipeline; private int pending; private boolean failed;
        Writer(StructType schema, String encoded) {
            this.schema = schema; plan = JSONUtil.parseObj(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8));
            connection = EtlManagedRedis.connect(plan, true, EtlManagedSecrets::readFromEnvironment); pipeline = connection.pipelined();
        }
        @Override public void write(InternalRow row) throws IOException {
            try {
                var values = new LinkedHashMap<String, String>();
                for (int index = 0; index < schema.size(); index++) values.put(schema.fields()[index].name(), EtlSparkRowValues.format(row, schema.fields()[index], index, Map.of()));
                EtlNativeRowGuard.validate(JSONUtil.toJsonStr(values), plan);
                EtlManagedRedis.write(plan, pipeline, values);
                if (++pending >= plan.getInt("batchSize")) flush();
            } catch (RuntimeException failure) { failed = true; throw new IOException("Redis 写入失败，请检查受管认证、Key 或数据类型"); }
        }
        private void flush() {
            var replies = pipeline.syncAndReturnAll(); pending = 0;
            if (replies.stream().anyMatch(value -> value instanceof Exception)) { failed = true; throw new IllegalStateException("Redis 批次响应包含失败"); }
        }
        @Override public WriterCommitMessage commit() throws IOException {
            try { flush(); return Complete.INSTANCE; } catch (RuntimeException failure) { failed = true; throw new IOException("Redis 批次未完整确认"); }
        }
        // Redis 已确认命令不能事务性撤回；失败会报告任务失败，不能伪报全局回滚或恰好一次。
        @Override public void abort() { failed = true; }
        @Override public void close() { try { if (!failed && pending > 0) flush(); } finally { connection.close(); } }
    }
}
