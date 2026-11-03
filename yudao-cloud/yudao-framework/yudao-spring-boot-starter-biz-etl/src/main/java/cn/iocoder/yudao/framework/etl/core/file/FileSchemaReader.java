package cn.iocoder.yudao.framework.etl.core.file;

import cn.iocoder.yudao.framework.etl.core.executor.support.EtlExcelValues;
import org.apache.poi.ss.usermodel.*;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.*;
import java.math.*;
import java.nio.charset.*;
import java.util.*;

/** 有界文件字段采样；不返回数据行、不求值公式、不猜测字符串中的日期或数值。 */
public final class FileSchemaReader {
    public static final int SAMPLE_ROWS = 1000;
    public static final TabularFileReader.Limits LIMITS = new TabularFileReader.Limits(50L * 1024 * 1024, 10000, 256, 65536);
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16).maxStringLength(65536).maxNumberLength(256).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private FileSchemaReader() { }
    public record Field(String name, String dataType, Integer length, Integer precision, Integer scale, String path) { }
    public record Result(List<Field> fields, List<String> warnings) { }

    /** CSV没有类型声明，按UTF-8逗号分隔文本读取；保留前导零，不将文本猜测为数值或日期。 */
    public static Result csv(byte[] content) throws IOException {
        if (content.length == 0 || content.length > LIMITS.maxBytes())
            throw new IllegalArgumentException("CSV 文件大小超过限额或为空");
        try {
            List<String> names = TabularFileReader.readCsv(new ByteArrayInputStream(content),
                    TabularFileReader.CsvOptions.defaults(), LIMITS, (headers, values) -> { });
            if (names.stream().anyMatch(name -> name.isBlank() || name.length() > 256 || name.codePoints().anyMatch(Character::isISOControl)))
                throw new IllegalArgumentException("CSV 字段名称无效或超过256字符");
            return new Result(names.stream().map(name -> new Field(name, "STRING", null, null, null, null)).toList(),
                    List.of("CSV按UTF-8逗号分隔文本读取；文件未声明字段长度与精度，请确认属性字段类型和日期格式。"));
        } catch (IOException | IllegalArgumentException validation) { throw validation; }
        catch (Exception failure) { throw new IOException("CSV 格式无效或超过解析限额", failure); }
    }

    /** 起始行是首数据行，行列均从1开始；大于1时首行为标题，等于1时生成列名。 */
    public static Result excel(byte[] content, String extension, int startRow, int startColumn, String sheetName) throws IOException {
        if (startRow < 1 || startRow > LIMITS.maxRows() || startColumn < 1 || startColumn > LIMITS.maxColumns())
            throw new IllegalArgumentException("Excel 起始行列超过限额");
        try (Workbook workbook = TabularFileReader.openExcelWorkbook(content, extension, LIMITS)) {
            if (workbook.getNumberOfSheets() == 0) throw new IllegalArgumentException("Excel 没有工作表");
            Sheet sheet = sheetName == null || sheetName.isBlank() ? workbook.getSheetAt(0) : workbook.getSheet(sheetName);
            if (sheet == null) throw new IllegalArgumentException("Excel 工作表不存在");
            if (sheet.getLastRowNum() >= LIMITS.maxRows()) throw new IllegalArgumentException("Excel 超过10000行限额");
            boolean hasHeader = startRow > 1;
            Row header = sheet.getRow(0);
            if (header == null || header.getLastCellNum() < startColumn || header.getLastCellNum() > LIMITS.maxColumns())
                throw new IllegalArgumentException("Excel 标题范围无效或超过256列");
            List<String> names = new ArrayList<>();
            for (int c = startColumn - 1; c < header.getLastCellNum(); c++) {
                String name = hasHeader ? EtlExcelValues.read(header.getCell(c), EtlExcelValues.FormulaMode.LITERAL) : "column_" + (c + 1);
                if (name == null || name.isBlank() || name.length() > 256 || name.codePoints().anyMatch(Character::isISOControl) || names.contains(name))
                    throw new IllegalArgumentException("Excel 字段名不能为空、重复、包含控制字符或超过256字符");
                names.add(name);
            }
            List<Set<String>> types = names.stream().<Set<String>>map(_ -> new HashSet<String>()).toList();
            int firstDataRow = startRow - 1;
            int end = Math.min(sheet.getLastRowNum(), firstDataRow + SAMPLE_ROWS - 1);
            for (int r = firstDataRow; r <= end; r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                if (row.getLastCellNum() > header.getLastCellNum()) throw new IllegalArgumentException("Excel 数据字段超出标题范围");
                for (int c = startColumn - 1; c < header.getLastCellNum(); c++) {
                    Cell cell = row.getCell(c); String value = EtlExcelValues.read(cell, EtlExcelValues.FormulaMode.LITERAL);
                    if (value != null && value.length() > LIMITS.maxCellChars()) throw new IllegalArgumentException("Excel 单元格超过字符限额");
                    String type = excelType(cell, value);
                    if (type != null) types.get(c - startColumn + 1).add(type);
                }
            }
            var result = result(names, types, sheet.getLastRowNum() >= firstDataRow + SAMPLE_ROWS, false);
            if (hasHeader) return result;
            var warnings = new ArrayList<>(result.warnings());
            warnings.add("起始行为1，按无标题文件保留首行数据，字段名使用原始列位置生成。");
            return new Result(result.fields(), List.copyOf(warnings));
        }
    }

    /** 每个非空行必须是字段集合一致的JSON对象；拒绝重复键和尾随内容。 */
    public static Result jsonl(byte[] content) throws IOException {
        if (content.length == 0 || content.length > LIMITS.maxBytes()) throw new IllegalArgumentException("JSONL 文件大小超过限额或为空");
        var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        List<String> names = null; List<Set<String>> types = null; int row = 0; boolean truncated = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(content), decoder))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (++row > SAMPLE_ROWS) { truncated = true; break; }
                if (row == 1 && line.startsWith("\uFEFF")) line = line.substring(1);
                if (line.isBlank() || line.length() > LIMITS.maxCellChars()) throw new IllegalArgumentException("JSONL 不能有空行或超过65536字符的行");
                Object value;
                try { value = JSON.readValue(line, Object.class); }
                catch (RuntimeException malformed) { throw new IllegalArgumentException("JSONL 第" + row + "行格式无效"); }
                if (!(value instanceof Map<?, ?> object) || object.isEmpty() || object.size() > LIMITS.maxColumns())
                    throw new IllegalArgumentException("JSONL 每行必须是非空对象且不超过256字段");
                List<String> keys = object.keySet().stream().map(Object::toString).toList();
                if (keys.stream().anyMatch(name -> name.isBlank() || name.length() > 256 || name.codePoints().anyMatch(Character::isISOControl)))
                    throw new IllegalArgumentException("JSONL 字段名称无效");
                if (names == null) { names = keys; types = names.stream().<Set<String>>map(_ -> new HashSet<String>()).toList(); }
                if (!new HashSet<>(names).equals(object.keySet())) throw new IllegalArgumentException("JSONL 各行字段结构不一致");
                for (int c = 0; c < names.size(); c++) {
                    String type = jsonType(object.get(names.get(c)));
                    if (type != null) types.get(c).add(type);
                }
            }
        }
        if (names == null) throw new IllegalArgumentException("JSONL 没有可解析的记录");
        return result(names, types, truncated, true);
    }

    private static String excelType(Cell cell, String text) {
        if (cell == null || text == null) return null;
        return switch (cell.getCellType()) {
            case STRING -> "STRING";
            case BOOLEAN -> "BOOLEAN";
            case NUMERIC -> DateUtil.isCellDateFormatted(cell) ? "TIMESTAMP" : numberType(new BigDecimal(text));
            case FORMULA -> "UNKNOWN";
            default -> null;
        };
    }
    private static String jsonType(Object value) {
        if (value == null) return null;
        if (value instanceof String) return "STRING";
        if (value instanceof Boolean) return "BOOLEAN";
        if (value instanceof BigInteger integer) return integer.bitLength() < 64 ? "LONG" : "DECIMAL";
        if (value instanceof BigDecimal decimal) return numberType(decimal);
        return "UNKNOWN";
    }
    private static String numberType(BigDecimal number) {
        return number.scale() <= 0 && number.compareTo(BigDecimal.valueOf(Long.MIN_VALUE)) >= 0
                && number.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) <= 0 ? "LONG" : "DECIMAL";
    }
    private static Result result(List<String> names, List<Set<String>> types, boolean truncated, boolean paths) {
        List<Field> fields = new ArrayList<>(); List<String> warnings = new ArrayList<>();
        warnings.add("字段类型来自最多1000行的实际采样；文件没有声明数据库字段长度与精度，未知值保持为空，请确认字段配置。");
        if (truncated) warnings.add("仅检查前1000行，其余记录的结构和类型尚未确认。");
        for (int c = 0; c < names.size(); c++) {
            Set<String> candidates = types.get(c); String type = candidates.size() == 1 ? candidates.iterator().next() : null;
            if ("UNKNOWN".equals(type)) type = null;
            if (type == null) warnings.add("字段【" + names.get(c) + "】为空、类型混合或属于未支持结构，未推断类型。");
            fields.add(new Field(names.get(c), type, null, null, null,
                    paths ? "$['" + names.get(c).replace("\\", "\\\\").replace("'", "\\'") + "']" : null));
        }
        return new Result(List.copyOf(fields), List.copyOf(warnings));
    }
}
