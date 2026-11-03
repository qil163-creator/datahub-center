package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;

import java.util.List;

/** 先执行 Oracle 原生标识符限制，再使用共享 SQL 标识符渲染器。 */
public final class OracleSqlNames {

    /**
     * 初始化 {@code OracleSqlNames}。
     */
    private OracleSqlNames() {
    }

    /**
     * 按Oracle方言转义并引用标识符。
     */
    public static String quote(String name) {
        // Oracle 不允许标识符内含双引号，即使在引用语法中双写引号也不允许。
        if (name == null || name.isBlank() || name.indexOf('"') >= 0 || name.indexOf('\0') >= 0
                || name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 128) {
            throw new IllegalArgumentException("Invalid Oracle physical identifier");
        }
        return RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.ORACLE, name);
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
        return String.join(",", names.stream().map(OracleSqlNames::quote).toList());
    }
}
