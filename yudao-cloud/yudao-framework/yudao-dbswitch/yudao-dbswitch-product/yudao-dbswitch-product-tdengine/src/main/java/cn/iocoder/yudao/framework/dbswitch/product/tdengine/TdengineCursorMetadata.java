package cn.iocoder.yudao.framework.dbswitch.product.tdengine;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursorValue;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.util.TdengineRelationIdentity;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;
import java.util.*;

/**
 * 用于类型化游标和时间戳键覆盖的 TDengine 3.3.8.8 关系结构：物理代次（集群、数据库
 * 创建、表或超级表 UID）、精度、DESCRIBE 结构、子表 TAG 证据和精确值编解码器。
 * 叶表（普通表或子表）按时间戳及可选整数 COMPOSITE KEY 排序；超级表按
 * 时间戳、子表名 {@code tbname}（二进制顺序）及可选键排序。仅负责有界目录读取。
 */
public final class TdengineCursorMetadata {

    public static final String TBNAME = "tbname";

    public static final String VERIFIED_SERVER = "3.3.8.8";

    private static final Set<String> KEY_TYPES = Set.of("INT", "INT UNSIGNED", "BIGINT", "BIGINT UNSIGNED");

    /**
     * 初始化 {@code TdengineCursorMetadata}。
     */
    private TdengineCursorMetadata() {
    }

    public record Column(String name, String type, boolean key, boolean tag, TdengineJdbcSupport.Column jdbc) {

        /**
         * 按当前值域约定解析或转换时间戳。
         */
        public boolean timestamp() {
            return type.equals("TIMESTAMP");
        }
    }

    public record Shape(String schema, String table, String kind, String uid, String parent, String parentUid,
                        int digits,
                        List<Column> columns, List<String> tagEvidence, String cluster, String database) {

        /**
         * 返回当前 TDengine 子表所属的超级表信息。
         */
        public boolean superTable() {
            return kind.equals("SUPER_TABLE");
        }

        /**
         * 返回精度。
         */
        public String precision() {
            return TdengineTimestamp.precision(digits);
        }

        /**
         * 按当前值域约定解析或转换时间戳。
         */
        public Column timestamp() {
            return columns.getFirst();
        }

        /**
         * 根据当前列集合构建复合键定义。
         */
        public Optional<Column> compositeKey() {
            return columns.stream().filter(Column::key).findFirst();
        }

        /**
         * 返回当前标签集合。
         */
        public List<Column> tags() {
            return columns.stream().filter(Column::tag).toList();
        }

        /**
         * 返回列。
         */
        public Optional<Column> column(String name) {
            return columns.stream().filter(c -> c.name().equals(name)).findFirst();
        }

        /** 精确有序物理键：叶表 [ts{,key}]，超级表 [ts,tbname{,key}]。 */
        public List<String> keys() {
            var keys = new ArrayList<String>();
            keys.add(timestamp().name());
            if (superTable()) {
                keys.add(TBNAME);
            }
            compositeKey().ifPresent(key -> keys.add(key.name()));
            return List.copyOf(keys);
        }

        /** 完整物理结构和代次证据；纳入源、目标指纹。 */
        public String evidence() {
            var text = new StringBuilder("TDENGINE_RELATION_V1").append('\0').append(VERIFIED_SERVER).append('\0')
                    .append(cluster)
                    .append('\0').append(database).append('\0').append(schema).append('\0').append(table).append('\0')
                    .append(kind)
                    .append('\0').append(uid).append('\0').append(parent).append('\0').append(parentUid).append('\0')
                    .append(digits);
            for (var column : columns) {
                text.append('\0').append(column.name()).append(':').append(column.type()).append(':')
                        .append(column.key())
                        .append(':').append(column.tag()).append(':').append(column.jdbc().jdbcType()).append(':')
                        .append(column.jdbc().nativeType())
                        .append(':').append(column.jdbc().precision()).append(':').append(column.jdbc().scale());
            }
            tagEvidence.forEach(tag -> text.append('\0').append(tag));
            return text.toString();
        }

        /**
         * 有序键相等值域。不包含列名，使显式重命名仍然可行。子表还
         * 绑定 TAG 类型和值：一个设备的行不得重放到标签不同的子表。
         */
        public String keyEvidence() {
            var text = new StringBuilder("TDENGINE_CURSOR_KEY_V1").append('\0').append(precision()).append('\0')
                    .append(superTable() ? "SUPER" : kind.equals("CHILD_TABLE") ? "CHILD" : "NORMAL").append('\0')
                    .append("TIMESTAMP");
            if (superTable()) {
                text.append('\0').append("TBNAME");
            }
            compositeKey().ifPresent(key -> text.append('\0').append(key.type()));
            if (kind.equals("CHILD_TABLE")) {
                for (var tag : tags()) {
                    text.append('\0').append(tag.type());
                }
                tagEvidence.forEach(tag -> text.append('\0').append(tag));
            }
            return DigestUtil.sha256Hex(text.toString());
        }

        /** 源值域：时间戳是精确有符号纪元计数；整数键精确；tbname 仅接受原生类型。 */
        public CursorKeyContract sourceContract() {
            var domains = new ArrayList<CursorKeyContract.Domain>();
            domains.add(CursorKeyContract.Domain.integer(64, false));
            if (superTable()) {
                domains.add(CursorKeyContract.Domain.nativeOnly());
            }
            compositeKey().ifPresent(key -> domains.add(switch (key.type()) {
                case "INT" -> CursorKeyContract.Domain.integer(32, false);
                case "INT UNSIGNED" -> CursorKeyContract.Domain.integer(32, true);
                case "BIGINT" -> CursorKeyContract.Domain.integer(64, false);
                default -> CursorKeyContract.Domain.integer(64, true);
            }));
            return new CursorKeyContract(ProductTypeEnum.TDENGINE, keyEvidence(), domains);
        }

        /** 目标值域仅接受原生类型：其他产品的整数不携带 TDengine 时间戳单位。 */
        public CursorKeyContract targetContract() {
            return new CursorKeyContract(ProductTypeEnum.TDENGINE, keyEvidence(),
                    Collections.nCopies(keys().size(), CursorKeyContract.Domain.nativeOnly()));
        }
    }

