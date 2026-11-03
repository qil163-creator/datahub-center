package cn.iocoder.yudao.framework.dbswitch.product.oceanbase;

import cn.iocoder.yudao.framework.dbswitch.common.util.JdbcValueConversions;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** MySQL 模式原生精度。JDBC 2.4.x 将含小数时间列的 scale 报告为零。 */
public final class OceanbaseTemporalColumns {

    private static final int[] POWERS = {1, 10, 100, 1000, 10000, 100000, 1000000, 10000000, 100000000, 1000000000};

    private static final long MAX_TIME_MICROS = 3_020_399_000_000L;

    /**
     * 初始化 {@code OceanbaseTemporalColumns}。
     */
    private OceanbaseTemporalColumns() {
    }

    public record Column(String type, Integer scale) {

        /**
         * 校验构造参数并初始化 {@code Column}。
         */
        public Column {
            if (type == null || (scale != null && (scale < 0 || scale > 6
                    || !(type.equals("time") || type.equals("datetime") || type.equals("timestamp"))))) {
                throw new IllegalArgumentException("Invalid OceanBase temporal column");
            }
        }

        /**
         * 确定当前列或值对应的 JDBC 类型。
         */
        public int jdbcType() {
            return type.equals("time") ? Types.TIME : scale != null ? Types.TIMESTAMP : Types.OTHER;
        }

        /** 在 JDBC 可能舍入前先校验，并将时长绑定为精确文本，不使用时钟时间。 */
        public Object exactValue(Object value) {
            if (value == null || scale == null) {
                return value;
            }
            if (type.equals("time")) {
                String text;
                if (value instanceof String literal) {
                    text = literal;
                } else if (value instanceof LocalTime || value instanceof java.sql.Time) {
                    LocalTime time = JdbcValueConversions.toTime(value);
                    if (time.getNano() % POWERS[9 - scale] != 0) {
                        throw precisionLoss();
                    }
                    text = String.format(Locale.ROOT, "%02d:%02d:%02d.%06d", time.getHour(), time.getMinute(),
                            time.getSecond(), time.getNano() / 1000);
                } else {
                    throw new IllegalArgumentException("Unsupported OceanBase TIME value type");
                }
                long micros = JdbcValueConversions.timeDurationMicros(text);
                if (Math.abs(micros) > MAX_TIME_MICROS || micros % POWERS[6 - scale] != 0) {
                    throw precisionLoss();
                }
                return text;
            }
            LocalDateTime time;
            if (value instanceof Timestamp timestamp) {
                time = timestamp.toLocalDateTime();
            } else if (value instanceof LocalDateTime dateTime) {
                time = dateTime;
            } else {
                throw new IllegalArgumentException("Unsupported OceanBase timestamp value type");
            }
            if (time.getNano() % POWERS[9 - scale] != 0) {
                throw precisionLoss();
            }
            return Timestamp.valueOf(time);
        }

        /**
         * 根据当前输入构建并返回 {@code IllegalArgumentException}。
         */
        private static IllegalArgumentException precisionLoss() {
            return new IllegalArgumentException("Value cannot be represented exactly by the OceanBase temporal column");
        }
    }

    /** 仅在请求列为时间列时借用连接；写入期间绝不持有该连接。 */
    public static Map<String, Column> prepare(DataSource source, String schema, String table,
            Map<String, Integer> jdbcTypes, int timeout) {
        if (jdbcTypes.values().stream().noneMatch(type -> type == Types.TIME || type == Types.TIMESTAMP)) {
            return Map.of();
        }
        try (var connection = source.getConnection()) {
            Map<String, Column> catalog = read(connection, schema, table, timeout);
            var selected = new LinkedHashMap<String, Column>();
            jdbcTypes.forEach((name, type) -> {
                Column column = catalog.get(name);
                if (column == null || ((type == Types.TIME || type == Types.TIMESTAMP || column.scale() != null)
                        && column.jdbcType() != type)) {
                    throw new IllegalStateException("OceanBase column catalog changed during preparation");
                }
                if (column.scale() != null) {
                    selected.put(name, column);
                }
            });
            return Collections.unmodifiableMap(selected);
        } catch (SQLException failure) {
            throw new IllegalStateException("OceanBase temporal catalog is unavailable");
        }
    }

    /** 调用方持有连接。保留顺序及所有列，以便检查元数据一致性。 */
    public static Map<String, Column> read(Connection connection, String schema, String table, int timeout) {
        var observed = new LinkedHashMap<String, Column>();
        String sql = "SELECT COLUMN_NAME, DATA_TYPE, DATETIME_PRECISION FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA=? AND TABLE_NAME=? ORDER BY ORDINAL_POSITION";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(2001);
            statement.setString(1, schema);
            statement.setString(2, table);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    String name = rows.getString(1), type = rows.getString(2);
                    Integer scale = rows.getObject(3, Integer.class);
                    if (name == null || type == null || observed.containsKey(name) || observed.size() == 2000) {
                        throw new IllegalStateException("OceanBase column catalog is ambiguous or exceeds the bound");
                    }
                    type = type.toLowerCase(Locale.ROOT);
                    boolean temporal = type.equals("time") || type.equals("datetime") || type.equals("timestamp");
                    if (temporal && (scale == null || scale < 0 || scale > 6)) {
                        throw new IllegalStateException("OceanBase temporal precision is unavailable");
                    }
                    observed.put(name, new Column(type, temporal ? scale : null));
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("OceanBase temporal catalog is unavailable");
        }
        return Collections.unmodifiableMap(observed);
    }
}
