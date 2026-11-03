package cn.iocoder.yudao.framework.dbswitch.product.clickhouse;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** ClickHouse 0.5 值保护。无符号包装对象被分离；String 字节在服务器端检查。 */
public final class ClickhouseReadValues {

    /**
     * 初始化 {@code ClickhouseReadValues}。
     */
    private ClickhouseReadValues() {
    }

    public record ColumnSignature(String label, String name, String nativeType, int jdbcType, int precision,
                                  int scale, int nullable) {

    }

    /** 冻结的驱动描述；既不是目录锁，也不是数据快照。 */
    public record Signature(List<ColumnSignature> columns) {

        /**
         * 初始化 {@code Signature}。
         */
        public Signature {
            columns = List.copyOf(columns);
        }

        /**
         * 检查实际是否满足当前操作的约定。
         */
        public void verify(ResultSetMetaData actual) throws SQLException {
            if (!equals(describe(actual))) {
                throw new SQLException("ClickHouse result metadata changed after planning");
            }
        }
    }

    /**
     * 根据当前输入构建对象或结构的描述信息。
     */
    public static Signature describe(ResultSetMetaData metadata) throws SQLException {
        int count = metadata.getColumnCount();
        if (count < 1 || count > 2000) {
            throw new SQLException("ClickHouse result column count is outside the supported bound");
        }
        var names = new HashSet<String>();
        var columns = new ArrayList<ColumnSignature>();
        for (int i = 1; i <= count; i++) {
            String label = metadata.getColumnLabel(i), name = metadata.getColumnName(
                    i), type = metadata.getColumnTypeName(i);
            if (label == null || label.isEmpty() || name == null || name.isEmpty() || type == null || type.isEmpty() || !names.add(
                    label)) {
                throw new SQLException("ClickHouse result description is incomplete or ambiguous");
            }
            columns.add(new ColumnSignature(label, name, type, metadata.getColumnType(i), metadata.getPrecision(i),
                    metadata.getScale(i), metadata.isNullable(i)));
        }
        return new Signature(columns);
    }

    /** 零行描述；不读取源值，也不建立源值快照。 */
    public static String headerSql(String sql) {
        return "SELECT * FROM (" + sql + ") AS dbswitch_value_source WHERE 1=0";
    }

    /**
     * JDBC 0.5 getBytes(String) 已替换格式错误的 UTF-8，因此客户端解码无法恢复它。
     * throwIf 在实际选中 String 到达该编解码器前于服务器端校验。此谓词
     * 对每个可表示行都为零；不会跳过坏行，也不把先前扫描当作证明。
     */
    public static String guardedSql(String sql, ResultSetMetaData metadata) throws SQLException {
        return guardedSql(sql, describe(metadata));
    }

    /**
     * 为查询补充文本和浮点值域检查条件。
     */
    public static String guardedSql(String sql, Signature signature) {
        var text = signature.columns().stream().filter(column -> stringType(column.nativeType()))
                .map(ColumnSignature::label).toList();
        var floating = signature.columns().stream().filter(column -> floatingType(column.nativeType()))
                .map(ColumnSignature::label).toList();
        return guardedSql(sql, text, floating);
    }

    /** 游标目录列同时冻结原始零行 JDBC 结构和原生类型。 */
    public static void verifyCursor(ResultSetMetaData actual,
            List<ClickhouseCursorMetadata.Column> expected) throws SQLException {
        var observed = describe(actual).columns();
        if (observed.size() != expected.size()) {
            throw new SQLException("ClickHouse cursor result metadata changed after planning");
        }
        for (int i = 0; i < expected.size(); i++) {
            var before = expected.get(i);
            var after = observed.get(i);
            if (!before.name().equals(after.label()) || !before.name().equals(after.name())
                    || !before.type().replace(" ", "").equals(after.nativeType().replace(" ", ""))
                    || before.jdbcType() != after.jdbcType() || before.precision() != after.precision()
                    || before.scale() != after.scale() || before.nullable() != (after.nullable() != ResultSetMetaData.columnNoNulls)) {
                throw new SQLException("ClickHouse cursor result metadata changed after planning");
            }
        }
    }

