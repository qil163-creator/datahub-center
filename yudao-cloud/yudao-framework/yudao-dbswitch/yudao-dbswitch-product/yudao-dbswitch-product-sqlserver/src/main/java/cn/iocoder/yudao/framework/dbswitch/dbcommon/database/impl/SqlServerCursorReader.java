package cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.entity.*;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.util.SqlServerSourceGeneration;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.*;
import cn.iocoder.yudao.framework.dbswitch.product.sqlserver.SqlServerCursorMetadata;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

import static cn.iocoder.yudao.framework.dbswitch.product.sqlserver.SqlServerCursorMetadata.*;

/** SQL Server 16 自主管理的 SNAPSHOT；DDL 使快照失效，不自动修改数据库配置。 */
final class SqlServerCursorReader implements CursorReadSession {

    private static final int SNAPSHOT = 4096;

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
        SqlServerCursorReader reader = null;
        try {
            reader = new SqlServerCursorReader(source.getConnection(), request, options, fetchSize);
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
                    "SQL Server cursor snapshot, generation, schema or ordering is not verified");
        }
    }

    /**
     * 初始化 {@code SqlServerCursorReader}。
     */
    private SqlServerCursorReader(Connection c, CursorReadRequest request, DbSwitchOptions options, int fetchSize) {
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
        verifyGeneration();
        try (var p = connection.createStatement()) {
            p.setQueryTimeout(timeout());
            try (var r = p.executeQuery(
                    "SELECT snapshot_isolation_state FROM sys.databases WHERE database_id=DB_ID()")) {
                if (!r.next() || r.getInt(1) != 1 || r.next()) {
                    throw new SQLException("Database SNAPSHOT isolation must be enabled by its administrator");
                }
            }
        }
        previousIsolation = connection.getTransactionIsolation();
        ownsTransaction = true;
        connection.setTransactionIsolation(SNAPSHOT);
        connection.setAutoCommit(false);
        if (connection.getTransactionIsolation() != SNAPSHOT) {
            throw new SQLException("Native SNAPSHOT isolation is inactive");
        }
        var shape = SqlServerCursorMetadata.read(connection, request.schema(), request.table(), timeout());
        columns = shape.columns();
        keys = keys(shape, request.primaryKeys());
        for (String name : request.fields()) {
            if (!columns.containsKey(name)) {
                throw new SQLException("SQL Server cursor projection is incomplete");
            }
        }
        for (String name : order) {
            var column = columns.get(name);
            if (column == null) {
                throw new SQLException("SQL Server cursor order is incomplete");
            }
            requireOrdered(column);
        }
        request.limits().requireStatementParameters(order.size() * (order.size() + 1) + 1);
        fingerprint = DigestUtil.sha256Hex(
                "SQLSERVER_CURSOR_V1\0" + shape.physical() + '\0' + shape.fingerprint() + '\0' + String.join("\0",
                        request.fields()) + '\0' + String.join("\0", order) + '\0' + request.sourceGenerationPin()
                        .fingerprint());
        if (request.expectedSchemaFingerprint() != null && !request.expectedSchemaFingerprint().equals(fingerprint)) {
            throw new SQLException("SQL Server cursor source identity, generation or schema changed");
        }
        validate(request.lower());
        validate(request.fixedUpper());
        if (request.lower() != null) {
            try (var p = connection.prepareStatement(
                    "SELECT TOP(1) 1 FROM " + reference() + " WHERE " + quote(request.watermark()) + ">=" + parameter(
                            request.watermark()))) {
                p.setQueryTimeout(timeout());
                bind(p, 1, request.lower().values().getFirst());
                try (var r = p.executeQuery()) {
                    if (!r.next()) {
                        throw new SQLException("SQL Server source watermark moved backwards");
                    }
                }
            }
        }
        upper = request.fixedUpper();
        if (upper == null && !request.upperBoundFrozen()) {
            try (var p = connection.createStatement()) {
                p.setQueryTimeout(timeout());
                try (var r = p.executeQuery(
                        "SELECT TOP(1) " + fields(order) + " FROM " + reference() + " ORDER BY " + ordered("DESC"))) {
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
     * 返回参数。
     */
    private String parameter(String name) throws SQLException {
        return "CAST(? AS " + nativeType(columns.get(name)) + ")";
    }

    /**
     * 返回当前边界。
     */
    private String bound(boolean upperBound) throws SQLException {
        var alternatives = new ArrayList<String>();
        for (int i = 0; i < order.size(); i++) {
            var terms = new ArrayList<String>();
            for (int j = 0; j < i; j++) {
                terms.add(quote(order.get(j)) + "=" + parameter(order.get(j)));
            }
            terms.add(quote(order.get(i)) + (upperBound ? (i == order.size() - 1 ? "<=" : "<") : ">") + parameter(
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
        String sql = "SELECT TOP(" + pageSize + ") " + fields(
                request.fields()) + " FROM " + reference() + " WHERE " + bound(true);
        if (request.lower() != null) {
            sql += " AND " + quote(request.watermark()) + ">=" + parameter(request.watermark());
        }
        if (pageAfter != null) {
            sql += " AND " + bound(false);
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
     * 将当前值绑定到预编译语句参数。
     */
    private static void bind(PreparedStatement p, int index, TypedCursorValue value) throws SQLException {
        String type = value.typeName();
        Object exact = SqlServerTimeValues.bindable(value.jdbcValue(),
                type.contains("(") ? type.substring(0, type.indexOf('(')) : type);
        if (exact instanceof org.springframework.jdbc.core.SqlTypeValue text) {
            text.setTypeValue(p, index, value.jdbcType(), null);
        } else {
            p.setObject(index, exact, value.jdbcType());
        }
    }

    /**
     * 检查游标元组分量数与键列一致，并校验各键值符合声明的类型和取值范围。
     */
    private void validate(TypedCursor value) throws SQLException {
        if (value == null) {
            return;
        }
        if (value.values().size() != order.size()) {
            throw new SQLException("SQL Server cursor tuple changed");
        }
        for (int i = 0; i < order.size(); i++) {
            var c = columns.get(order.get(i));
            var v = value.values().get(i);
            if (v.jdbcType() != jdbcType(c) || !v.typeName().equals(nativeType(c))) {
                throw new SQLException("SQL Server cursor key type changed");
            }
            // 游标文本来自此原生列，验证其无损标量值域；文本比较由数据库负责。
            if (!nativeType(c).matches("n?(?:var)?char\\(.+")) {
                exact(v.jdbcValue(), c, null);
            }
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
            throw new SQLException("SQL Server cursor interrupted or closed");
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

    /**
     * 检查当前资源代次是否与预期代次一致。
     */
    private void verifyGeneration() throws SQLException {
        SqlServerSourceGeneration.verifyCurrent(connection, request.sourceGenerationPin(), timeout());
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
            throw new IllegalStateException("SQL Server cursor has no row");
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
            throw new SQLException("SQL Server cursor physical cleanup was not confirmed");
        }
    }
}
