package cn.iocoder.yudao.framework.dbswitch.core.schema.mapping;

/**
 * 目标原生类型文本及可选的值域 CHECK。模板中的 {@value #COLUMN} 由框架替换为
 * 加引号后的物理列名；映射器绝不自行对标识符加引号或拼接。
 */
public record NativeColumnType(String typeSql, String checkTemplate) {

    public static final String COLUMN = "{column}";

    /**
     * 校验构造参数并初始化 {@code NativeColumnType}。
     */
    public NativeColumnType {
        if (typeSql == null || typeSql.isBlank() || typeSql.indexOf(';') >= 0 || typeSql.contains(COLUMN)) {
            throw new IllegalArgumentException("Invalid native column type");
        }
        if (checkTemplate != null && (checkTemplate.isBlank() || checkTemplate.indexOf(
                ';') >= 0 || !checkTemplate.contains(COLUMN))) {
            throw new IllegalArgumentException("Invalid native column check");
        }
    }

    /**
     * 根据给定参数创建 {@code NativeColumnType} 实例。
     */
    public static NativeColumnType of(String typeSql) {
        return new NativeColumnType(typeSql, null);
    }

    /**
     * 检查类型SQL、检查模板是否满足当前操作的约定。
     */
    public static NativeColumnType checked(String typeSql, String checkTemplate) {
        return new NativeColumnType(typeSql, checkTemplate);
    }

    /**
     * 检查引用列是否满足当前操作的约定。
     */
    public String check(String quotedColumn) {
        return checkTemplate == null ? null : checkTemplate.replace(COLUMN, quotedColumn);
    }
}
