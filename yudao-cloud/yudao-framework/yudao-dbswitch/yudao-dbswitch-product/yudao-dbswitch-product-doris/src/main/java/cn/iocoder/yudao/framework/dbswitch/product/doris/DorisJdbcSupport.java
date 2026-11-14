package cn.iocoder.yudao.framework.dbswitch.product.doris;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import cn.iocoder.yudao.framework.dbswitch.common.util.JdbcValueConversions;
import cn.iocoder.yudao.framework.dbswitch.core.util.DorisRelationIdentity;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.NonTransactionalWriteExecution;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.PartialBatchWriteException;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import cn.iocoder.yudao.framework.dbswitch.product.olap.OlapPartitioning;

import javax.sql.DataSource;
import java.sql.*;
import java.math.*;
import java.time.*;
import java.util.*;
import java.lang.reflect.*;

import static cn.iocoder.yudao.framework.dbswitch.product.doris.DorisNativeSchema.*;

/** Doris 4.0 原生元数据、无损标量编解码器及请求确认。 */
public final class DorisJdbcSupport {

    /**
     * 初始化 {@code DorisJdbcSupport}。
     */
    private DorisJdbcSupport() {
    }

    public record Evidence(Table table, String generation, String physical) {

    }

    /** 对授权创建的精确内部表计数；超时、额外行或空值都不能作为完整复制凭证。 */
    public static long countRows(Connection connection, String schema, String table, int timeout) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(2);
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM " + relation(schema, table))) {
                if (!rows.next()) throw new SQLException("Doris row count is missing");
                long count = rows.getLong(1);
                if (rows.wasNull() || count < 0 || rows.next()) throw new SQLException("Doris row count is unverified");
                return count;
            }
        }
    }

    /**
     * 根据当前输入构建并返回 {@code Evidence}。
     */
    public static Evidence inspect(Connection c, String schema, String table, int timeout) throws SQLException {
        String generation = DorisRelationIdentity.fingerprint(c, relation(schema, table));
        Table shape;
        String tableComment;
        var columnComments = new LinkedHashMap<String, String>();
        try (var p = c.prepareStatement(
                "SELECT TABLE_COMMENT,TABLE_SCHEMA,TABLE_NAME FROM internal.information_schema.tables "
                        + "WHERE STRCMP(TABLE_SCHEMA,?)=0 AND STRCMP(TABLE_NAME,?)=0")) {
            p.setQueryTimeout(timeout);
            p.setMaxRows(2);
            p.setString(1, schema);
            p.setString(2, table);
            try (var r = p.executeQuery()) {
                if (!r.next() || !schema.equals(r.getString(2)) || !table.equals(r.getString(3))) {
                    throw new SQLException("Doris native comment is absent");
                }
                tableComment = r.getString(1);
                if (r.next() || tableComment == null) {
                    throw new SQLException("Doris native comment is ambiguous");
                }
            }
        }
        // Doris 4.0 错误地将 TABLE_NAME 等值谓词作为模式传给
        // getTableNames。STRCMP 保持精确相等，避免模式下推；
        // 接受任何列前，独立验证返回的物理名称。
        try (var p = c.prepareStatement(
                "SELECT COLUMN_NAME,COLUMN_COMMENT,TABLE_NAME,TABLE_SCHEMA FROM internal.information_schema.columns "
                        + "WHERE STRCMP(TABLE_SCHEMA,?)=0 AND STRCMP(TABLE_NAME,?)=0 ORDER BY ORDINAL_POSITION")) {
            p.setQueryTimeout(timeout);
            p.setMaxRows(1025);
            p.setString(1, schema);
            p.setString(2, table);
            try (var r = p.executeQuery()) {
                while (r.next()) {
                    if (!table.equals(r.getString(3)) || !schema.equals(r.getString(4))
                            || columnComments.size() >= 1024 || columnComments.put(
                            r.getString(1), r.getString(2)) != null) {
                        throw new SQLException("Doris native comment projection is incomplete");
                    }
                }
            }
        }
        try (var s = c.createStatement()) {
            s.setQueryTimeout(timeout);
            s.setMaxRows(2);
            try (var r = s.executeQuery("SHOW CREATE TABLE " + relation(schema, table))) {
                if (!r.next() || !table.equals(r.getString(1))) {
                    throw new SQLException("Doris native relation is absent");
                }
                shape = parseCatalog(r.getString(2), tableComment, columnComments);
                if (r.next()) {
                    throw new SQLException("Doris native relation is ambiguous");
                }
            }
        }
        var columns = new ArrayList<String>();
        try (var s = c.createStatement()) {
            s.setQueryTimeout(timeout);
            s.setMaxRows(1025);
            try (var r = s.executeQuery("DESC " + relation(schema, table))) {
                while (r.next()) {
                    if (columns.size() >= 1024) {
                        throw new SQLException("Doris native column budget exceeded");
                    }
                    String name = r.getString("Field");
                    Column col = shape.column(name);
                    if (!col.type().equals(type(r.getString("Type"))) || col.nullable() != r.getString("Null")
                            .equalsIgnoreCase("YES") || !attributes(shape, col, r.getString("Extra"))) {
                        throw new SQLException("Doris catalog type observations disagree");
                    }
                    columns.add(name);
                }
            }
        }
        try (var s = c.createStatement()) {
            s.setQueryTimeout(timeout);
            s.setMaxRows(66);
            try (var r = s.executeQuery("SHOW INDEX FROM " + relation(schema, table))) {
                var seen = new HashSet<String>();
                while (r.next()) {
                    String name = r.getString("Key_name");
                    var index = shape.indexes().stream().filter(i -> i.name().equals(name)).findFirst()
                            .orElseThrow(() -> new SQLException("Doris index is outside the verified grammar"));
                    if (!seen.add(name) || !index.type().equals(r.getString("Index_type")) || !index.columns()
                            .equals(List.of(r.getString("Column_name"))) || !index.comment()
                            .equals(Objects.requireNonNullElse(r.getString("Comment"), ""))) {
                        throw new SQLException("Doris index observations disagree");
                    }
                }
                if (seen.size() != shape.indexes().size()) {
                    throw new SQLException("Doris index observations disagree");
                }
            }
        }
        // SHOW CREATE 仅渲染表默认值；拥有独立分桶数的分区无法忠实重放。
        // 它还会渲染未转义字面值，因此独立格式化的 SHOW PARTITIONS 键必须一致。
        try (var s = c.createStatement()) {
            s.setQueryTimeout(timeout);
            s.setMaxRows(1026);
            try (var r = s.executeQuery("SHOW PARTITIONS FROM " + relation(schema, table))) {
                var witnesses = new HashMap<String, String>();
                while (r.next()) {
                    String name = r.getString("PartitionName");
                    if (witnesses.size() >= 1025 || !"NORMAL".equals(
                            r.getString("State")) || name == null || witnesses.put(name,
                            Objects.requireNonNullElse(r.getString("Range"),
                                    "")) != null || shape.buckets() > 0 && !Integer.toString(shape.buckets())
                            .equals(r.getString("Buckets"))) {
                        throw new SQLException("Doris partition observations disagree");
                    }
                }
                var partitioning = shape.partitioning();
                if (!witnesses.keySet().equals(partitioning == null ? Set.of(table) : partitioning.names())) {
                    throw new SQLException("Doris partition observations disagree");
                }
                if (partitioning != null) {
                    for (String name : partitioning.names()) {
                        if (!partitioning.witnessed(name,
                                OlapPartitioning.keysWitness(witnesses.get(name), partitioning.kind()),
                                column -> shape.column(column).type())) {
                            throw new SQLException("Doris partition values disagree with the catalog witness");
                        }
                    }
                }
            }
        }
        if (!columns.equals(shape.columns().stream().map(Column::name).toList()) || !generation.equals(
                DorisRelationIdentity.fingerprint(c, relation(schema, table)))) {
            throw new SQLException("Doris native observation changed");
        }
        String id;
        try (var p = c.prepareStatement(
                "SELECT DATABASE_ID,TABLE_ID FROM internal.information_schema.metadata_name_ids WHERE CATALOG_NAME='internal' AND DATABASE_NAME=? AND TABLE_NAME=?")) {
            p.setQueryTimeout(timeout);
            p.setString(1, schema);
            p.setString(2, table);
            try (var r = p.executeQuery()) {
                if (!r.next()) {
                    throw new SQLException("Doris physical identity absent");
                }
                id = r.getString(1) + ":" + r.getString(2);
                if (r.next()) {
                    throw new SQLException("Doris physical identity ambiguous");
                }
            }
        }
        String cluster = null;
        try (var s = c.createStatement()) {
            s.setQueryTimeout(timeout);
            s.setMaxRows(65);
            try (var r = s.executeQuery("SHOW FRONTENDS")) {
                int n = 0;
                while (r.next()) {
                    String current = r.getString("ClusterId");
                    if (++n > 64 || cluster != null && !cluster.equals(current)) {
                        throw new SQLException("Doris cluster observation disagrees");
                    }
                    cluster = current;
                }
            }
        }
        if (cluster == null || !(cluster + ":" + id).matches("[1-9][0-9]{0,18}:[1-9][0-9]{0,18}:[1-9][0-9]{0,18}")) {
            throw new SQLException("Doris physical identity is invalid");
        }
        return new Evidence(shape, generation, cluster + ":" + id);
    }

    /** DESC Extra 独立证明从 SHOW CREATE 解析的聚合与布隆过滤器归属。 */
    private static boolean attributes(Table shape, Column column, String extra) {
        var tokens = Arrays.stream(Objects.requireNonNullElse(extra, "").split(",", -1)).map(String::trim)
                .filter(t -> !t.isEmpty()).toList();
        String bloom = shape.properties().get("bloom_filter_columns");
        boolean declared = bloom != null && Arrays.asList(bloom.split(", ")).contains(column.name());
        var rest = tokens.stream().filter(t -> !t.equals("BLOOM_FILTER")).toList();
        if (declared != tokens.contains("BLOOM_FILTER") || tokens.size() - rest.size() > 1 || rest.size() > 1) {
            return false;
        }
        String aggregate = rest.isEmpty() ? null : rest.getFirst();
        if (shape.keys().contains(column.name())) {
            return aggregate == null;
        }
        if (shape.model().equals("AGGREGATE")) {
            return column.aggregation().equals(aggregate);
        }
        return aggregate == null || aggregate.equals("NONE") || shape.unique() && aggregate.equals("REPLACE");
    }

    /** 借用任何连接或发送任何请求前，先转换整个批次并完成原生分区路由。 */
    public static List<Object[]> convert(List<Object[]> rows, List<String> fields, Table table, BatchLimits limits) {
        names(fields);
        fields.forEach(table::column);
        var converted = BatchValueConverter.convert(rows, fields.size(), limits,
                (value, index) -> value(value, table.column(fields.get(index))));
        var partitioning = table.partitioning();
        if (partitioning == null) {
            return converted;
        }
        int[] positions = partitioning.columns().stream().mapToInt(name -> {
            int position = fields.indexOf(name);
            if (position < 0) {
                throw new IllegalArgumentException("Doris partition columns must be written");
            }
            return position;
        }).toArray();
        for (Object[] row : converted) {
            var key = new ArrayList<Object>(positions.length);
            for (int position : positions) {
                key.add(row[position]);
            }
            partitioning.partitionOf(key, name -> table.column(name).type());
        }
        return converted;
    }

    /**
     * 返回值。
     */
    public static Object value(Object value, Column column) {
        if (value == null) {
            if (!column.nullable()) {
                throw new IllegalArgumentException("Doris required value is null");
            }
            return null;
        }
        try {
            return switch (column.kind()) {
                case "tinyint" -> JdbcValueConversions.toByte(value);
                case "smallint" -> JdbcValueConversions.toShort(value);
                case "int" -> JdbcValueConversions.toInteger(value);
                case "bigint" -> JdbcValueConversions.toLong(value);
                case "boolean" -> JdbcValueConversions.toBoolean(value);
                case "largeint" -> {
                    BigInteger integer = JdbcValueConversions.toDecimal(value).toBigIntegerExact();
                    if (integer.bitLength() > 127) {
                        throw new ArithmeticException();
                    }
                    yield new BigDecimal(integer);
                }
                case "decimal" -> {
                    BigDecimal decimal = JdbcValueConversions.toDecimal(value)
                            .setScale(column.scale(), RoundingMode.UNNECESSARY);
                    if (decimal.precision() > column.precision()) {
                        throw new ArithmeticException();
                    }
                    yield decimal;
                }
                case "float", "double" -> {
                    boolean single = column.kind().equals("float");
                    double number = value instanceof Number n ? n.doubleValue() : Double.NaN;
                    if (!Double.isFinite(number) || single && (!Float.isFinite(
                            (float) number) || (double) (float) number != number)) {
                        throw new ArithmeticException();
                    }
                    if (!(value instanceof Float || value instanceof Double) && new BigDecimal(number).compareTo(
                            JdbcValueConversions.toDecimal(value)) != 0) {
                        throw new ArithmeticException();
                    }
                    yield single ? (Object) (float) number : number;
                }
                case "string", "varchar", "char" -> {
                    if (!(value instanceof String text)) {
                        throw new ArithmeticException();
                    }
                    var encoder = java.nio.charset.StandardCharsets.UTF_8.newEncoder()
                            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
                    if (encoder.encode(java.nio.CharBuffer.wrap(text)).remaining() > (column.kind()
                            .equals("string") ? Integer.MAX_VALUE : column.precision())) {
                        throw new ArithmeticException();
                    }
                    yield text;
                }
                case "date" -> {
                    LocalDate date = value instanceof java.sql.Date day ? day.toLocalDate() : value instanceof LocalDate local ? local : null;
                    if (date == null || date.getYear() < 1 || date.getYear() > 9999) {
                        throw new ArithmeticException();
                    }
                    yield date;
                }
                case "datetime" -> {
                    LocalDateTime time = value instanceof Timestamp stamp ? stamp.toLocalDateTime() : value instanceof LocalDateTime local ? local : null;
                    int divisor = (int) Math.pow(10, 9 - column.scale());
                    if (time == null || time.getYear() < 1 || time.getYear() > 9999 || time.getNano() % divisor != 0) {
                        throw new ArithmeticException();
                    }
                    yield time;
                }
                default -> throw new ArithmeticException();
            };
        } catch (ArithmeticException | java.nio.charset.CharacterCodingException failure) {
            throw new IllegalArgumentException("Doris value cannot be represented without loss");
        }
    }

    /**
     * MySQL Connector/J 在 getBigDecimal() 中通过有符号 long 解码负 LARGEINT 字符串，。
     */
    public static ResultSet typedRows(ResultSet rows, List<Column> columns) throws SQLException {
        var original = rows.getMetaData();
        if (original.getColumnCount() != columns.size()) {
            throw new SQLException("Doris read projection changed");
        }
        for (int i = 0; i < columns.size(); i++) {
            if (!columns.get(i).name().equals(original.getColumnLabel(i + 1))) {
                throw new SQLException("Doris read projection order changed");
            }
        }
        var metadata = (ResultSetMetaData) Proxy.newProxyInstance(ResultSetMetaData.class.getClassLoader(),
                new Class<?>[]{ResultSetMetaData.class}, (p, m, a) -> {
                    if (a != null && a.length == 1 && a[0] instanceof Integer i) {
                        Column column = columns.get(i - 1);
                        switch (m.getName()) {
                            case "getColumnType":
                                return column.jdbc();
                            case "getColumnTypeName":
                                return column.type();
                            case "getPrecision":
                                return column.kind().equals("largeint") ? 39 : column.precision();
                            case "getScale":
                                return column.scale();
                        }
                    }
                    return invoke(m, original, a);
                });
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class},
                (p, m, a) -> {
                    if (m.getName().equals("getMetaData")) {
                        return metadata;
                    }
                    if (m.getName().equals("getObject") && a != null && a.length == 1) {
                        int i = a[0] instanceof Integer index ? index : rows.findColumn((String) a[0]);
                        Column col = columns.get(i - 1);
                        // MySQL Connector/J 在 getBigDecimal() 中通过有符号 long 解码负 LARGEINT 字符串，
                        // 导致合法的、低于 Long.MIN_VALUE 的值被拒绝。原生目录
                        // 证据选择对未修改文本表示进行精确十进制解析。
                        if (col.kind().equals("largeint")) {
                            String text = rows.getString(i);
                            if (text == null) {
                                return null;
                            }
                            if (!text.matches("-?[0-9]{1,39}")) {
                                throw new SQLException("Doris LARGEINT wire representation is invalid");
                            }
                            return value(new BigDecimal(text), col);
                        }
                        if (col.kind().equals("decimal")) {
                            return rows.getBigDecimal(i);
                        }
                    }
                    return invoke(m, rows, a);
                });
    }

    /**
     * 调用当前受控运行时的目标方法并传播调用结果。
     */
    private static Object invoke(Method m, Object target, Object[] args) throws Throwable {
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    public enum Operation {INSERT, DIFF_INSERT, UPDATE, DELETE}

    /**
     * 按目标表定义转换数据行，并通过受支持 JDBC 写入路径提交。
     */
    public static long write(DataSource source, String schema, String name, Evidence captured, List<String> fields,
            List<Object[]> input, Operation operation, int timeout, BatchLimits limits) {
        return write(source, schema, name, captured, fields, input, operation, timeout, limits, null);
    }

    /** 已授权模板逐行原样执行；保留原生类型绑定、代次复核、限额及未知提交结果。 */
    public static long write(DataSource source, String schema, String name, Evidence captured, List<String> fields,
            List<Object[]> input, Operation operation, int timeout, BatchLimits limits, String authorizedInsertSql) {
        if (authorizedInsertSql != null && (operation != Operation.INSERT || !authorizedInsertSql.equals(
                DorisAuthorizedCreateNew.insertSql(schema, name, fields)))) {
            throw new IllegalArgumentException("Doris authorized INSERT target or projection changed");
        }
        List<Object[]> rows = convert(input, fields, captured.table(), limits);
        if (rows.isEmpty()) {
            return 0;
        }
        limits.requireStatementParameters(fields.size());
        boolean insert = operation == Operation.INSERT || operation == Operation.DIFF_INSERT;
        var table = captured.table();
        if (operation != Operation.INSERT && (!table.unique() || !fields.containsAll(table.keys()))) {
            throw new IllegalArgumentException("Doris mutations require native unique keys");
        }
        if (table.unique() && !"true".equals(table.properties().get("enable_unique_key_merge_on_write"))) {
            throw new IllegalArgumentException("Doris unique writes require the verified merge-on-write model");
        }
        if (table.properties().containsKey("function_column.sequence_col")) {
            throw new IllegalArgumentException("Doris sequence precedence requires a distinct mutation plan");
        }
        List<String> values = fields.stream().filter(f -> !table.keys().contains(f)).toList();
        int capacity = insert && authorizedInsertSql == null ? limits.maxMultiValueRows(fields.size(), 0) : 1;
        long[] acknowledged = {0}, sent = {0};
        try {
            return NonTransactionalWriteExecution.execute(source, (connection, attempt) -> {
                Evidence current = inspect(connection, schema, name, timeout);
                // FULL 在调用方显式 TRUNCATE 前准备；TRUNCATE 会替换分区编号，
                // 但保留物理表。DIFF 必须保留完整捕获的分区代次。
                if (!connection.getAutoCommit() || !captured.physical().equals(current.physical()) || !captured.table()
                        .equals(current.table())
                        || (operation != Operation.INSERT || authorizedInsertSql != null)
                        && !captured.generation().equals(current.generation())) {
                    throw new SQLException("Doris target changed before batch");
                }
                if (operation != Operation.INSERT) {
                    var seen = new HashSet<List<Object>>();
                    for (Object[] row : rows) {
                        var key = new ArrayList<Object>();
                        for (String f : table.keys()) {
                            Object value = row[fields.indexOf(f)];
                            if (value == null) {
                                throw new SQLException("Doris mutation key is null");
                            }
                            key.add(value);
                        }
                        Object[] before = read(connection, schema, name, table, fields, row, timeout);
                        if (!seen.add(key) || (operation == Operation.DIFF_INSERT ? before != null : before == null)) {
                            throw new SQLException("Doris mutation keys changed after comparison");
                        }
                    }
                }
                try (var literals = LiteralMode.open(connection, timeout); var settings = Settings.open(connection,
                        timeout)) {
                    for (int start = 0; start < rows.size(); start += capacity) {
                        int size = Math.min(capacity, rows.size() - start);
                        String sql;
                        List<String> parameters;
                        if (insert) {
                            parameters = fields;
                            sql = authorizedInsertSql != null ? authorizedInsertSql : "INSERT INTO " + relation(schema, name) + " (" + names(
                                    fields) + ") VALUES " + String.join(",", Collections.nCopies(size,
                                    "(" + String.join(",", Collections.nCopies(fields.size(), "?")) + ")"));
                        } else {
                            parameters = new ArrayList<>();
                            if (operation == Operation.UPDATE) {
                                parameters.addAll(values);
                            }
                            parameters.addAll(table.keys());
                            String where = String.join(" AND ",
                                    table.keys().stream().map(k -> quote(k) + "=?").toList());
                            sql = operation == Operation.DELETE ? "DELETE FROM " + relation(schema,
                                    name) + " WHERE " + where : "UPDATE " + relation(schema,
                                    name) + " SET " + (values.isEmpty() ? quote(table.keys().getFirst()) + "=" + quote(
                                    table.keys().getFirst()) : String.join(",",
                                    values.stream().map(k -> quote(k) + "=?").toList())) + " WHERE " + where;
                        }
                        try (var statement = connection.prepareStatement(sql)) {
                            statement.setQueryTimeout(timeout);
                            int index = 1;
                            for (int r = 0; r < size; r++) {
                                for (String field : parameters) {
                                    bind(statement, index++, rows.get(start + r)[fields.indexOf(field)],
                                            table.column(field));
                                }
                            }
                            attempt.sending();
                            sent[0] = size;
                            int count = statement.executeUpdate();
                            if (count < 0 || count > size || insert && count != size || statement.getWarnings() != null) {
                                throw new SQLException("Doris native acknowledgement is incomplete");
                            }
                            if (operation != Operation.INSERT) {
                                for (int r = 0; r < size; r++) {
                                    Object[] actual = read(connection, schema, name, table, fields, rows.get(start + r),
                                            timeout);
                                    if (operation == Operation.DELETE ? actual != null : actual == null || !Arrays.deepEquals(
                                            actual, rows.get(start + r))) {
                                        throw new SQLException("Doris mutation visibility is unconfirmed");
                                    }
                                }
                            }
                            acknowledged[0] += size;
                            sent[0] = 0;
                        }
                    }
                }
                if (authorizedInsertSql != null) {
                    // 新表复制不允许 TRUNCATE；结束复核失败时本批全部转为未知。
                    long completed = acknowledged[0];
                    acknowledged[0] = 0;
                    sent[0] = rows.size();
                    Evidence after = inspect(connection, schema, name, timeout);
                    if (!captured.equals(after)) throw new SQLException("Doris authorized target generation changed during batch");
                    acknowledged[0] = completed;
                    sent[0] = 0;
                }
                return acknowledged[0];
            });
        } catch (UnconfirmedWriteException failure) {
            throw new PartialBatchWriteException(acknowledged[0], rows.size() - acknowledged[0] - sent[0], sent[0],
                    false);
        }
    }

    /**
     * 按键条件读取 Doris 目标行，供当前写入操作核对结果。
     */
    private static Object[] read(Connection c, String schema, String name, Table table, List<String> fields,
            Object[] key, int timeout) throws SQLException {
        String where = String.join(" AND ", table.keys().stream().map(k -> quote(k) + "=?").toList());
        try (var p = c.prepareStatement(
                "SELECT " + names(fields) + " FROM " + relation(schema, name) + " WHERE " + where + " LIMIT 2")) {
            p.setQueryTimeout(timeout);
            p.setMaxRows(2);
            for (int i = 0; i < table.keys().size(); i++) {
                String f = table.keys().get(i);
                bind(p, i + 1, key[fields.indexOf(f)], table.column(f));
            }
            try (var r = typedRows(p.executeQuery(), fields.stream().map(table::column).toList())) {
                if (!r.next()) {
                    return null;
                }
                Object[] result = new Object[fields.size()];
                for (int i = 0; i < result.length; i++) {
                    result[i] = value(r.getObject(i + 1), table.column(fields.get(i)));
                }
                if (r.next()) {
                    throw new SQLException("Doris unique key returned multiple rows");
                }
                return result;
            }
        }
    }

    /**
     * 将当前值绑定到预编译语句参数。
     */
    private static void bind(PreparedStatement p, int index, Object value, Column column) throws SQLException {
        if (value == null) {
            p.setNull(index, column.jdbc());
        } else if (value instanceof BigDecimal decimal) {
            p.setBigDecimal(index, decimal);
        } else if (value instanceof LocalDateTime time) {
            p.setString(index, time.toString().replace('T', ' '));
        } else if (value instanceof LocalDate date) {
            p.setString(index, date.toString());
        } else {
            p.setObject(index, value, column.jdbc());
        }
    }

    /** 生成的目录字面值采用一个明确方言模式，并在同一连接上恢复。 */
    public record LiteralMode(Connection connection, int timeout, String original) implements AutoCloseable {

        /**
         * 打开字面量模式并准备后续访问。
         */
        public static LiteralMode open(Connection c, int timeout) throws SQLException {
            String original;
            try (var s = c.createStatement()) {
                s.setQueryTimeout(timeout);
                try (var r = s.executeQuery("SELECT @@sql_mode")) {
                    if (!r.next() || (original = r.getString(1)) == null || !original.matches(
                            "[A-Za-z0-9_,]*") || original.length() > 4096 || r.next()) {
                        throw new SQLException("Doris literal mode is unknown");
                    }
                }
            }
            var mode = new LiteralMode(c, timeout, original);
            String desired = String.join(",",
                    Arrays.stream(original.split(",")).filter(s -> !s.equalsIgnoreCase("NO_BACKSLASH_ESCAPES"))
                            .toList());
            if (!original.equals(desired)) {
                try {
                    mode.set(desired);
                } catch (SQLException failure) {
                    try {
                        mode.close();
                    } catch (SQLException ignored) {
                    }
                    throw failure;
                }
            }
            return mode;
        }

        /**
         * 按给定键或位置设置当前字段值。
         */
        private void set(String value) throws SQLException {
            try (var s = connection.createStatement()) {
                s.setQueryTimeout(timeout);
                s.execute("SET sql_mode='" + value + "'");
            }
        }

        /**
         * 关闭 {@code DorisJdbcSupport} 当前持有的资源。
         */
        @Override
        public void close() throws SQLException {
            try {
                set(original);
            } catch (SQLException failure) {
                try {
                    connection.abort(Runnable::run);
                } catch (Throwable ignored) {
                }
                throw failure;
            }
        }
    }

    /** 归还本方拥有的池连接前，恢复会话开关。 */
    private record Settings(Connection connection, int timeout, boolean strict, boolean partial,
                            String group) implements AutoCloseable {

        /**
         * 打开配置并准备后续访问。
         */
        static Settings open(Connection c, int timeout) throws SQLException {
            boolean strict, partial;
            String group;
            try (var s = c.createStatement()) {
                s.setQueryTimeout(timeout);
                try (var r = s.executeQuery(
                        "SELECT @@enable_insert_strict,@@enable_unique_key_partial_update,@@group_commit")) {
                    if (!r.next()) {
                        throw new SQLException("Doris write session is unknown");
                    }
                    strict = r.getBoolean(1);
                    partial = r.getBoolean(2);
                    group = r.getString(3);
                    if (!Set.of("off_mode", "sync_mode", "async_mode").contains(group) || r.next()) {
                        throw new SQLException("Doris group commit mode is unknown");
                    }
                }
            }
            var settings = new Settings(c, timeout, strict, partial, group);
            try {
                settings.set(true, false, "off_mode");
                return settings;
            } catch (SQLException failure) {
                try {
                    settings.close();
                } catch (SQLException ignored) {
                }
                throw failure;
            }
        }

        /**
         * 按给定键或位置设置当前字段值。
         */
        void set(boolean strict, boolean partial, String group) throws SQLException {
            try (var s = connection.createStatement()) {
                s.setQueryTimeout(timeout);
                s.execute(
                        "SET enable_insert_strict=" + strict + ",enable_unique_key_partial_update=" + partial + ",group_commit='" + group + "'");
            }
        }

        /**
         * 关闭 {@code DorisJdbcSupport} 当前持有的资源。
         */
        @Override
        public void close() throws SQLException {
            try {
                set(strict, partial, group);
            } catch (SQLException failure) {
                try {
                    connection.abort(Runnable::run);
                } catch (Throwable ignored) {/* 不得从失败的 abort 推断退出证明。 */}
                throw failure;
            }
        }
    }
}
