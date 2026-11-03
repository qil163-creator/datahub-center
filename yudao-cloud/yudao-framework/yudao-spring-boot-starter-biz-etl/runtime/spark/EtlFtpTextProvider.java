package cn.iocoder.yudao.framework.etl.spark;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.etl.core.executor.support.EtlManagedFtp;
import cn.iocoder.yudao.framework.etl.core.executor.support.EtlNativeRowGuard;
import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.connector.catalog.*;
import org.apache.spark.sql.connector.expressions.Transform;
import org.apache.spark.sql.connector.read.ScanBuilder;
import org.apache.spark.sql.connector.write.*;
import org.apache.spark.sql.types.StructType;

import java.io.*;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/** FTP输出采用Spark批次写入，分区先生成有界本地文件，再通过STOU发布服务器唯一新文件。 */
public final class EtlFtpTextProvider implements TableProvider {
    @Override public StructType inferSchema(org.apache.spark.sql.util.CaseInsensitiveStringMap options) { throw new IllegalArgumentException("FTP输出需要明确字段类型"); }
    @Override public boolean supportsExternalMetadata() { return true; }
    @Override public Table getTable(StructType schema, Transform[] transforms, Map<String, String> options) {
        String encoded = options.get("datahub.plan");
        if (schema == null || schema.isEmpty() || encoded == null || encoded.length() > 1048576) throw new IllegalArgumentException("FTP输出计划无效");
        JSONObject plan = decode(encoded);
        String roots = System.getenv("DATAHUB_ETL_FTP_ROOTS");
        EtlManagedFtp.root(plan.getStr("rootUri"), roots == null ? Set.of() : Set.copyOf(JSONUtil.parseArray(roots).toList(String.class)));
        return new Sink(schema, encoded);
    }
    private static JSONObject decode(String encoded) { return JSONUtil.parseObj(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8)); }
    private record Sink(StructType schema, String encoded) implements Table, SupportsWrite, SupportsRead {
        @Override public String name() { return "受管FTP唯一新文件输出"; }
        @Override public Set<TableCapability> capabilities() { return Set.of(TableCapability.BATCH_READ, TableCapability.BATCH_WRITE); }
        @Override public ScanBuilder newScanBuilder(org.apache.spark.sql.util.CaseInsensitiveStringMap ignored) { throw new IllegalArgumentException("FTP输出视图只用于写入，请通过FTP输入组件读取"); }
        @Override public WriteBuilder newWriteBuilder(LogicalWriteInfo info) {
            String job = UUID.randomUUID().toString();
            return new WriteBuilder() {
                @Override public BatchWrite buildForBatch() {
                    return new BatchWrite() {
                        @Override public DataWriterFactory createBatchWriterFactory(PhysicalWriteInfo ignored) { return new Factory(schema, encoded, job); }
                        @Override public void commit(WriterCommitMessage[] messages) { }
                        @Override public void abort(WriterCommitMessage[] messages) {
                            if (messages == null || messages.length == 0) return;
                            try (var session = EtlManagedFtp.fromEnvironment(decode(encoded))) {
                                for (WriterCommitMessage message : messages) if (message instanceof Part part) session.deleteCreated(part.path());
                            } catch (Exception failure) { throw new IllegalStateException("FTP批次失败后的本次新文件清理失败"); }
                        }
                    };
                }
            };
        }
    }
    private record Part(String path) implements WriterCommitMessage { }
    private record Factory(StructType schema, String encoded, String job) implements DataWriterFactory {
        @Override public DataWriter<InternalRow> createWriter(int partition, long attempt) { return new Writer(schema, decode(encoded), job, partition, attempt); }
    }
    private static final class Writer implements DataWriter<InternalRow> {
        private final StructType schema; private final JSONObject plan; private Path local;
        private java.io.Writer writer; private final String requested; private final Map<String, String> formats = new LinkedHashMap<>();
        private boolean closed; private int rows; private String remote;
        Writer(StructType schema, JSONObject plan, String job, int partition, long attempt) {
            this.schema = schema; this.plan = plan;
            try {
                local = Files.createTempFile("datahub-ftp-", ".stage");
                String suffix = plan.getStr("fileType", "csv").equals("csv") ? ".csv" : ".txt";
                requested = plan.getStr("fileName") + "-" + job + "-" + partition + "-" + attempt + suffix + (plan.getStr("compression", "").equals("gzip") ? ".gz" : "");
                OutputStream output = Files.newOutputStream(local);
                if (plan.getStr("compression", "").equals("gzip")) output = new GZIPOutputStream(output);
                long limit = plan.getLong("maxBytes", 8388608L);
                output = new FilterOutputStream(output) {
                    long written;
                    private void count(int length) throws IOException { if (length < 0 || written + length > limit) throw new IOException("FTP单文件输出超过预算"); written += length; }
                    @Override public void write(int value) throws IOException { count(1); out.write(value); }
                    @Override public void write(byte[] value, int offset, int length) throws IOException { count(length); out.write(value, offset, length); }
                };
                writer = new BufferedWriter(new OutputStreamWriter(output, java.nio.charset.Charset.forName(plan.getStr("encoding", "UTF-8")).newEncoder()
                        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)));
                for (Object value : plan.getJSONArray("columns")) {
                    JSONObject column = JSONUtil.parseObj(value); String format = column.getStr("format", "");
                    if (!Set.of("", "-").contains(format)) formats.put("datahub.format." + column.getStr("name").toLowerCase(Locale.ROOT), format);
                }
                if (plan.getBool("includeHeader", false)) writeValues(Arrays.asList(schema.fieldNames()), false);
            } catch (Exception failure) {
                if (writer != null) try { writer.close(); } catch (IOException ignored) { }
                if (local != null) try { Files.deleteIfExists(local); } catch (IOException ignored) { }
                throw new IllegalStateException("FTP本地有界文件初始化失败");
            }
        }
        @Override public void write(InternalRow row) throws IOException {
            try {
                if (++rows > plan.getInt("maxRows", 100000)) throw new IllegalArgumentException("FTP单文件行数超过预算");
                var json = JSONUtil.createObj(); var values = new ArrayList<String>();
                for (int index = 0; index < schema.size(); index++) {
                    String value = row.isNullAt(index) ? null : EtlSparkRowValues.format(row, schema.fields()[index], index, Map.of());
                    json.set(schema.fields()[index].name(), value);
                    values.add(row.isNullAt(index) ? null : EtlSparkRowValues.format(row, schema.fields()[index], index, formats));
                }
                EtlNativeRowGuard.validate(json.toString(), plan); writeValues(values, true);
            } catch (RuntimeException failure) { throw new IOException("FTP输出行不能按声明格式无损写入"); }
        }
        private void writeValues(List<String> values, boolean nullable) throws IOException {
            String delimiter = plan.getStr("fieldDelimiter", ","), marker = plan.getStr("nullLiteral", "\\N");
            boolean csv = plan.getStr("fileType", "csv").equals("csv");
            for (int index = 0; index < values.size(); index++) {
                if (index > 0) writer.write(delimiter);
                String value = values.get(index);
                if (value == null && nullable) { writer.write(marker); continue; }
                if (value == null) throw new IOException("FTP文件表头不可为空");
                if (value.equals(marker) || value.indexOf('\0') >= 0 || !csv && (value.isEmpty() || value.contains(delimiter) || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)) throw new IOException("FTP字段与空值标识或文本分隔格式冲突");
                if (csv && (value.isEmpty() || value.contains(delimiter) || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)) value = "\"" + value.replace("\"", "\"\"") + "\"";
                writer.write(value);
            }
            writer.write('\n');
        }
        private void finish() throws IOException { if (!closed) { closed = true; writer.close(); } }
        @Override public WriterCommitMessage commit() throws IOException {
            finish();
            try (var session = EtlManagedFtp.fromEnvironment(plan)) {
                remote = session.storeUnique(plan.getStr("path"), requested, local, plan.getLong("maxBytes", 8388608L));
                Files.deleteIfExists(local); return new Part(remote);
            } catch (Exception failure) { throw new IOException("FTP唯一新文件发布失败"); }
        }
        @Override public void abort() throws IOException {
            finish(); Files.deleteIfExists(local);
            if (remote != null) try (var session = EtlManagedFtp.fromEnvironment(plan)) { session.deleteCreated(remote); }
            catch (Exception failure) { throw new IOException("FTP本次新文件清理失败"); }
        }
        @Override public void close() throws IOException { try { finish(); } finally { Files.deleteIfExists(local); } }
    }
}
