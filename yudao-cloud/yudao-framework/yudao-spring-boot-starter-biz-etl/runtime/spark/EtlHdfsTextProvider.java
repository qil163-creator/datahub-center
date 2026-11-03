package cn.iocoder.yudao.framework.etl.spark;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.*;
import org.apache.hadoop.io.compress.*;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.connector.catalog.*;
import org.apache.spark.sql.connector.expressions.Transform;
import org.apache.spark.sql.connector.write.*;
import org.apache.spark.sql.connector.read.ScanBuilder;
import org.apache.spark.sql.execution.datasources.v2.csv.CSVDataSourceV2;
import org.apache.spark.sql.types.*;

import java.io.*;
import java.net.URI;
import java.nio.charset.*;
import java.util.*;

/** Java17 工作节点 HDFS 数据源：读取委托 Spark CSV，写入使用新文件和批次暂存提交。 */
public final class EtlHdfsTextProvider implements TableProvider {
    @Override public StructType inferSchema(org.apache.spark.sql.util.CaseInsensitiveStringMap options) {
        throw new IllegalArgumentException("HDFS 受管连接器必须声明完整字段类型");
    }
    @Override public boolean supportsExternalMetadata() { return true; }
    @Override public Table getTable(StructType schema, Transform[] transforms, Map<String, String> input) {
        if (schema == null || schema.isEmpty()) throw new IllegalArgumentException("HDFS 字段结构缺失");
        var options = new LinkedHashMap<String, String>(); input.forEach((key, value) -> options.put(key.toLowerCase(Locale.ROOT), value));
        Configuration conf = configuration(options);
        Path path = new Path(Objects.requireNonNull(options.get("path"), "HDFS path"));
        authorize(path, options, conf, "write".equals(options.get("datahub.mode")));
        if (!"write".equals(options.get("datahub.mode"))) {
            var csv = new LinkedHashMap<>(options); csv.keySet().removeIf(key -> key.startsWith("datahub."));
            options.forEach((key, value) -> { if (key.startsWith("datahub.hadoop.")) csv.put(key.substring(15), value); });
            return new CSVDataSourceV2().getTable(schema, transforms, csv);
        }
        return new SinkTable(schema, options, xml(conf));
    }

    private static final class SinkTable implements Table, SupportsWrite, SupportsRead {
        private final StructType schema; private final Map<String, String> options; private final String conf;
        SinkTable(StructType schema, Map<String, String> options, String conf) { this.schema = schema; this.options = Map.copyOf(options); this.conf = conf; }
        @Override public String name() { return "受管 HDFS 新文件输出"; }
        @Override public StructType schema() { return schema; }
        // Spark 注册临时数据源视图时要求读接口；输出表实际扫描仍明确拒绝，写入由独立批次接口处理。
        @Override public Set<TableCapability> capabilities() { return Set.of(TableCapability.BATCH_READ, TableCapability.BATCH_WRITE); }
        @Override public ScanBuilder newScanBuilder(org.apache.spark.sql.util.CaseInsensitiveStringMap ignored) {
            throw new IllegalArgumentException("HDFS 输出视图仅用于写入，请通过受管输入组件读取文件");
        }
        @Override public WriteBuilder newWriteBuilder(LogicalWriteInfo info) {
            String job = UUID.randomUUID().toString();
            return new WriteBuilder() {
                @Override public BatchWrite buildForBatch() {
                    return new BatchWrite() {
                        @Override public DataWriterFactory createBatchWriterFactory(PhysicalWriteInfo ignored) { return new Factory(info.schema(), options, conf, job); }
                        @Override public void commit(WriterCommitMessage[] messages) {
                            var published = new ArrayList<Path>();
                            try (FileSystem fs = FileSystem.newInstance(URI.create(options.get("path")), fromXml(conf))) {
                                FileContext context = FileContext.getFileContext(fs.getUri(), fromXml(conf));
                                for (var message : messages) {
                                    Part part = (Part) message;
                                    context.rename(new Path(part.temporary()), new Path(part.destination()), Options.Rename.NONE);
                                    published.add(new Path(part.destination()));
                                }
                                fs.delete(stage(options, job), true);
                            } catch (IOException failure) { throw new IllegalStateException("HDFS 新文件提交失败，已发布文件数：" + published.size(), failure); }
                        }
                        @Override public void abort(WriterCommitMessage[] messages) {
                            try (FileSystem fs = FileSystem.newInstance(URI.create(options.get("path")), fromXml(conf))) { fs.delete(stage(options, job), true); }
                            catch (IOException failure) { throw new IllegalStateException("HDFS 暂存批次清理失败", failure); }
                        }
                    };
                }
            };
        }
    }

