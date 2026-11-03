package cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursor;
import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursorValue;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import cn.iocoder.yudao.framework.dbswitch.core.util.GreenplumCursorMetadata;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadSession;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Greenplum 原生可重复读快照：固定部署代次，使用精确数值键集
 * 分页，并持有 ACCESS SHARE 锁。GP 7.1.0 的可重复读及其 SERIALIZABLE 别名
 * 是唯一已验证的跨语句快照；不假设跨分段返回顺序，每页都由
 * 类型化元组键集限定，并在协调节点进行全局有序扫描。
 */
final class GreenplumCursorReader implements CursorReadSession {

    private final Connection connection;

    private final CursorReadRequest request;

    private final DbSwitchOptions options;

    private final List<String> order;

    private final int pageSize;

    private GreenplumCursorMetadata.Shape shape;

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
        GreenplumCursorReader reader = null;
        try {
            reader = new GreenplumCursorReader(source.getConnection(), request, options, fetchSize);
            reader.prepare();
            return reader;
        } catch (Exception failure) {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                }
            }
            throw new IllegalArgumentException(
                    "Greenplum cursor generation, snapshot or exact ordering is not verified");
        }
    }

    /**
     * 初始化 {@code GreenplumCursorReader}。
     */
    private GreenplumCursorReader(Connection c, CursorReadRequest r, DbSwitchOptions o, int fetch) {
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
            throw new SQLException("Owned Greenplum connection and external source generation pin required");
        }
        previousIsolation = connection.getTransactionIsolation();
        previousReadOnly = connection.isReadOnly();
        ownsTransaction = true;
        connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        connection.setReadOnly(true);
        connection.setAutoCommit(false);
        try (var probe = connection.createStatement(); var rows = probe.executeQuery(
                "SELECT pg_catalog.current_setting('transaction_isolation')")) {
            // pgjdbc 为 GP 可重复读会话报告 SERIALIZABLE（4）；已验证的证据来自服务器 GUC。
            if (!rows.next() || !"repeatable read".equals(
                    rows.getString(1)) || rows.next() || !connection.isReadOnly()) {
                throw new SQLException("Greenplum snapshot isolation is not active");
            }
        }
        GreenplumCursorMetadata.lock(connection, request.schema(), request.table(), false, timeout());
        shape = GreenplumCursorMetadata.inspect(connection, request.schema(), request.table(), timeout());
        keys = GreenplumCursorMetadata.contract(shape, request.primaryKeys());
        for (String name : request.fields()) {
            if (!shape.columns().containsKey(name)) {
                throw new SQLException("Greenplum cursor projection changed");
            }
        }
        for (String name : order) {
            var c = shape.columns().get(name);
            if (c == null || !c.notNull()) {
                throw new SQLException("Greenplum cursor ordering must be non-null");
            }
            GreenplumCursorMetadata.numeric(c);
        }
        request.limits().requireStatementParameters(order.size() * 2 + 1);
        fingerprint = DigestUtil.sha256Hex(
                "GREENPLUM_CURSOR_V1\0" + shape.identity() + '\0' + shape.fingerprint() + '\0'
                        + String.join("\0", request.fields()) + '\0' + String.join("\0",
                        order) + '\0' + request.sourceGenerationPin().fingerprint());
        if (request.expectedSchemaFingerprint() != null && !request.expectedSchemaFingerprint().equals(fingerprint)) {
            throw new SQLException("Greenplum source generation or schema changed");
        }
        validate(request.lower());
        validate(request.fixedUpper());
        if (request.lower() != null) {
            try (var p = connection.prepareStatement(
                    "SELECT 1 FROM ONLY " + reference() + " WHERE " + q(request.watermark()) + " >= ? LIMIT 1")) {
                p.setQueryTimeout(timeout());
                bind(p, 1, request.lower().values().getFirst());
                try (var r = p.executeQuery()) {
                    if (!r.next()) {
                        throw new SQLException("Greenplum source watermark moved backwards");
                    }
                }
            }
        }
        upper = request.fixedUpper();
        if (upper == null && !request.upperBoundFrozen()) {
            try (var p = connection.createStatement()) {
                p.setQueryTimeout(timeout());
                try (var r = p.executeQuery(
                        "SELECT " + quoted(order) + " FROM ONLY " + reference() + " ORDER BY " + ordered(
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
                request.fields()) + " FROM ONLY " + reference() + " WHERE " + tuple + " <= " + parameters;
        if (request.lower() != null) {
            sql += " AND " + q(request.watermark()) + " >= ?";
        }
        if (pageAfter != null) {
            sql += " AND " + tuple + " > " + parameters;
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
            throw new SQLException("Greenplum cursor tuple changed");
        }
        for (int i = 0; i < order.size(); i++) {
            var c = shape.columns().get(order.get(i));
            var v = value.values().get(i);
            if (v.jdbcType() != GreenplumCursorMetadata.jdbcType(c) || !v.typeName().equals(c.type())) {
                throw new SQLException("Greenplum cursor type changed");
            }
            GreenplumCursorMetadata.exact(v.jdbcValue(), c);
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
            values.add(TypedCursorValue.of(GreenplumCursorMetadata.jdbcType(c), c.type(),
                    GreenplumCursorMetadata.read(r, index, c)));
        }
        return new TypedCursor(1, values);
    }

    /**
     * 推进游标并返回下一条数据或后续读取状态。
     */
    @Override
    public boolean next() throws SQLException {
        if (closed || Thread.currentThread().isInterrupted()) {
            throw new SQLException("Greenplum cursor interrupted or closed");
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
            values[i] = GreenplumCursorMetadata.read(result, i + 1, shape.columns().get(request.fields().get(i)));
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
        return GreenplumCursorMetadata.reference(request.schema(), request.table());
    }

    /**
     * 按Greenplum方言转义并引用标识符。
     */
    private static String q(String n) {
        return GreenplumCursorMetadata.quote(n);
    }

    /**
     * 返回按当前方言加引号后的标识符。
     */
    private static String quoted(List<String> names) {
        return String.join(",", names.stream().map(GreenplumCursorReader::q).toList());
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
            throw new IllegalStateException("Greenplum cursor has no row");
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
            throw new SQLException("Greenplum cursor physical cleanup was not confirmed");
        }
    }
}
