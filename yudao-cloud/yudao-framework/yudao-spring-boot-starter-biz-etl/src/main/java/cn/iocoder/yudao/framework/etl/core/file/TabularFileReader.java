package cn.iocoder.yudao.framework.etl.core.file;

import cn.hutool.core.text.csv.CsvParser;
import cn.hutool.core.text.csv.CsvReadConfig;
import cn.hutool.core.text.csv.CsvRow;
import cn.iocoder.yudao.framework.etl.core.executor.support.EtlExcelValues;
import cn.iocoder.yudao.framework.etl.core.executor.support.EtlFileInput;
import cn.iocoder.yudao.framework.etl.core.executor.support.EtlTextValues;
import org.apache.poi.ss.usermodel.Workbook;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 有界表格读取；流由调用方持有，单元格以精确文本交付，公式不会求值。 */
public final class TabularFileReader {

    /**
     * 初始化 {@code TabularFileReader}。
     */
    private TabularFileReader() {
    }

    public record Limits(long maxBytes, int maxRows, int maxColumns, int maxCellChars) {

        /**
         * 校验构造参数并初始化 {@code Limits}。
         */
        public Limits {
            if (maxBytes < 1 || maxBytes > Integer.MAX_VALUE - 8L || maxRows < 1 || maxColumns < 1 || maxCellChars < 1) {
                throw new IllegalArgumentException("表格读取限额无效");
            }
        }

        /**
         * 创建当前组件的默认配置。
         */
        public static Limits defaults() {
            return new Limits(20L * 1024 * 1024, 100_000, 256, 1024 * 1024);
        }

        /**
         * 计算最大总量单元格集合。
         */
        public long maxTotalCells() {
            return Math.max(maxColumns, maxBytes / 16);
        }

        /**
         * 返回最大值字节数。
         */
        public long maxDecodedChars() {
            return maxBytes;
        }

        /**
         * 计算最大展开字节数。
         */
        public long maxExpandedBytes() {
            return maxBytes * 4;
        }
    }

    public record Table(List<String> headers, List<List<String>> rows) {

    }

    public record CsvOptions(Charset charset, char delimiter, boolean hasHeader, boolean skipEmptyRows,
                             boolean typedValues) {

        /**
         * 创建当前组件的默认配置。
         */
        public static CsvOptions defaults() {
            return new CsvOptions(StandardCharsets.UTF_8, ',', true, false, false);
        }
    }

    public record ExcelOptions(String sheetName, int headerRow, int startRow, int endRow, boolean skipEmptyRows,
                               EtlExcelValues.FormulaMode formulaMode) {

        /**
         * 校验构造参数并初始化 {@code ExcelOptions}。
         */
        public ExcelOptions {
            if (headerRow < 0 || startRow < 1 || endRow < 0 || endRow > 0 && endRow < startRow) {
                throw new IllegalArgumentException("Excel 行范围无效");
            }
            Objects.requireNonNull(formulaMode);
        }

        /**
         * 创建当前组件的默认配置。
         */
        public static ExcelOptions defaults() {
            return new ExcelOptions(null, 1, 2, 0, false, EtlExcelValues.FormulaMode.LITERAL);
        }
    }

    @FunctionalInterface
    public interface RowConsumer {

        /**
         * 接收当前输入并更新当前处理器的状态。
         */
        void accept(List<String> headers, List<String> values) throws Exception;
    }

    /**
     * 按文件格式和读取配置解析表格数据。
     */
    public static Table read(byte[] content, String extension, Limits limits) throws Exception {
        Objects.requireNonNull(content);
        if (content.length > limits.maxBytes()) {
            throw new IllegalArgumentException("文件超过字节限额");
        }
        return read(new ByteArrayInputStream(content), extension, limits);
    }

    /**
     * 按文件格式和读取配置解析表格数据。
     */
    public static Table read(InputStream source, String extension, Limits limits) throws Exception {
        List<List<String>> rows = new ArrayList<>();
        String format = extension.toLowerCase(Locale.ROOT).replaceFirst("^\\.", "");
        RowConsumer sink = (headers, values) -> rows.add(Collections.unmodifiableList(new ArrayList<>(values)));
        List<String> headers = switch (format) {
            case "csv" -> readCsv(source, CsvOptions.defaults(), limits, sink);
            case "xls", "xlsx" -> readExcel(source, format, ExcelOptions.defaults(), limits, sink);
            default -> throw new IllegalArgumentException("仅支持 CSV、XLS 和 XLSX 文件");
        };
        return new Table(headers, List.copyOf(rows));
    }

