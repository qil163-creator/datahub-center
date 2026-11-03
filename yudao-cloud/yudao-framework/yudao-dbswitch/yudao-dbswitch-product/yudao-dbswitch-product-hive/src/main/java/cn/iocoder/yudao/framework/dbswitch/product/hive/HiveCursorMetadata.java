package cn.iocoder.yudao.framework.dbswitch.product.hive;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.util.JdbcValueConversions;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseHiveImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Hive 4.1 目录标识游标依据：精确数值及时间水位，可持久化的有序键。 */
public final class HiveCursorMetadata {

    private static final DatabaseHiveImpl DIALECT = new DatabaseHiveImpl();

    /**
     * 初始化 {@code HiveCursorMetadata}。
     */
    private HiveCursorMetadata() {
    }

    public record Shape(Map<String, ColumnDescription> columns, String physical, List<String> partitionColumns) {

        /**
         * 校验构造参数并初始化 {@code Shape}。
         */
        public Shape {
            columns = Map.copyOf(columns);
            partitionColumns = List.copyOf(partitionColumns);
            if (physical == null || physical.isBlank()) {
                throw new IllegalArgumentException("Hive physical identity is absent");
            }
        }

        /**
         * 根据当前内容计算结构指纹，供一致性比较使用。
         */
        public String fingerprint() {
            return DigestUtil.sha256Hex("HIVE_CURSOR_SHAPE_V1\0" + physical);
        }
    }

