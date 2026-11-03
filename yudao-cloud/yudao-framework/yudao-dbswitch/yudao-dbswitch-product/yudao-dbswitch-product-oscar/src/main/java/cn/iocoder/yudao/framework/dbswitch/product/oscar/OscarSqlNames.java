package cn.iocoder.yudao.framework.dbswitch.product.oscar;

import java.util.List;

/** 来自元数据的物理名称；绝不折叠大小写或删除标点。 */
public final class OscarSqlNames {

    /**
     * 初始化 {@code OscarSqlNames}。
     */
    private OscarSqlNames() {
    }

    /**
     * 按当前数据库方言转义并引用标识符。
     */
    public static String quote(String name) {
        if (name == null || name.isEmpty() || name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Oscar requires a physical identifier");
        }
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    /**
     * 生成模式与表组成的限定关系名。
     */
    public static String relation(String schema, String table) {
        return quote(schema) + "." + quote(table);
    }

    /**
     * 返回字段集合。
     */
    public static String fields(List<String> names) {
        return String.join(",", names.stream().map(OscarSqlNames::quote).toList());
    }
}
