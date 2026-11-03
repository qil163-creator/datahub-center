package cn.iocoder.yudao.framework.etl.core.file;

import cn.iocoder.yudao.framework.etl.core.executor.support.EtlExcelCellReader;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.*;
import java.util.*;
import java.util.zip.ZipInputStream;

/** 跨 Java17 工作节点复用的全量有界 Excel 读取；公式不求值，输入流由调用方关闭。 */
public final class EtlBoundedExcelReader {
    private EtlBoundedExcelReader() { }
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
            return new Limits(50L * 1024 * 1024, 100_000, 256, 1024 * 1024);
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


    public record Options(String sheetName, int headerRow, int startRow, int endRow,
                          boolean skipEmptyRows, boolean cachedFormulas) {
        public Options {
            if (headerRow < 0 || startRow < 1 || endRow < 0 || endRow > 0 && endRow < startRow)
                throw new IllegalArgumentException("Excel 行范围无效");
        }
    }
    @FunctionalInterface public interface RowConsumer {
        void accept(List<String> headers, List<String> values) throws Exception;
    }
    public static List<String> readExcel(InputStream source, String extension, Options options, Limits limits,
            RowConsumer sink) throws Exception {
        if (!List.of("xls", "xlsx").contains(extension.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Excel 文件类型无效");
        }
        byte[] content = source.readNBytes((int) limits.maxBytes() + 1);
        try (Workbook workbook = openExcelWorkbook(content, extension, limits)) {
            if (workbook.getNumberOfSheets() == 0) {
                throw new IllegalArgumentException("Excel 没有工作表");
            }
            Sheet sheet = options.sheetName() == null || options.sheetName().isBlank()
                    ? workbook.getSheetAt(0) : workbook.getSheet(options.sheetName());
            if (sheet == null) {
                throw new IllegalArgumentException("Excel 工作表不存在");
            }
            var budget = new Budget(limits);
            Row header = sheet.getRow(options.headerRow() > 0 ? options.headerRow() - 1 : sheet.getFirstRowNum());
            if (header == null || header.getLastCellNum() < 1) {
                throw new IllegalArgumentException("Excel 标题行为空");
            }
            if (header.getLastCellNum() > limits.maxColumns()) {
                throw new IllegalArgumentException("文件超过列数限额");
            }
            List<String> initial = new ArrayList<>();
            for (int i = 0; i < header.getLastCellNum(); i++) {
                initial.add(EtlExcelCellReader.read(header.getCell(i), options.cachedFormulas()));
            }
            List<String> headers = headers(initial, options.headerRow() > 0, budget);
            int end = options.endRow() > 0 ? Math.min(options.endRow() - 1,
                    sheet.getLastRowNum()) : sheet.getLastRowNum();
            int start = Math.max(options.startRow() - 1, sheet.getFirstRowNum());
            // 稀疏文件也按扫描范围限制，防止通过大量空行规避资源预算。
            if ((long) end - start + 1 > limits.maxRows()) {
                throw new IllegalArgumentException("文件超过行数限额");
            }
            for (int index = start; index <= end; index++) {
                Row row = sheet.getRow(index);
                if (row != null && row.getLastCellNum() > headers.size()) {
                    throw new IllegalArgumentException("Excel 数据列超出表头范围");
                }
                List<String> values = new ArrayList<>();
                for (int i = 0; i < headers.size(); i++) {
                    values.add(EtlExcelCellReader.read(row == null ? null : row.getCell(i), options.cachedFormulas()));
                }
                if (options.skipEmptyRows() && values.stream().allMatch(Objects::isNull)) {
                    continue;
                }
                budget.row(values);
                sink.accept(headers, values);
            }
            return headers;
        }
    }

    /** 共享现有压缩、二进制工作簿和字节预算；返回的工作簿由当前调用方关闭。 */
    public static Workbook openExcelWorkbook(byte[] content, String extension, Limits limits) throws IOException {
        if (content.length == 0 || content.length > limits.maxBytes()) throw new IllegalArgumentException("Excel 文件大小超过限额或为空");
        if ("xlsx".equalsIgnoreCase(extension)) {
            validateExpandedArchive(content, limits);
            return new XSSFWorkbook(new ByteArrayInputStream(content));
        }
        if ("xls".equalsIgnoreCase(extension)) {
            validateBinaryWorkbook(content, limits);
            return new HSSFWorkbook(new ByteArrayInputStream(content));
        }
        throw new IllegalArgumentException("仅支持 XLS 和 XLSX");
    }


    private static List<String> headers(List<String> initial, boolean hasHeader, Budget budget) {
        if (initial.isEmpty() || initial.size() > budget.limits.maxColumns()) throw new IllegalArgumentException("文件表头为空或超过列数限额");
        List<String> headers = new ArrayList<>();
        for (int index = 0; index < initial.size(); index++) {
            String value = hasHeader ? initial.get(index) : null;
            if (index == 0 && value != null && value.startsWith("\uFEFF")) value = value.substring(1);
            headers.add(value == null || value.isEmpty() ? columnLetter(index) : value);
        }
        budget.cells(headers);
        if (new HashSet<>(headers).size() != headers.size()) throw new IllegalArgumentException("文件表头必须唯一");
        return List.copyOf(headers);
    }
    private static String columnLetter(int index) {
        StringBuilder result = new StringBuilder();
        do { result.insert(0, (char) ('A' + index % 26)); index = index / 26 - 1; } while (index >= 0);
        return result.toString();
    }
    /** 使用 POI BIFF 记录和事件读取器，在构造 HSSFWorkbook 用户模型前检查资源预算。 */
    private static void validateBinaryWorkbook(byte[] bytes, Limits limits) throws IOException {
        try (var filesystem = new org.apache.poi.poifs.filesystem.POIFSFileSystem(new ByteArrayInputStream(bytes))) {
            String workbookName = filesystem.getRoot().hasEntry("Workbook") ? "Workbook" : "Book";
            try (var input = filesystem.createDocumentInputStream(workbookName)) {
                var records = new org.apache.poi.hssf.record.RecordInputStream(input);
                long recordCount = 0;
                while (records.hasNextRecord()) {
                    records.nextRecord();
                    if (++recordCount > limits.maxTotalCells() * 4) {
                        throw new IOException("Excel BIFF 记录超过内存预算");
                    }
                    if (records.getSid() == org.apache.poi.hssf.record.SSTRecord.sid) {
                        records.readInt();
                        if (Integer.toUnsignedLong(records.readInt()) > limits.maxTotalCells()) {
                            throw new IOException("Excel 共享字符串数量超过内存预算");
                        }
                    }
                    records.readRemainder();
                }
            }
            var budget = new BinaryWorkbookBudget(limits);
            var request = new org.apache.poi.hssf.eventusermodel.HSSFRequest();
            request.addListenerForAllRecords(budget::record);
            new org.apache.poi.hssf.eventusermodel.HSSFEventFactory().processWorkbookEvents(request, filesystem);
        }
    }

    private static final class BinaryWorkbookBudget {

        private final Limits limits;

        private long cells, rows, chars;

        /**
         * 初始化 {@code BinaryWorkbookBudget}。
         */
        private BinaryWorkbookBudget(Limits limits) {
            this.limits = limits;
        }

        /**
         * 记录当前操作的状态或结果。
         */
        private void record(org.apache.poi.hssf.record.Record record) {
            if (record instanceof org.apache.poi.hssf.record.CellValueRecordInterface cell) {
                cells++;
                column(Short.toUnsignedInt(cell.getColumn()) + 1);
            } else if (record instanceof org.apache.poi.hssf.record.MulBlankRecord packed) {
                cells += packed.getNumColumns();
                column(packed.getLastColumn() + 1);
            } else if (record instanceof org.apache.poi.hssf.record.MulRKRecord packed) {
                cells += packed.getNumColumns();
                column(packed.getLastColumn() + 1);
            }
            if (cells > limits.maxTotalCells()) {
                throw new IllegalArgumentException("Excel 单元格总数超过内存预算");
            }
            if (record instanceof org.apache.poi.hssf.record.RowRecord && ++rows > (long) limits.maxRows() + 1) {
                throw new IllegalArgumentException("Excel 工作簿总行数超过限额");
            }
            if (record instanceof org.apache.poi.hssf.record.SSTRecord strings) {
                for (int i = 0; i < strings.getNumUniqueStrings(); i++) {
                    text(strings.getString(i).getString());
                }
            } else if (record instanceof org.apache.poi.hssf.record.StringRecord value) {
                text(value.getString());
            } else if (record instanceof org.apache.poi.hssf.record.LabelRecord value) {
                text(value.getValue());
            }
        }

        /**
         * 返回列。
         */
        private void column(int count) {
            if (count > limits.maxColumns()) {
                throw new IllegalArgumentException("Excel 超过列数限额");
            }
        }

        /**
         * 按当前值域约定解析或转换文本。
         */
        private void text(String value) {
            if (value.length() > limits.maxCellChars()) {
                throw new IllegalArgumentException("Excel 单元格内容超过限额");
            }
            chars += value.length();
            if (chars > limits.maxDecodedChars()) {
                throw new IllegalArgumentException("Excel 工作簿文本超过内存预算");
            }
        }
    }

    /**
     * 校验展开压缩包。
     */
    private static void validateExpandedArchive(byte[] bytes, Limits limits) throws IOException {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int entries = 0;
            while (zip.getNextEntry() != null) {
                if (++entries > 4096) {
                    throw new IOException("Excel 压缩包条目过多");
                }
                int count;
                while ((count = zip.read(buffer)) != -1) {
                    total += count;
                    if (total > limits.maxExpandedBytes()) {
                        throw new IOException("Excel 解压内容超过限额");
                    }
                }
            }
        }
        // POI 的用户模型会先分配整个工作簿；在此之前扫描 XML，空单元格也计入预算。
        var factory = javax.xml.stream.XMLInputFactory.newFactory();
        factory.setProperty(javax.xml.stream.XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        long cells = 0, text = 0, rows = 0;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (!(name.startsWith("xl/worksheets/") && name.endsWith(".xml")) && !"xl/sharedStrings.xml".equals(
                        name)) {
                    continue;
                }
                var xml = factory.createXMLStreamReader(new FilterInputStream(zip) {
                    /**
                     * 关闭当前读取器及其持有的输入资源。
                     */
                    @Override
                    public void close() {
                    }
                });
                try {
                    while (xml.hasNext()) {
                        int event = xml.next();
                        if (event == javax.xml.stream.XMLStreamConstants.DTD) {
                            throw new IOException("Excel XML 不允许 DTD");
                        }
                        if (event == javax.xml.stream.XMLStreamConstants.START_ELEMENT) {
                            if ("c".equals(xml.getLocalName()) || "si".equals(xml.getLocalName())) {
                                if (++cells > limits.maxTotalCells()) {
                                    throw new IOException("Excel 单元格总数超过内存预算");
                                }
                            }
                            if ("row".equals(xml.getLocalName()) && ++rows > (long) limits.maxRows() + 1) {
                                throw new IOException("Excel 工作簿总行数超过限额");
                            }
                        } else if (event == javax.xml.stream.XMLStreamConstants.CHARACTERS || event == javax.xml.stream.XMLStreamConstants.CDATA) {
                            text += xml.getTextLength();
                            if (text > limits.maxDecodedChars()) {
                                throw new IOException("Excel 工作簿文本超过内存预算");
                            }
                        }
                    }
                } finally {
                    xml.close();
                }
            }
        } catch (javax.xml.stream.XMLStreamException invalid) {
            throw new IOException("Excel XML 内容无效", invalid);
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
