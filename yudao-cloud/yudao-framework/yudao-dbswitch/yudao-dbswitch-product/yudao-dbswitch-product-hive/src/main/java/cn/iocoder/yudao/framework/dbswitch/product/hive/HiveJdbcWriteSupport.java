package cn.iocoder.yudao.framework.dbswitch.product.hive;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import cn.iocoder.yudao.framework.dbswitch.common.util.JdbcValueConversions;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseHiveImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.NonTransactionalWriteExecution;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 产品持有的 SQL、会话和值行为，由 Hive 全量及差异写入器共享。 */
public final class HiveJdbcWriteSupport {

    private static final DatabaseHiveImpl DIALECT = new DatabaseHiveImpl();

    /**
     * 初始化 {@code HiveJdbcWriteSupport}。
     */
    private HiveJdbcWriteSupport() {
    }

    /**
     * 按Hive方言转义并引用标识符。
     */
    public static String quote(String name) {
        return DIALECT.quoteIdentifier(name);
    }

    /**
     * 返回表。
     */
    public static String table(String schema, String table) {
        return DIALECT.getQuotedSchemaTableCombination(schema, table);
    }

    /**
     * 读取或组装当前操作所使用的列定义。
     */
    public static String columns(List<String> fields) {
        if (fields == null || fields.isEmpty() || fields.stream().distinct().count() != fields.size()) {
            throw new IllegalArgumentException("Hive requires distinct physical columns");
        }
        return fields.stream().map(HiveJdbcWriteSupport::quote).collect(Collectors.joining(","));
    }

    /**
     * 返回键集合。
     */
    public static String keys(List<String> keys) {
        columns(keys);
        return keys.stream().map(key -> quote(key) + "=?").collect(Collectors.joining(" AND "));
    }

    /**
     * 返回键集合。
     */
    public static String keys(List<String> keys, Map<String, ColumnDescription> columns) {
        columns(keys);
        return keys.stream().map(key -> quote(key) + "=" + placeholder(columns.get(key)))
                .collect(Collectors.joining(" AND "));
    }

