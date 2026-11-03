package cn.iocoder.yudao.framework.etl.core.file;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FileSchemaReaderTest {
    @Test void csvReadsQuotedChineseHeadersWithoutGuessingNumericAndDateText() throws Exception {
        var result = FileSchemaReader.csv(("\uFEFF编号,\"站名,别称\",日期\r\n0012,\"河道\nA\",2026-11-01\r\n"
                + "9223372036854775808,\"站点\"\"B\",2026-11-02\r\n").getBytes(StandardCharsets.UTF_8));
        assertEquals(List.of("编号", "站名,别称", "日期"), result.fields().stream().map(FileSchemaReader.Field::name).toList());
        assertTrue(result.fields().stream().allMatch(field -> "STRING".equals(field.dataType()) && field.length() == null && field.precision() == null));
        assertTrue(result.warnings().getFirst().contains("文本"));
    }

    @Test void csvRejectsInvalidEncodingDuplicateColumnsMalformedWidthAndResourceExhaustion() {
        for (String text : List.of("", "name,name\na,b", "name,other\na,b,c", "\"name\nunsafe\"\na", "a".repeat(257) + "\nb",
                "name\n" + "x\n".repeat(10001), "name\n" + "x".repeat(65537)))
            assertThrows(Exception.class, () -> FileSchemaReader.csv(text.getBytes(StandardCharsets.UTF_8)));
        assertThrows(Exception.class, () -> FileSchemaReader.csv(new byte[]{(byte) 0xc3, 0x28}));
        assertThrows(Exception.class, () -> FileSchemaReader.csv(new byte[50 * 1024 * 1024 + 1]));
    }

    @Test void excelPreservesTypedCellsAndNeverEvaluatesFormulas() throws Exception {
        for (String extension : List.of("xlsx", "xls")) try (Workbook book = extension.equals("xlsx") ? new XSSFWorkbook() : new HSSFWorkbook()) {
            var sheet = book.createSheet("数据");
            var header = sheet.createRow(0);
            for (int i = 0; i < 5; i++) header.createCell(i).setCellValue(List.of("跳过", "编号", "启用", "公式", "空值").get(i));
            var row = sheet.createRow(1);
            row.createCell(1).setCellValue("0012345678901234567890");
            row.createCell(2).setCellValue(true);
            row.createCell(3).setCellFormula("1+2");
            var result = FileSchemaReader.excel(bytes(book), extension, 2, 2, "数据");
            assertEquals(List.of("编号", "启用", "公式", "空值"), result.fields().stream().map(FileSchemaReader.Field::name).toList());
            assertEquals("STRING", result.fields().get(0).dataType());
            assertEquals("BOOLEAN", result.fields().get(1).dataType());
            assertNull(result.fields().get(2).dataType());
            assertNull(result.fields().get(3).dataType());
            result.fields().forEach(field -> { assertNull(field.length()); assertNull(field.precision()); assertNull(field.scale()); });
            assertTrue(result.warnings().stream().anyMatch(item -> item.contains("公式")));
        }
    }

    @Test void excelFirstDataRowOneKeepsFirstRowWithGeneratedNames() throws Exception {
        try (var book = new XSSFWorkbook()) {
            var sheet = book.createSheet(); sheet.createRow(0).createCell(1).setCellValue("0012");
            var result = FileSchemaReader.excel(bytes(book), "xlsx", 1, 2, null);
            assertEquals("column_2", result.fields().getFirst().name());
            assertEquals("STRING", result.fields().getFirst().dataType());
            assertTrue(result.warnings().stream().anyMatch(item -> item.contains("保留首行")));
        }
    }

    @Test void excelRejectsDuplicateHeadersMissingSheetAndExcessiveRows() throws Exception {
        try (var book = new XSSFWorkbook()) {
            var sheet = book.createSheet("data"); var header = sheet.createRow(0);
            header.createCell(0).setCellValue("same"); header.createCell(1).setCellValue("same");
            var content = bytes(book);
            assertThrows(IllegalArgumentException.class, () -> FileSchemaReader.excel(content, "xlsx", 2, 1, "data"));
            assertThrows(IllegalArgumentException.class, () -> FileSchemaReader.excel(content, "xlsx", 2, 1, "missing"));
            header.getCell(1).setCellValue("different"); sheet.createRow(10000).createCell(0).setCellValue("late");
            var excessive = bytes(book);
            assertThrows(IllegalArgumentException.class, () -> FileSchemaReader.excel(excessive, "xlsx", 2, 1, null));
        }
        assertThrows(Exception.class, () -> FileSchemaReader.excel(new byte[]{1, 2}, "xlsx", 2, 1, null));
        assertThrows(IllegalArgumentException.class, () -> FileSchemaReader.excel(new byte[0], "xls", 2, 0, null));
    }

    @Test void jsonlUsesActualPrimitiveTypesAndKeepsUnknownMetadataNull() throws Exception {
        var result = json("\uFEFF{\"id\":1,\"big\":9223372036854775808,\"amount\":1.25,\"text\":\"0012\",\"active\":true,\"nested\":{},\"empty\":null}\n"
                + "{\"active\":false,\"id\":2,\"big\":9223372036854775809,\"amount\":2.50,\"text\":\"中文\",\"nested\":[],\"empty\":null}\n");
        assertEquals(List.of("LONG", "DECIMAL", "DECIMAL", "STRING", "BOOLEAN"), result.fields().subList(0, 5).stream().map(FileSchemaReader.Field::dataType).toList());
        assertNull(result.fields().get(5).dataType()); assertNull(result.fields().get(6).dataType());
        result.fields().forEach(field -> { assertNull(field.length()); assertNull(field.precision()); assertNull(field.scale()); });
        assertEquals("$['text']", result.fields().get(3).path());
        assertTrue(result.warnings().stream().anyMatch(item -> item.contains("采样")));
    }

    @Test void jsonlDoesNotGuessMixedOrNumericLookingStrings() throws Exception {
        var result = json("{\"a\":1,\"b\":\"2026-11-01\",\"q'Data\":\"0012\"}\n{\"a\":\"1\",\"b\":\"2026-11-02\",\"q'Data\":\"2\"}");
        assertNull(result.fields().get(0).dataType());
        assertEquals("STRING", result.fields().get(1).dataType());
        assertEquals("$['q\\'Data']", result.fields().get(2).path());
    }

    @Test void jsonlRejectsMalformedUnsafeAndInconsistentRecords() {
        for (String text : List.of("", "\n", "[]", "{}", "{\"a\":1} trailing", "{\"a\":1,\"a\":2}",
                "{\"a\":1}\n{\"b\":2}", "{\"a\":1}\n\n{\"a\":2}", "{\"\":1}", "{\"a\\n\":1}",
                "{\"a\":" + "[".repeat(20) + "0" + "]".repeat(20) + "}", "{\"a\":\"" + "x".repeat(65536) + "\"}"))
            assertThrows(IllegalArgumentException.class, () -> json(text));
        assertThrows(Exception.class, () -> FileSchemaReader.jsonl(new byte[]{(byte) 0xc3, 0x28}));
        assertThrows(IllegalArgumentException.class, () -> FileSchemaReader.jsonl(new byte[50 * 1024 * 1024 + 1]));
    }

    @Test void jsonlSamplingReportsUnexaminedRowsInsteadOfClaimingCompleteSchema() throws Exception {
        var result = json("{\"id\":1}\n".repeat(1000) + "{\"different\":true}\n");
        assertEquals("id", result.fields().getFirst().name());
        assertTrue(result.warnings().stream().anyMatch(item -> item.contains("其余记录")));
    }

    @Test void csvAcceptsTheFullFiftyMiBUploadWithoutIncreasingRowOrCellBudgets() throws Exception {
        byte[] content = new byte[50 * 1024 * 1024];
        java.util.Arrays.fill(content, (byte) 'x');
        content[0] = 'v'; content[1] = '\n';
        for (int i = 6001; i < content.length; i += 6001) content[i] = '\n';
        assertEquals(50L * 1024 * 1024, FileSchemaReader.LIMITS.maxBytes());
        assertEquals("v", FileSchemaReader.csv(content).fields().getFirst().name());
        assertEquals(10000, FileSchemaReader.LIMITS.maxRows());
        assertEquals(65536, FileSchemaReader.LIMITS.maxCellChars());
    }

    private static FileSchemaReader.Result json(String text) throws Exception { return FileSchemaReader.jsonl(text.getBytes(StandardCharsets.UTF_8)); }
    private static byte[] bytes(Workbook book) throws Exception { var stream = new ByteArrayOutputStream(); book.write(stream); return stream.toByteArray(); }
}
