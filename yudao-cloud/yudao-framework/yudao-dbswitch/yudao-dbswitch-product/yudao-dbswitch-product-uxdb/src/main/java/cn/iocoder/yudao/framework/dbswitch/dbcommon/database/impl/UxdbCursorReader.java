package cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.entity.*;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import cn.iocoder.yudao.framework.dbswitch.core.util.UxdbCursorMetadata;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.*;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/**
 * UXsinoDB 2.1.0.3 原生可重复读快照，采用精确数值或时间水位、数值键或
 * 固定排序规则的文本键、键集分页，并持有 ACCESS SHARE 锁；仅使用 ux_catalog 的 PG10
 * 目录形态，绝不借用 PostgreSQL 16 读取器。
 */
final class UxdbCursorReader implements CursorReadSession {

    private final Connection connection;

    private final CursorReadRequest request;

    private final DbSwitchOptions options;

    private final List<String> order;

    private final int pageSize;

    private UxdbCursorMetadata.Shape shape;

    private CursorKeyContract keys;

    private String fingerprint;

    private TypedCursor upper, position, pageAfter;

    private PreparedStatement statement;

    private ResultSet result;

    private Object[] row;

    private int pageRows, previousIsolation;

    private boolean closed, ownsTransaction, previousReadOnly;

