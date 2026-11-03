package cn.iocoder.yudao.framework.dbswitch.core.util;

import java.util.List;

/** 来自元数据的物理 DM 标识符保留大小写、标点及内嵌引号。 */
public final class DmSqlNames {

    /**
     * 初始化 {@code DmSqlNames}。
     */
    private DmSqlNames() {
    }

    /**
     * 按DM方言转义并引用标识符。
     */
    public static String quote(String name) {
        if (name == null || name.isEmpty() || name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid DM physical identifier");
        }
        return '"' + name.replace("\"", "\"\"") + '"';
    }

    /**
     * 生成模式与表组成的限定关系名。
     */
    public static String relation(String schema, String table) {
        return quote(schema) + '.' + quote(table);
    }

    /**
     * 读取或组装当前操作所使用的列定义。
     */
    public static String columns(List<String> names) {
        return String.join(",", names.stream().map(DmSqlNames::quote).toList());
    }
}