    /**
     * 读取CSV。
     */
    public static List<String> readCsv(InputStream source, CsvOptions options, Limits limits,
            RowConsumer sink) throws Exception {
        var config = new CsvReadConfig().setFieldSeparator(options.delimiter())
                .setSkipEmptyRows(options.skipEmptyRows());
        // 关闭解析器只关闭包装器，不接管调用方输入流的生命周期。
        try (var reader = new CsvBudgetReader(csvReader(source, options.charset(), limits.maxBytes()), options, limits);
             var parser = new CsvParser(reader, config)) {
            var budget = new Budget(limits);
            List<String> headers = null;
            CsvRow row;
            while ((row = parser.nextRow()) != null) {
                List<String> values = new ArrayList<>(row.getRawList());
                if (headers == null) {
                    if (!values.isEmpty()) {
                        values.set(0, stripBom(values.getFirst()));
                    }
                    headers = headers(values, options.hasHeader(), options.typedValues(), !options.typedValues(),
                            budget);
                    if (options.hasHeader()) {
                        continue;
                    }
                }
                if (values.size() != headers.size()) {
                    throw new IllegalArgumentException("CSV 字段数量与表头不一致");
                }
                if (options.typedValues()) {
                    for (int i = 0; i < values.size(); i++) {
                        values.set(i, (String) EtlTextValues.decode(values.get(i)));
                    }
                }
                budget.row(values);
                sink.accept(headers, values);
            }
            if (headers == null) {
                throw new IllegalArgumentException("文件没有可解析的表头");
            }
            return headers;
        }
    }

    /**
     * 读取Excel。
     */
    public static List<String> readExcel(InputStream source, String extension, ExcelOptions options, Limits limits,
            RowConsumer sink) throws Exception {
        return EtlBoundedExcelReader.readExcel(source, extension,
                new EtlBoundedExcelReader.Options(options.sheetName(), options.headerRow(), options.startRow(), options.endRow(),
                        options.skipEmptyRows(), options.formulaMode() == EtlExcelValues.FormulaMode.CACHED),
                excelLimits(limits), sink::accept);
    }

    /** 共享工作节点同一全量解析与资源预算，不改变现有调用方的输入流所有权。 */
    static Workbook openExcelWorkbook(byte[] content, String extension, Limits limits) throws IOException {
        return EtlBoundedExcelReader.openExcelWorkbook(content, extension, excelLimits(limits));
    }

    private static EtlBoundedExcelReader.Limits excelLimits(Limits limits) {
        return new EtlBoundedExcelReader.Limits(limits.maxBytes(), limits.maxRows(), limits.maxColumns(), limits.maxCellChars());
    }

    /**
     * 返回表头集合。
     */
    private static List<String> headers(List<String> initial, boolean hasHeader, boolean typedValues,
            boolean generateBlankHeaders, Budget budget) {
        if (initial.isEmpty() || initial.size() > budget.limits.maxColumns()) {
            throw new IllegalArgumentException("文件表头为空或超过列数限额");
        }
        List<String> headers = new ArrayList<>();
        for (int i = 0; i < initial.size(); i++) {
            String value = hasHeader ? initial.get(i) : null;
            if (i == 0) {
                value = stripBom(value);
            }
            if (hasHeader && typedValues) {
                value = (String) EtlTextValues.decode(value);
                if (value == null || value.isEmpty()) {
                    throw new IllegalArgumentException("CSV 表头不能为空");
                }
            }
            // RAW CSV 将空白标题视为空；编码 CSV 和 Excel 的空格标题属于精确文本契约。
            headers.add(value == null || value.isEmpty() || generateBlankHeaders && value.isBlank()
                    ? columnLetter(i) : value);
        }
        budget.cells(headers);
        if (new HashSet<>(headers).size() != headers.size()) {
            throw new IllegalArgumentException("文件表头必须唯一");
        }
        return List.copyOf(headers);
    }

    /**
     * 去除BOM。
     */
    private static String stripBom(String value) {
        return value != null && value.startsWith("\uFEFF") ? value.substring(1) : value;
    }

    /**
     * 将零起始列序号转换为 Excel 字母列标。
     */
    private static String columnLetter(int index) {
        StringBuilder result = new StringBuilder();
        do {
            result.insert(0, (char) ('A' + index % 26));
            index = index / 26 - 1;
        } while (index >= 0);
        return result.toString();
    }

    /**
     * 创建保留底层输入流所有权的有界读取包装。
     */
    private static InputStream nonClosingBounded(InputStream source, long limit) {
        return EtlFileInput.bounded(new FilterInputStream(source) {
            /**
             * 关闭当前读取器及其持有的输入资源。
             */
            @Override
            public void close() {
            }
        }, limit);
    }

