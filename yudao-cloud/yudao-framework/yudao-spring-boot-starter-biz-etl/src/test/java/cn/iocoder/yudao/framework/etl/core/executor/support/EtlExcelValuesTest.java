package cn.iocoder.yudao.framework.etl.core.executor.support;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.CellType;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.math.BigInteger;
import static org.junit.jupiter.api.Assertions.*;

class EtlExcelValuesTest {
    @Test void existingXmlNumericLexemeDoesNotPassThroughDouble() throws Exception {
        try(var workbook=new XSSFWorkbook()){
            var cell=workbook.createSheet().createRow(0).createCell(0);cell.setCellType(CellType.NUMERIC);
            cell.getCTCell().setV("12345678901234567890.12345678");
            assertEquals("12345678901234567890.12345678",EtlExcelValues.read(cell,EtlExcelValues.FormulaMode.LITERAL));
        }
    }
    @Test void formulaDefaultsToLiteralAndCachedStringBooleanErrorAreHandledExplicitly() throws Exception {
        try(var workbook=new XSSFWorkbook()){
            var row=workbook.createSheet().createRow(0);var text=row.createCell(0);
            text.setCellFormula("\"text\"");workbook.getCreationHelper().createFormulaEvaluator().evaluateFormulaCell(text);
            assertEquals("=\"text\"",EtlExcelValues.read(text,EtlExcelValues.FormulaMode.LITERAL));
            assertEquals("text",EtlExcelValues.read(text,EtlExcelValues.FormulaMode.CACHED));
            var bool=row.createCell(1);bool.setCellFormula("TRUE()");workbook.getCreationHelper().createFormulaEvaluator().evaluateFormulaCell(bool);
            assertEquals("true",EtlExcelValues.read(bool,EtlExcelValues.FormulaMode.CACHED));
            var error=row.createCell(2);error.setCellErrorValue(org.apache.poi.ss.usermodel.FormulaError.DIV0.getCode());
            assertThrows(IllegalArgumentException.class,()->EtlExcelValues.read(error,EtlExcelValues.FormulaMode.CACHED));
        }
    }
    @Test void decimalAndBigIntegerAreTextAndBlankDiffersFromEmpty() throws Exception {
        try(var workbook=new XSSFWorkbook()){
            var row=workbook.createSheet().createRow(0);
            Object[] values={new BigDecimal("1.230000"),new BigInteger("999999999999999999999"),1000000000000001L,null,"","  ","=1+1"};
            for(int i=0;i<values.length;i++)EtlExcelValues.write(row.createCell(i),values[i]);
            assertEquals("1.230000",row.getCell(0).getStringCellValue());
            assertEquals("999999999999999999999",row.getCell(1).getStringCellValue());
            assertEquals("1000000000000001",row.getCell(2).getStringCellValue());
            assertNull(EtlExcelValues.read(row.getCell(3),EtlExcelValues.FormulaMode.LITERAL));
            assertEquals("",EtlExcelValues.read(row.getCell(4),EtlExcelValues.FormulaMode.LITERAL));
            assertEquals("  ",EtlExcelValues.read(row.getCell(5),EtlExcelValues.FormulaMode.LITERAL));
            assertEquals(CellType.STRING,row.getCell(6).getCellType());
            assertThrows(IllegalArgumentException.class,()->EtlExcelValues.write(row.createCell(7),Double.NaN));
        }
    }
}
