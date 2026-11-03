package cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl;

import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.StatementResultSet;
import org.springframework.jdbc.core.SqlTypeValue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * 精确的 SQL Server 值传输；服务器按延伸格里高利历和 100 纳秒刻度存储时间；java.sql
 * 访问器会偏移 1582-10-05 至 14 的日期，java.time 设置器会偏移历法切换前日期，因此读取精确的 java.time 值，
 * 并绑定不受 DATEFORMAT 或 LANGUAGE 影响的 ISO 8601 文本。GUID 使用 java.util.UUID，绝不根据字节或文本
 * 推断 SQL Server GUID 的字节序及排序；MAX 和 LOB 值在字节上限内流式处理。
 */
public final class SqlServerTimeValues {

    /** microsoft.sql.Types.DATETIMEOFFSET，驱动仅作为运行时依赖。 */
    public static final int DATETIMEOFFSET = -155;

    private static final LocalDate CUTOVER = LocalDate.of(1582, 10, 15), AMBIGUOUS_UNTIL = LocalDate.of(1582, 10, 25);

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd", Locale.ROOT);

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSSSSSS", Locale.ROOT);

    private static final DateTimeFormatter DATETIME2 = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSS",
            Locale.ROOT);

    private static final DateTimeFormatter OFFSET = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSSxxx",
            Locale.ROOT);

    private enum Kind {PLAIN, DATE, DATETIME2, OFFSET, GUID, TEXT, BYTES}

    /**
     * 初始化 {@code SqlServerTimeValues}。
     */
    private SqlServerTimeValues() {
    }

    /**
     * 使用保留当前类型精确语义的读取路径。
     */
    public static StatementResultSet losslessRead(StatementResultSet resource) {
        return losslessRead(resource,
                cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions.defaults().maxCacheBytes());
    }

    /** 第一行读取前即拒绝 sql_variant，每个值的基础类型无法在 JDBC 重新绑定时保留。 */
    public static StatementResultSet losslessRead(StatementResultSet resource, long maxCellBytes) {
        try {
            if (maxCellBytes < 1) {
                throw new IllegalArgumentException("Invalid SQL Server cell limit");
            }
            ResultSetMetaData metadata = resource.getResultset().getMetaData();
            Kind[] kinds = new Kind[metadata.getColumnCount() + 1];
            for (int i = 1; i < kinds.length; i++) {
                String name = metadata.getColumnTypeName(i) == null ? "" : metadata.getColumnTypeName(i)
                        .toLowerCase(Locale.ROOT);
                int type = metadata.getColumnType(i);
                if (type == -156 || name.equals("sql_variant")) {
                    throw new IllegalArgumentException(
                            "SQL Server sql_variant values have no exact transport; convert the column explicitly in a source query");
                }
                kinds[i] = kind(type, name, metadata.getPrecision(i));
            }
            resource = cn.iocoder.yudao.framework.dbswitch.dbcommon.database.JdbcTimeValues.losslessRead(resource);
            ResultSet delegate = resource.getResultset();
            ResultSetMetaData described = (ResultSetMetaData) Proxy.newProxyInstance(
                    ResultSetMetaData.class.getClassLoader(),
                    new Class<?>[]{ResultSetMetaData.class}, (ignored, method, args) -> {
                        if (args != null && args.length == 1 && args[0] instanceof Integer index && index > 0 && index < kinds.length
                                && method.getName().equals("getColumnClassName") && kinds[index] == Kind.GUID) {
                            return UUID.class.getName();
                        }
                        try {
                            return method.invoke(metadata, args);
                        } catch (InvocationTargetException error) {
                            throw error.getCause();
                        }
                    });
            ResultSet proxy = (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                    new Class<?>[]{ResultSet.class},
                    (ignored, method, args) -> {
                        if (method.getName().equals("getMetaData") && args == null) {
                            return described;
                        }
                        if (method.getName().equals("getObject") && args != null && args.length == 1) {
                            int index = args[0] instanceof Integer number ? number : delegate.findColumn(
                                    (String) args[0]);
                            if (index > 0 && index < kinds.length && kinds[index] != Kind.PLAIN) {
                                return read(delegate, index, kinds[index], maxCellBytes);
                            }
                        }
                        try {
                            return method.invoke(delegate, args);
                        } catch (InvocationTargetException error) {
                            throw error.getCause();
                        }
                    });
            // 原始资源仍管理结果集、语句和连接。
            resource.setResultset(proxy);
            return resource;
        } catch (SQLException | RuntimeException failure) {
            try {
                resource.close();
            } catch (Exception ignored) { /* 无论哪种情况都拒绝读取 */ }
            if (failure instanceof IllegalArgumentException rejected) {
                throw rejected;
            }
            throw new IllegalStateException("SQL Server result metadata could not be verified");
        }
    }

    /**
     * 根据当前输入判定类型或操作种类。
     */
    private static Kind kind(int type, String name, int precision) {
        if (type == Types.DATE) {
            return Kind.DATE;
        }
        if (type == Types.TIMESTAMP && name.equals("datetime2")) {
            return Kind.DATETIME2;
        }
        if (type == DATETIMEOFFSET) {
            return Kind.OFFSET;
        }
        if (name.equals("uniqueidentifier")) {
            return Kind.GUID;
        }
        // MAX、旧版 LOB 和 XML 文本；geometry 和 geography 是无界 CLR 序列化值。
        if (precision >= 1_073_741_823 && (type == Types.VARCHAR || type == Types.NVARCHAR || type == Types.LONGVARCHAR
                || type == Types.LONGNVARCHAR || type == Types.CHAR || type == Types.NCHAR)) {
            return Kind.TEXT;
        }
        if (type == Types.LONGNVARCHAR && name.equals("xml")) {
            return Kind.TEXT;
        }
        if (precision >= 1_073_741_823 && (type == Types.VARBINARY || type == Types.LONGVARBINARY || type == Types.BINARY)
                || type == -157 || type == -158) {
            return Kind.BYTES;
        }
        return Kind.PLAIN;
    }

    /**
     * 从 JDBC 结果集读取当前字段或数据行。
     */
    private static Object read(ResultSet rows, int index, Kind kind, long maxCellBytes) throws SQLException {
        return switch (kind) {
            case DATE -> {
                Object legacy = rows.getObject(index);
                if (legacy == null) {
                    yield null;
                }
                LocalDate exact = rows.getObject(index, LocalDate.class);
                yield legacy instanceof java.sql.Date date && date.toLocalDate().equals(exact) ? legacy : exact;
            }
            case DATETIME2 -> {
                Object legacy = rows.getObject(index);
                if (legacy == null) {
                    yield null;
                }
                LocalDateTime exact = rows.getObject(index, LocalDateTime.class);
                yield legacy instanceof java.sql.Timestamp timestamp && timestamp.toLocalDateTime()
                        .equals(exact) ? legacy : exact;
            }
            case OFFSET -> {
                // 驱动值保留已有 JDBC 绑定，差异暂存会在任何写入前拒绝它。
                Object legacy = rows.getObject(index);
                if (legacy == null) {
                    yield null;
                }
                requireUnambiguous(rows.getObject(index, OffsetDateTime.class));
                yield legacy;
            }
            case GUID -> {
                String text = rows.getString(index);
                yield text == null ? null : UUID.fromString(text);
            }
            case TEXT -> boundedText(rows, index, maxCellBytes);
            case BYTES -> boundedBytes(rows, index, maxCellBytes);
            default -> rows.getObject(index);
        };
    }

    /** 采用与批次记账一致的 UTF-16 估算；超长值直接拒绝，不截断也不完整缓冲。 */
    public static String boundedText(ResultSet rows, int index, long maxBytes) throws SQLException {
        try (var reader = rows.getCharacterStream(index)) {
            if (reader == null) {
                return null;
            }
            StringBuilder text = new StringBuilder();
            char[] buffer = new char[8192];
            for (int count; (count = reader.read(buffer)) != -1; ) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new SQLException("SQL Server text read interrupted");
                }
                if (2L * (text.length() + (long) count) > maxBytes) {
                    throw new SQLException("SQL Server text value exceeds the cell limit");
                }
                text.append(buffer, 0, count);
            }
            return text.toString();
        } catch (IOException failure) {
            throw new SQLException("SQL Server text value could not be read");
        }
    }

    /**
     * 检查字节数组长度处于当前协议允许范围。
     */
    public static byte[] boundedBytes(ResultSet rows, int index, long maxBytes) throws SQLException {
        try (var input = rows.getBinaryStream(index)) {
            if (input == null) {
                return null;
            }
            var bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) != -1; ) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new SQLException("SQL Server binary read interrupted");
                }
                if ((long) bytes.size() + count > maxBytes) {
                    throw new SQLException("SQL Server binary value exceeds the cell limit");
                }
                bytes.write(buffer, 0, count);
            }
            return bytes.toByteArray();
        } catch (IOException failure) {
            throw new SQLException("SQL Server binary value could not be read");
        }
    }

    /** 驱动将 UTC 历法空缺日期向后规范化十天，这种读取结果无法与真实日期区分。 */
    public static OffsetDateTime requireUnambiguous(OffsetDateTime value) throws SQLException {
        LocalDate utc = value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
        if (!utc.isBefore(CUTOVER) && utc.isBefore(AMBIGUOUS_UNTIL)) {
            throw new SQLException(
                    "SQL Server datetimeoffset values near the 1582 Gregorian cutover cannot be read exactly");
        }
        return value;
    }

    /** 未验证 SQL Server 16 值域的服务器使用历史绑定，仅 TIME 避免 java.sql.Time 精度损失。 */
    public static java.util.List<Object[]> bind(java.util.List<Object[]> rows, int[] types) {
        if (java.util.Arrays.stream(types).noneMatch(type -> type == Types.TIME)) {
            return rows;
        }
        java.util.List<Object[]> copy = new java.util.ArrayList<>(rows.size());
        for (var row : rows) {
            Object[] values = row.clone();
            for (int i = 0; i < types.length; i++) {
                if (types[i] == Types.TIME && values[i] != null) {
                    LocalTime time = cn.iocoder.yudao.framework.dbswitch.common.util.JdbcValueConversions.toTime(
                            values[i]);
                    if (time.getNano() % 100 != 0) {
                        throw new IllegalArgumentException("SQL Server TIME cannot preserve sub-100ns precision");
                    }
                    String text = time.toString();
                    values[i] = (SqlTypeValue) (statement, index, sqlType, typeName) -> statement.setString(index,
                            text);
                }
            }
            copy.add(values);
        }
        return java.util.List.copyOf(copy);
    }

    /** 已验证值的精确参数；datetime 和 smalldatetime 保留已验证刻度的驱动绑定。 */
    public static Object bindable(Object value, String baseType) {
        if (value == null) {
            return null;
        }
        String text = switch (baseType) {
            case "date" -> value instanceof LocalDate date ? DATE.format(date) : null;
            case "time" -> value instanceof LocalTime time ? TIME.format(time) : null;
            case "datetime2" -> value instanceof LocalDateTime time ? DATETIME2.format(time) : null;
            case "datetimeoffset" -> value instanceof OffsetDateTime time ? OFFSET.format(time) : null;
            default -> null;
        };
        if (text == null) {
            return value;
        }
        return (SqlTypeValue) (statement, index, sqlType, typeName) -> statement.setString(index, text);
    }
}
