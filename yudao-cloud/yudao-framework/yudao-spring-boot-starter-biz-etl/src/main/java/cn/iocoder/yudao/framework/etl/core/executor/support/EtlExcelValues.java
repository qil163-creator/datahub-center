package cn.iocoder.yudao.framework.etl.core.executor.support;

import cn.iocoder.yudao.framework.dbswitch.common.util.JdbcValueConversions;
import org.apache.poi.ss.usermodel.Cell;

import java.math.BigDecimal;
import java.math.BigInteger;

/** ETL 单元格契约：对精度敏感的数字使用文本；BLANK 表示 NULL，STRING 可以为空。 */
public final class EtlExcelValues {

    public enum FormulaMode {LITERAL, CACHED}

    /**
     * 初始化 {@code EtlExcelValues}。
     */
    private EtlExcelValues() {
    }

    /**
     * 按 Excel 单元格契约写入值，对精度敏感的数字使用文本表示。
     */
    public static void write(Cell cell, Object input) {
        Object value = JdbcValueConversions.normalize(input);
        if (value == null) {
            cell.setBlank();
            return;
        }
        if (value instanceof Boolean flag) {
            cell.setCellValue(flag);
            return;
        }
        // Excel 的十进制精度为 15 位，比 JavaScript 安全整数范围更窄。
        if (value instanceof BigDecimal decimal) {
            cell.setCellValue(decimal.toPlainString());
            return;
        }
        if (value instanceof BigInteger integer) {
            cell.setCellValue(integer.toString());
            return;
        }
        if (value instanceof Long integer && BigInteger.valueOf(integer).abs().toString().length() > 15) {
            cell.setCellValue(integer.toString());
            return;
        }
        if (value instanceof Number number) {
            double numeric = number.doubleValue();
            if (!Double.isFinite(numeric)) {
                throw new IllegalArgumentException("Excel does not support non-finite numeric cells");
            }
            cell.setCellValue(numeric);
            return;
        }
        // setCellValue(String) 始终创建文本；以 =、+、-、@ 开头的内容绝不会按公式计算。
        cell.setCellValue(JdbcValueConversions.toText(value));
    }

    /**
     * 读取 Excel 单元格值，并按单元格类型保留数字及文本语义。
     */
    public static String read(Cell cell, FormulaMode formulas) {
        return EtlExcelCellReader.read(cell, formulas == FormulaMode.CACHED);
    }
}
