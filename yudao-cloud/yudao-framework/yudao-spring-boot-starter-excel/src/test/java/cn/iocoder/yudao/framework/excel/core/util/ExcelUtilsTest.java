package cn.iocoder.yudao.framework.excel.core.util;

import cn.idev.excel.FastExcelFactory;
import cn.idev.excel.annotation.ExcelProperty;
import jakarta.servlet.ServletOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ExcelUtils} 的单元测试
 *
 * @author 芋道源码
 */
public class ExcelUtilsTest {

    @Test
    public void testRead_maxRowCount() throws Exception {
        // mock 数据
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        FastExcelFactory.write(outputStream, TestRow.class).sheet().doWrite(Arrays.asList(
                new TestRow("员工一"), new TestRow("员工二"), new TestRow("员工三")));
        MockMultipartFile file = new MockMultipartFile(
                "file", "employees.xlsx", null, outputStream.toByteArray());

        // 调用
        List<TestRow> rows = ExcelUtils.read(file, TestRow.class, 2);

        // 断言
        assertEquals(2, rows.size());
        assertEquals("员工一", rows.get(0).getName());
        assertEquals("员工二", rows.get(1).getName());
    }

    @Test
    public void writesWorkbookHeadersBeforeOpeningTheStream() throws Exception {
        for (boolean dynamicHead : List.of(false, true)) {
            MockHttpServletResponse response = new MockHttpServletResponse() {
                @Override
                public ServletOutputStream getOutputStream() {
                    assertEquals("attachment;filename=records.xlsx", getHeader("Content-Disposition"));
                    assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", getContentType());
                    return super.getOutputStream();
                }
            };
            if (dynamicHead) {
                ExcelUtils.write(response, "records.xlsx", "数据", List.of(List.of("姓名")), List.of(List.of("员工一")));
            } else {
                ExcelUtils.write(response, "records.xlsx", "数据", TestRow.class, List.of(new TestRow("员工一")));
            }
            MockMultipartFile workbook = new MockMultipartFile("file", response.getContentAsByteArray());
            assertEquals("员工一", ExcelUtils.read(workbook, TestRow.class).getFirst().getName());
        }
    }

    @Test
    void dynamicTemplateAndBoundedReadPreserveTextNumbersAndWorksheetPositions() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        ExcelUtils.writeTextTemplate(response, "bulk.xlsx", "数据", List.of("id", "value"));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            var sheet = workbook.getSheetAt(0);
            assertEquals("@", sheet.getColumnStyle(0).getDataFormatString());
            var row = sheet.createRow(6);
            row.createCell(0).setCellValue("00000000000000000001");
            row.createCell(1).setCellValue(12.25);
            sheet.createRow(7).createCell(0).setCellValue("下一行");
            workbook.write(output);
        }
        var rows = ExcelUtils.readXlsxRows(new MockMultipartFile("file", output.toByteArray()), 2);
        assertEquals(2, rows.size());
        assertEquals("id", rows.getFirst().cells().get(0).getStringValue());
        assertEquals(7, rows.getLast().number());
        assertEquals("00000000000000000001", rows.getLast().cells().get(0).getStringValue());
        assertEquals("12.25", rows.getLast().cells().get(1).getOriginalNumberValue().toPlainString());
    }

    public static class TestRow {

        @ExcelProperty("姓名")
        private String name;

        public TestRow() {
        }

        public TestRow(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

    }

}
