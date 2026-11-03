package cn.iocoder.yudao.framework.etl.core.file;

import cn.iocoder.yudao.framework.etl.core.executor.support.EtlExcelValues;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class TabularFileReaderTest {
    private final TabularFileReader.Limits limits = TabularFileReader.Limits.defaults();

    @Test void csvBomQuotedMultilineAndPreciseStrings() throws Exception {
        var result = csv("\uFEFF\"编号\",内容,\n9007199254740993,\"含逗号,和\"\"引号\"\"\n换行\",12345678901234567890.0001\n");
        assertEquals(List.of("编号", "内容", "C"), result.headers());
        assertEquals(List.of("9007199254740993", "含逗号,和\"引号\"\n换行", "12345678901234567890.0001"), result.rows().getFirst());
    }

    @Test void duplicateAndEmptyHeadersCannotSilentlyOverwriteValues() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> csv("编号,编号\n1,2"));
        assertThrows(IllegalArgumentException.class, () -> csv(",A\n1,2"));
        assertEquals(List.of("A", "B"), csv(",\n1,2").headers());
        assertThrows(IllegalArgumentException.class, () -> csv("a,b\n1,2,3"));
        assertThrows(IllegalArgumentException.class, () -> csv(""));
    }

    @Test void readEnforcesBytesRowsColumnsAndCellLimits() {
        assertThrows(Exception.class, () -> TabularFileReader.read("a\n123".getBytes(StandardCharsets.UTF_8), "csv", new TabularFileReader.Limits(4, 2, 2, 20)));
        assertThrows(Exception.class, () -> TabularFileReader.read(new ByteArrayInputStream("a\n123".getBytes(StandardCharsets.UTF_8)), "csv", new TabularFileReader.Limits(4, 2, 2, 20)));
        assertThrows(IllegalArgumentException.class, () -> TabularFileReader.read("a\n1\n2".getBytes(StandardCharsets.UTF_8), "csv", new TabularFileReader.Limits(100, 1, 2, 20)));
        assertThrows(IllegalArgumentException.class, () -> TabularFileReader.read("a,b\n1,2".getBytes(StandardCharsets.UTF_8), "csv", new TabularFileReader.Limits(100, 2, 1, 20)));
        assertThrows(IllegalArgumentException.class, () -> TabularFileReader.read("a\n123".getBytes(StandardCharsets.UTF_8), "csv", new TabularFileReader.Limits(100, 2, 2, 2)));
        assertThrows(IllegalArgumentException.class, () -> TabularFileReader.read(new byte[20 * 1024 * 1024 + 1], "csv", limits));
    }

    @Test void xlsAndXlsxKeepFormulaLiteralAndDecimalText() throws Exception {
        for (String extension : List.of("xls", "xlsx")) {
            try (Workbook workbook = extension.equals("xls") ? new HSSFWorkbook() : new XSSFWorkbook()) {
                var sheet = workbook.createSheet("data");
                var header = sheet.createRow(0);
                header.createCell(0).setCellValue("decimal");
                header.createCell(1).setCellValue("formula");
                header.createCell(2).setBlank();
                var row = sheet.createRow(1);
                row.createCell(0).setCellValue("12345678901234567890.00100");
                row.createCell(1).setCellFormula("1+2");
                row.createCell(2).setCellValue("");
                var result = TabularFileReader.read(bytes(workbook), extension, limits);
                assertEquals(List.of("decimal", "formula", "C"), result.headers());
                assertEquals(List.of("12345678901234567890.00100", "=1+2", ""), result.rows().getFirst());
            }
        }
    }

    @Test void xlsxRawNumericTextDoesNotRoundThroughDouble() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("data");
            sheet.createRow(0).createCell(0).setCellValue("n");
            XSSFCell cell = sheet.createRow(1).createCell(0);
            cell.setCellValue(0.0);
            cell.getCTCell().setV("12345678901234567890.00001");
            assertEquals("12345678901234567890.00001", TabularFileReader.read(bytes(workbook), "xlsx", limits).rows().getFirst().getFirst());
        }
    }

    @Test void xlsxSparseRowRangeAndDuplicateHeadersAreRejected() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("data");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("n");
            sheet.createRow(100_001).createCell(0).setCellValue("late");
            assertThrows(IllegalArgumentException.class, () -> TabularFileReader.read(bytes(workbook), "xlsx", limits));
            header.createCell(1).setCellValue("n");
            assertThrows(IllegalArgumentException.class, () -> TabularFileReader.read(bytes(workbook), "xlsx", limits));
        }
    }

    @Test void callerRetainsInputStreamOwnershipAndNullCells() throws Exception {
        var closed = new AtomicBoolean();
        var source = new ByteArrayInputStream("a\n1".getBytes(StandardCharsets.UTF_8)) {
            @Override public void close() { closed.set(true); }
        };
        TabularFileReader.read(source, "csv", limits);
        assertFalse(closed.get());
        try (var workbook = new HSSFWorkbook()) {
            var sheet = workbook.createSheet();
            sheet.createRow(0).createCell(0).setCellValue("a");
            sheet.createRow(1).createCell(0).setBlank();
            assertNull(TabularFileReader.read(bytes(workbook), "xls", limits).rows().getFirst().getFirst());
        }
    }

    @Test void existingCsvTypedEncodingKeepsNullAndLiteralTokens() throws Exception {
        var rows = new java.util.ArrayList<List<String>>();
        TabularFileReader.readCsv(new ByteArrayInputStream("a\n\\N\n\\\\N\n\\F=1+2".getBytes(StandardCharsets.UTF_8)),
                new TabularFileReader.CsvOptions(StandardCharsets.UTF_8, ',', true, false, true), limits,
                (headers, values) -> rows.add(values));
        assertNull(rows.get(0).getFirst());
        assertEquals("\\N", rows.get(1).getFirst());
        assertEquals("=1+2", rows.get(2).getFirst());
    }

    @Test void aggregateCellBudgetIncludesEmptyCellsAndZipExpansionIsBounded() throws Exception {
        String csv = ",".repeat(19) + "\n" + ",".repeat(19);
        assertThrows(IllegalArgumentException.class, () -> TabularFileReader.read(csv.getBytes(StandardCharsets.UTF_8),
                "csv", new TabularFileReader.Limits(100, 100, 20, 20)));
        var buffer = new ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(buffer)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("xl/worksheets/sheet1.xml"));
            zip.write(new byte[10_000]); zip.closeEntry();
        }
        assertThrows(java.io.IOException.class, () -> TabularFileReader.read(buffer.toByteArray(), "xlsx",
                new TabularFileReader.Limits(1000, 100, 20, 20)));
    }

    @Test void csvWideRowsAreRejectedBeforeParserConsumesTheWholeRecord() throws Exception {
        var consumed = new java.util.concurrent.atomic.AtomicInteger();
        var source = new java.io.InputStream() {
            @Override public int read() { return consumed.incrementAndGet() <= 1_000_000 ? ',' : -1; }
        };
        assertThrows(IllegalArgumentException.class, () -> TabularFileReader.read(source, "csv",
                new TabularFileReader.Limits(2_000_000, 100, 4, 100)));
        assertTrue(consumed.get() < 65_536, "超宽行必须在完整分配百万字段前被截断");
        String valid = "# ignored,comment,with,\"quotes\nvalue,other\r\n\",,\"\"\ntext\",ok\r\n";
        var parsed = TabularFileReader.read(valid.getBytes(StandardCharsets.UTF_8), "csv",
                new TabularFileReader.Limits(1000, 10, 2, 40));
        assertEquals(List.of(",,\"\ntext", "ok"), parsed.rows().getFirst());
    }

    @Test void xlsEventBudgetChecksUnselectedSheetsBeforeUserModelAllocation() throws Exception {
        try (var workbook = new HSSFWorkbook()) {
            var first = workbook.createSheet("selected");
            first.createRow(0).createCell(0).setCellValue("name");
            first.createRow(1).createCell(0).setCellValue("ok");
            var second = workbook.createSheet("unselected");
            for (int i = 0; i < 3; i++) second.createRow(i).createCell(0).setBlank();
            assertThrows(IllegalArgumentException.class, () -> TabularFileReader.read(bytes(workbook), "xls",
                    new TabularFileReader.Limits(100_000, 1, 4, 100)));
            second.getRow(0).createCell(4).setBlank();
            assertThrows(IllegalArgumentException.class, () -> TabularFileReader.read(bytes(workbook), "xls",
                    new TabularFileReader.Limits(100_000, 100, 4, 100)));
        }
    }

    @Test void xlsSharedStringCountIsRejectedBeforeMaterializingStringRecords() throws Exception {
        var biff = java.nio.ByteBuffer.allocate(12).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        biff.putShort(org.apache.poi.hssf.record.SSTRecord.sid).putShort((short) 8).putInt(Integer.MAX_VALUE).putInt(Integer.MAX_VALUE);
        var output = new ByteArrayOutputStream();
        try (var filesystem = new org.apache.poi.poifs.filesystem.POIFSFileSystem()) {
            filesystem.createDocument(new ByteArrayInputStream(biff.array()), "Workbook");
            filesystem.writeFilesystem(output);
        }
        var failure = assertThrows(java.io.IOException.class, () -> TabularFileReader.read(output.toByteArray(), "xls", limits));
        assertTrue(failure.getMessage().contains("共享字符串数量"));
    }

    private TabularFileReader.Table csv(String value) throws Exception { return TabularFileReader.read(value.getBytes(StandardCharsets.UTF_8), "csv", limits); }
    private byte[] bytes(Workbook workbook) throws Exception { var out = new ByteArrayOutputStream(); workbook.write(out); return out.toByteArray(); }
}
