package cn.iocoder.yudao.framework.etl.flink.bounded;

import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.etl.core.executor.support.*;

import java.util.*;

/** 复用现有受管 SDK 和凭据边界，外部确认失败会使真实 Flink 任务失败。 */
final class ManagedFlinkSinks {
    private ManagedFlinkSinks() { }
    static EtlBoundedConnectorTransport.Writer open(String kind, JSONObject plan) throws Exception {
        return switch (kind) {
            case "redis" -> redis(plan);
            case "odps" -> odps(plan);
            case "ftp" -> ftp(plan);
            case "hdfs" -> ManagedFlinkHdfs.writer(plan);
            case "mongodb" -> ManagedFlinkMongo.writer(plan);
            default -> throw new IllegalArgumentException("当前有界连接器不提供输出协议");
        };
    }
    private static EtlBoundedConnectorTransport.Writer redis(JSONObject plan) {
        var connection = EtlManagedRedis.connect(plan, true, EtlManagedSecrets::readFromEnvironment); var pipeline = connection.pipelined();
        int maximum = plan.getInt("batchSize", 1000); if (maximum < 1 || maximum > 10000) { connection.close(); throw new IllegalArgumentException("Redis 批次预算无效"); }
        return new EtlBoundedConnectorTransport.Writer() {
            private int pending;
            private void flush() {
                var replies = pipeline.syncAndReturnAll(); pending = 0;
                if (replies.stream().anyMatch(value -> value instanceof Exception)) throw new IllegalStateException("Redis 批次包含未确认命令");
            }
            @Override public void write(Map<String, String> row) { EtlManagedRedis.write(plan, pipeline, row); if (++pending >= maximum) flush(); }
            @Override public void finish() { if (pending > 0) flush(); }
            @Override public void close() { connection.close(); }
        };
    }
    private static EtlBoundedConnectorTransport.Writer odps(JSONObject plan) throws Exception {
        var session = EtlManagedOdps.upload(plan, EtlManagedSecrets::readFromEnvironment, null);
        var opened = session.openRecordWriter(0L);
        if (!(opened instanceof com.aliyun.odps.tunnel.io.TunnelRecordWriter writer)) { opened.close(); throw new IllegalArgumentException("ODPS 未提供可确认批次的受管 writer"); }
        int maximum = plan.getInt("batchSize", 1000);
        if (maximum < 1 || maximum > 10000) { writer.close(); throw new IllegalArgumentException("ODPS 批次预算无效"); }
        return new EtlBoundedConnectorTransport.Writer() {
            private boolean closed, finished; private int pending;
            private void closeWriter() throws Exception { if (!closed) { closed = true; writer.close(); } }
            @Override public void write(Map<String, String> row) throws Exception {
                var record = session.newRecord();
                for (var field : row.entrySet()) {
                    if (field.getValue() == null && !session.getSchema().getColumn(field.getKey()).isNullable()) throw new IllegalArgumentException("ODPS 字段不允许空值");
                    record.set(field.getKey(), EtlManagedOdps.value(plan.getJSONObject("columns").getStr(field.getKey()), field.getValue()));
                }
                writer.write(record); if (++pending >= maximum) { writer.flush(); pending = 0; }
            }
            @Override public void finish() throws Exception { if (!finished) { closeWriter(); session.commit(new Long[]{0L}); finished = true; } }
            @Override public void close() throws Exception { closeWriter(); }
        };
    }
    private static EtlBoundedConnectorTransport.Writer ftp(JSONObject plan) throws Exception {
        var stage = new ManagedFlinkTextStage(plan, "gzip".equals(plan.getStr("compression", "")));
        String prefix = plan.getStr("fileName", "data");
        if (!prefix.matches("[A-Za-z0-9_-]{1,100}")) { stage.close(); throw new IllegalArgumentException("FTP 文件名前缀无效"); }
        String name = prefix + "-" + UUID.randomUUID() + (plan.getStr("fileType", "csv").equals("csv") ? ".csv" : ".txt") + ("gzip".equals(plan.getStr("compression", "")) ? ".gz" : "");
        return new EtlBoundedConnectorTransport.Writer() {
            private boolean published;
            @Override public void write(Map<String, String> row) throws Exception { stage.write(row); }
            @Override public void finish() throws Exception {
                if (published) return;
                stage.finish();
                try (var session = EtlManagedFtp.fromEnvironment(plan)) { session.storeUnique(plan.getStr("path"), name, stage.file, plan.getLong("maxBytes", 8388608L)); published = true; }
            }
            @Override public void close() throws Exception { stage.close(); }
        };
    }
}
