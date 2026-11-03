package cn.iocoder.yudao.framework.quality.core.audit;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.util.OceanbaseRelationIdentity;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * 稽查 SQL 方言片段：只覆盖 MySQL 系 / PostgreSQL 系差异点，其他方言按标准 SQL 兜底
 *
 * @author Li
 */
public class AuditSqlDialect {

    /**
     * 初始化 {@code AuditSqlDialect}。
     */
    private AuditSqlDialect() {
    }

    /** 生成 SQL 必须在实际执行连接上验证字符串词法和产品兼容模式。 */
    public static void validateConnection(Connection connection, ProductTypeEnum product) throws SQLException {
        if (product == ProductTypeEnum.OCEANBASE) {
            OceanbaseRelationIdentity.requireVerifiedMysqlMode(connection);
        }
        if (isPostgresLike(product)) {
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(5);
                try (var setting = statement.executeQuery("SHOW standard_conforming_strings")) {
                    if (!setting.next() || !"on".equalsIgnoreCase(setting.getString(1)) || setting.next()) {
                        throw new SQLException("UNSAFE_SQL_STRING_MODE: standard_conforming_strings must be on");
                    }
                }
            }
        }
    }

    /**
     * 判断当前产品是否采用 MySQL 家族方言。
     */
    private static boolean isMysqlLike(ProductTypeEnum productType) {
        return productType != null && productType.isLikeMysql();
    }

    /**
     * 判断当前产品是否采用 PostgreSQL 家族方言。
     */
    private static boolean isPostgresLike(ProductTypeEnum productType) {
        return productType != null && productType.isLikePostgres();
    }

    /**
     * 表达式转文本（PostgreSQL 对数值列使用字符串函数需要显式 CAST）
     */
    public static String castText(cn.iocoder.yudao.framework.dbswitch.core.database.AbstractDatabase database,
            String expr) {
        ProductTypeEnum productType = database.getDatabaseType();
        if (productType == ProductTypeEnum.SQLSERVER || productType == ProductTypeEnum.MARIADB || productType == ProductTypeEnum.CLICKHOUSE || productType == ProductTypeEnum.STARROCKS
                || productType == ProductTypeEnum.DORIS || productType == ProductTypeEnum.TIDB || productType == ProductTypeEnum.OCEANBASE
                || productType == ProductTypeEnum.TDENGINE || productType == ProductTypeEnum.INFLUXDB3 || productType == ProductTypeEnum.HIVE) {
            return database.textColumnExpression(expr);
        }
        if (isPostgresLike(productType)) {
            return "CAST(" + expr + " AS TEXT)";
        }
        return expr;
    }

    /**
     * 正则匹配片段
     */
    public static String regexpMatch(ProductTypeEnum productType, String expr, String regex) {
        if (isMysqlLike(productType)) {
            return caseSensitive(productType, expr) + " REGEXP " + stringLiteral(productType, regex);
        }
        if (isPostgresLike(productType)) {
            return expr + " ~ " + stringLiteral(productType, regex);
        }
        return "REGEXP_LIKE(" + expr + ", " + stringLiteral(productType, regex) + ")";
    }

    /**
     * 字符串长度（按字符数）
     */
    public static String charLength(ProductTypeEnum productType, String expr) {
        if (isMysqlLike(productType)) {
            return "CHAR_LENGTH(" + expr + ")";
        }
        return "LENGTH(" + expr + ")";
    }

    /** 在默认排序规则通常为 *_ci 的 MySQL 系列数据库中，强制使用字节和大小写敏感的相等比较。 */
    public static String caseSensitive(ProductTypeEnum productType, String expr) {
        return isMysqlLike(productType) ? "BINARY " + expr : expr;
    }

    /**
     * 子串位置（从 1 开始，未命中返回 0）
     */
    public static String position(ProductTypeEnum productType, String expr, String search) {
        if (isPostgresLike(productType)) {
            return "POSITION(" + stringLiteral(productType, search) + " IN " + expr + ")";
        }
        return "INSTR(" + expr + ", " + stringLiteral(productType, search) + ")";
    }

    /**
     * SQL 字符串常量转义
     */
    public static String escape(String value) {
        return value == null ? "" : value.replace("'", "''");
    }

    /**
     * 为单个 SQL 字符串字面值转义内容。MySQL 兼容服务器在未启用
     * NO_BACKSLASH_ESCAPES 时会解释反斜杠转义，因此仅转义引号
     * 并不充分（例如 {@code \\'; DROP ...}）。
     */
    public static String escape(ProductTypeEnum productType, String value) {
        String escaped = value == null ? "" : value;
        if (isMysqlLike(productType)) {
            escaped = escaped.replace("\\", "\\\\");
        }
        return escaped.replace("'", "''");
    }

    /**
     * 构建完整的 SQL 字符串字面值。MySQL 系列目标使用十六进制 UTF-8
     * 字面值，而非引号或反斜杠转义。无论是否启用
     * {@code NO_BACKSLASH_ESCAPES}，生成 SQL 的语义均一致，配置的枚举或正则文本绝不能
     * 提前终止字面值。PostgreSQL 调用方由执行器的
     * {@code standard_conforming_strings=on} 检查保护。
     */
    public static String stringLiteral(ProductTypeEnum productType, String value) {
        if (isMysqlLike(productType)) {
            byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
            return "CONVERT(X'" + HexFormat.of().formatHex(bytes) + "' USING utf8mb4)";
        }
        return "'" + escape(productType, value) + "'";
    }

}
