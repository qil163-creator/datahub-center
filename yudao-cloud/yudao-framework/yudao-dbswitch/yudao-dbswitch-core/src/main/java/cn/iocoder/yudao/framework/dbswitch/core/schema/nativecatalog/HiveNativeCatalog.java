package cn.iocoder.yudao.framework.dbswitch.core.schema.nativecatalog;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataProvenance;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import org.apache.commons.lang3.StringEscapeUtils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 普通 TEXTFILE 表的 Hive 4.1.0 列目录。
 * JDBC {@code SELECT * WHERE 1=0} 将列命名为 {@code table.column}；DESCRIBE {@code col_name} 不带限定名。
 * 通过不带限定名的名称对齐。此读取器绝不查询 information_schema。
 * 仅接受采用 TextInputFormat 和 LazySimpleSerDe 的 {@code EXTERNAL_TABLE}。
 * 拒绝 ACID 表、分区表和视图。
 */
public final class HiveNativeCatalog {

    public static final String VERSION = "4.1.0 r75e40b7537c91a70ccaa31c397d21823c7528eeb";

    private static final String TEXT_INPUT = "org.apache.hadoop.mapred.TextInputFormat";

    private static final String LAZY_SERDE = "org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe";

    private static final int CHAR_MAX = 255;

    private static final int VARCHAR_MAX = 65535;

    private static final Pattern CHAR_TYPE = Pattern.compile("char\\((\\d+)\\)");

    private static final Pattern VARCHAR_TYPE = Pattern.compile("varchar\\((\\d+)\\)");

    private static final Pattern DECIMAL_TYPE = Pattern.compile("decimal\\((\\d+),(\\d+)\\)");

    /**
     * 初始化 {@code HiveNativeCatalog}。
     */
    private HiveNativeCatalog() {
    }

    /**
     * 供公共读取及独立保护的 Hive 执行适配器使用的物理列元数据。
     * 即使调用方使用 DummyTxnManager，Metastore getColumns 仍可读取 ACID 表；不会
     * 对关系执行 SELECT，也不执行会话 SET。这不授予 DDL 重建能力。
     */
    public static List<ColumnDescription> readMetadata(Connection connection, String schema, String table,
            int timeoutSeconds)
            throws SQLException {
        if (connection == null || timeoutSeconds < 1) {
            throw new SQLException("Hive catalog arguments are incomplete", "22023");
        }
        String relation = RestrictedSelectGuard.qualifiedTableName(ProductTypeEnum.HIVE, schema, table);
        String version = one(connection, "SELECT version()", timeoutSeconds);
        if (!VERSION.equals(version)) {
            throw new SQLFeatureNotSupportedException("Hive native version is unverified", "0A000");
        }
        List<Described> described = metadataDescription(connection, relation, timeoutSeconds);
        var ordered = new java.util.TreeMap<Integer, JdbcColumn>();
        var comments = new java.util.HashMap<String, String>();
        try (ResultSet rows = connection.getMetaData().getColumns(null, schema, table, null)) {
            int observed = 0;
            while (rows.next()) {
                if (++observed > 8192) {
                    throw new SQLException("Hive metastore result exceeds the bound", "54000");
                }
                // JDBC 参数属于模式匹配。绝不能让 '_' 或 '%' 选择另一个物理对象。
                if (!schema.equalsIgnoreCase(rows.getString("TABLE_SCHEM"))
                        || !table.equalsIgnoreCase(rows.getString("TABLE_NAME"))) {
                    continue;
                }
                int ordinal = rows.getInt("ORDINAL_POSITION"), type = rows.getInt("DATA_TYPE");
                if (ordinal < 1 || ordinal > 4096) {
                    throw new SQLException("Hive column metadata exceeds the bound", "54000");
                }
                int precision = rows.getInt("COLUMN_SIZE"), scale = rows.getInt("DECIMAL_DIGITS");
                // Hive 4.1 metastore 报告 BOOLEAN 大小为 0；其 ResultSet 元数据报告 precision/display 为 1。
                if (type == java.sql.Types.BOOLEAN) {
                    if (precision != 0 || scale != 0) {
                        throw new SQLException("Hive boolean metadata is unverified", "22000");
                    }
                    precision = 1;
                }
                String comment = rows.getString("REMARKS");
                if (comment != null && comment.length() > 1_048_576) {
                    throw new SQLException("Hive comment exceeds the bound", "54000");
                }
                comments.put(rows.getString("COLUMN_NAME"), comment);
                String typeName = rows.getString("TYPE_NAME");
                if (typeName == null || typeName.isBlank()) {
                    throw new SQLException("Hive metastore type name is absent", "22000");
                }
                var column = new JdbcColumn(rows.getString("COLUMN_NAME"), type, typeName.toLowerCase(Locale.ROOT),
                        javaClass(type), displaySize(type, precision), precision, scale, rows.getInt("NULLABLE"), false,
                        signed(type));
                if (ordered.put(ordinal, column) != null) {
                    throw new SQLException("Hive metastore ordinal is duplicated", "22000");
                }
            }
        }
        for (int ordinal = 1; ordinal <= ordered.size(); ordinal++) {
            if (!ordered.containsKey(ordinal)) {
                throw new SQLException("Hive metastore ordinal is incomplete", "22000");
            }
        }
        var provenance = new MetadataProvenance(ProductTypeEnum.HIVE, "Apache Hive", version, "HIVE_CATALOG_V1",
                Instant.now());
        var result = align(table, provenance, List.copyOf(ordered.values()), described);
        // 文本 DESCRIBE 会填充单元格；getColumns 保留注释中有意存在的前后空白。
        for (var column : result) {
            column.setRemarks(comments.get(column.getFieldName()));
        }
        return result;
    }