    /**
     * 创建遇到非法字符时报告错误的 CSV 输入读取器。
     */
    private static PushbackReader csvReader(InputStream source, Charset charset, long limit) throws IOException {
        var decoder = charset.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
        var reader = new PushbackReader(
                new BufferedReader(new InputStreamReader(nonClosingBounded(source, limit), decoder)), 1);
        int first = reader.read();
        if (first != -1 && first != '\uFEFF') {
            reader.unread(first);
        }
        return reader;
    }

    /** Hutool 负责 CSV 解码；此包装器只在解析器分配字段集合之前施加列数与单字段预算。 */
    private static final class CsvBudgetReader extends FilterReader {

        private final char delimiter;

        private final int maxColumns;

        private final long maxCellChars;

        private int columns = 1;

        private long cellChars;

        private boolean quoted, quotePending, comment;

        private boolean fieldStart = true, lineStart = true;

        /**
         * 初始化 {@code CsvBudgetReader}及其父类状态。
         */
        private CsvBudgetReader(Reader source, CsvOptions options, Limits limits) {
            super(source);
            delimiter = options.delimiter();
            maxColumns = limits.maxColumns();
            // 编码 CSV 的公式/字面量保护前缀最多增加两个字符；精确解码后的限额仍由 Budget 校验。
            maxCellChars = (long) limits.maxCellChars() + (options.typedValues() ? 2 : 0);
        }

        /**
         * 按文件格式和读取配置解析表格数据。
         */
        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                count((char) value);
            }
            return value;
        }

        /**
         * 按文件格式和读取配置解析表格数据。
         */
        @Override
        public int read(char[] buffer, int offset, int length) throws IOException {
            int size = super.read(buffer, offset, length);
            for (int i = offset; i < offset + size; i++) {
                count(buffer[i]);
            }
            return size;
        }

        /**
         * 返回数量。
         */
        private void count(char value) throws IOException {
            if (comment) {
                if (value == '\r' || value == '\n') {
                    comment = false;
                    lineStart = true;
                    fieldStart = true;
                }
                return;
            }
            if (quoted) {
                if (quotePending) {
                    quotePending = false;
                    if (value == '"') {
                        character();
                        return;
                    }
                    quoted = false;
                } else {
                    if (value == '"') {
                        quotePending = true;
                    } else {
                        character();
                    }
                    return;
                }
            }
            // 与 CsvReadConfig 的默认行首注释约定一致，不解释注释中的分隔符和引号。
            if (lineStart && value == '#') {
                comment = true;
                return;
            }
            if (fieldStart && value == '"') {
                quoted = true;
                fieldStart = false;
                lineStart = false;
                return;
            }
            if (value == delimiter) {
                if (++columns > maxColumns) {
                    throw new IllegalArgumentException("CSV 超过列数限额");
                }
                cellChars = 0;
                fieldStart = true;
                lineStart = false;
            } else if (value == '\r' || value == '\n') {
                columns = 1;
                cellChars = 0;
                fieldStart = true;
                lineStart = true;
            } else {
                character();
                fieldStart = false;
                lineStart = false;
            }
        }

        /**
         * 按当前值域约定解析或转换字符。
         */
        private void character() throws IOException {
            if (++cellChars > maxCellChars) {
                throw new IllegalArgumentException("CSV 单元格内容超过限额");
            }
        }
    }

    private static final class Budget {

        private final Limits limits;

        private long chars;

        private long cellCount;

        private int rows;

        /**
         * 初始化 {@code Budget}。
         */
        private Budget(Limits limits) {
            this.limits = limits;
        }

        /**
         * 返回数据行。
         */
        private void row(List<String> values) throws InterruptedException {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("表格读取已取消");
            }
            if (++rows > limits.maxRows()) {
                throw new IllegalArgumentException("文件超过行数限额");
            }
            cells(values);
        }

        /**
         * 累计并检查表格数据的列数及单元格数量预算。
         */
        private void cells(List<String> values) {
            if (values.size() > limits.maxColumns()) {
                throw new IllegalArgumentException("文件超过列数限额");
            }
            cellCount += values.size();
            if (cellCount > limits.maxTotalCells()) {
                throw new IllegalArgumentException("表格单元格总数超过内存预算");
            }
            for (String value : values) {
                if (value == null) {
                    continue;
                }
                if (value.length() > limits.maxCellChars()) {
                    throw new IllegalArgumentException("单元格内容超过限额");
                }
                chars += value.length();
                if (chars > limits.maxDecodedChars()) {
                    throw new IllegalArgumentException("表格内容超过内存预算");
                }
            }
        }
    }
}
