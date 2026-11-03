package cn.iocoder.yudao.framework.etl.flink.bounded;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.etl.core.file.*;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.core.JsonParser;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.*;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.*;
import java.time.temporal.ChronoField;
import java.util.*;
import java.util.function.Consumer;

/** Flink 有界文件读取复用工作节点根目录授权和真实文件解析，不读取控制面上传附件。 */
final class ManagedFlinkFileRows {
    private ManagedFlinkFileRows() { }
    static void read(JSONObject plan, Consumer<String> rows) throws Exception {
        var roots = roots();
        var fields = plan.getJSONArray("fields");
        if (fields == null || fields.isEmpty() || fields.size() > 1024) throw new IllegalArgumentException("文件字段合同缺失");
        if ("EXCEL".equals(plan.getStr("fileFormat"))) {
            var options = new EtlBoundedExcelReader.Options(plan.getStr("sheetName", ""), plan.getInt("headerRow", 1),
                    plan.getInt("startRow", 2), plan.getInt("endRow", 0), plan.getBool("skipEmptyRows", true), "CACHED".equals(plan.getStr("formulaMode", "LITERAL")));
            EtlManagedExcelInput.read(roots, plan.getStr("rootPath"), plan.getStr("relativePath"), options, plan.getInt("startColumn", 1), fields.size(),
                    EtlBoundedExcelReader.Limits.defaults(), (headers, values) -> emit(plan, values, null, rows));
            return;
        }
        EtlManagedLocalInput.read(roots, plan.getStr("rootPath"), plan.getStr("relativePath"), plan.getLong("maxBytes", 52_428_800L), source -> {
            var charset = Charset.forName(plan.getStr("encoding", "UTF-8")).newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
            var reader = new BufferedReader(new InputStreamReader(source, charset));
            if ("CSV".equals(plan.getStr("fileFormat"))) {
                boolean[] first = {true}; int[] count = {0};
                String delimiter = plan.getStr("delimiter", ","), quote = plan.getStr("quote", "\"");
                EtlDelimitedRows.read(reader, delimiter.charAt(0), quote.isEmpty() ? 0 : quote.charAt(0), plan.getStr("escape", "\"").charAt(0),
                        plan.getBool("multiLine", true), 1_000_001, 1024, 1_048_576, cells -> {
                            if (cells.size() == 1 && cells.get(0).value().isEmpty() && !cells.get(0).quoted()) return;
                            var values = new ArrayList<String>(); var quoted = new ArrayList<Boolean>();
                            for (var cell : cells) { values.add(cell.value()); quoted.add(cell.quoted()); }
                            if (first[0]) {
                                first[0] = false;
                                if (values.get(0).startsWith("\uFEFF")) values.set(0, values.get(0).substring(1));
                                if (plan.getBool("hasHeader", false)) {
                                    var expected = fields.stream().map(JSONUtil::parseObj).map(field -> field.getStr("source")).toList();
                                    if (!expected.equals(values)) throw new IllegalArgumentException("CSV 表头与完整源字段合同不一致");
                                    return;
                                }
                            }
                            if (++count[0] > plan.getInt("maxRows", 100_000)) throw new IllegalArgumentException("文件读取行数超过预算");
                            emit(plan, values, quoted, rows);
                        });
            } else if ("JSONL".equals(plan.getStr("fileFormat"))) {
                var mapper = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
                int count = 0; boolean first = true;
                for (String line; (line = reader.readLine()) != null;) {
                    if (Thread.currentThread().isInterrupted() || line.length() > 1_048_576) throw new IllegalArgumentException("JSONL 已取消或单行超过预算");
                    if (first && line.startsWith("\uFEFF")) line = line.substring(1); first = false;
                    if (line.isBlank()) continue;
                    if (++count > plan.getInt("maxRows", 100_000)) throw new IllegalArgumentException("JSONL 行数超过预算");
                    JsonNode input = mapper.readTree(line); if (input == null || !input.isObject()) throw new IllegalArgumentException("JSONL 每行必须是独立对象");
                    var output = mapper.createObjectNode();
                    for (Object raw : fields) {
                        var field = JSONUtil.parseObj(raw); JsonNode value = input.get(field.getStr("source"));
                        if (value == null) throw new IllegalArgumentException("JSONL 缺少已声明字段");
                        output.set(field.getStr("target"), value);
                    }
                    rows.accept(output.toString());
                }
            } else throw new IllegalArgumentException("文件类型无效");
        });
    }
    private static void emit(JSONObject plan, List<String> values, List<Boolean> quoted, Consumer<String> rows) {
        var fields = plan.getJSONArray("fields");
        if (fields.size() != values.size()) throw new IllegalArgumentException("文件字段数量与完整声明不一致");
        var row = JSONUtil.createObj();
        int start = "EXCEL".equals(plan.getStr("fileFormat")) ? 0 : plan.getInt("startColumn", 1) - 1;
        for (int index = start; index < fields.size(); index++) {
            var field = JSONUtil.parseObj(fields.get(index)); String value = values.get(index);
            if (quoted != null && !quoted.get(index) && Objects.equals(value, plan.getStr("nullValue", "\\N"))) value = null;
            String type = field.getStr("type");
            value = normalize(type, value, plan.getStr("dateFormat", ""), plan.getStr("timestampFormat", ""));
            row.set(field.getStr("target"), value);
        }
        rows.accept(row.toString());
    }
    static String normalize(String type, String value, String dateFormat, String timestampFormat) {
        if (value == null || !Set.of("DATE", "TIMESTAMP").contains(type)) return value;
        String format = type.equals("DATE") ? dateFormat : timestampFormat;
        if (format.isBlank()) return value;
        var formatter = new DateTimeFormatterBuilder().appendPattern(format).parseDefaulting(ChronoField.ERA, 1).toFormatter(Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);
        try { return type.equals("DATE") ? LocalDate.parse(value).toString() : LocalDateTime.parse(value.replace(' ', 'T')).toString(); }
        catch (DateTimeParseException ignored) { return type.equals("DATE") ? LocalDate.parse(value, formatter).toString() : LocalDateTime.parse(value, formatter).toString(); }
    }
    private static Set<Path> roots() throws Exception {
        String setting = System.getenv("DATAHUB_ETL_FILE_ROOTS_FILE");
        Path configuration = Path.of(setting == null || setting.isBlank() ? "/opt/datahub-etl-excel/roots.txt" : setting);
        if (!Files.isRegularFile(configuration, LinkOption.NOFOLLOW_LINKS) || Files.size(configuration) > 65_536) throw new IllegalArgumentException("工作节点尚未声明文件根目录授权");
        var roots = new HashSet<Path>();
        for (String value : Files.readAllLines(configuration)) if (!value.isBlank()) roots.add(Path.of(value).toAbsolutePath().normalize());
        return roots;
    }
}