    /**
     * 按当前游标位置读取受限数量的数据行。
     */
    public static Shape read(Connection connection, String schema, String table, int timeout) throws SQLException {
        product(connection);
        String qualified = HiveJdbcWriteSupport.table(schema, table);
        var columns = new LinkedHashMap<String, ColumnDescription>();
        for (var column : DIALECT.queryTableColumnMeta(connection, schema, table)) {
            columns.put(column.getFieldName(), column);
        }
        var identity = new StringBuilder();
        var partitions = new ArrayList<String>();
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            try (var rows = statement.executeQuery("DESCRIBE FORMATTED " + qualified)) {
                if (rows.getMetaData().getColumnCount() != 3) {
                    throw new SQLException("Hive storage response is not supported", "22000");
                }
                int count = 0;
                boolean section = false;
                boolean detailed = false;
                while (rows.next()) {
                    if (++count > 8192) {
                        throw new SQLException("Hive storage metadata exceeds the bound", "54000");
                    }
                    String key = rows.getString(1), value = rows.getString(2);
                    key = key == null ? "" : key.strip();
                    value = value == null ? "" : value.strip();
                    if (key.equals("# Partition Information")) {
                        section = true;
                        continue;
                    }
                    if (key.startsWith("# Detailed")) {
                        detailed = true;
                        section = false;
                        continue;
                    }
                    if (section && !detailed && !key.isBlank() && !key.startsWith("#")) {
                        partitions.add(key);
                    }
                    if (key.equals("Location:") || key.equals("InputFormat:") || key.equals("Num Buckets:")
                            || key.equals("Bucket Columns:") || key.equals("Sort Columns:") || key.equals("CreateTime:")
                            || key.equals("Table Type:")) {
                        identity.append('\0').append(key).append(value);
                    }
                }
            }
        }
        if (columns.isEmpty()) {
            throw new SQLException("Hive cursor columns are unavailable", "22000");
        }
        if (!columns.keySet().containsAll(partitions)) {
            throw new SQLException("Hive partition columns are not described", "22000");
        }
        for (var column : columns.values()) {
            identity.append('\0').append(column.getFieldName()).append('\u0001').append(column.getFieldType())
                    .append('\u0001').append(column.getFieldTypeName()).append('\u0001')
                    .append(column.getPrecisionSize()).append('\u0001').append(column.getScaleSize());
        }
        return new Shape(columns, identity.toString(), partitions);
    }

    /**
     * 返回当前操作使用的数据库产品。
     */
    public static void product(Connection connection) throws SQLException {
        var metadata = connection.getMetaData();
        if (!"Apache Hive".equals(metadata.getDatabaseProductName()) || !metadata.getDatabaseProductVersion()
                .startsWith("4.1.")) {
            throw new SQLException("Hive cursor requires the verified Apache Hive 4.1 server", "0A000");
        }
    }

    /** 水位键为精确数值或日期时间；此处尚未证明文本水位具有单调标识。 */
    public static void requireWatermark(ColumnDescription column) throws SQLException {
        if (column == null || numeric(column) == null
                && !Set.of(Types.DATE, Types.TIMESTAMP).contains(column.getFieldType())) {
            throw new SQLException("Hive cursor watermark requires an exact numeric or date/time column", "0A000");
        }
    }

    /** 有序键必须无损持久化到 TypedCursorValue，并保持 Hive 自身比较顺序。 */
    public static void requireOrdered(ColumnDescription column) throws SQLException {
        if (column == null || numeric(column) == null && !Set.of(Types.DATE, Types.TIMESTAMP,
                Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR).contains(column.getFieldType())) {
            throw new SQLException("Hive cursor keys require exact numeric, date/time or text columns", "0A000");
        }
    }

    /** 持久化类型化元组中携带的 JDBC 类型；目录名称使结构依据独立保留。 */
    public static int persistedType(ColumnDescription column) {
        return column.getFieldType() == Types.CHAR || column.getFieldType() == Types.LONGVARCHAR
                ? Types.VARCHAR : column.getFieldType();
    }

    /**
     * 返回键集合。
     */
    public static CursorKeyContract keys(Shape shape, List<String> keys) throws SQLException {
        var domains = new ArrayList<CursorKeyContract.Domain>();
        // 重放契约仅绑定有序键值域：同一产品的源表和目标表
        // 可位于不同物理位置，但仍须无损重放。
        var material = new StringBuilder("HIVE_CURSOR_KEYS_V1");
        for (String key : keys) {
            var column = shape.columns().get(key);
            requireOrdered(column);
            var domain = numeric(column);
            domains.add(domain == null ? CursorKeyContract.Domain.nativeOnly() : domain);
            material.append('\0').append(column.getFieldType()).append('\u0001').append(column.getFieldTypeName())
                    .append('\u0001').append(column.getPrecisionSize()).append('\u0001').append(column.getScaleSize());
        }
        return new CursorKeyContract(ProductTypeEnum.HIVE, DigestUtil.sha256Hex(material.toString()), domains);
    }

    /**
     * 按当前值域约定解析或转换数值。
     */
    private static CursorKeyContract.Domain numeric(ColumnDescription column) {
        return switch (column.getFieldType()) {
            case Types.TINYINT -> CursorKeyContract.Domain.integer(8, false);
            case Types.SMALLINT -> CursorKeyContract.Domain.integer(16, false);
            case Types.INTEGER -> CursorKeyContract.Domain.integer(32, false);
            case Types.BIGINT -> CursorKeyContract.Domain.integer(64, false);
            case Types.DECIMAL, Types.NUMERIC -> column.getPrecisionSize() < 1 || column.getPrecisionSize() > 38
                    || column.getScaleSize() < 0 || column.getScaleSize() > column.getPrecisionSize()
                    ? null : CursorKeyContract.Domain.decimal(column.getPrecisionSize(), column.getScaleSize(), false);
            default -> null;
        };
    }

    /** Hive JDBC 插值参数；数值、文本及时间值通过已验证的仅含 ASCII 形式渲染。 */
    public static String comparison(ColumnDescription column, String operator) {
        String quoted = HiveJdbcWriteSupport.quote(column.getFieldName());
        return switch (column.getFieldType()) {
            case Types.DECIMAL, Types.NUMERIC -> quoted + " " + operator + " CAST(? AS DECIMAL("
                    + column.getPrecisionSize() + "," + column.getScaleSize() + "))";
            case Types.DATE -> quoted + " " + operator + " CAST(? AS DATE)";
            case Types.TIMESTAMP -> quoted + " " + operator + " CAST(? AS TIMESTAMP)";
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR -> quoted + " " + operator + " decode(unhex(?),'UTF-8')";
            default -> quoted + " " + operator + " ?";
        };
    }

    /**
     * 生成当前键分量的相等比较表达式。
     */
    public static String equality(ColumnDescription column) {
        return comparison(column, "=");
    }

    /**
     * 将当前值绑定到预编译语句参数。
     */
    public static void bind(PreparedStatement statement, int index, Object value,
            ColumnDescription column) throws SQLException {
        if (value == null) {
            throw new SQLException("Hive cursor bounds cannot be NULL", "22000");
        }
        switch (column.getFieldType()) {
            case Types.DECIMAL, Types.NUMERIC -> statement.setString(index, ((BigDecimal) value).toPlainString());
            case Types.DATE -> statement.setString(index, ((LocalDate) value).toString());
            case Types.TIMESTAMP -> {
                // Hive 将只含分钟的字面量解析为 NULL；必须提供完整秒数，并保持纳秒精确。
                var time = (LocalDateTime) value;
                String text = time.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                if (time.getNano() != 0) {
                    text += "." + String.format("%09d", time.getNano()).replaceFirst("0+$", "");
                }
                statement.setString(index, text);
            }
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR -> statement.setString(index,
                    java.util.HexFormat.of().formatHex(String.valueOf(value).getBytes(StandardCharsets.UTF_8)));
            default -> statement.setLong(index, ((Number) value).longValue());
        }
    }

    /** 游标行及类型化位置的原始 JDBC 单元格；十进制及时间值保留精确形式。 */
    public static Object read(ResultSet rows, int index, ColumnDescription column,
            long maxCellBytes) throws SQLException {
        return JdbcValueConversions.normalize(rows.getObject(index), maxCellBytes);
    }

    /**
     * 生成模式与表组成的限定关系名。
     */
    public static String relation(String schema, String table) {
        return HiveJdbcWriteSupport.table(schema, table);
    }
}
