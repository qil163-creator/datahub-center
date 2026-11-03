package cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl;

import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.entity.*;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import cn.iocoder.yudao.framework.dbswitch.core.util.*;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.*;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/** 一个自主管理的 SQLite 读取事务，采用固定上界元组、有界分页及原生 BINARY 文本排序。 */
final class SqliteCursorReader implements CursorReadSession {

    private final Connection connection;

    private final CursorReadRequest request;

    private final DbSwitchOptions options;

    private final List<String> order;

    private final int pageSize;

    private SqliteCursorMetadata.Shape shape;

    private CursorKeyContract keys;

    private String fingerprint, fileIdentity;

    private TypedCursor upper, position, pageAfter;

    private PreparedStatement statement;

    private ResultSet result;

    private Object[] row;

    private int pageRows;

    private boolean closed, ownsTransaction;

    /**
     * 打开游标读取会话并准备当前读取范围。
     */
    static CursorReadSession open(DataSource source, CursorReadRequest request, DbSwitchOptions options,
            int fetchSize) {
        SqliteCursorReader reader = null;
        try {
            reader = new SqliteCursorReader(source.getConnection(), request, options, fetchSize);
            reader.prepare();
            return reader;
        } catch (Exception failure) {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                }
            }
            throw new IllegalArgumentException("SQLite typed cursor schema, generation or ordering is unsupported");
        }
    }

    /**
     * 初始化 {@code SqliteCursorReader}。
     */
    private SqliteCursorReader(Connection c, CursorReadRequest request, DbSwitchOptions options, int fetchSize) {
        this.connection = c;
        this.request = request;
        this.options = options;
        this.order = request.orderFields();
        this.pageSize = Math.max(1, Math.min(fetchSize, request.limits().maxRows()));
    }

    /**
     * 核对源代次、目标关系和键列事实，固定读取上界并准备当前游标分页状态。
     */
    private void prepare() throws SQLException {
        if (!connection.getAutoCommit() || request.sourceGenerationPin() == null || !request.schema()
                .equals(request.sourceGenerationPin().schema())) {
            throw new SQLException(
                    "SQLite durable cursor requires an owned connection and deployed file generation pin");
        }
        connection.setAutoCommit(false);
        ownsTransaction = true;
        // 首次读取固定 SQLite 快照，标记和表元数据在此快照内观测。
        SqliteSourceGeneration.verify(connection, request.sourceGenerationPin());
        shape = SqliteCursorMetadata.inspect(connection, request.schema(), request.table(), timeout());
        fileIdentity = SqliteCursorMetadata.fileIdentity(connection, request.schema(), timeout());
        keys = SqliteCursorMetadata.keyContract(connection, request.schema(), request.table(), request.primaryKeys(),
                shape, timeout(), true);
        for (String name : request.fields()) {
            if (!shape.columns().containsKey(name) || shape.columns().get(name).hidden() != 0) {
                throw new SQLException("SQLite cursor projection is unsupported");
            }
        }
        if (!"INTEGER".equals(shape.columns().get(request.watermark()).affinity())) {
            throw new SQLException("SQLite watermark requires exact signed integer storage");
        }
        SqliteCursorMetadata.requireStorageTypes(connection, request.schema(), request.table(), order, shape,
                timeout());
        fingerprint = SqliteCursorMetadata.digest(
                "SQLITE_CURSOR_V1\0" + shape.identity() + "\0" + keys.nativeFingerprint() + "\0" + request.sourceGenerationPin()
                        .fingerprint() + "\0" + String.join("\0", request.fields()) + "\0" + String.join("\0", order));
        if (request.expectedSchemaFingerprint() != null && !request.expectedSchemaFingerprint().equals(fingerprint)) {
            throw new SQLException("SQLite source generation or schema changed");
        }
        validate(request.lower());
        validate(request.fixedUpper());
        if (request.lower() != null) {
            try (var s = connection.prepareStatement(
                    "SELECT 1 FROM " + reference() + " WHERE " + expression(request.watermark()) + ">=? LIMIT 1")) {
                s.setQueryTimeout(timeout());
                bind(s, 1, request.lower().values().getFirst());
                try (var r = s.executeQuery()) {
                    if (!r.next()) {
                        throw new SQLException("SQLite watermark moved backwards");
                    }
                }
            }
        }
        upper = request.fixedUpper();
        if (upper == null && !request.upperBoundFrozen()) {
            try (var s = connection.createStatement()) {
                s.setQueryTimeout(timeout());
                try (var r = s.executeQuery(
                        "SELECT " + projection(order) + " FROM " + reference() + " ORDER BY " + ordered(
                                "DESC") + " LIMIT 1")) {
                    if (r.next()) {
                        upper = cursor(r, true);
                    }
                }
            }
        }
        if (upper != null) {
            openPage();
        }
    }

    /**
     * 按当前游标边界打开下一页结果集。
     */
    private void openPage() throws SQLException {
        if (result != null) {
            result.close();
        }
        if (statement != null) {
            statement.close();
        }
        String tuple = "(" + String.join(",", order.stream().map(this::expression).toList()) + ")";
        String parameters = "(" + String.join(",", Collections.nCopies(order.size(), "?")) + ")";
        String sql = "SELECT " + projection(
                request.fields()) + " FROM " + reference() + " WHERE " + tuple + "<=" + parameters;
        if (request.lower() != null) {
            sql += " AND " + expression(request.watermark()) + ">=?";
        }
        if (pageAfter != null) {
            sql += " AND " + tuple + ">" + parameters;
        }
        sql += " ORDER BY " + ordered("ASC") + " LIMIT " + pageSize;
        statement = connection.prepareStatement(sql);
        statement.setQueryTimeout(timeout());
        int index = 1;
        for (var value : upper.values()) {
            bind(statement, index++, value);
        }
        if (request.lower() != null) {
            bind(statement, index++, request.lower().values().getFirst());
        }
        if (pageAfter != null) {
            for (var value : pageAfter.values()) {
                bind(statement, index++, value);
            }
        }
        result = statement.executeQuery();
        pageRows = 0;
    }

    /**
     * 推进游标并返回下一条数据或后续读取状态。
     */
    @Override
    public boolean next() throws SQLException {
        if (closed || Thread.currentThread().isInterrupted()) {
            throw new SQLException("SQLite cursor interrupted or closed");
        }
        if (result == null) {
            return false;
        }
        if (!result.next()) {
            if (pageRows < pageSize) {
                row = null;
                position = null;
                return false;
            }
            pageAfter = position;
            openPage();
            if (!result.next()) {
                row = null;
                position = null;
                return false;
            }
        }
        Object[] values = new Object[request.fields().size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = readExact(result, i + 1, values.length);
        }
        position = cursor(result, false);
        pageRows++;
        row = BatchValueConverter.convert(List.<Object[]>of(values), values.length, request.limits()).getFirst();
        if (!fileIdentity.equals(SqliteCursorMetadata.fileIdentity(connection, request.schema(), timeout()))) {
            throw new SQLException("SQLite file changed during cursor read");
        }
        return true;
    }

    /**
     * 返回游标。
     */
    private TypedCursor cursor(ResultSet r, boolean projected) throws SQLException {
        var values = new ArrayList<TypedCursorValue>();
        for (int i = 0; i < order.size(); i++) {
            String name = order.get(i);
            int index = projected ? i + 1 : request.fields().indexOf(name) + 1;
            boolean integer = shape.columns().get(name).affinity().equals("INTEGER");
            Object value;
            if (integer) {
                value = r.getLong(index);
                if (r.wasNull()) {
                    throw new SQLException("NULL cursor integer");
                }
            } else {
                value = readExact(r, index, projected ? order.size() : request.fields().size());
                if (!(value instanceof String)) {
                    throw new SQLException("NULL or nontext cursor key");
                }
            }
            values.add(TypedCursorValue.of(integer ? Types.BIGINT : Types.VARCHAR,
                    integer ? "SQLITE_INTEGER" : "SQLITE_TEXT_BINARY", value));
        }
        return new TypedCursor(1, values);
    }

    /**
     * 检查游标元组分量数与键列一致，并校验各键值符合声明的类型和取值范围。
     */
    private void validate(TypedCursor cursor) throws SQLException {
        if (cursor == null) {
            return;
        }
        if (cursor.values().size() != order.size()) {
            throw new SQLException("SQLite cursor shape changed");
        }
        for (int i = 0; i < order.size(); i++) {
            boolean integer = shape.columns().get(order.get(i)).affinity().equals("INTEGER");
            var value = cursor.values().get(i);
            if (value.jdbcType() != (integer ? Types.BIGINT : Types.VARCHAR) || !value.typeName()
                    .equals(integer ? "SQLITE_INTEGER" : "SQLITE_TEXT_BINARY")) {
                throw new SQLException("SQLite cursor types changed");
            }
        }
    }

    /**
     * 将当前值绑定到预编译语句参数。
     */
    private static void bind(PreparedStatement s, int i, TypedCursorValue value) throws SQLException {
        if (value.jdbcType() == Types.BIGINT) {
            s.setLong(i, (Long) value.jdbcValue());
        } else {
            s.setString(i, (String) value.jdbcValue());
        }
    }

    /**
     * 返回当前投影。
     */
    private static String projection(List<String> fields) {
        return SqliteCursorMetadata.quoted(fields) + "," + String.join(",",
                fields.stream().map(name -> "CASE WHEN typeof("
                        + SqliteCursorMetadata.quote(name) + ")='text' THEN CAST(" + SqliteCursorMetadata.quote(
                        name) + " AS BLOB) END").toList());
    }

    /**
     * 读取精确。
     */
    private static Object readExact(ResultSet rows, int index, int width) throws SQLException {
        Object value = rows.getObject(index);
        if (value instanceof String text && !Arrays.equals(text.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                rows.getBytes(width + index))) {
            throw new SQLException("SQLite text cannot be decoded without losing bytes");
        }
        return value;
    }

    /**
     * 返回表达式。
     */
    private String expression(String name) {
        return SqliteCursorMetadata.quote(name) + (shape.columns().get(name).affinity()
                .equals("TEXT") ? " COLLATE BINARY" : "");
    }

    /**
     * 按既定比较规则排列当前键或字段集合。
     */
    private String ordered(String direction) {
        return String.join(",", order.stream().map(n -> expression(n) + " " + direction).toList());
    }

    /**
     * 生成当前操作引用的限定关系名。
     */
    private String reference() {
        return SqliteCursorMetadata.reference(request.schema(), request.table());
    }

    /**
     * 返回超时设置。
     */
    private int timeout() {
        return options.queryTimeoutSeconds();
    }

    /**
     * 返回指纹。
     */
    @Override
    public String schemaFingerprint() {
        return fingerprint;
    }

    /**
     * 返回当前游标键契约的原生指纹。
     */
    @Override
    public String keyContractFingerprint() {
        return keys.nativeFingerprint();
    }

    /**
     * 返回键集合。
     */
    @Override
    public CursorKeyContract keyContract() {
        return keys;
    }

    /**
     * 返回上界。
     */
    @Override
    public TypedCursor upperBound() {
        return upper;
    }

    /**
     * 返回位置。
     */
    @Override
    public TypedCursor position() {
        return position;
    }

    /**
     * 返回数据行。
     */
    @Override
    public Object[] row() {
        if (row == null) {
            throw new IllegalStateException("Cursor has no row");
        }
        return row.clone();
    }

    /**
     * 结束当前读取作用域，回滚只读事务并恢复借用连接的状态。
     */
    @Override
    public void close() throws SQLException {
        if (closed) {
            return;
        }
        closed = true;
        boolean failed = false;
        try {
            if (result != null) {
                result.close();
            }
        } catch (Exception e) {
            failed = true;
        }
        try {
            if (statement != null) {
                statement.close();
            }
        } catch (Exception e) {
            failed = true;
        }
        try {
            if (ownsTransaction) {
                connection.rollback();
                connection.setAutoCommit(true);
            }
        } catch (Exception e) {
            failed = true;
        }
        try {
            connection.close();
        } catch (Exception e) {
            failed = true;
        }
        if (failed) {
            throw new SQLException("SQLite source cleanup was not confirmed");
        }
    }
}