    /** 自有操作连接可以配置会话；仅获取目录元数据时不得配置。 */
    public static void session(Connection connection, int timeout) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            statement.execute("SET hive.resultset.use.unique.column.names=false");
            statement.execute("SET hive.support.concurrency=true");
            statement.execute("SET hive.txn.manager=org.apache.hadoop.hive.ql.lockmgr.DbTxnManager");
        }
    }

    /**
     * 返回元数据。
     */
    public static Map<String, ColumnDescription> metadata(Connection connection, String schema, String table,
            List<String> fields) {
        columns(fields);
        var columns = DIALECT.queryTableColumnMeta(connection, schema, table).stream()
                .collect(Collectors.toUnmodifiableMap(ColumnDescription::getFieldName, column -> column));
        if (!columns.keySet().containsAll(fields)) {
            throw new IllegalArgumentException("Hive selected columns are not present");
        }
        return columns;
    }

    /** Hive 声明式键最多为 DISABLE/NOVALIDATE；必须根据当前数据证明唯一性。 */
    public static void requireUniqueKeys(Connection connection, String schema, String table, List<String> keys,
            int timeout) throws Exception {
        String from = table(schema, table);
        String nulls = keys.stream().map(key -> quote(key) + " IS NULL").collect(Collectors.joining(" OR "));
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            try (var rows = statement.executeQuery("SELECT 1 FROM " + from + " WHERE " + nulls + " LIMIT 1")) {
                if (rows.next()) {
                    throw new IllegalArgumentException("Hive match key contains NULL");
                }
            }
            try (var rows = statement.executeQuery(
                    "SELECT 1 FROM " + from + " GROUP BY " + columns(keys) + " HAVING COUNT(*)>1 LIMIT 1")) {
                if (rows.next()) {
                    throw new IllegalArgumentException("Hive match keys are not unique");
                }
            }
        }
    }

    /**
     * 按目标类型约定转换当前输入值。
     */
    public static List<Object[]> convert(List<Object[]> records, List<String> fields,
            Map<String, ColumnDescription> columns, BatchLimits limits) {
        return BatchValueConverter.convert(records, fields.size(), limits, (value, index) -> {
            if (value == null) {
                return null;
            }
            ColumnDescription column = columns.get(fields.get(index));
            if (column == null) {
                throw new IllegalArgumentException("Hive target column metadata is unavailable");
            }
            if (text(column)) {
                String text = JdbcValueConversions.toText(value);
                textBytes(text); // 任何分块发送前先校验每一行。
                return text;
            }
            if (column.getFieldType() == Types.DECIMAL || column.getFieldType() == Types.NUMERIC) {
                try {
                    var decimal = JdbcValueConversions.toDecimal(value)
                            .setScale(column.getScaleSize(), java.math.RoundingMode.UNNECESSARY);
                    if (column.getPrecisionSize() < 1 || column.getPrecisionSize() > 38 || decimal.precision() > column.getPrecisionSize()) {
                        throw new ArithmeticException();
                    }
                    return decimal;
                } catch (RuntimeException failure) {
                    throw new IllegalArgumentException("Hive decimal cannot be represented exactly");
                }
            }
            return switch (column.getFieldType()) {
                case Types.TINYINT -> JdbcValueConversions.toByte(value);
                case Types.SMALLINT -> JdbcValueConversions.toShort(value);
                case Types.INTEGER -> JdbcValueConversions.toInteger(value);
                case Types.BIGINT -> JdbcValueConversions.toLong(value);
                case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> JdbcValueConversions.toBytes(value);
                default -> value;
            };
        });
    }

    /**
     * 按当前值域约定解析或转换二进制。
     */
    private static boolean binary(ColumnDescription column) {
        return column.getFieldType() == Types.BINARY || column.getFieldType() == Types.VARBINARY || column.getFieldType() == Types.LONGVARBINARY;
    }

    /**
     * 按当前值域约定解析或转换文本。
     */
    private static boolean text(ColumnDescription column) {
        return switch (column.getFieldType()) {
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR -> true;
            default -> false;
        };
    }

    /**
     * 将文本严格编码为 UTF-8 字节并拒绝不可编码字符。
     */
    private static byte[] textBytes(String text) {
        try {
            var encoded = java.nio.charset.StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(text));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (java.nio.charset.CharacterCodingException failure) {
            throw new IllegalArgumentException("Hive text cannot be represented as valid UTF-8");
        }
    }

    /**
     * 根据目标列类型生成带必要转换的参数占位符。
     */
    public static String placeholder(ColumnDescription column) {
        if (column == null) {
            return "?";
        }
        if (column.getFieldType() == Types.DECIMAL || column.getFieldType() == Types.NUMERIC) {
            return "CAST(? AS DECIMAL(" + column.getPrecisionSize() + "," + column.getScaleSize() + "))";
        }
        return binary(column) ? "unhex(?)" : text(column) ? "decode(unhex(?),'UTF-8')" : "?";
    }

    /**
     * 将当前值绑定到预编译语句参数。
     */
    public static void bind(PreparedStatement statement, int index, Object value,
            ColumnDescription column) throws Exception {
        // Hive JDBC 的 byte[] setObject/getBytes 实现不一致。BINARY 显式编码。
        if (value != null && (column.getFieldType() == Types.DECIMAL || column.getFieldType() == Types.NUMERIC)) {
            statement.setString(index, ((java.math.BigDecimal) value).toPlainString());
        } else if (value != null && text(column)) {
            // Hive JDBC 对 SQL 字符串插值，并丢弃转义字符前的反斜杠。
            // 仅绑定 ASCII 十六进制；SQL 解析后由 Hive 解码原始 UTF-8 字节。
            statement.setString(index,
                    java.util.HexFormat.of().formatHex(textBytes(JdbcValueConversions.toText(value))));
        } else {
            statement.setObject(index,
                    binary(column) && value != null ? java.util.HexFormat.of().formatHex((byte[]) value) : value);
        }
    }

    /**
     * 按当前定义插入一条或一批数据。
     */
    public static long insert(Connection connection, NonTransactionalWriteExecution.Attempt attempt, String schema,
            String table,
            List<String> fields, Map<String, ColumnDescription> columns, List<Object[]> records, BatchLimits limits,
            int timeout) throws Exception {
        int chunkSize = limits.maxMultiValueRows(fields.size(), 0);
        String rowSql = "(" + fields.stream().map(field -> placeholder(columns.get(field)))
                .collect(Collectors.joining(",")) + ")";
        long confirmed = 0;
        for (int start = 0; start < records.size(); start += chunkSize) {
            int end = Math.min(records.size(), start + chunkSize);
            String sql = "INSERT INTO TABLE " + table(schema, table) + " (" + columns(fields) + ") VALUES "
                    + String.join(",", java.util.Collections.nCopies(end - start, rowSql));
            try (var statement = connection.prepareStatement(sql)) {
                statement.setQueryTimeout(timeout);
                int parameter = 1;
                for (int row = start; row < end; row++) {
                    for (int column = 0; column < fields.size(); column++) {
                        bind(statement, parameter++, records.get(row)[column], columns.get(fields.get(column)));
                    }
                }
                attempt.sending();
                int affected = statement.executeUpdate();
                if (affected != end - start) {
                    throw new IllegalStateException("Hive INSERT did not confirm every row");
                }
                confirmed += affected;
            }
        }
        return confirmed;
    }
}
