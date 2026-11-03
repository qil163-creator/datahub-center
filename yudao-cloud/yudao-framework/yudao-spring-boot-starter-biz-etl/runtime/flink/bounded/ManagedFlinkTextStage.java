package cn.iocoder.yudao.framework.etl.flink.bounded;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/** 输出先暂存到单个有界本地文件，任务失败或取消时 close 不发布任何远端文件。 */
final class ManagedFlinkTextStage implements AutoCloseable {
    final Path file;
    private final JSONObject plan;
    private final Writer writer;
    private final String delimiter, marker;
    private final boolean csv;
    private final Map<String, String> formats = new HashMap<>();
    private boolean closed, header;
    private long rows;
    ManagedFlinkTextStage(JSONObject plan, boolean gzip) throws Exception {
        this.plan = plan; delimiter = plan.getStr("fieldDelimiter", ","); marker = plan.getStr("nullLiteral", "\\N"); csv = plan.getStr("fileType", "csv").equals("csv");
        if (delimiter.length() != 1 || delimiter.charAt(0) == '\n' || delimiter.charAt(0) == '\r' || marker.isEmpty() || marker.length() > 128)
            throw new IllegalArgumentException("输出文件分隔符或空值标记无效");
        file = Files.createTempFile("datahub-flink-bounded-", ".stage");
        OutputStream raw = Files.newOutputStream(file);
        try {
            long maximum = plan.getLong("maxBytes", 67_108_864L);
            if (maximum < 1 || maximum > 67_108_864) throw new IllegalArgumentException("输出文件字节预算无效");
            OutputStream bounded = new FilterOutputStream(raw) {
                long bytes;
                private void count(int value) throws IOException { if (value < 0 || (bytes += value) > maximum) throw new IOException("输出文件超过字节预算"); }
                @Override public void write(int value) throws IOException { count(1); out.write(value); }
                @Override public void write(byte[] value, int offset, int length) throws IOException { count(length); out.write(value, offset, length); }
            };
            OutputStream output = gzip ? new GZIPOutputStream(bounded) : bounded;
            var charset = Charset.forName(plan.getStr("encoding", "UTF-8")).newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
            writer = new BufferedWriter(new OutputStreamWriter(output, charset));
            for (Object value : plan.getJSONArray("columns")) {
                var column = JSONUtil.parseObj(value); String format = column.getStr("format", "");
                if (!format.isBlank() && !format.equals("-")) formats.put(column.getStr("name"), format);
            }
        } catch (Throwable failure) { raw.close(); Files.deleteIfExists(file); throw failure; }
    }
    void write(Map<String, String> values) throws Exception {
        if (closed || Thread.currentThread().isInterrupted() || ++rows > plan.getInt("maxRows", 100000)) throw new IOException("文件输出已结束、取消或超过行数预算");
        if (!header) { header = true; if (plan.getBool("includeHeader", false)) record(new ArrayList<>(values.keySet()), false); }
        var row = new ArrayList<String>();
        for (var field : values.entrySet()) {
            String value = field.getValue(), format = formats.get(field.getKey());
            if (value != null && format != null) {
                var formatter = DateTimeFormatter.ofPattern(format, Locale.ROOT);
                try { value = LocalDate.parse(value).format(formatter); }
                catch (java.time.format.DateTimeParseException ignored) { value = LocalDateTime.parse(value.replace(' ', 'T')).format(formatter); }
            }
            row.add(value);
        }
        record(row, true);
    }
    private void record(List<String> values, boolean nullable) throws IOException {
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) writer.write(delimiter);
            String value = values.get(index);
            if (value == null) { if (!nullable) throw new IOException("输出表头不允许空值"); writer.write(marker); continue; }
            if (value.equals(marker) || value.indexOf('\0') >= 0 || !csv && (value.isEmpty() || value.contains(delimiter) || value.contains("\n") || value.contains("\r")))
                throw new IOException("输出值与空值标记或分隔文本格式冲突");
            if (csv && (value.isEmpty() || value.contains(delimiter) || value.contains("\"") || value.contains("\n") || value.contains("\r"))) value = "\"" + value.replace("\"", "\"\"") + "\"";
            writer.write(value);
        }
        writer.write('\n');
    }
    void finish() throws Exception { if (!closed) { closed = true; writer.close(); } }
    @Override public void close() throws Exception { try { finish(); } finally { Files.deleteIfExists(file); } }
}