    private record Part(String temporary, String destination) implements WriterCommitMessage { }
    private static final class Factory implements DataWriterFactory {
        private final StructType schema; private final Map<String, String> options; private final String configuration; private final String job;
        Factory(StructType schema, Map<String, String> options, String configuration, String job) {
            this.schema = schema; this.options = options; this.configuration = configuration; this.job = job;
        }
        @Override public DataWriter<InternalRow> createWriter(int partition, long attempt) { return new Writer(schema, options, configuration, job, partition, attempt); }
    }

    private static final class Writer implements DataWriter<InternalRow> {
        private final StructType schema; private final Map<String, String> options;
        private final FileSystem fs; private final Path temporary; private final Path destination;
        private final java.io.Writer writer; private boolean closed;
        Writer(StructType schema, Map<String, String> options, String configuration, String job, int partition, long attempt) {
            this.schema = schema; this.options = options;
            try {
                Configuration conf = fromXml(configuration); Path path = new Path(options.get("path")); authorize(path, options, conf, true);
                fs = FileSystem.newInstance(path.toUri(), conf); fs.mkdirs(path); fs.mkdirs(stage(options, job));
                String suffix = "csv".equals(options.get("datahub.filetype")) ? ".csv" : ".txt";
                CompressionCodec codec = codec(options.getOrDefault("compression", ""), conf);
                String file = options.get("datahub.filename") + "-" + job + "-" + partition + "-" + attempt + suffix + (codec == null ? "" : codec.getDefaultExtension());
                temporary = new Path(stage(options, job), file); destination = new Path(path, file);
                OutputStream raw = fs.create(temporary, false);
                try {
                    OutputStream output = codec == null ? raw : codec.createOutputStream(raw);
                    var encoder = Charset.forName(options.getOrDefault("encoding", "UTF-8")).newEncoder()
                            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
                    writer = new BufferedWriter(new OutputStreamWriter(output, encoder));
                } catch (Throwable failure) { raw.close(); fs.close(); throw failure; }
            } catch (Exception failure) { throw new IllegalStateException("HDFS 新文件写入初始化失败", failure); }
        }
        @Override public void write(InternalRow row) throws IOException {
            String delimiter = options.get("delimiter");
            for (int index = 0; index < schema.size(); index++) {
                if (index > 0) writer.write(delimiter);
                String value = row.isNullAt(index) ? options.getOrDefault("nullvalue", "") : EtlSparkRowValues.format(row, schema.fields()[index], index, options);
                if (value.indexOf('\0') >= 0) throw new IOException("HDFS 字符串不能包含 NUL");
                String nullMarker = options.getOrDefault("nullvalue", "");
                if (!row.isNullAt(index) && !nullMarker.isEmpty() && value.equals(nullMarker))
                    throw new IOException("HDFS 字段值与 NULL 标识冲突，不能无损写入");
                if (!"csv".equals(options.get("datahub.filetype")) && (value.contains(delimiter) || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0
                        || !row.isNullAt(index) && value.isEmpty()))
                    throw new IOException("HDFS 分隔文本不能无损表示分隔符、换行或非空字段的空字符串，请使用 CSV");
                if ("csv".equals(options.get("datahub.filetype")) && (!row.isNullAt(index) && value.isEmpty()
                        || value.contains(delimiter) || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0))
                    value = "\"" + value.replace("\"", "\"\"") + "\"";
                writer.write(value);
            }
            writer.write('\n');
        }
        @Override public WriterCommitMessage commit() throws IOException { finish(); return new Part(temporary.toString(), destination.toString()); }
        @Override public void abort() throws IOException { finish(); fs.delete(temporary, false); }
        private void finish() throws IOException { if (!closed) { closed = true; writer.close(); } }
        @Override public void close() throws IOException { try { finish(); } finally { fs.close(); } }
    }

