package cn.iocoder.yudao.framework.dbswitch.pgwriter.util;

import cn.iocoder.yudao.framework.dbswitch.pgwriter.exceptions.PgConnectionException;
import org.postgresql.PGConnection;

import java.sql.Connection;
import java.util.Optional;

/**
 * PostgreSQL 连接与标识符工具类,负责从 JDBC 连接解包 PGConnection、给标识符加引号及拼装全限定表名。
 */
public final class PostgreSqlUtils {

    /**
     * 初始化 {@code PostgreSqlUtils}。
     */
    private PostgreSqlUtils() {
    }

    /**
     * 取得 PostgreSQL 原生连接，无法转换或解包时抛出异常。
     */
    public static PGConnection getPGConnection(final Connection connection) {
        return tryGetPGConnection(connection)
                .orElseThrow(() -> new PgConnectionException("Could not obtain a PGConnection"));
    }

    /**
     * 尝试直接转换或解包当前连接为 PostgreSQL 原生连接。
     */
    public static Optional<PGConnection> tryGetPGConnection(final Connection connection) {
        final Optional<PGConnection> fromCast = tryCastConnection(connection);
        if (fromCast.isPresent()) {
            return fromCast;
        }
        return tryUnwrapConnection(connection);
    }

    /**
     * 尝试将当前连接直接转换为 PostgreSQL 原生连接。
     */
    private static Optional<PGConnection> tryCastConnection(final Connection connection) {
        if (connection instanceof PGConnection) {
            return Optional.of((PGConnection) connection);
        }
        return Optional.empty();
    }

    /**
     * 尝试通过 JDBC 包装接口取得 PostgreSQL 原生连接。
     */
    private static Optional<PGConnection> tryUnwrapConnection(final Connection connection) {
        try {
            if (connection.isWrapperFor(PGConnection.class)) {
                return Optional.of(connection.unwrap(PGConnection.class));
            }
        } catch (Exception e) {
            // 无需处理
        }
        return Optional.empty();
    }

    public static final char QuoteChar = '"';

    /**
     * 按当前产品方言转义并引用数据库标识符。
     */
    public static String quoteIdentifier(String identifier) {
        // 调用方传入从元数据获得的物理名称，绝不传入已经渲染的 SQL 片段。
        if (identifier == null || identifier.isEmpty() || identifier.codePoints().anyMatch(Character::isISOControl)
                || identifier.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 63) {
            throw new IllegalArgumentException("PostgreSQL physical identifier is invalid");
        }
        return QuoteChar + identifier.replace("\"", "\"\"") + QuoteChar;
    }

    /** 显式转义语法使反斜杠在任一 standard_conforming_strings 模式下都保持字面值。 */
    public static String quoteLiteral(String text) {
        if (text == null || text.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("PostgreSQL string literal is invalid");
        }
        return "E'" + text.replace("\\", "\\\\").replace("'", "''") + "'";
    }

    /**
     * 返回包含模式名的完整限定表名。
     */
    public static String getFullyQualifiedTableName(String schemaName, String tableName,
            boolean usePostgresQuoting) {
        if (usePostgresQuoting) {
            return StringUtils.isNullOrWhiteSpace(schemaName) ? quoteIdentifier(tableName)
                    : String.format("%s.%s", quoteIdentifier(schemaName), quoteIdentifier(tableName));
        }

        if (StringUtils.isNullOrWhiteSpace(schemaName)) {
            return tableName;
        }

        return String.format("%1$s.%2$s", schemaName, tableName);
    }

}
