package cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.entity.*;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.util.Db2SourceGeneration;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.*;
import cn.iocoder.yudao.framework.dbswitch.product.db2.Db2CursorMetadata;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

import static cn.iocoder.yudao.framework.dbswitch.product.db2.Db2CursorMetadata.*;

/**
 * DB2 LUW 12.1.5.0 原生 REPEATABLE READ 读取：通过
 * SET CURRENT ISOLATION = RR 切换会话隔离级别，并从 CURRENT ISOLATION 寄存器读回确认。JCC 4.13.127 的
 * JDBC setTransactionIsolation 在此服务端不可靠；表级 WITH RR 子句后接
 * WHERE/ORDER BY 时会被解析器拒绝。范围锁一直持有到工作单元
 * 结束，因此快照存续期间会阻塞并发写入，已在真实服务端验证。
 * 在分页之间，通过专用游标稳定性连接重新读取代次标记，因此
 * 显式隔离可使快照失效。键集分页对精确排序主键使用 FETCH FIRST n ROWS ONLY。
 * 关闭时恢复 CS，再将连接归还连接池。
 */
final class Db2CursorReader implements CursorReadSession {

    private final Connection connection;

    private final Connection fence;

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

    private int pageRows;

    private boolean closed, ownsTransaction;

    /**
     * 打开游标读取会话并准备当前读取范围。
     */
    static CursorReadSession open(DataSource source, CursorReadRequest request, DbSwitchOptions options,
            int fetchSize) {
        Db2CursorReader reader = null;
        try {
            reader = new Db2CursorReader(source.getConnection(), source.getConnection(), request, options, fetchSize);
            reader.prepare();
            return reader;
        } catch (Exception failure) {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                }
            }
            throw new IllegalArgumentException("DB2 cursor generation, schema or ordering is not verified", failure);
        }
    }

    /**
     * 初始化 {@code Db2CursorReader}。
     */
    private Db2CursorReader(Connection c, Connection fence, CursorReadRequest request, DbSwitchOptions options,
            int fetchSize) {
        this.connection = c;
        this.fence = fence;
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
        product(fence);
        if (!connection.getAutoCommit() || !fence.getAutoCommit() || request.sourceGenerationPin() == null) {
            throw new SQLException("Cursor requires idle owned connections and a retained deployment pin");
        }
        Db2SourceGeneration.verifyCurrent(fence, request.sourceGenerationPin(), timeout());
        // 事务之前读取目录元数据，确保 RR 范围锁绝不覆盖 SYSCAT。
        var shape = Db2CursorMetadata.read(connection, request.schema(), request.table(), timeout());
        columns = shape.columns();
        keys = keys(shape, request.primaryKeys());
        for (String name : request.fields()) {
            if (!columns.containsKey(name)) {
                throw new SQLException("DB2 cursor projection is incomplete");
            }
        }
        var watermark = columns.get(request.watermark());
        if (watermark == null) {
            throw new SQLException("DB2 cursor watermark is absent");
        }
        requireWatermark(watermark);
        for (String name : order) {
            var column = columns.get(name);
            if (column == null) {
                throw new SQLException("DB2 cursor order is incomplete");
            }
            requireOrdered(column);
        }
        request.limits().requireStatementParameters(order.size() * (order.size() + 1) + 1);
        fingerprint = DigestUtil.sha256Hex(
                "DB2_CURSOR_V1\0" + shape.physical() + '\0' + shape.fingerprint() + '\0' + String.join("\0",
                        request.fields()) + '\0' + String.join("\0", order) + '\0' + request.sourceGenerationPin()
                        .fingerprint());
        if (request.expectedSchemaFingerprint() != null && !request.expectedSchemaFingerprint().equals(fingerprint)) {
            throw new SQLException("DB2 cursor source identity, generation or schema changed");
        }
        validate(request.lower());
        validate(request.fixedUpper());
        connection.setAutoCommit(false);
        ownsTransaction = true;
        try (var s = connection.createStatement()) {
            s.setQueryTimeout(timeout());
            s.execute("SET CURRENT ISOLATION = RR");
        }
        try (var s = connection.createStatement(); var r = s.executeQuery("VALUES CURRENT ISOLATION")) {
            if (!r.next() || !"RR".equals(r.getString(1) == null ? null : r.getString(1).trim())) {
                throw new SQLException("Native REPEATABLE READ isolation is inactive");
            }
        }
        if (request.lower() != null) {
            try (var p = connection.prepareStatement("SELECT 1 FROM " + reference() + " WHERE " + quote(
                    request.watermark()) + ">=? FETCH FIRST 1 ROWS ONLY")) {
                p.setQueryTimeout(timeout());
                bind(p, 1, request.lower().values().getFirst());
                try (var r = p.executeQuery()) {
                    if (!r.next()) {
                        throw new SQLException("DB2 source watermark moved backwards");
                    }
                }
            }
        }
        upper = request.fixedUpper();
        if (upper == null && !request.upperBoundFrozen()) {
            try (var s = connection.createStatement()) {
                s.setQueryTimeout(timeout());
                try (var r = s.executeQuery("SELECT " + fields(order) + " FROM " + reference() + " ORDER BY " + ordered(
                        "DESC") + " FETCH FIRST 1 ROWS ONLY")) {
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
        String sql = "SELECT " + fields(request.fields()) + " FROM " + reference() + " WHERE " + bounds(true);
        if (request.lower() != null) {
            sql += " AND " + quote(request.watermark()) + ">=?";
        }
        if (pageAfter != null) {
            sql += " AND " + bounds(false);
        }
        sql += " ORDER BY " + ordered("ASC") + " FETCH FIRST ? ROWS ONLY";
        statement = connection.prepareStatement(sql);
        statement.setQueryTimeout(timeout());
        int index = bindBound(statement, 1, upper);
        if (request.lower() != null) {
            bind(statement, index++, request.lower().values().getFirst());
        }
        if (pageAfter != null) {
            index = bindBound(statement, index, pageAfter);
        }
        statement.setInt(index, pageSize);
        result = statement.executeQuery();
        pageRows = 0;
    }

    /**
     * 返回当前边界集合。
     */
    private String bounds(boolean upperBound) {
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
        p.setObject(index, Db2CursorMetadata.bindable(value.jdbcValue(), type), value.jdbcType());
    }

    /**
     * 检查游标元组分量数与键列一致，并校验各键值符合声明的类型和取值范围。
     */
    private void validate(TypedCursor value) throws SQLException {
        if (value == null) {
            return;
        }
        if (value.values().size() != order.size()) {
            throw new SQLException("DB2 cursor tuple changed");
        }
        for (int i = 0; i < order.size(); i++) {
            var c = columns.get(order.get(i));
            var v = value.values().get(i);
            if (v.jdbcType() != jdbcType(c) || !v.typeName().equals(nativeType(c))) {
                throw new SQLException("DB2 cursor key type changed");
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
            throw new SQLException("DB2 cursor interrupted or closed");
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

    /** 通过专用 CS 隔离连接重新读取标记，使已提交隔离标记在 RR 快照内可见。 */
    private void verifyGeneration() throws SQLException {
        Db2SourceGeneration.verifyCurrent(fence, request.sourceGenerationPin(), timeout());
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
            throw new IllegalStateException("DB2 cursor has no row");
        }
        return row.clone();
    }

    /**
     * 关闭游标、连接与围栏；自建事务时尝试回滚并重置隔离和自动提交配置。
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
                    try (var s = connection.createStatement()) {
                        s.setQueryTimeout(timeout());
                        s.execute("SET CURRENT ISOLATION = CS");
                    }
                } catch (Exception ignored) {
                }
                try {
                    connection.setAutoCommit(true);
                } catch (Exception ignored) {
                }
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
        try {
            fence.close();
        } catch (Exception e) {
            failed = true;
        }
        if (failed) {
            throw new SQLException("DB2 cursor physical cleanup was not confirmed");
        }
    }
}
