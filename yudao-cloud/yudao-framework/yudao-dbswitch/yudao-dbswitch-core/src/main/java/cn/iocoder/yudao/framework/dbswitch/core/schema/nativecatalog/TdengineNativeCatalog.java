package cn.iocoder.yudao.framework.dbswitch.core.schema.nativecatalog;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataProvenance;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableSchemaMetadata;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * TDengine 3.3.8.8 列目录。{@code SERVER_VERSION()} 与原生结构读取器使用相同版本固定依据。
 * 列标识、类型名和长度来自 {@code DESCRIBE}。不读取 {@code information_schema.ins_columns}：
 * 其中 {@code col_length} 是存储宽度（VARCHAR/VARBINARY n+2，NCHAR n*4+2）；
 * {@code col_precision}、{@code col_scale} 和 {@code col_nullable} 为 SQL NULL。目录 NULL 绝不能
 * 作为 {@link MetadataValue.State#VALUE} 的值存储。
 * <p>
 * {@code INTEGER} 存为 {@code INT}，{@code BINARY(n)} 存为 {@code VARCHAR(n)}。该名称中
 * 的长度来自 DESCRIBE。TIMESTAMP 小数位数取数据库 {@code precision}
 * （ms=3、us=6、ns=9）；JDBC scale 保持 0，不复制。precision 无法读取时，scale 保持缺失。
 * 标签不是普通列。JSON 仅作为标签存在，与其他标签一起省略。
 * 服务器会拒绝首列不是 TIMESTAMP 的表；此读取器也拒绝该结构。
 * 不提交，也不持有连接。不使用 {@code MYSQL_COLUMNS}。
 */
public final class TdengineNativeCatalog {

    public static final String VERSION_SQL = "SELECT SERVER_VERSION()";

    /** 限定名经 {@link RestrictedSelectGuard} 加引号后追加。不使用 {@code ins_columns}。 */
    public static final String DESCRIBE_SQL = "DESCRIBE ";

    /** 仅表示数据库精度，不是列属性，也不是 {@code ins_columns.col_scale}。 */
    public static final String PRECISION_SQL = "SELECT `precision` FROM information_schema.ins_databases WHERE name=";

    private static final Pattern VERSION = Pattern.compile("3\\.3\\.8\\.[0-9]+");

    private static final Pattern SIZED = Pattern.compile("(?:NCHAR|BINARY|VARCHAR|VARBINARY|GEOMETRY)");

    private static final int MAX_COLUMNS = 4096;

    /**
     * 初始化 {@code TdengineNativeCatalog}。
     */
    private TdengineNativeCatalog() {
    }

    /** 仅支持 ms、us 和 ns。其他值（包括 null）均视为未读取。 */
    public static Integer timestampDigits(String databasePrecision) {
        if (databasePrecision == null) {
            return null;
        }
        return switch (databasePrecision) {
            case "ms" -> 3;
            case "us" -> 6;
            case "ns" -> 9;
            default -> null;
        };
    }

    /** 数据库精度无法读取时为缺失状态，绝不将缺失转换为数值。 */
    public static MetadataValue<Integer> timestampScale(String databasePrecision) {
        Integer digits = timestampDigits(databasePrecision);
        return digits == null ? MetadataValue.absent() : MetadataValue.value(digits);
    }

    /**
     * 对于带长度的类型，使用 DESCRIBE 类型标记加 DESCRIBE 长度。不重写类型标记：
     * 存储的 {@code INT} 保持 {@code INT}，存储的 {@code VARCHAR} 保持 {@code VARCHAR}。
     */
    public static String nativeType(String describeType, int describeLength) {
        if (describeType == null || describeType.isBlank()) {
            throw new IllegalArgumentException("TDengine DESCRIBE type is missing");
        }
        String compact = describeType.replace(" ", "").toUpperCase(Locale.ROOT);
        String type = compact.replace("UNSIGNED", " UNSIGNED");
        int paren = type.indexOf('(');
        String base = paren < 0 ? type : type.substring(0, paren);
        if (paren < 0 && SIZED.matcher(base).matches()) {
            if (describeLength < 1) {
                throw new IllegalArgumentException("TDengine DESCRIBE length is missing");
            }
            return base + "(" + describeLength + ")";
        }
        return type;
    }

    /**
     * 读取TDengine原生目录并构建表结构元数据。
     */
    public static TableSchemaMetadata read(Connection connection, String schema, String table,
            int timeoutSeconds) throws SQLException {
        if (timeoutSeconds < 1 || timeoutSeconds > 120) {
            throw new IllegalArgumentException("Invalid metadata timeout");
        }
        String version = serverVersion(connection, timeoutSeconds);
        var provenance = new MetadataProvenance(ProductTypeEnum.TDENGINE, "TDengine", version, "TDENGINE_CATALOG_V1",
                Instant.now());
        String qualified = RestrictedSelectGuard.qualifiedTableName(ProductTypeEnum.TDENGINE, schema, table);
        List<Described> described = describe(connection, qualified, timeoutSeconds);
        if (described.isEmpty() || described.size() > MAX_COLUMNS) {
            throw new SQLException("TDengine DESCRIBE column count is outside the catalog bound");
        }
        if (!"TIMESTAMP".equals(nativeType(described.get(0).type(), described.get(0).length())) || described.get(0)
                .tag()) {
            throw new SQLException("TDengine rejects a table whose first column is not TIMESTAMP");
        }
        Map<String, JdbcColumn> jdbc = selectStar(connection, qualified, timeoutSeconds);
        var known = new HashSet<String>();
        for (Described column : described) {
            if (column.name() == null || column.name().isBlank() || !known.add(column.name())) {
                throw new SQLException("TDengine DESCRIBE column identity is ambiguous");
            }
        }
        for (String name : jdbc.keySet()) {
            if (!known.contains(name)) {
                throw new SQLException("Schema changed during TDengine catalog observation");
            }
        }
        String databasePrecision = databasePrecision(connection, schema, timeoutSeconds);
        List<ColumnDescription> columns = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        int data = 0;
        for (Described column : described) {
            if (column.tag()) {
                continue;
            }
            if ("JSON".equals(nativeType(column.type(), column.length()))) {
                throw new SQLException("JSON is only a TDengine tag and is not a column");
            }
            JdbcColumn meta = jdbc.get(column.name());
            if (meta == null) {
                throw new SQLException("Schema changed during TDengine catalog observation");
            }
            if (++data > MAX_COLUMNS) {
                throw new SQLException("TDengine column count exceeds the catalog bound");
            }
            boolean key = data == 1 || column.compositeKey();
            columns.add(column(provenance, column, meta, databasePrecision, key));
            if (key) {
                keys.add(column.name());
            }
        }
        if (columns.isEmpty()) {
            throw new SQLException("TDengine ordinary column catalog is empty");
        }
        // 标签不是额外对象，也未按索引或触发器查询，所以仍为未知。
        return new TableSchemaMetadata(columns, keys, provenance, MetadataValue.value(true), MetadataValue.unknown());
    }

    /**
     * 返回列。
     */
    private static ColumnDescription column(MetadataProvenance provenance, Described described, JdbcColumn meta,
            String databasePrecision, boolean key) {
        String type = nativeType(described.type(), described.length());
        var column = new ColumnDescription();
        column.setDbType(ProductTypeEnum.TDENGINE);
        column.setFieldName(described.name());
        column.setFieldType(meta.jdbcType());
        column.setFieldTypeName(meta.typeName());
        column.setFiledTypeClassName(meta.className());
        column.setDisplaySize(meta.displaySize());
        column.setPrecisionSize(meta.precision());
        column.setScaleSize(meta.scale());
        column.setSigned(meta.signed());
        column.setAutoIncrement(meta.autoIncrement());
        // 驱动标记仅保留到旧版布尔字段。ins_columns.col_nullable 为 NULL，且
        // WebSocket 的 isNullable 对时间戳列返回不稳定，因此不能作为目录依据。
        column.setNullable(meta.nullable() == ResultSetMetaData.columnNullable);
        column.setPk(key);
        column.setMetadataEvidence(
                new ColumnMetadataEvidence(provenance, MetadataValue.value(type), MetadataValue.absent(),
                        MetadataValue.absent(), numericPrecision(type, meta),
                        numericScale(type, meta, databasePrecision),
                        characterLength(type, described.length()), MetadataValue.absent(), MetadataValue.absent(),
                        MetadataValue.absent(), MetadataValue.absent(), MetadataValue.absent()));
        return column;
    }

    /** JDBC nullable 是驱动标记。null 的 {@code ins_columns.col_nullable} 单元格不构成有效值。 */
    public static MetadataValue<Boolean> nullable(int jdbcNullable) {
        if (jdbcNullable == ResultSetMetaData.columnNoNulls) {
            return MetadataValue.value(false);
        }
        if (jdbcNullable == ResultSetMetaData.columnNullable) {
            return MetadataValue.value(true);
        }
        return MetadataValue.unknown();
    }

    /**
     * 返回数值精度。
     */
    private static MetadataValue<Integer> numericPrecision(String nativeType, JdbcColumn meta) {
        String type = nativeType.toLowerCase(Locale.ROOT);
        if (type.matches("(tinyint|smallint|int|bigint)( unsigned)?") || type.startsWith("decimal(")
                || type.equals("float") || type.equals("double")) {
            return MetadataValue.value(meta.precision());
        }
        return MetadataValue.absent();
    }

    /**
     * 返回小数位数。
     */
    private static MetadataValue<Integer> numericScale(String nativeType, JdbcColumn meta, String databasePrecision) {
        String type = nativeType.toLowerCase(Locale.ROOT);
        if (type.equals("timestamp")) {
            return timestampScale(databasePrecision);
        }
        if (type.matches("(tinyint|smallint|int|bigint)( unsigned)?") || type.startsWith("decimal(")
                || type.equals("float") || type.equals("double")) {
            return MetadataValue.value(meta.scale());
        }
        return MetadataValue.absent();
    }

    /** 仅对带长度类型使用 DESCRIBE 长度。INT 宽度 4 不是字符长度。 */
    public static MetadataValue<Long> characterLength(String nativeType, int describeLength) {
        int paren = nativeType.indexOf('(');
        String base = paren < 0 ? nativeType : nativeType.substring(0, paren);
        if (SIZED.matcher(base).matches() && paren > 0) {
            return MetadataValue.value((long) describeLength);
        }
        return MetadataValue.absent();
    }

    /**
     * 拒绝当前实现未支持的 {@code serverVersion} 操作。
     */
    private static String serverVersion(Connection connection, int timeout) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            try (ResultSet rows = statement.executeQuery(VERSION_SQL)) {
                if (!rows.next()) {
                    throw new SQLFeatureNotSupportedException("TDengine native version unavailable", "0A000");
                }
                String version = rows.getString(1);
                if (rows.next() || version == null || !VERSION.matcher(version).matches()) {
                    throw new SQLFeatureNotSupportedException("TDengine native version is unverified", "0A000");
                }
                return version;
            }
        }
    }

    /**
     * 根据当前输入构建对象或结构的描述信息。
     */
    private static List<Described> describe(Connection connection, String qualified, int timeout) throws SQLException {
        var columns = new ArrayList<Described>();
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            try (ResultSet rows = statement.executeQuery(DESCRIBE_SQL + qualified)) {
                while (rows.next()) {
                    if (columns.size() >= MAX_COLUMNS) {
                        throw new SQLException("TDengine DESCRIBE exceeds the catalog bound");
                    }
                    String name = rows.getString(1);
                    String note = rows.getString(4);
                    note = note == null ? "" : note.trim();
                    columns.add(new Described(name == null ? null : name.trim(), rows.getString(2), rows.getInt(3),
                            "TAG".equals(note), "COMPOSITE KEY".equals(note)));
                }
            }
        }
        return columns;
    }

    /**
     * LIMIT 0 保留投影。WHERE 1=0 不是已探测的 TDengine 写法。
     */
    private static Map<String, JdbcColumn> selectStar(Connection connection, String qualified,
            int timeout) throws SQLException {
        var columns = new LinkedHashMap<String, JdbcColumn>();
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            // LIMIT 0 保留投影。WHERE 1=0 不是已探测的 TDengine 写法。
            try (ResultSet rows = statement.executeQuery("SELECT * FROM " + qualified + " LIMIT 0")) {
                ResultSetMetaData metadata = rows.getMetaData();
                if (metadata.getColumnCount() > MAX_COLUMNS) {
                    throw new SQLException("TDengine SELECT * exceeds the catalog bound");
                }
                for (int index = 1; index <= metadata.getColumnCount(); index++) {
                    String name = metadata.getColumnName(index);
                    if (name == null || name.isBlank() || columns.put(name,
                            new JdbcColumn(metadata.getColumnType(index),
                                    metadata.getColumnTypeName(index), metadata.getColumnClassName(index),
                                    metadata.getPrecision(index),
                                    metadata.getScale(index), metadata.getColumnDisplaySize(index),
                                    metadata.isSigned(index),
                                    metadata.isAutoIncrement(index), metadata.isNullable(index))) != null) {
                        throw new SQLException("TDengine SELECT * column identity is ambiguous");
                    }
                }
            }
        }
        return columns;
    }

    /**
     * 读取数据库报告的数值或时间精度。
     */
    private static String databasePrecision(Connection connection, String schema, int timeout) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            try (ResultSet rows = statement.executeQuery(PRECISION_SQL + "'" + schema + "'")) {
                if (!rows.next()) {
                    return null;
                }
                String precision = rows.getString(1);
                boolean missing = precision == null;
                if (rows.next()) {
                    throw new SQLException("TDengine database precision is ambiguous");
                }
                return missing ? null : precision;
            }
        }
    }

    private record Described(String name, String type, int length, boolean tag, boolean compositeKey) {

    }

    private record JdbcColumn(int jdbcType, String typeName, String className, int precision, int scale,
                              int displaySize, boolean signed, boolean autoIncrement, int nullable) {

    }
}
