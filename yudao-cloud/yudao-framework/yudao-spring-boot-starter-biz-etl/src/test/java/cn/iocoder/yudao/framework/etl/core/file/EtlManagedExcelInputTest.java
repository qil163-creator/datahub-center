package cn.iocoder.yudao.framework.etl.core.file;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class EtlManagedExcelInputTest {
    @TempDir Path temporary;
    @BeforeEach void realRoot() throws Exception { temporary = temporary.toRealPath(); }

    @Test void readsTheCompleteSelectedSheetRangeAndRetainsDatesPrecisionNullEmptyAndLiteralFormulas() throws Exception {
        Path source = temporary.resolve("source.xlsx"); create(source, false, false);
        var rows = new ArrayList<List<String>>();
        EtlManagedExcelInput.read(Set.of(temporary), temporary.toString(), "source.xlsx",
                new EtlBoundedExcelReader.Options("所选工作表", 1, 2, 2, false, false), 2, 6,
                EtlBoundedExcelReader.Limits.defaults(), (headers, values) -> rows.add(values));
        assertEquals(1, rows.size());
        assertEquals(Arrays.asList("9007199254740993", "12345678901234567890.1234", "2026-11-02T03:04:05", "=1+2", null, ""), rows.getFirst());
        var cached = new ArrayList<List<String>>();
        EtlManagedExcelInput.read(Set.of(temporary), temporary.toString(), "source.xlsx",
                new EtlBoundedExcelReader.Options("所选工作表", 1, 2, 3, false, true), 2, 6,
                EtlBoundedExcelReader.Limits.defaults(), (headers, values) -> cached.add(values));
        assertEquals("3.0", cached.getFirst().get(3));
        assertEquals(Arrays.asList(null, null, null, null, null, null), cached.get(1));
        Path fixtures = Path.of("target", "native-file-sql"); Files.createDirectories(fixtures);
        Files.copy(source, fixtures.resolve("input.xlsx"), StandardCopyOption.REPLACE_EXISTING);
        create(fixtures.resolve("bad-type.xlsx"), false, true);
        create(fixtures.resolve("input.xls"), true, false);
    }

    @Test void binaryExcelUsesTheSameBoundedFullReaderAndCachedFormulaSemantics() throws Exception {
        Path source = temporary.resolve("source.xls"); create(source, true, false);
        var rows = new ArrayList<List<String>>();
        EtlManagedExcelInput.read(Set.of(temporary), temporary.toString(), "source.xls",
                new EtlBoundedExcelReader.Options("所选工作表", 1, 2, 2, true, true), 2, 6,
                EtlBoundedExcelReader.Limits.defaults(), (headers, values) -> rows.add(values));
        assertEquals("9007199254740993", rows.getFirst().getFirst());
        assertEquals("3", rows.getFirst().get(3));
    }

    @Test void rejectsUnauthorizedRootsSymlinkFilesSymlinkDirectoriesOversizedFilesAndMissingSheets() throws Exception {
        Path source = temporary.resolve("source.xlsx"); create(source, false, false);
        var options = new EtlBoundedExcelReader.Options("所选工作表", 1, 2, 2, true, false);
        assertThrows(Exception.class, () -> read(Set.of(), "source.xlsx", options, EtlBoundedExcelReader.Limits.defaults()));
        assertThrows(Exception.class, () -> read(Set.of(temporary), "../source.xlsx", options, EtlBoundedExcelReader.Limits.defaults()));
        Files.createSymbolicLink(temporary.resolve("link.xlsx"), source);
        assertThrows(Exception.class, () -> read(Set.of(temporary), "link.xlsx", options, EtlBoundedExcelReader.Limits.defaults()));
        Files.createSymbolicLink(temporary.resolve("linked"), temporary);
        assertThrows(Exception.class, () -> read(Set.of(temporary), "linked/source.xlsx", options, EtlBoundedExcelReader.Limits.defaults()));
        assertThrows(Exception.class, () -> read(Set.of(temporary), "source.xlsx", options, new EtlBoundedExcelReader.Limits(128, 100, 256, 1024)));
        assertThrows(Exception.class, () -> read(Set.of(temporary), "source.xlsx", new EtlBoundedExcelReader.Options("不存在", 1, 2, 2, true, false), EtlBoundedExcelReader.Limits.defaults()));
    }

    private void read(Set<Path> roots, String path, EtlBoundedExcelReader.Options options, EtlBoundedExcelReader.Limits limits) throws Exception {
        EtlManagedExcelInput.read(roots, temporary.toString(), path, options, 2, 6, limits, (headers, values) -> { });
    }

    private static void create(Path path, boolean binary, boolean invalidType) throws Exception {
        try (Workbook book = binary ? new HSSFWorkbook() : new XSSFWorkbook()) {
            book.createSheet("未选择").createRow(0).createCell(0).setCellValue("ignore");
            var sheet = book.createSheet("所选工作表");
            var header = sheet.createRow(0);
            String[] names = {"skip", "id", "amount", "day", "formula", "blank", "empty"};
            for (int index = 0; index < names.length; index++) header.createCell(index).setCellValue(names[index]);
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("skip"); row.createCell(1).setCellValue(invalidType ? "notnumber" : "9007199254740993");
            var amount = row.createCell(2);
            if (amount instanceof XSSFCell xml) { xml.setCellValue(1); xml.getCTCell().setV("12345678901234567890.1234"); }
            else amount.setCellValue("12345678901234567890.1234");
            var date = row.createCell(3); date.setCellValue(LocalDateTime.of(2026, 11, 2, 3, 4, 5));
            var style = book.createCellStyle(); style.setDataFormat(book.createDataFormat().getFormat("yyyy-mm-dd hh:mm:ss")); date.setCellStyle(style);
            var formula = row.createCell(4); formula.setCellFormula("1+2"); book.getCreationHelper().createFormulaEvaluator().evaluateFormulaCell(formula);
            row.createCell(5).setBlank(); row.createCell(6).setCellValue(""); sheet.createRow(2);
            try (var output = Files.newOutputStream(path)) { book.write(output); }
        }
    }
}
