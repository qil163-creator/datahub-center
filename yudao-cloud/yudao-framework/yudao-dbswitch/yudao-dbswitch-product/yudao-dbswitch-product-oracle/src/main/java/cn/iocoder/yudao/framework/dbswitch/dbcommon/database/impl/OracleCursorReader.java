package cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursor;
import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursorValue;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import cn.iocoder.yudao.framework.dbswitch.core.util.OracleCursorMetadata;
import cn.iocoder.yudao.framework.dbswitch.product.oracle.OracleReplayProfile;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadSession;
import cn.iocoder.yudao.framework.dbswitch.product.oracle.OracleExactValues;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Oracle SERIALIZABLE 快照加事务内持有的 ROW SHARE 表锁。首次
 * 读取固定快照，因此目录、固定上界及每个键集页共享该快照；SERIALIZABLE 读取器
 * 不持有行锁，也不会因 ORA-08177 失败。ROW SHARE 在整个快照期间阻止 TRUNCATE 及结构 DDL，
 * 同时允许并发 DML。Oracle 没有行值元组比较，键集边界
 * 以等价的字典序 OR 展开表示，已在真实 Free 23.26.3 服务端与 ORDER BY 核对。
 * 越过边界的 TRUNCATE/DROP 由服务端报告 ORA-08103/ORA-00942，绝不
 * 静默继续。会话 NLS_COMP/NLS_SORT 固定为 BINARY，使 ORDER BY 与绑定比较
 * 共享同一文本排序；时间比较参数使用固定格式的 TO_DATE/TO_TIMESTAMP 文本。
 */
public final class OracleCursorReader implements CursorReadSession {

    private final OracleReplayProfile profile;

    private final Connection connection;

    private String snapshotIdentity;

    private final CursorReadRequest request;

    private final DbSwitchOptions options;

    private final List<String> order;

    private final int pageSize;