    /**
     * 按当前游标位置读取受限数量的数据行。
     */
    public static Shape read(Connection connection, String schema, String table, int timeout) throws SQLException {
        TdengineJdbcSupport.requireValueDriver(connection);
        TdengineJdbcSupport.table(schema, table);
        if (timeout < 1) {
            throw unsupported("TDengine cursor requires a bounded query timeout");
        }
        if (!VERIFIED_SERVER.equals(one(connection, "SELECT server_version()", 1, timeout, false)[0])) {
            throw unsupported("TDengine server version is not verified for typed cursors");
        }
        String cluster = TdengineRelationIdentity.clusterFingerprint(connection, timeout);
        String[] database = one(connection,
                "SELECT CAST(CAST(create_time AS BIGINT) AS VARCHAR(32)),`precision`,`status` FROM information_schema.ins_databases WHERE name="
                        + literal(schema) + " LIMIT 2", 3, timeout, false);
        if (!"ready".equals(database[2])) {
            throw unsupported("TDengine database is not ready");
        }
        int digits;
        try {
            digits = TdengineTimestamp.digits(database[1]);
        } catch (IllegalArgumentException failure) {
            throw unsupported("TDengine timestamp precision is unsupported");
        }
        String kind, uid, parent = "", parentUid = "";
        String[] relation = optional(connection,
                "SELECT `type`,CAST(uid AS VARCHAR(64)),COALESCE(stable_name,'') FROM information_schema.ins_tables WHERE db_name="
                        + literal(schema) + " AND table_name=" + literal(table) + " LIMIT 2", 3, timeout);
        if (relation != null) {
            kind = relation[0];
            uid = relation[1];
            if (kind.equals("CHILD_TABLE")) {
                parent = relation[2];
                TdengineJdbcSupport.quote(parent);
                parentUid = stableUid(connection, schema, parent, timeout);
            } else if (!kind.equals("NORMAL_TABLE") || !relation[2].isEmpty()) {
                throw unsupported("TDengine relation kind is unsupported");
            }
        } else {
            kind = "SUPER_TABLE";
            uid = stableUid(connection, schema, table, timeout);
        }
        if (!uid.matches("-?[1-9][0-9]{0,19}") || !parentUid.isEmpty() && !parentUid.matches("-?[1-9][0-9]{0,19}")) {
            throw unsupported("TDengine physical identity is not verified");
        }
        var described = new ArrayList<String[]>();
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(4097);
            try (var rows = statement.executeQuery("DESCRIBE " + TdengineJdbcSupport.table(schema, table))) {
                while (rows.next()) {
                    if (described.size() == 4096) {
                        throw unsupported("TDengine column count exceeds the cursor bound");
                    }
                    String name = rows.getString(1), type = rows.getString(2), note = Objects.toString(
                            rows.getString(4), "");
                    if (name == null || type == null) {
                        throw unsupported("TDengine column description is incomplete");
                    }
                    type = type.replace(" ", "").toUpperCase(Locale.ROOT).replace("UNSIGNED", " UNSIGNED");
                    if (Set.of("NCHAR", "BINARY", "VARCHAR", "VARBINARY").contains(type)) {
                        type += "(" + rows.getInt(3) + ")";
                    }
                    described.add(new String[]{name, type, note});
                }
            }
        }
        if (described.isEmpty()) {
            throw unsupported("TDengine relation has no columns");
        }
        var jdbc = new HashMap<String, TdengineJdbcSupport.Column>();
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            String projection = String.join(",",
                    described.stream().map(column -> TdengineJdbcSupport.quote(column[0])).toList());
            try (var rows = statement.executeQuery(
                    "SELECT " + projection + " FROM " + TdengineJdbcSupport.table(schema, table) + " LIMIT 0")) {
                var metadata = rows.getMetaData();
                if (metadata.getColumnCount() != described.size()) {
                    throw unsupported("TDengine projection metadata is ambiguous");
                }
                for (int i = 1; i <= metadata.getColumnCount(); i++) {
                    if (metadata.getColumnType(i) == Types.NULL) {
                        throw unsupported("TDengine driver cannot represent a physical column type");
                    }
                    jdbc.put(described.get(i - 1)[0],
                            new TdengineJdbcSupport.Column(described.get(i - 1)[0], metadata.getColumnType(i),
                                    metadata.getColumnTypeName(i), metadata.getPrecision(i), metadata.getScale(i)));
                }
            }
        }
        var columns = new ArrayList<Column>();
        for (int i = 0; i < described.size(); i++) {
            String[] column = described.get(i);
            boolean key = "COMPOSITE KEY".equals(column[2]), tag = "TAG".equals(column[2]);
            if (TBNAME.equalsIgnoreCase(column[0]) || !column[2].isEmpty() && !key && !tag) {
                throw unsupported("TDengine column role is unsupported");
            }
            if (i == 0 && (!column[1].equals("TIMESTAMP") || tag) || key && (i != 1 || tag)) {
                throw unsupported("TDengine timestamp/composite key layout is not verified");
            }
            columns.add(new Column(column[0], column[1], key, tag, jdbc.get(column[0])));
        }
        if (kind.equals("NORMAL_TABLE") != columns.stream().noneMatch(Column::tag)) {
            throw unsupported("TDengine table kind differs from TAG ownership");
        }
        var tagEvidence = new ArrayList<String>();
        if (kind.equals("CHILD_TABLE")) {
            var tags = columns.stream().filter(Column::tag).toList();
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(timeout);
                statement.setMaxRows(2);
                try (var rows = statement.executeQuery(
                        "SELECT TAGS " + TdengineJdbcSupport.columns(tags.stream().map(Column::name).toList())
                                + " FROM " + TdengineJdbcSupport.table(schema, table))) {
                    if (!rows.next()) {
                        throw unsupported("TDengine child TAG values are missing");
                    }
                    for (int i = 0; i < tags.size(); i++) {
                        tagEvidence.add(tagEvidence(rows.getObject(i + 1), tags.get(i), digits));
                    }
                    if (rows.next()) {
                        throw unsupported("TDengine child TAG values are ambiguous");
                    }
                }
            }
        }
        return new Shape(schema, table, kind, uid, parent, parentUid, digits, List.copyOf(columns),
                List.copyOf(tagEvidence), cluster,
                database[0] + "\0" + database[1]);
    }

    /** 规范 TAG 值证据；TIMESTAMP TAG 是按数据库精度表示的精确纪元计数。 */
    public static String tagEvidence(Object value, Column tag, int digits) {
        if (value instanceof TdengineTimestamp timestamp) {
            if (timestamp.digits() != digits) {
                throw new IllegalArgumentException("TDengine TAG timestamp precision differs");
            }
            value = timestamp.epoch();
        }
        return TdengineNativeTables.tagEvidence(value, TdengineTimestamp.precision(digits), tag.type());
    }

    /**
     * 判断当前字段值类型是否在支持范围内。
     */
    public static boolean supportedValueType(String type) {
        return type.matches(
                "TIMESTAMP|BOOL|TINYINT|SMALLINT|INT|BIGINT|FLOAT|DOUBLE|(?:TINYINT|SMALLINT|INT|BIGINT) UNSIGNED"
                        + "|(?:NCHAR|BINARY|VARCHAR|VARBINARY)\\([1-9][0-9]{0,5}\\)|DECIMAL\\([1-9][0-9]?,[0-9]{1,2}\\)");
    }

    /**
     * 判断当前类型是否处于已支持的游标键值域。
     */
    public static boolean supportedKeyType(String type) {
        return KEY_TYPES.contains(type);
    }

    /** 投影列的精确源值；TIMESTAMP 列投影为 CAST(... AS BIGINT)。 */
    public static Object read(ResultSet rows, int index, Column column, int digits) throws SQLException {
        Object value = rows.getObject(index);
        if (value == null) {
            return null;
        }
        String type = column.type();
        if (column.timestamp()) {
            if (!(value instanceof Long epoch)) {
                throw unsupported("TDengine timestamp projection is not an exact epoch count");
            }
            return new TdengineTimestamp(epoch, digits);
        }
        if (type.equals("BOOL")) {
            if (value instanceof Boolean) {
                return value;
            }
            throw unsupported("TDengine BOOL value class differs");
        }
        if (type.equals("FLOAT")) {
            if (value instanceof Float) {
                return value;
            }
            throw unsupported("TDengine FLOAT value class differs");
        }
        if (type.equals("DOUBLE")) {
            if (value instanceof Double) {
                return value;
            }
            throw unsupported("TDengine DOUBLE value class differs");
        }
        if (type.startsWith("DECIMAL(")) {
            if (!(value instanceof BigDecimal decimal) || decimal.scale() != column.jdbc()
                    .scale() || decimal.precision() > column.jdbc().precision()) {
                throw unsupported("TDengine DECIMAL value is not exact");
            }
            return decimal;
        }
        if (type.startsWith("NCHAR(")) {
            if (value instanceof String) {
                return value;
            }
            throw unsupported("TDengine NCHAR value class differs");
        }
        if (type.matches("(?:BINARY|VARCHAR|VARBINARY)\\([0-9]+\\)")) {
            if (value instanceof byte[] bytes) {
                return bytes;
            }
            throw unsupported("TDengine byte string value class differs");
        }
        BigInteger integer;
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            integer = BigInteger.valueOf(((Number) value).longValue());
        } else if (value instanceof BigInteger exact) {
            integer = exact;
        } else {
            throw unsupported("TDengine integer value class differs");
        }
        boolean unsigned = type.endsWith(" UNSIGNED");
        int bits = switch (unsigned ? type.substring(0, type.length() - 9) : type) {
            case "TINYINT" -> 8;
            case "SMALLINT" -> 16;
            case "INT" -> 32;
            case "BIGINT" -> 64;
            default -> throw unsupported("TDengine value type requires an explicit cursor codec");
        };
        if (unsigned ? integer.signum() < 0 || integer.bitLength() > bits : integer.bitLength() > bits - 1) {
            throw unsupported("TDengine integer is outside its native range");
        }
        // 规范类与 WebSocket 驱动及原生写入器编解码器一致。
        if (!unsigned) {
            return bits == 8 ? (Object) integer.byteValueExact() : bits == 16 ? (Object) integer.shortValueExact()
                    : bits == 32 ? (Object) integer.intValueExact() : integer.longValueExact();
        }
        return bits == 8 ? (Object) integer.shortValueExact() : bits == 16 ? (Object) integer.intValueExact()
                : bits == 32 ? (Object) integer.longValueExact() : integer;
    }

    /** 单个有序键的类型化游标分量；超级表子表名对应的 {@code column} 为 null。 */
    public static TypedCursorValue cursorValue(Column column, Object value, int digits) {
        Object exact = value instanceof BigInteger integer ? new BigDecimal(integer) : value;
        return TypedCursorValue.of(cursorType(column), cursorTypeName(column, digits), exact);
    }

    /** 校验持久化分量：时间戳纪元计数、精确整数键或子表名。 */
    public static Object cursorComponent(Column column, TypedCursorValue value, int digits) {
        if (value == null || value.jdbcType() != cursorType(column) || !value.typeName()
                .equals(cursorTypeName(column, digits))) {
            throw new IllegalArgumentException("TDengine cursor tuple type changed");
        }
        Object decoded = value.jdbcValue();
        if (column == null) {
            String name = (String) decoded;
            if (name.isEmpty() || name.indexOf('\0') >= 0 || name.getBytes(
                    java.nio.charset.StandardCharsets.UTF_8).length > 192) {
                throw new IllegalArgumentException("TDengine child name in cursor is invalid");
            }
            return name;
        }
        if (column.timestamp()) {
            return decoded;
        }
        BigInteger integer = decoded instanceof BigDecimal decimal ? decimal.toBigIntegerExact() : BigInteger.valueOf(
                ((Number) decoded).longValue());
        boolean unsigned = column.type().endsWith(" UNSIGNED");
        int bits = column.type().startsWith("INT") ? 32 : 64;
        if (unsigned ? integer.signum() < 0 || integer.bitLength() > bits : integer.bitLength() > bits - 1) {
            throw new IllegalArgumentException("TDengine cursor key is outside its native range");
        }
        return integer;
    }

    /**
     * 返回游标类型。
     */
    private static int cursorType(Column column) {
        if (column == null) {
            return Types.VARCHAR;
        }
        if (column.timestamp()) {
            return Types.BIGINT;
        }
        return switch (column.type()) {
            case "INT" -> Types.INTEGER;
            case "INT UNSIGNED", "BIGINT" -> Types.BIGINT;
            case "BIGINT UNSIGNED" -> Types.NUMERIC;
            default -> throw new IllegalArgumentException("TDengine cursor key type is unsupported");
        };
    }

    /**
     * 返回游标键在当前数据库中的原生类型名称。
     */
    private static String cursorTypeName(Column column, int digits) {
        return column == null ? "TBNAME" : column.timestamp() ? "TIMESTAMP(" + TdengineTimestamp.precision(
                digits) + ")" : column.type();
    }

    /**
     * 读取或计算当前对象的稳定唯一标识。
     */
    static String stableUid(Connection connection, String schema, String stable, int timeout) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(2);
            try (var rows = statement.executeQuery(
                    "SELECT CAST(uid AS VARCHAR(64)),isvirtual FROM information_schema.ins_stables WHERE db_name="
                            + literal(schema) + " AND stable_name=" + literal(stable) + " LIMIT 2")) {
                if (!rows.next()) {
                    throw unsupported("TDengine relation is absent or not visible");
                }
                String uid = rows.getString(1);
                boolean virtual = rows.getBoolean(2);
                if (uid == null || virtual || rows.wasNull() || rows.next()) {
                    throw unsupported("TDengine supertable identity is unsupported");
                }
                return uid;
            }
        }
    }

    /**
     * 返回当前可选。
     */
    private static String[] optional(Connection connection, String sql, int columns, int timeout) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(2);
            try (var rows = statement.executeQuery(sql)) {
                if (!rows.next()) {
                    return null;
                }
                String[] values = new String[columns];
                for (int i = 0; i < columns; i++) {
                    values[i] = Objects.toString(rows.getString(i + 1), "");
                }
                if (rows.next()) {
                    throw unsupported("TDengine catalog evidence is ambiguous");
                }
                return values;
            }
        }
    }

    /**
     * 读取查询结果中的单个标量或唯一记录。
     */
    private static String[] one(Connection connection, String sql, int columns, int timeout,
            boolean allowBlank) throws SQLException {
        String[] values = optional(connection, sql, columns, timeout);
        if (values == null) {
            throw unsupported("TDengine catalog evidence is absent or not visible");
        }
        for (String value : values) {
            if (!allowBlank && value.isBlank() || value.length() > 1024) {
                throw unsupported("TDengine catalog evidence is unavailable");
            }
        }
        return values;
    }

    /**
     * 返回当前字面量。
     */
    public static String literal(String value) {
        if (value == null || !value.matches("[A-Za-z_][A-Za-z0-9_]{0,191}")) {
            throw new IllegalArgumentException("TDengine catalog name is not an ordinary identifier");
        }
        return "'" + value + "'";
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static SQLFeatureNotSupportedException unsupported(String reason) {
        return new SQLFeatureNotSupportedException(reason, "0A000");
    }
}
