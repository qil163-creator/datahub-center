package cn.iocoder.yudao.framework.etl.core.executor.support;

import cn.iocoder.yudao.framework.dbswitch.common.util.JdbcValueConversions;

import java.math.BigDecimal;

/** CSV v1：NULL=\N，字面值开头的每个反斜杠均转义。空值和空白仍保留为字符串。 */
public final class EtlTextValues {

    /**
     * 初始化 {@code EtlTextValues}。
     */
    private EtlTextValues() {
    }

    /**
     * 将当前数据编码为字符串。
     */
    public static String encode(Object value, boolean spreadsheetSafe) {
        if (value == null) {
            return "\\N";
        }
        Object normalized = JdbcValueConversions.normalize(value);
        String text = normalized instanceof BigDecimal decimal ? decimal.toPlainString() : JdbcValueConversions.toText(
                normalized);
        if (text.startsWith("\\")) {
            return "\\" + text;
        }
        String stripped = text.stripLeading();
        if (spreadsheetSafe && (!stripped.isEmpty() && "=+-@".indexOf(stripped.charAt(0)) >= 0
                || !text.isEmpty() && "\t\r\n".indexOf(text.charAt(0)) >= 0)) {
            return "\\F" + text;
        }
        return text;
    }

    /**
     * 从编码数据恢复对象。
     */
    public static Object decode(String text) {
        if (text == null) {
            throw new IllegalArgumentException("CSV row width does not match its header");
        }
        if (text.equals("\\N")) {
            return null;
        }
        if (text.startsWith("\\\\")) {
            return text.substring(1);
        }
        if (text.startsWith("\\F")) {
            return text.substring(2);
        }
        return text;
    }
}