    private static Configuration configuration(Map<String, String> options) {
        var conf = new Configuration(SparkSession.active().sparkContext().hadoopConfiguration());
        options.forEach((key, value) -> { if (key.startsWith("datahub.hadoop.")) conf.set(key.substring(15), value); }); return conf;
    }
    private static String xml(Configuration conf) {
        try { var writer = new StringWriter(); conf.writeXml(writer); return writer.toString(); }
        catch (IOException failure) { throw new IllegalStateException(failure); }
    }
    private static Configuration fromXml(String value) {
        var conf = new Configuration(false); conf.addResource(new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8))); return conf;
    }
    private static Path stage(Map<String, String> options, String job) { return new Path(options.get("path"), ".datahub-stage-" + job); }
    private static CompressionCodec codec(String name, Configuration conf) throws ReflectiveOperationException {
        String implementation = switch (name) {
            case "", "none" -> null; case "gzip" -> GzipCodec.class.getName(); case "bzip2" -> BZip2Codec.class.getName();
            case "snappy" -> SnappyCodec.class.getName(); case "lzo" -> "com.hadoop.compression.lzo.LzopCodec";
            default -> throw new IllegalArgumentException("HDFS 压缩格式未知");
        };
        if (implementation == null) return null;
        var value = (CompressionCodec) Class.forName(implementation).getDeclaredConstructor().newInstance();
        if (value instanceof org.apache.hadoop.conf.Configurable configurable) configurable.setConf(conf); return value;
    }
    private static void authorize(Path path, Map<String, String> options, Configuration conf, boolean output) {
        URI root = URI.create(Objects.requireNonNull(options.get("datahub.root"), "HDFS root"));
        if (!"hdfs".equals(root.getScheme()) || root.getRawQuery() != null || root.getRawFragment() != null || root.getUserInfo() != null)
            throw new IllegalArgumentException("HDFS 根目录必须为明确的无凭据 HDFS URI");
        String allowlist = System.getenv("DATAHUB_ETL_HDFS_ROOTS");
        if (allowlist == null || Arrays.stream(allowlist.split(";", -1)).noneMatch(value -> value.equals(root.toString())))
            throw new IllegalArgumentException("工作节点未授权此 HDFS 根目录");
        checkWithin(path.toUri(), root);
        try (FileSystem fs = FileSystem.newInstance(path.toUri(), conf)) {
            Path existing = path;
            while (!fs.exists(existing) && existing.getParent() != null) existing = existing.getParent();
            checkWithin(fs.resolvePath(existing).toUri(), root);
            if (!output && !fs.exists(path)) throw new IllegalArgumentException("HDFS 输入路径不存在");
        } catch (IOException failure) { throw new IllegalStateException("HDFS 物理路径校验失败", failure); }
    }
    private static void checkWithin(URI path, URI root) {
        String relative = path.getPath(); String prefix = root.getPath().replaceAll("/+$", "");
        if (!Objects.equals(path.getScheme(), root.getScheme()) || !Objects.equals(path.getAuthority(), root.getAuthority())
                || relative == null || !relative.equals(prefix) && !relative.startsWith(prefix + "/")
                || Arrays.stream(relative.split("/", -1)).anyMatch(part -> part.equals(".") || part.equals("..")))
            throw new IllegalArgumentException("HDFS 路径超出受管根目录");
    }
}