    /**
     * 打开游标读取会话并准备当前读取范围。
     */
    static CursorReadSession open(DataSource source, CursorReadRequest request, DbSwitchOptions options,
            int fetchSize) {
        UxdbCursorReader reader = null;
        try {
            reader = new UxdbCursorReader(source.getConnection(), request, options, fetchSize);
            reader.prepare();
            return reader;
        } catch (Exception failure) {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                }
            }
            throw new IllegalArgumentException("UXDB cursor generation, snapshot or exact ordering is not verified");
        }
    }

    /**
     * 初始化 {@code UxdbCursorReader}。
     */
    private UxdbCursorReader(Connection c, CursorReadRequest r, DbSwitchOptions o, int fetch) {
        connection = c;
        request = r;
        options = o;
        order = r.orderFields();
        pageSize = Math.max(1, Math.min(fetch, r.limits().maxRows()));
    }

    /**
     * 核对源代次、目标关系和键列事实，固定读取上界并准备当前游标分页状态。
     */
    private void prepare() throws SQLException {
        if (!connection.getAutoCommit() || request.sourceGenerationPin() == null) {
            throw new SQLException("Owned UXDB connection and external source generation pin required");
        }
        var metadata = connection.getMetaData();
        if (!"Uxdb".equals(metadata.getDatabaseProductName()) || metadata.getDatabaseMajorVersion() != 10) {
            throw new SQLException("UXDB cursor requires the verified UXsinoDB 2.1.0.3 server");
        }
        try (var p = connection.createStatement(); var r = p.executeQuery(
                "SELECT ux_catalog.version(),ux_catalog.current_setting('server_version')")) {
            if (!r.next() || !r.getString(1).startsWith("UXsinoDB 2.1.0.3") || !"10.0".equals(
                    r.getString(2)) || r.next()) {
                throw new SQLException("UXDB cursor requires the verified 2.1.0.3 profile");
            }
        }
        previousIsolation = connection.getTransactionIsolation();
        previousReadOnly = connection.isReadOnly();
        ownsTransaction = true;
        connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        connection.setReadOnly(true);
        connection.setAutoCommit(false);
        if (connection.getTransactionIsolation() != Connection.TRANSACTION_REPEATABLE_READ) {
            throw new SQLException("UXDB snapshot isolation is not active");
        }
        UxdbCursorMetadata.lock(connection, request.schema(), request.table(), false, timeout());
        shape = UxdbCursorMetadata.inspect(connection, request.schema(), request.table(), timeout());
        keys = UxdbCursorMetadata.contract(shape, request.primaryKeys());
        for (String name : request.fields()) {
            if (!shape.columns().containsKey(name)) {
                throw new SQLException("UXDB cursor projection changed");
            }
        }
        for (String name : order) {
            var c = shape.columns().get(name);
            if (c == null) {
                throw new SQLException("UXDB cursor ordering is incomplete");
            }
            UxdbCursorMetadata.key(c);
        }
        UxdbCursorMetadata.watermark(shape.columns().get(request.watermark()));
        request.limits().requireStatementParameters(order.size() * 2 + 1);
        fingerprint = DigestUtil.sha256Hex(
                "UXDB_CURSOR_V1\0" + shape.identity() + '\0' + shape.fingerprint() + '\0' + String.join("\0",
                        request.fields()) + '\0' + String.join("\0", order) + '\0' + request.sourceGenerationPin()
                        .fingerprint());
        if (request.expectedSchemaFingerprint() != null && !request.expectedSchemaFingerprint().equals(fingerprint)) {
            throw new SQLException("UXDB source generation or schema changed");
        }
        validate(request.lower());
        validate(request.fixedUpper());
        if (request.lower() != null) {
            try (var p = connection.prepareStatement(
                    "SELECT 1 FROM " + reference() + " WHERE " + q(request.watermark()) + ">=? LIMIT 1")) {
                p.setQueryTimeout(timeout());
                bind(p, 1, request.lower().values().getFirst());
                try (var r = p.executeQuery()) {
                    if (!r.next()) {
                        throw new SQLException("UXDB source watermark moved backwards");
                    }
                }
            }
        }
        upper = request.fixedUpper();
        if (upper == null && !request.upperBoundFrozen()) {
            try (var p = connection.createStatement()) {
                p.setQueryTimeout(timeout());
                try (var r = p.executeQuery("SELECT " + quoted(order) + " FROM " + reference() + " ORDER BY " + ordered(
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
        String tuple = "(" + quoted(order) + ")", parameters = "(" + String.join(",",
                Collections.nCopies(order.size(), "?")) + ")";
        String sql = "SELECT " + quoted(
                request.fields()) + " FROM " + reference() + " WHERE " + tuple + "<=" + parameters;
        if (request.lower() != null) {
            sql += " AND " + q(request.watermark()) + ">=?";
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
     * 将当前值绑定到预编译语句参数。
     */
    private static void bind(PreparedStatement p, int index, TypedCursorValue v) throws SQLException {
        p.setObject(index, v.jdbcValue(), v.jdbcType());
    }

    /**
     * 检查游标元组分量数与键列一致，并校验各键值符合声明的类型和取值范围。
     */
    private void validate(TypedCursor value) throws SQLException {
        if (value == null) {
            return;
        }
        if (value.values().size() != order.size()) {
            throw new SQLException("UXDB cursor tuple changed");
        }
        for (int i = 0; i < order.size(); i++) {
            var c = shape.columns().get(order.get(i));
            var v = value.values().get(i);
            if (v.jdbcType() != UxdbCursorMetadata.jdbcType(c) || !v.typeName().equals(UxdbCursorMetadata.keyType(c))) {
                throw new SQLException("UXDB cursor type changed");
            }
            UxdbCursorMetadata.exact(v.jdbcValue(), c);
        }
    }

    /**
     * 返回游标。
     */
    private TypedCursor cursor(ResultSet r, boolean projected) throws SQLException {
        var values = new ArrayList<TypedCursorValue>();
        for (int i = 0; i < order.size(); i++) {
            String name = order.get(i);
            var c = shape.columns().get(name);
            int index = projected ? i + 1 : request.fields().indexOf(name) + 1;
            values.add(TypedCursorValue.of(UxdbCursorMetadata.jdbcType(c), UxdbCursorMetadata.keyType(c),
                    UxdbCursorMetadata.read(r, index, c)));
        }
        return new TypedCursor(1, values);
    }

    /**
     * 推进游标并返回下一条数据或后续读取状态。
     */
    @Override
    public boolean next() throws SQLException {
        if (closed || Thread.currentThread().isInterrupted()) {
            throw new SQLException("UXDB cursor interrupted or closed");
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
        var values = new Object[request.fields().size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = UxdbCursorMetadata.read(result, i + 1, shape.columns().get(request.fields().get(i)));
        }
        position = cursor(result, false);
        pageRows++;
        row = BatchValueConverter.convert(List.<Object[]>of(values), values.length, request.limits()).getFirst();
        return true;
    }

    /**
     * 返回超时设置。
     */
    private int timeout() {
        return options.queryTimeoutSeconds();
    }

    /**
     * 生成当前操作引用的限定关系名。
     */
    private String reference() {
        return UxdbCursorMetadata.reference(request.schema(), request.table());
    }

    /**
     * 按当前数据库方言转义并引用标识符。
     */
    private static String q(String n) {
        return UxdbCursorMetadata.quote(n);
    }

    /**
     * 返回按当前方言加引号后的标识符。
     */
    private static String quoted(List<String> names) {
        return String.join(",", names.stream().map(UxdbCursorReader::q).toList());
    }

    /**
     * 按既定比较规则排列当前键或字段集合。
     */
    private String ordered(String direction) {
        return String.join(",", order.stream().map(n -> q(n) + " " + direction).toList());
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
            throw new IllegalStateException("UXDB cursor has no row");
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
                connection.setReadOnly(previousReadOnly);
                connection.setTransactionIsolation(previousIsolation);
            }
        } catch (Exception e) {
            failed = true;
            try {
                connection.abort(Runnable::run);
            } catch (Exception ignored) {
            }
        }
        try {
            connection.close();
        } catch (Exception e) {
            failed = true;
        }
        if (failed) {
            throw new SQLException("UXDB cursor physical cleanup was not confirmed");
        }
    }
}