    private OracleCursorMetadata.Shape shape;

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
        return open(source, request, options, fetchSize, OracleReplayProfile.ORACLE);
    }

    /**
     * 打开游标读取会话并准备当前读取范围。
     */
    public static CursorReadSession open(DataSource source, CursorReadRequest request, DbSwitchOptions options,
            int fetchSize, OracleReplayProfile profile) {
        java.util.Objects.requireNonNull(profile);
        OracleCursorReader reader = null;
        try {
            reader = new OracleCursorReader(source.getConnection(), request, options, fetchSize, profile);
            reader.prepare();
            return reader;
        } catch (Exception failure) {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                }
            }
            throw new IllegalArgumentException("Oracle cursor generation, schema or exact ordering is not verified");
        }
    }

    /**
     * 初始化 {@code OracleCursorReader}。
     */
    private OracleCursorReader(Connection connection, CursorReadRequest request, DbSwitchOptions options, int fetchSize,
            OracleReplayProfile profile) {
        this.profile = profile;
        this.connection = connection;
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
                    "Oracle cursor requires an idle owned connection and a deployed source generation pin");
        }
        profile.prepareSession(connection, timeout(), false);
        String textOrdering = profile.comparison(connection, timeout());
        previousIsolation = connection.getTransactionIsolation();
        connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        connection.setAutoCommit(false);
        if (connection.getTransactionIsolation() != Connection.TRANSACTION_SERIALIZABLE) {
            throw new SQLException("Oracle SERIALIZABLE snapshot isolation is not active");
        }
        ownsTransaction = true;
        profile.lock(connection, request.schema(), request.table(), timeout(), false);
        profile.verifyGeneration(connection, request.sourceGenerationPin(), timeout());
        shape = profile.inspect(connection, request.schema(), request.table(), timeout());
        snapshotIdentity = profile.snapshotIdentity(connection, timeout());
        if (!shape.primaryKey().equals(request.primaryKeys())) {
            throw new SQLException("Oracle cursor requires the exact ordered primary key");
        }
        keys = profile.keys(shape, request.primaryKeys(), textOrdering);
        for (String field : request.fields()) {
            var column = shape.columns().get(field);
            if (column == null) {
                throw new SQLException("Oracle cursor projection is incomplete");
            }
            OracleExactValues.kind(column);
            if (order.contains(field)) {
                OracleCursorMetadata.ordered(column);
            }
        }
        var watermark = shape.columns().get(request.watermark());
        if (watermark == null || !Set.of("NUMBER", "DATE", "TIMESTAMP").contains(watermark.dataType())) {
            throw new SQLException("Oracle watermark must be an exact numeric, date or timestamp column");
        }
        OracleCursorMetadata.ordered(watermark);
        request.limits().requireStatementParameters(order.size() * (order.size() + 1) + 1);
        var material = new StringBuilder("ORACLE_CURSOR_V1\0").append(profile.identity(shape)).append('\0')
                .append(keys.nativeFingerprint())
                .append('\0').append(request.watermark()).append('\0').append(String.join("\0", order))
                .append('\0').append(request.sourceGenerationPin().fingerprint());
        for (String field : request.fields()) {
            var column = shape.columns().get(field);
            material.append('\0').append(column.catalog());
        }
        fingerprint = DigestUtil.sha256Hex(material.toString());
        if (request.expectedSchemaFingerprint() != null && !request.expectedSchemaFingerprint().equals(fingerprint)) {
            throw new SQLException("Oracle cursor source identity, generation or schema changed");
        }
        validate(request.lower());
        validate(request.fixedUpper());
        if (request.lower() != null) {
            var column = shape.columns().get(request.watermark());
            try (var probe = connection.prepareStatement(
                    "SELECT 1 FROM " + reference() + " WHERE " + quote(request.watermark())
                            + ">=" + OracleExactValues.keyParameter(column) + " FETCH FIRST 1 ROWS ONLY")) {
                probe.setQueryTimeout(timeout());
                OracleExactValues.bindKey(probe, 1, request.lower().values().getFirst(), column);
                try (var rows = probe.executeQuery()) {
                    if (!rows.next()) {
                        throw new SQLException("Oracle source watermark moved backwards");
                    }
                }
            }
        }
        upper = request.fixedUpper();
        if (upper == null && !request.upperBoundFrozen()) {
            try (var probe = connection.createStatement()) {
                probe.setQueryTimeout(timeout());
                try (var rows = probe.executeQuery("SELECT " + projected(order) + " FROM " + reference()
                        + " ORDER BY " + ordered("DESC") + " FETCH FIRST 1 ROWS ONLY")) {
                    if (rows.next()) {
                        var values = new ArrayList<TypedCursorValue>();
                        for (int i = 0; i < order.size(); i++) {
                            values.add(OracleExactValues.key(shape.columns().get(order.get(i)),
                                    OracleExactValues.read(rows, i + 1, shape.columns().get(order.get(i)))));
                        }
                        upper = new TypedCursor(1, values);
                    }
                }
            }
        }
        if (upper != null) {
            openPage();
        }
    }

    /** Oracle 不实现有序行构造器；此处是等价的字典序析取表达式。 */
    private String bound(boolean upperBound) throws SQLException {
        var alternatives = new ArrayList<String>();
        for (int i = 0; i < order.size(); i++) {
            var terms = new ArrayList<String>();
            for (int j = 0; j < i; j++) {
                terms.add(
                        quote(order.get(j)) + "=" + OracleExactValues.keyParameter(shape.columns().get(order.get(j))));
            }
            terms.add(quote(order.get(
                    i)) + (upperBound ? (i == order.size() - 1 ? "<=" : "<") : ">") + OracleExactValues.keyParameter(
                    shape.columns().get(order.get(i))));
            alternatives.add("(" + String.join(" AND ", terms) + ")");
        }
        return "(" + String.join(" OR ", alternatives) + ")";
    }

    /**
     * 将读取边界值绑定到预编译查询参数。
     */
    private int bindBound(PreparedStatement statement, int index, TypedCursor bound) throws SQLException {
        for (int i = 0; i < order.size(); i++) {
            for (int j = 0; j <= i; j++) {
                OracleExactValues.bindKey(statement, index++, bound.values().get(j), shape.columns().get(order.get(j)));
            }
        }
        return index;
    }

    /**
     * 校验并取得快照。
     */
    private void requireSnapshot() throws SQLException {
        if (snapshotIdentity != null && !snapshotIdentity.equals(profile.snapshotIdentity(connection, timeout()))) {
            throw new SQLException("Cursor snapshot transaction changed");
        }
    }

    /**
     * 按当前游标边界打开下一页结果集。
     */
    private void openPage() throws SQLException {
        requireSnapshot();
        if (result != null) {
            result.close();
        }
        if (statement != null) {
            statement.close();
        }
        String sql = "SELECT " + projected(request.fields()) + " FROM " + reference() + " WHERE " + bound(true);
        if (request.lower() != null) {
            sql += " AND " + quote(request.watermark()) + ">=" + OracleExactValues.keyParameter(
                    shape.columns().get(request.watermark()));
        }
        if (pageAfter != null) {
            sql += " AND " + bound(false);
        }
        sql += " ORDER BY " + ordered("ASC") + " FETCH FIRST " + pageSize + " ROWS ONLY";
        statement = connection.prepareStatement(sql);
        statement.setQueryTimeout(timeout());
        int index = bindBound(statement, 1, upper);
        if (request.lower() != null) {
            OracleExactValues.bindKey(statement, index++, request.lower().values().getFirst(),
                    shape.columns().get(request.watermark()));
        }
        if (pageAfter != null) {
            bindBound(statement, index, pageAfter);
        }
        result = statement.executeQuery();
        pageRows = 0;
    }

    /**
     * 检查游标元组分量数与键列一致，并校验各键值符合声明的类型和取值范围。
     */
    private void validate(TypedCursor cursor) throws SQLException {
        if (cursor == null) {
            return;
        }
        if (cursor.values().size() != order.size()) {
            throw new SQLException("Oracle cursor tuple shape changed");
        }
        for (int i = 0; i < order.size(); i++) {
            OracleCursorMetadata.validateKey(cursor.values().get(i), shape.columns().get(order.get(i)));
        }
    }

    /**
     * 推进游标并返回下一条数据或后续读取状态。
     */
    @Override
    public boolean next() throws SQLException {
        if (closed || Thread.currentThread().isInterrupted()) {
            throw new SQLException("Oracle cursor interrupted or closed");
        }
        if (result == null) {
            return false;
        }
        if (!result.next()) {
            if (pageRows < pageSize) {
                requireSnapshot();
                row = null;
                position = null;
                return false;
            }
            pageAfter = position;
            openPage();
            if (!result.next()) {
                requireSnapshot();
                row = null;
                position = null;
                return false;
            }
        }
        Object[] values = new Object[request.fields().size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = OracleExactValues.read(result, i + 1, shape.columns().get(request.fields().get(i)));
        }
        var tuple = new ArrayList<TypedCursorValue>();
        for (String name : order) {
            tuple.add(OracleExactValues.key(shape.columns().get(name), values[request.fields().indexOf(name)]));
        }
        position = new TypedCursor(1, tuple);
        pageRows++;
        row = BatchValueConverter.convert(List.<Object[]>of(values), values.length, request.limits()).getFirst();
        return true;
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
            throw new IllegalStateException("Oracle cursor has no row");
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
        } catch (Exception failure) {
            failed = true;
        }
        try {
            if (statement != null) {
                statement.close();
            }
        } catch (Exception failure) {
            failed = true;
        }
        try {
            if (ownsTransaction) {
                connection.rollback();
                connection.setAutoCommit(true);
                connection.setTransactionIsolation(previousIsolation);
            }
        } catch (Exception failure) {
            failed = true;
            try {
                connection.abort(Runnable::run);
            } catch (Exception ignored) {
            }
        }
        try {
            connection.close();
        } catch (Exception failure) {
            failed = true;
        }
        if (failed) {
            throw new SQLException("Oracle cursor physical cleanup was not confirmed");
        }
    }

    /**
     * 返回列。
     */
    private OracleCursorMetadata.Column column(String name) {
        return shape.columns().get(name);
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
        return OracleCursorMetadata.relation(request.schema(), request.table());
    }

    /**
     * 返回当前投影。
     */
    private String projected(List<String> fields) throws SQLException {
        var expressions = new ArrayList<String>();
        for (String field : fields) {
            expressions.add(OracleExactValues.projection(column(field)));
        }
        return String.join(",", expressions);
    }

    /**
     * 按既定比较规则排列当前键或字段集合。
     */
    private String ordered(String direction) {
        return String.join(",", order.stream().map(name -> quote(name) + " " + direction).toList());
    }

    /**
     * 按Oracle方言转义并引用标识符。
     */
    private static String quote(String name) {
        return OracleCursorMetadata.quote(name);
    }
}
