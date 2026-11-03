package cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.entity.*;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import cn.iocoder.yudao.framework.dbswitch.core.util.DmSqlNames;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.*;
import cn.iocoder.yudao.framework.dbswitch.product.dm.*;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/** DM SERIALIZABLE 快照加事务内持有的行共享表锁；对已验证键值域执行有数量上限的键集分页。 */
final class DmCursorReader implements CursorReadSession {

    private final Connection connection;

    private final CursorReadRequest request;

    private final DbSwitchOptions options;

    private final List<String> order;

    private final int pageSize;

    private Map<String, DmNativeTables.ReadColumn> columns;

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
        DmCursorReader reader = null;
        try {
            reader = new DmCursorReader(source.getConnection(), request, options, fetchSize);
            reader.prepare();
            return reader;
        } catch (Exception failure) {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                }
            }
            throw new IllegalArgumentException("DM cursor generation, schema or exact ordering is not verified");
        }
    }

    /**
     * 初始化 {@code DmCursorReader}。
     */
    private DmCursorReader(Connection c, CursorReadRequest request, DbSwitchOptions options, int fetchSize) {
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
        if (!connection.getAutoCommit() || request.sourceGenerationPin() == null) {
            throw new SQLException(
                    "DM cursor requires a fresh owned connection and externally retained source generation pin");
        }
        previousIsolation = connection.getTransactionIsolation();
        ownsTransaction = true;
        connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        connection.setAutoCommit(false);
        if (connection.getTransactionIsolation() != Connection.TRANSACTION_SERIALIZABLE) {
            throw new SQLException("DM serializable isolation is not active");
        }
        DmCursorMetadata.lock(connection, request.schema(), request.table(), timeout());
        var shape = DmNativeTables.readReplayShape(connection, request.schema(), request.table(), timeout());
        columns = DmCursorMetadata.columns(shape);
        keys = DmCursorMetadata.keyContract(connection, shape, request.primaryKeys(), timeout());
        for (String name : request.fields()) {
            var c = columns.get(name);
            if (c == null) {
                throw new SQLException("DM cursor projection is incomplete");
            }
            DmCursorMetadata.jdbcType(c);
        }
        boolean text = false;
        for (String name : order) {
            var c = columns.get(name);
            if (c == null) {
                throw new SQLException("DM cursor ordering is incomplete");
            }
            DmCursorMetadata.ordered(c);
            text |= DmCursorMetadata.textual(c);
        }
        if (text) {
            DmCursorMetadata.requireTextOrdering(connection, timeout());
        }
        request.limits().requireStatementParameters(order.size() * (order.size() + 1) + 1);
        fingerprint = DigestUtil.sha256Hex(
                "DM_CURSOR_V1\0" + shape.identity() + '\0' + shape.fingerprint() + '\0' + String.join("\0",
                        request.fields()) + '\0' + String.join("\0", order)
                        + '\0' + (request.sourceGenerationPin() == null ? "native" : request.sourceGenerationPin()
                        .fingerprint()));
        if (request.expectedSchemaFingerprint() != null && !request.expectedSchemaFingerprint().equals(fingerprint)) {
            throw new SQLException("DM source relation, schema or generation changed");
        }
        validate(request.lower());
        validate(request.fixedUpper());
        if (request.lower() != null) {
            try (var p = connection.prepareStatement(
                    "SELECT 1 FROM " + reference() + " WHERE " + q(request.watermark()) + ">=" + parameter(
                            request.watermark()) + " FETCH FIRST 1 ROWS ONLY")) {
                p.setQueryTimeout(timeout());
                bind(p, 1, request.lower().values().getFirst(), request.watermark());
                try (var r = p.executeQuery()) {
                    if (!r.next()) {
                        throw new SQLException("DM source watermark moved backwards");
                    }
                }
            }
        }
        upper = request.fixedUpper();
        if (upper == null && !request.upperBoundFrozen()) {
            try (var p = connection.createStatement()) {
                p.setQueryTimeout(timeout());
                try (var r = p.executeQuery("SELECT " + quoted(order) + " FROM " + reference() + " ORDER BY " + ordered(
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

    /** DM 不实现有序行构造器；此处是等价的字典序析取表达式。 */
    private String bound(boolean upperBound) throws SQLException {
        var alternatives = new ArrayList<String>();
        for (int i = 0; i < order.size(); i++) {
            var terms = new ArrayList<String>();
            for (int j = 0; j < i; j++) {
                terms.add(q(order.get(j)) + "=" + parameter(order.get(j)));
            }
            terms.add(q(order.get(i)) + (upperBound ? (i == order.size() - 1 ? "<=" : "<") : ">") + parameter(
                    order.get(i)));
            alternatives.add("(" + String.join(" AND ", terms) + ")");
        }
        return "(" + String.join(" OR ", alternatives) + ")";
    }

    /**
     * 将读取边界值绑定到预编译查询参数。
     */
    private int bindBound(PreparedStatement p, int index, TypedCursor bound) throws SQLException {
        for (int i = 0; i < order.size(); i++) {
            for (int j = 0; j <= i; j++) {
                bind(p, index++, bound.values().get(j), order.get(j));
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
        String sql = "SELECT " + quoted(request.fields()) + " FROM " + reference() + " WHERE " + bound(true);
        if (request.lower() != null) {
            sql += " AND " + q(request.watermark()) + ">=" + parameter(request.watermark());
        }
        if (pageAfter != null) {
            sql += " AND " + bound(false);
        }
        sql += " ORDER BY " + ordered("ASC") + " FETCH FIRST " + pageSize + " ROWS ONLY";
        statement = connection.prepareStatement(sql);
        statement.setQueryTimeout(timeout());
        int index = bindBound(statement, 1, upper);
        if (request.lower() != null) {
            bind(statement, index++, request.lower().values().getFirst(), request.watermark());
        }
        if (pageAfter != null) {
            bindBound(statement, index, pageAfter);
        }
        result = statement.executeQuery();
        pageRows = 0;
    }

    /**
     * 返回参数。
     */
    private String parameter(String name) throws SQLException {
        return DmCursorMetadata.parameter(columns.get(name));
    }

    /**
     * 将当前值绑定到预编译语句参数。
     */
    private void bind(PreparedStatement p, int index, TypedCursorValue value, String name) throws SQLException {
        DmCursorMetadata.bind(p, index, value, columns.get(name));
    }

    /**
     * 检查游标元组分量数与键列一致，并校验各键值符合声明的类型和取值范围。
     */
    private void validate(TypedCursor value) throws SQLException {
        if (value == null) {
            return;
        }
        if (value.values().size() != order.size()) {
            throw new SQLException("DM cursor tuple changed");
        }
        for (int i = 0; i < order.size(); i++) {
            DmCursorMetadata.cursorValue(value.values().get(i), columns.get(order.get(i)));
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
            values.add(TypedCursorValue.of(DmCursorMetadata.cursorJdbcType(c), DmCursorMetadata.keySqlType(c),
                    DmCursorMetadata.readKey(r, index, c)));
        }
        return new TypedCursor(1, values);
    }

    /**
     * 推进游标并返回下一条数据或后续读取状态。
     */
    @Override
    public boolean next() throws SQLException {
        if (closed || Thread.currentThread().isInterrupted()) {
            throw new SQLException("DM cursor interrupted or closed");
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
            values[i] = DmCursorMetadata.read(result, i + 1, columns.get(request.fields().get(i)));
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
        return DmSqlNames.relation(request.schema(), request.table());
    }

    /**
     * 按DM方言转义并引用标识符。
     */
    private static String q(String n) {
        return DmSqlNames.quote(n);
    }

    /**
     * 返回按当前方言加引号后的标识符。
     */
    private static String quoted(List<String> names) {
        return DmSqlNames.columns(names);
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
            throw new IllegalStateException("DM cursor has no row");
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
            throw new SQLException("DM cursor physical cleanup was not confirmed");
        }
    }
}
