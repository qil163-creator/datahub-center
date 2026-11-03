package cn.iocoder.yudao.framework.etl.core.executor.support;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.util.NumberToTextConverter;
import org.apache.poi.xssf.usermodel.XSSFCell;
import java.math.BigDecimal;

/** Java17 工作节点与内嵌 ETL 共用的精确单元格读取，公式仅文本或缓存值，不触发求值。 */
public final class EtlExcelCellReader {
    private EtlExcelCellReader() { }
    public static String read(Cell cell, boolean cachedFormulas) {
        if (cell == null) {
            return null;
        }
        CellType type = cell.getCellType();
        if (type == CellType.FORMULA) {
            if (!cachedFormulas) {
                return "=" + cell.getCellFormula();
            }
            type = cell.getCachedFormulaResultType();
        }
        return switch (type) {
            case BLANK, _NONE -> null;
            case STRING -> cell.getStringCellValue();
            case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
            case NUMERIC -> {
                if (org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell)) {
                    yield cell.getLocalDateTimeCellValue().toString();
                }
                // OOXML 已经存储了十进制字面文本。不得通过 Java double 进行舍入。
                String text = cell instanceof XSSFCell xml ? xml.getRawValue() : NumberToTextConverter.toText(
                        cell.getNumericCellValue());
                if (text == null) {
                    throw new IllegalArgumentException("Excel numeric cell has no stored value");
                }
                try {
                    yield new BigDecimal(text).toPlainString();
                } catch (NumberFormatException invalid) {
                    throw new IllegalArgumentException("Excel numeric cell is invalid");
                }
            }
            case ERROR -> throw new IllegalArgumentException("Excel contains an error cell");
            default -> throw new IllegalArgumentException("Excel cell type is unsupported");
        };
    }
}
