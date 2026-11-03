package cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.entity.*;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.util.SybaseSourceGeneration;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.*;
import cn.iocoder.yudao.framework.dbswitch.product.sybase.SybaseCursorMetadata;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

import static cn.iocoder.yudao.framework.dbswitch.product.sybase.SybaseCursorMetadata.*;

/**
 * Sybase ASE 16 自主管理的 SERIALIZABLE 读取，按顺序精确的主键执行 set rowcount 键集分页。
 * 3 级锁在整个快照期间阻止并发写入；分页之间以已提交隔离级别
 * 重读代次标记，使显式隔离操作能够使快照失效。
 */
final class SybaseCursorReader implements CursorReadSession {

    private static final int SERIALIZABLE = Connection.TRANSACTION_SERIALIZABLE;

    private final Connection connection;

    private final CursorReadRequest request;

    private final DbSwitchOptions options;

    private final List<String> order;

    private final int pageSize;

    private Map<String, ColumnDescription> columns;

    private CursorKeyContract keys;

    private String fingerprint;

    private TypedCursor upper, position, pageAfter;

    private PreparedStatement statement;

    private ResultSet result;

    private Object[] row;

    private int pageRows, previousIsolation;

    private boolean closed, ownsTransaction;

    /**
     * 打开游标读取会话并准备当前读取范围。
     */
    static CursorReadSession open(DataSource source, CursorReadRequest request, DbSwitchOptions options,
            int fetchSize) {
        SybaseCursorReader reader = null;
        try {
            reader = new SybaseCursorReader(source.getConnection(), request, options, fetchSize);
            reader.prepare();
            return reader;
        } catch (Exception failure) {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                }
            }
            throw new IllegalArgumentException("Sybase cursor generation, schema or ordering is not verified", failure);
        }
    }

    /**
     * 初始化 {@code SybaseCursorReader}。
     */
    private SybaseCursorReader(Connection c, CursorReadRequest request, DbSwitchOptions options, int fetchSize) {
        this.connection = c;
        this.request = request;
        this.options = options;
        order = request.orderFields();
        pageSize = Math.max(1, Math.min(fetchSize, request.limits().maxRows()));
    }

    /**
     * 核对源代次、目标关系和键列事实，固定读取上界并准备当前游标分页状态。
     */
    private void prepare() throws SQLException {
        product(connection);
        if (!connection.getAutoCommit() || request.sourceGenerationPin() == null) {
            throw new SQLException("Cursor requires an idle owned connection and retained deployment pin");
        }
        SybaseSourceGeneration.verifyCurrent(connection, request.sourceGenerationPin(), timeout());
        // 目录元数据须在事务前读取，jconn4 的 getPrimaryKeys 会创建临时表。
        var shape = SybaseCursorMetadata.read(connection, request.schema(), request.table(), timeout());
        columns = shape.columns();
        keys = keys(shape, request.primaryKeys());
        for (String name : request.fields()) {
            if (!columns.containsKey(name)) {
                throw new SQLException("Sybase cursor projection is incomplete");
            }
        }
        var watermark = columns.get(request.watermark());
        if (watermark == null) {
            throw new SQLException("Sybase cursor watermark is absent");
        }
        requireWatermark(watermark);
        for (String name : order) {
            var column = columns.get(name);
            if (column == null) {
                throw new SQLException("Sybase cursor order is incomplete");
            }
            requireOrdered(column);
        }
        request.limits().requireStatementParameters(order.size() * (order.size() + 1) + 1);
        fingerprint = DigestUtil.sha256Hex(
                "SYBASE_CURSOR_V1\0" + shape.physical() + '\0' + shape.fingerprint() + '\0' + shape.sortOrder() + '\0' + String.join(
                        "\0", request.fields()) + '\0' + String.join("\0", order) + '\0' + request.sourceGenerationPin()
                        .fingerprint());
        if (request.expectedSchemaFingerprint() != null && !request.expectedSchemaFingerprint().equals(fingerprint)) {
            throw new SQLException("Sybase cursor source identity, generation or schema changed");
        }
        validate(request.lower());
        validate(request.fixedUpper());
        previousIsolation = connection.getTransactionIsolation();
        connection.setTransactionIsolation(SERIALIZABLE);
        connection.setAutoCommit(false);
        ownsTransaction = true;
        if (isolation(connection) != 3) {
            throw new SQLException("Native SERIALIZABLE isolation is inactive");
        }
        resetRowcount();
        if (request.lower() != null) {
            try (var p = connection.prepareStatement(
                    "SELECT TOP 1 1 FROM " + reference() + " WHERE " + quote(request.watermark()) + ">=?")) {
                p.setQueryTimeout(timeout());
                bind(p, 1, request.lower().values().getFirst());
                try (var r = p.executeQuery()) {
                    if (!r.next()) {
                        throw new SQLException("Sybase source watermark moved backwards");
                    }
                }
            }
        }
        upper = request.fixedUpper();
        if (upper == null && !request.upperBoundFrozen()) {
            try (var s = connection.createStatement()) {
                s.setQueryTimeout(timeout());
                try (var r = s.executeQuery(
                        "SELECT TOP 1 " + fields(order) + " FROM " + reference() + " ORDER BY " + ordered("DESC"))) {
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
     * 重置行数。
     */
    private void resetRowcount() throws SQLException {
        try (var s = connection.createStatement()) {
            s.setQueryTimeout(timeout());
            s.execute("SET ROWCOUNT 0");
        }
    }

    /**
     * 将读取边界值绑定到预编译查询参数。
     */
    private int bindBound(PreparedStatement p, int index, TypedCursor bound) throws SQLException {
        for (int i = 0; i < order.size(); i++) {
            for (int j = 0; j <= i; j++) {
                bind(p, index++, bound.values().get(j));
            }
        }
        return index;
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
        verifyGeneration();
        try (var s = connection.createStatement()) {
            s.setQueryTimeout(timeout());
            s.execute("SET ROWCOUNT " + pageSize);
        }
        String sql = "SELECT " + fields(request.fields()) + " FROM " + reference() + " WHERE " + bounds(true);
        if (request.lower() != null) {
            sql += " AND " + quote(request.watermark()) + ">=?";
        }
        if (pageAfter != null) {
            sql += " AND " + bounds(false);
        }
        sql += " ORDER BY " + ordered("ASC");
        statement = connection.prepareStatement(sql);
        statement.setQueryTimeout(timeout());
        int index = bindBound(statement, 1, upper);
        if (request.lower() != null) {
            bind(statement, index++, request.lower().values().getFirst());
        }
        if (pageAfter != null) {
            bindBound(statement, index, pageAfter);
        }
        result = statement.executeQuery();
        pageRows = 0;
    }

    /**
     * 返回当前边界集合。
     */
    private String bounds(boolean upperBound) throws SQLException {
        var alternatives = new ArrayList<String>();
        for (int i = 0; i < order.size(); i++) {
            var terms = new ArrayList<String>();
            for (int j = 0; j < i; j++) {
                terms.add(quote(order.get(j)) + "=?");
            }
            terms.add(quote(order.get(i)) + (upperBound ? (i == order.size() - 1 ? "<=" : "<") : ">") + "?");
            alternatives.add("(" + String.join(" AND ", terms) + ")");
        }
        return "(" + String.join(" OR ", alternatives) + ")";
    }

    /**
     * 将当前值绑定到预编译语句参数。
     */
    private static void bind(PreparedStatement p, int index, TypedCursorValue value) throws SQLException {
        String type = value.typeName();
        p.setObject(index, SybaseCursorMetadata.bindable(value.jdbcValue(), type), value.jdbcType());
    }

    /**
     * 检查游标元组分量数与键列一致，并校验各键值符合声明的类型和取值范围。
     */
    private void validate(TypedCursor value) throws SQLException {
        if (value == null) {
            return;
        }
        if (value.values().size() != order.size()) {
            throw new SQLException("Sybase cursor tuple changed");
        }
        for (int i = 0; i < order.size(); i++) {
            var c = columns.get(order.get(i));
            var v = value.values().get(i);
            if (v.jdbcType() != jdbcType(c) || !v.typeName().equals(nativeType(c))) {
                throw new SQLException("Sybase cursor key type changed");
            }
            exact(v.jdbcValue(), c);
        }
    }

    /**
     * 返回游标。
     */
    private TypedCursor cursor(ResultSet r, boolean projected) throws SQLException {
        var values = new ArrayList<TypedCursorValue>();
        for (int i = 0; i < order.size(); i++) {
            String name = order.get(i);
            var c = columns.get(name);
            int index = projected ? i + 1 : request.fields().indexOf(name) + 1;
            values.add(TypedCursorValue.of(jdbcType(c), nativeType(c),
                    read(r, index, c, request.limits().maxCellBytes())));
        }
        return new TypedCursor(1, values);
    }

    /**
     * 推进游标并返回下一条数据或后续读取状态。
     */
    @Override
    public boolean next() throws SQLException {
        if (closed || Thread.currentThread().isInterrupted()) {
            throw new SQLException("Sybase cursor interrupted or closed");
        }
        if (result == null) {
            verifyGeneration();
            return false;
        }
        if (!result.next()) {
            verifyGeneration();
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
            values[i] = read(result, i + 1, columns.get(request.fields().get(i)), request.limits().maxCellBytes());
        }
        position = cursor(result, false);
        pageRows++;
        row = BatchValueConverter.convert(List.<Object[]>of(values), values.length, request.limits()).getFirst();
        return true;
    }

    /** 以已提交隔离级别重读标记，确保 3 级快照内也能看到显式隔离标记。 */
    private void verifyGeneration() throws SQLException {
        try (var s = connection.createStatement()) {
            s.setQueryTimeout(timeout());
            s.execute("SET TRANSACTION ISOLATION LEVEL 1");
        }
        try {
            SybaseSourceGeneration.verifyCurrent(connection, request.sourceGenerationPin(), timeout());
        } finally {
            try (var s = connection.createStatement()) {
                s.setQueryTimeout(timeout());
                s.execute("SET TRANSACTION ISOLATION LEVEL 3");
            }
        }
    }

    /**
     * 返回事务隔离级别。
     */
    private static int isolation(Connection c) throws SQLException {
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT @@isolation")) {
            if (!r.next()) {
                throw new SQLException("Sybase isolation unavailable");
            }
            return r.getInt(1);
        }
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
        return relation(request.schema(), request.table());
    }

    /**
     * 按既定比较规则排列当前键或字段集合。
     */
    private String ordered(String direction) {
        return String.join(",", order.stream().map(n -> quote(n) + " " + direction).toList());
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
            throw new IllegalStateException("Sybase cursor has no row");
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
                try {
                    connection.rollback();
                } catch (Exception ignored) {
                }
                try {
                    resetRowcount();
                } catch (Exception ignored) {
                }
                try (var s = connection.createStatement()) {
                    s.setQueryTimeout(timeout());
                    s.execute("SET TRANSACTION ISOLATION LEVEL 1");
                } catch (Exception ignored) {
                }
                try {
                    connection.setAutoCommit(true);
                } catch (Exception ignored) {
                }
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
            throw new SQLException("Sybase cursor physical cleanup was not confirmed");
        }
    }
}