    /**
     * 拒绝当前实现未支持的 {@code metadataDescription} 操作。
     */
    private static List<Described> metadataDescription(Connection connection, String relation,
            int timeout) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            try (ResultSet rows = statement.executeQuery("DESCRIBE " + relation)) {
                int count = rows.getMetaData().getColumnCount();
                if (count == 3) {
                    var grid = new ArrayList<String[]>();
                    while (rows.next()) {
                        if (grid.size() >= 8192) {
                            throw new SQLException("Hive column metadata exceeds the bound", "54000");
                        }
                        grid.add(new String[]{rows.getString(1), rows.getString(2), rows.getString(3)});
                    }
                    return described(new Grid(grid));
                }
                if (count != 1 || !rows.next()) {
                    throw new SQLFeatureNotSupportedException("Hive DESCRIBE shape is unverified", "0A000");
                }
                String json = rows.getString(1);
                if (json == null || json.length() > 4_194_304 || rows.next()) {
                    throw new SQLException("Hive JSON metadata exceeds the bound", "54000");
                }
                try {
                    var object = cn.hutool.json.JSONUtil.parseObj(json);
                    var values = object.getJSONArray("columns");
                    if (values == null || values.isEmpty() || values.size() > 4096) {
                        throw new IllegalArgumentException();
                    }
                    var result = new ArrayList<Described>();
                    for (Object value : values) {
                        if (!(value instanceof cn.hutool.json.JSONObject item)) {
                            throw new IllegalArgumentException();
                        }
                        String name = item.getStr("name"), type = item.getStr("type"), comment = item.getStr("comment");
                        if (name == null || name.isBlank() || type == null || type.isBlank()) {
                            throw new IllegalArgumentException();
                        }
                        // JSON 解析已解码转义。不要再对这些单元格执行 Java 反转义。
                        result.add(new Described(name, type, comment));
                    }
                    return result;
                } catch (RuntimeException invalid) {
                    throw new SQLException("Hive JSON column metadata is unverified", "22000");
                }
            }
        }
    }

    /**
     * 返回当前有符号。
     */
    private static boolean signed(int type) {
        return switch (type) {
            case java.sql.Types.TINYINT, java.sql.Types.SMALLINT, java.sql.Types.INTEGER, java.sql.Types.BIGINT -> true;
            default -> false;
        };
    }

    /**
     * 返回显示长度。
     */
    private static int displaySize(int type, int precision) {
        return switch (type) {
            case java.sql.Types.TINYINT, java.sql.Types.SMALLINT, java.sql.Types.INTEGER, java.sql.Types.BIGINT ->
                    precision + 1;
            case java.sql.Types.FLOAT, java.sql.Types.REAL -> 24;
            case java.sql.Types.DOUBLE -> 25;
            case java.sql.Types.NUMERIC, java.sql.Types.DECIMAL -> precision + 2;
            default -> precision;
        };
    }

    /**
     * 已验证的 Hive 4.1 元数据报告 BigInteger，但 getObject 返回 BigDecimal。
     */
    private static String javaClass(int type) {
        return switch (type) {
            case java.sql.Types.TINYINT -> Byte.class.getName();
            case java.sql.Types.SMALLINT -> Short.class.getName();
            case java.sql.Types.INTEGER -> Integer.class.getName();
            case java.sql.Types.BIGINT -> Long.class.getName();
            case java.sql.Types.FLOAT, java.sql.Types.REAL -> Float.class.getName();
            case java.sql.Types.DOUBLE -> Double.class.getName();
            // 已验证的 Hive 4.1 元数据报告 BigInteger，但 getObject 返回 BigDecimal。
            case java.sql.Types.NUMERIC, java.sql.Types.DECIMAL -> java.math.BigInteger.class.getName();
            case java.sql.Types.BOOLEAN, java.sql.Types.BIT -> Boolean.class.getName();
            case java.sql.Types.DATE -> java.sql.Date.class.getName();
            case java.sql.Types.TIMESTAMP -> java.sql.Timestamp.class.getName();
            case java.sql.Types.BINARY, java.sql.Types.VARBINARY -> byte[].class.getName();
            default -> String.class.getName(); // Hive 复杂类型的显示值是字符串，而不是已脱离驱动的 JDBC 数组。
        };
    }

    /**
     * 拒绝当前实现未支持的 {@code read} 操作。
     */
    public static List<ColumnDescription> read(Connection connection, String schema, String table, int timeoutSeconds)
            throws SQLException {
        if (connection == null || timeoutSeconds < 1) {
            throw new SQLException("Hive catalog arguments are incomplete", "22023");
        }
        String relation = RestrictedSelectGuard.qualifiedTableName(ProductTypeEnum.HIVE, schema, table);
        String version = one(connection, "SELECT version()", timeoutSeconds);
        if (version == null) {
            throw new SQLFeatureNotSupportedException("Hive native version unavailable", "0A000");
        }
        if (!VERSION.equals(version)) {
            throw new SQLFeatureNotSupportedException("Hive native version is unverified", "0A000");
        }
        var provenance = new MetadataProvenance(ProductTypeEnum.HIVE, "Apache Hive", version, "HIVE_CATALOG_V1",
                Instant.now());
        TableShape shape = shape(grid(connection, "DESCRIBE FORMATTED " + relation, timeoutSeconds, 8192, 3));
        if (shape.view()) {
            throw new SQLFeatureNotSupportedException("Hive views are not an ordinary TEXTFILE catalog", "0A000");
        }
        // 分区列会在空白 DESCRIBE 行之前重复出现，因此通过格式化部分拒绝该结构。
        if (shape.partitioned()) {
            throw new SQLFeatureNotSupportedException("Hive partitioned tables are not an ordinary TEXTFILE catalog",
                    "0A000");
        }
        if (shape.transactional() || transactional(
                grid(connection, "SHOW TBLPROPERTIES " + relation, timeoutSeconds, 4096, 2))) {
            throw new SQLFeatureNotSupportedException("Hive ACID tables are not an ordinary TEXTFILE catalog", "0A000");
        }
        if (!shape.ordinaryTextFile()) {
            throw new SQLFeatureNotSupportedException("Hive catalog requires an ordinary TEXTFILE table", "0A000");
        }
        List<JdbcColumn> jdbcColumns = jdbcColumns(connection, relation, timeoutSeconds);
        List<Described> described = described(grid(connection, "DESCRIBE " + relation, timeoutSeconds, 4096, 3));
        return align(table, provenance, jdbcColumns, described);
    }

    /** {@code table.column} 或 {@code schema.table.column} 匹配 DESCRIBE {@code column}；裸名称匹配自身。 */
    private static String matchName(String table, String jdbcName, String describedName) {
        if (jdbcName == null || describedName == null || table == null) {
            return null;
        }
        if (jdbcName.equals(describedName)) {
            return describedName;
        }
        String prefix = table + ".";
        if (jdbcName.length() == prefix.length() + describedName.length()
                && jdbcName.regionMatches(true, 0, prefix, 0, prefix.length())
                && jdbcName.regionMatches(false, prefix.length(), describedName, 0, describedName.length())) {
            return describedName;
        }
        String marker = "." + table + ".";
        int at = lastIgnoreCase(jdbcName, marker);
        if (at >= 0 && jdbcName.regionMatches(false, at + marker.length(), describedName, 0, describedName.length())
                && at + marker.length() + describedName.length() == jdbcName.length()) {
            return describedName;
        }
        return null;
    }

    /**
     * 按目标列或字段顺序对齐输入结构。
     */
    private static List<ColumnDescription> align(String table, MetadataProvenance provenance,
            List<JdbcColumn> jdbcColumns, List<Described> described) throws SQLException {
        if (jdbcColumns.isEmpty() || jdbcColumns.size() != described.size()) {
            throw new SQLException("Hive JDBC columns do not match DESCRIBE", "22000");
        }
        var byName = new LinkedHashMap<String, Described>();
        for (Described column : described) {
            if (byName.put(column.name(), column) != null) {
                throw new SQLException("Hive DESCRIBE column is duplicated", "22000");
            }
        }
        var used = new LinkedHashMap<String, ColumnDescription>();
        for (JdbcColumn jdbc : jdbcColumns) {
            Described column = null;
            for (Described candidate : described) {
                if (matchName(table, jdbc.name(), candidate.name()) == null) {
                    continue;
                }
                if (column != null) {
                    throw new SQLException("Hive JDBC column matches more than one DESCRIBE name", "22000");
                }
                column = candidate;
            }
            if (column == null || used.containsKey(column.name())) {
                throw new SQLException("Hive JDBC column does not match DESCRIBE", "22000");
            }
            used.put(column.name(), evidence(provenance, jdbc, column));
        }
        if (used.size() != described.size()) {
            throw new SQLException("Hive DESCRIBE column is missing from JDBC metadata", "22000");
        }
        return List.copyOf(used.values());
    }

    /**
     * 返回当前证据。
     */
    private static ColumnDescription evidence(MetadataProvenance provenance, JdbcColumn jdbc, Described described)
            throws SQLException {
        String nativeType = described.type();
        String lower = nativeType.toLowerCase(Locale.ROOT);
        MetadataValue<Integer> precision = MetadataValue.absent();
        MetadataValue<Integer> scale = MetadataValue.absent();
        MetadataValue<Long> length = MetadataValue.absent();
        if (lower.equals("string") || lower.equals("binary")) {
            // 驱动精度为 2147483647。它不是声明的数值精度或 scale。
        } else if (lower.startsWith("char")) {
            length = MetadataValue.value(characterLength(CHAR_TYPE, lower, 1, CHAR_MAX, "CHAR"));
            agree(jdbc.precision(), length.value().intValue(), jdbc.scale() == 0, "CHAR");
        } else if (lower.startsWith("varchar")) {
            length = MetadataValue.value(characterLength(VARCHAR_TYPE, lower, 1, VARCHAR_MAX, "VARCHAR"));
            agree(jdbc.precision(), length.value().intValue(), jdbc.scale() == 0, "VARCHAR");
        } else if (lower.startsWith("decimal")) {
            int[] numbers = decimal(lower);
            precision = MetadataValue.value(numbers[0]);
            scale = MetadataValue.value(numbers[1]);
            if (jdbc.precision() != numbers[0] || jdbc.scale() != numbers[1]) {
                throw new SQLException("Hive decimal type disagrees with JDBC precision", "22000");
            }
        } else if (scalar(lower)) {
            precision = MetadataValue.value(jdbc.precision());
            scale = MetadataValue.value(jdbc.scale());
        }
        var column = new ColumnDescription();
        column.setFieldName(described.name());
        column.setFieldType(jdbc.type());
        column.setFieldTypeName(jdbc.typeName());
        column.setFiledTypeClassName(jdbc.className());
        column.setDisplaySize(jdbc.display());
        column.setPrecisionSize(jdbc.precision());
        column.setScaleSize(jdbc.scale());
        column.setAutoIncrement(jdbc.auto());
        column.setSigned(jdbc.signed());
        column.setNullable(jdbc.nullable() != ResultSetMetaData.columnNoNulls);
        column.setRemarks(described.comment());
        column.setDbType(ProductTypeEnum.HIVE);
        // DESCRIBE 没有默认值、标识生成或生成表达式。不要为这些事实凭空声明 ABSENT。
        column.setMetadataEvidence(new ColumnMetadataEvidence(provenance, MetadataValue.value(nativeType),
                MetadataValue.unknown(), nullable(jdbc.nullable()), precision, scale, length,
                MetadataValue.unknown(), MetadataValue.unknown(), MetadataValue.absent(), MetadataValue.absent()));
        return column;
    }

    /**
     * 读取或转换当前标量值。
     */
    private static boolean scalar(String lower) {
        return switch (lower) {
            case "tinyint", "smallint", "int", "bigint", "float", "double", "boolean", "date", "timestamp" -> true;
            default -> false;
        };
    }

    /**
     * 返回字符长度。
     */
    private static long characterLength(Pattern pattern, String lower, int min, int max,
            String kind) throws SQLException {
        Matcher matcher = pattern.matcher(lower);
        if (!matcher.matches()) {
            throw new SQLException("Hive " + kind + " type string is unverified", "22000");
        }
        long length;
        try {
            length = Long.parseLong(matcher.group(1));
        } catch (NumberFormatException failure) {
            throw new SQLException("Hive " + kind + " length is unverified", "22000");
        }
        if (length < min || length > max) {
            throw new SQLException("Hive " + kind + " length is outside " + min + ".." + max, "22000");
        }
        return length;
    }

    /**
     * 按当前值域约定解析或转换精确小数。
     */
    private static int[] decimal(String lower) throws SQLException {
        Matcher matcher = DECIMAL_TYPE.matcher(lower);
        if (!matcher.matches()) {
            throw new SQLException("Hive decimal type string is unverified", "22000");
        }
        int precision;
        int scale;
        try {
            precision = Integer.parseInt(matcher.group(1));
            scale = Integer.parseInt(matcher.group(2));
        } catch (NumberFormatException failure) {
            throw new SQLException("Hive decimal type string is unverified", "22000");
        }
        if (precision < 1 || precision > 38 || scale < 0 || scale > precision) {
            throw new SQLException("Hive decimal precision/scale is outside the verified range", "22000");
        }
        return new int[]{precision, scale};
    }

    /**
     * 检查多项元数据证据是否相互一致。
     */
    private static void agree(int jdbcPrecision, int length, boolean scaleZero, String kind) throws SQLException {
        if (jdbcPrecision != length || !scaleZero) {
            throw new SQLException("Hive " + kind + " length disagrees with JDBC precision", "22000");
        }
    }

    /**
     * 返回可空标记。
     */
    private static MetadataValue<Boolean> nullable(int flag) {
        if (flag == ResultSetMetaData.columnNoNulls) {
            return MetadataValue.value(false);
        }
        if (flag == ResultSetMetaData.columnNullable) {
            return MetadataValue.value(true);
        }
        return MetadataValue.unknown();
    }

    /**
     * 读取 JDBC 返回的列描述集合。
     */
    private static List<JdbcColumn> jdbcColumns(Connection connection, String relation,
            int timeout) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            try (ResultSet rows = statement.executeQuery("SELECT * FROM " + relation + " WHERE 1=0")) {
                ResultSetMetaData metadata = rows.getMetaData();
                int count = metadata.getColumnCount();
                if (count < 1 || count > 4096) {
                    throw new SQLException("Hive JDBC column count is outside the bound", "54000");
                }
                var columns = new ArrayList<JdbcColumn>(count);
                for (int i = 1; i <= count; i++) {
                    String name = metadata.getColumnName(i);
                    if (name == null || name.isBlank()) {
                        throw new SQLException("Hive JDBC column name is absent", "22000");
                    }
                    columns.add(new JdbcColumn(name, metadata.getColumnType(i), metadata.getColumnTypeName(i),
                            metadata.getColumnClassName(i), metadata.getColumnDisplaySize(i), metadata.getPrecision(i),
                            metadata.getScale(i), metadata.isNullable(i), metadata.isAutoIncrement(i),
                            metadata.isSigned(i)));
                }
                return columns;
            }
        }
    }

    /**
     * 拒绝当前实现未支持的 {@code described} 操作。
     */
    private static List<Described> described(Grid grid) throws SQLException {
        var columns = new ArrayList<Described>();
        for (String[] row : grid.rows()) {
            String name = cell(row, 0);
            if (name.isEmpty()) {
                break;
            }
            if (name.startsWith("#")) {
                throw new SQLFeatureNotSupportedException("Hive DESCRIBE returned a non-column section", "0A000");
            }
            String type = cell(row, 1);
            if (type.isEmpty()) {
                throw new SQLException("Hive native column type is absent", "22000");
            }
            String comment = cell(row, 2);
            columns.add(new Described(name, type, comment.isEmpty() ? null : comment));
            if (columns.size() > 4096) {
                throw new SQLException("Hive column metadata exceeds the bound", "54000");
            }
        }
        if (columns.isEmpty()) {
            throw new SQLException("Hive table metadata is absent or inaccessible", "42S02");
        }
        return columns;
    }

    /**
     * 读取或构建当前关系及字段的结构信息。
     */
    private static TableShape shape(Grid grid) {
        boolean partition = false;
        boolean partitioned = false;
        boolean transactional = false;
        String tableType = null;
        String input = null;
        String serde = null;
        for (String[] row : grid.rows()) {
            String key = cell(row, 0);
            String value = cell(row, 1);
            String extra = cell(row, 2);
            if (key.startsWith("#")) {
                if (key.equals("# Partition Information")) {
                    partition = true;
                } else if (!key.startsWith("# col_name")) {
                    partition = false;
                }
                continue;
            }
            if (partition && !key.isEmpty()) {
                partitioned = true;
            }
            if (key.equals("Table Type:")) {
                tableType = value;
            }
            if (key.equals("InputFormat:")) {
                input = value;
            }
            if (key.equals("SerDe Library:")) {
                serde = value;
            }
            if (key.equalsIgnoreCase("transactional") && value.equalsIgnoreCase("true")) {
                transactional = true;
            }
            if (key.isEmpty() && value.equalsIgnoreCase("transactional") && extra.equalsIgnoreCase("true")) {
                transactional = true;
            }
        }
        return new TableShape(tableType, input, serde, partitioned, transactional);
    }

    /**
     * 返回当前事务。
     */
    private static boolean transactional(Grid grid) {
        for (String[] row : grid.rows()) {
            String key = cell(row, 0);
            String value = row.length > 1 ? cell(row, 1) : "";
            if (key.equalsIgnoreCase("transactional") && value.equalsIgnoreCase("true")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 读取查询结果中的单个标量或唯一记录。
     */
    private static String one(Connection connection, String sql, int timeout) throws SQLException {
        Grid grid = grid(connection, sql, timeout, 2, 1);
        if (grid.rows().size() != 1) {
            return null;
        }
        String value = grid.rows().get(0)[0];
        return value == null ? null : value;
    }

    /**
     * 拒绝当前实现未支持的 {@code grid} 操作。
     */
    private static Grid grid(Connection connection, String sql, int timeout, int maxRows,
            int expectedColumns) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            try (ResultSet rows = statement.executeQuery(sql)) {
                int columns = rows.getMetaData().getColumnCount();
                if (columns != expectedColumns) {
                    throw new SQLFeatureNotSupportedException("Hive catalog result shape is unverified", "0A000");
                }
                var table = new ArrayList<String[]>();
                while (rows.next()) {
                    if (table.size() >= maxRows) {
                        throw new SQLException("Hive catalog metadata exceeds the bound", "54000");
                    }
                    String[] line = new String[columns];
                    for (int i = 0; i < columns; i++) {
                        line[i] = rows.getString(i + 1);
                    }
                    table.add(line);
                }
                return new Grid(table);
            }
        }
    }

    /** 文本 DESCRIBE 会填充并按 Java 规则转义单元格。按位置读取值，不按结果列标签读取。 */
    private static String cell(String[] row, int index) {
        if (row == null || index >= row.length || row[index] == null) {
            return "";
        }
        String text = StringEscapeUtils.unescapeJava(row[index]);
        return text == null ? "" : text.strip();
    }

    /**
     * 按忽略大小写规则定位最后一个匹配位置。
     */
    private static int lastIgnoreCase(String value, String marker) {
        String lower = value.toLowerCase(Locale.ROOT);
        String needle = marker.toLowerCase(Locale.ROOT);
        return lower.lastIndexOf(needle);
    }

    private record Grid(List<String[]> rows) {

    }

    private record JdbcColumn(String name, int type, String typeName, String className, int display, int precision,
                              int scale, int nullable, boolean auto, boolean signed) {

    }

    private record Described(String name, String type, String comment) {

    }

    private record TableShape(String tableType, String inputFormat, String serde, boolean partitioned,
                              boolean transactional) {

        /**
         * 构建或读取当前对象的只读视图。
         */
        boolean view() {
            return tableType != null && tableType.toUpperCase(Locale.ROOT).contains("VIEW");
        }

        /**
         * 此镜像将普通 CREATE TABLE 重写为 EXTERNAL_TABLE。MANAGED_TABLE 仅用于 ACID ORC 表。
         */
        boolean ordinaryTextFile() {
            // 此镜像将普通 CREATE TABLE 重写为 EXTERNAL_TABLE。MANAGED_TABLE 仅用于 ACID ORC 表。
            return "EXTERNAL_TABLE".equals(tableType) && TEXT_INPUT.equals(inputFormat) && LAZY_SERDE.equals(serde)
                    && !partitioned && !transactional;
        }
    }
}