    /** 游标投影已具备有界原生目录事实，无需另一次表头查询。 */
    public static String guardedSql(String sql, List<String> text, List<String> floating) {
        if (text.isEmpty() && floating.isEmpty()) {
            return sql;
        }
        return "SELECT dbswitch_value_source.* FROM (" + sql + ") AS dbswitch_value_source WHERE "
                + valuePredicate(text, floating, "dbswitch_value_source.");
    }

    /**
     * 判断原生类型是否属于当前路径支持的文本类型。
     */
    public static boolean stringType(String type) {
        return "String".equals(type) || "Nullable(String)".equals(type)
                || "LowCardinality(String)".equals(type) || "LowCardinality(Nullable(String))".equals(type);
    }

    /**
     * 判断原生类型是否属于当前路径支持的浮点类型。
     */
    public static boolean floatingType(String type) {
        return "Float32".equals(type) || "Float64".equals(type) || "Nullable(Float32)".equals(
                type) || "Nullable(Float64)".equals(type);
    }

    /** 也用于源的提前预检；数据查询仍必须施加自己的同一行防护。 */
    public static String textPredicate(List<String> names, String qualifier) {
        return valuePredicate(names, List.of(), qualifier);
    }

    /**
     * 根据字段集合构建受支持值域的 SQL 校验谓词。
     */
    public static String valuePredicate(List<String> names, List<String> floating, String qualifier) {
        if (names.size() + floating.size() < 1 || names.size() + floating.size() > 2000) {
            throw new IllegalArgumentException("Invalid ClickHouse value projection count");
        }
        var invalid = new ArrayList<String>();
        for (var name : names) {
            String column = qualifier + ClickhouseSql.quote(name);
            invalid.add("(isNotNull(" + column + ") AND NOT isValidUTF8(assumeNotNull(" + column + ")))");
        }
        for (var name : floating) {
            String column = qualifier + ClickhouseSql.quote(name);
            invalid.add("(isNotNull(" + column + ") AND NOT isFinite(assumeNotNull(" + column + ")))");
        }
        return "throwIf(" + String.join(" OR ",
                invalid) + ", 'ClickHouse value requires valid UTF-8 and finite floating point')=0";
    }

    /**
     * 根据当前输入构建并返回 {@code BigDecimal}。
     */
    public static ResultSet exactUnsigned(ResultSet delegate) throws SQLException {
        ResultSetMetaData metadata = delegate.getMetaData();
        int[] widths = new int[metadata.getColumnCount() + 1];
        for (int i = 1; i < widths.length; i++) {
            var type = metadata.getColumnTypeName(i);
            if (type != null && type.startsWith("Nullable(") && type.endsWith(")")) {
                type = type.substring(9, type.length() - 1);
            }
            widths[i] = switch (type == null ? "" : type) {
                case "UInt8" -> 8;
                case "UInt16" -> 16;
                case "UInt32" -> 32;
                case "UInt64" -> 64;
                default -> 0;
            };
        }
        var described = (ResultSetMetaData) Proxy.newProxyInstance(ResultSetMetaData.class.getClassLoader(),
                new Class<?>[]{ResultSetMetaData.class}, (proxy, method, args) -> {
                    if (method.getName()
                            .equals("getColumnClassName") && args != null && args[0] instanceof Integer index
                            && index > 0 && index < widths.length && widths[index] != 0) {
                        return BigDecimal.class.getName();
                    }
                    try {
                        return method.invoke(metadata, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getMetaData")) {
                        return described;
                    }
                    if (method.getName().equals("getObject") && args != null && args.length == 1) {
                        int index = args[0] instanceof Integer number ? number : delegate.findColumn((String) args[0]);
                        if (index > 0 && index < widths.length && widths[index] != 0) {
                            BigDecimal value = delegate.getBigDecimal(index);
                            if (value == null) {
                                return null;
                            }
                            try {
                                BigInteger integer = value.toBigIntegerExact();
                                if (integer.signum() < 0 || integer.bitLength() > widths[index]) {
                                    throw new ArithmeticException();
                                }
                                return new BigDecimal(integer);
                            } catch (ArithmeticException invalid) {
                                throw new SQLException("ClickHouse unsigned value is not exact for its native type");
                            }
                        }
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }
}
