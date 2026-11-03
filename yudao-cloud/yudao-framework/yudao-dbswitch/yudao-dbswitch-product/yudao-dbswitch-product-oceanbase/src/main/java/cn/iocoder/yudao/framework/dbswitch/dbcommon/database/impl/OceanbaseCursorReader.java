package cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursor;
import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursorValue;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import cn.iocoder.yudao.framework.dbswitch.core.util.OceanbaseCursorMetadata;
import cn.iocoder.yudao.framework.dbswitch.core.util.OceanbaseSourceGeneration;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadSession;
import cn.iocoder.yudao.framework.dbswitch.product.oceanbase.OceanbaseExactValues;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 在已验证 OceanBase MySQL 租户上持有单个只读 REPEATABLE READ 事务。OceanBase 首次读取时建立快照，
 * 所以先读取租户、tablet 和列目录，它们与固定上界及
 * 每个键集页面属于同一快照。快照不阻止 TRUNCATE/DROP：之后的下一次读取
 * 以 "read too old versioned data" 失败，恢复时变化的 tablet 代际会拒绝旧计划。
 */
final class OceanbaseCursorReader implements CursorReadSession {

    private final Connection connection;

    private final CursorReadRequest request;

    private final DbSwitchOptions options;

    private final List<String> order;

    private final int pageSize;

    private OceanbaseCursorMetadata.Shape shape;

    private CursorKeyContract keys;

    private String fingerprint, transaction, previousTimeZone, reference;

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
        OceanbaseCursorReader reader = null;
        try {
            reader = new OceanbaseCursorReader(source.getConnection(), request, options, fetchSize);
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
                    "OceanBase cursor tenant, generation, snapshot or exact ordering is not verified");
        }
    }

    /**
     * 初始化 {@code OceanbaseCursorReader}。
     */
    private OceanbaseCursorReader(Connection connection, CursorReadRequest request, DbSwitchOptions options,
            int fetchSize) {
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
                    "OceanBase cursor requires an owned idle connection and a deployed source generation pin");
        }
        // 模式、版本和驱动来自此连接；任何状态变更前先拒绝 Oracle 模式租户。
        OceanbaseCursorMetadata.requireSession(connection, timeout(), false);
        OceanbaseCursorMetadata.requireIdle(connection, timeout());
        previousIsolation = connection.getTransactionIsolation();
        previousReadOnly = connection.isReadOnly();
        ownsTransaction = true;
        try (var probe = connection.createStatement()) {
            probe.setQueryTimeout(timeout());
            try (var rows = probe.executeQuery("SELECT @@session.time_zone")) {
                if (!rows.next() || (previousTimeZone = rows.getString(1)) == null) {
                    throw new SQLException("OceanBase session time zone is unavailable");
                }
            }
            probe.execute("SET SESSION time_zone='+00:00'");
        }
        connection.setReadOnly(true);
        connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        connection.setAutoCommit(false);
        try (var probe = connection.createStatement()) {
            probe.setQueryTimeout(timeout());
            try (var rows = probe.executeQuery("SELECT @@transaction_isolation,@@tx_read_only,@@session.time_zone")) {
                if (!rows.next() || !"REPEATABLE-READ".equals(rows.getString(1)) || !"1".equals(rows.getString(2))
                        || !"+00:00".equals(rows.getString(3)) || rows.next()) {
                    throw new SQLException("OceanBase read-only snapshot isolation is not active");
                }
            }
        }
        reference = OceanbaseCursorMetadata.reference(request.schema(), request.table());
        shape = OceanbaseCursorMetadata.inspect(connection, request.schema(), request.table(), timeout());
        transaction = OceanbaseCursorMetadata.transaction(connection, timeout());
        if ("0".equals(transaction)) {
            throw new SQLException("OceanBase snapshot transaction is not active");
        }
        OceanbaseSourceGeneration.verify(shape.tenant(), request.sourceGenerationPin());
        if (!shape.primaryKey().equals(request.primaryKeys())) {
            throw new SQLException("OceanBase cursor requires the exact ordered primary key");
        }
        keys = OceanbaseCursorMetadata.keyContract(shape, request.primaryKeys());
        var material = new StringBuilder("OCEANBASE_CURSOR_V1").append('\0').append(shape.tenant().identity())
                .append('\0').append(shape.physical())
                .append('\0').append(keys.nativeFingerprint()).append('\0').append(request.watermark()).append('\0')
                .append(String.join("\0", order))
                .append('\0').append(request.sourceGenerationPin().fingerprint());
        for (String field : request.fields()) {
            var column = shape.columns().get(field);
            if (column == null) {
                throw new SQLException("OceanBase cursor projection is incomplete");
            }
            OceanbaseExactValues.kind(column);
            if (order.contains(field)) {
                if (column.nullable()) {
                    throw new SQLException("OceanBase cursor ordering cannot contain NULL");
                }
                OceanbaseExactValues.keyJdbcType(column);
            }
            material.append('\0').append(column.catalog());
        }
        var watermark = OceanbaseExactValues.kind(column(request.watermark()));
        if (watermark != OceanbaseExactValues.Kind.INTEGER && watermark != OceanbaseExactValues.Kind.DECIMAL && watermark != OceanbaseExactValues.Kind.DATE
                && watermark != OceanbaseExactValues.Kind.DATETIME && watermark != OceanbaseExactValues.Kind.TIMESTAMP) {
            throw new SQLException("OceanBase watermark must be exact numeric or temporal");
        }
        request.limits().requireStatementParameters(order.size() * 2 + 1);
        fingerprint = DigestUtil.sha256Hex(material.toString());
        if (request.expectedSchemaFingerprint() != null && !request.expectedSchemaFingerprint().equals(fingerprint)) {
            throw new SQLException("OceanBase cursor tenant, tablets, schema or deployed generation changed");
        }
        validate(request.lower());
        validate(request.fixedUpper());
        if (request.lower() != null) {
            var column = column(request.watermark());
            try (var probe = connection.prepareStatement(
                    "SELECT 1 FROM " + reference + " WHERE " + quote(request.watermark()) + ">="
                            + OceanbaseExactValues.keyParameter(column) + " LIMIT 1")) {
                probe.setQueryTimeout(timeout());
                OceanbaseExactValues.bindKey(probe, 1, request.lower().values().getFirst(), column);
                try (var rows = probe.executeQuery()) {
                    if (!rows.next()) {
                        throw new SQLException("OceanBase source watermark moved backwards");
                    }
                }
            }
        }
        upper = request.fixedUpper();
        if (upper == null && !request.upperBoundFrozen()) {
            try (var probe = connection.createStatement()) {
                probe.setQueryTimeout(timeout());
                try (var rows = probe.executeQuery(
                        "SELECT " + projected(order) + " FROM " + reference + " ORDER BY " + ordered(
                                "DESC") + " LIMIT 1")) {
                    if (rows.next()) {
                        var values = new ArrayList<TypedCursorValue>();
                        for (int i = 0; i < order.size(); i++) {
                            values.add(OceanbaseExactValues.key(column(order.get(i)),
                                    OceanbaseExactValues.read(rows, i + 1, column(order.get(i)))));
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
        String tuple = "(" + String.join(",", order.stream().map(OceanbaseCursorReader::quote).toList()) + ")";
        var bounds = new ArrayList<String>();
        for (String name : order) {
            bounds.add(OceanbaseExactValues.keyParameter(column(name)));
        }
        String parameters = "(" + String.join(",", bounds) + ")";
        String sql = "SELECT " + projected(
                request.fields()) + " FROM " + reference + " WHERE " + tuple + "<=" + parameters;
        if (request.lower() != null) {
            sql += " AND " + quote(request.watermark()) + ">=" + OceanbaseExactValues.keyParameter(
                    column(request.watermark()));
        }
        if (pageAfter != null) {
            sql += " AND " + tuple + ">" + parameters;
        }
        sql += " ORDER BY " + ordered("ASC") + " LIMIT " + pageSize;
        statement = connection.prepareStatement(sql);
        statement.setQueryTimeout(timeout());
        int index = 1;
        for (int i = 0; i < order.size(); i++) {
            OceanbaseExactValues.bindKey(statement, index++, upper.values().get(i), column(order.get(i)));
        }
        if (request.lower() != null) {
            OceanbaseExactValues.bindKey(statement, index++, request.lower().values().getFirst(),
                    column(request.watermark()));
        }
        if (pageAfter != null) {
            for (int i = 0; i < order.size(); i++) {
                OceanbaseExactValues.bindKey(statement, index++, pageAfter.values().get(i), column(order.get(i)));
            }
        }
        result = statement.executeQuery();
        pageRows = 0;
    }

    /** 每页必须仍在固定快照的事务中运行；不接受隐式重启。 */
    private void requireSnapshot() throws SQLException {
        if (!transaction.equals(OceanbaseCursorMetadata.transaction(connection, timeout()))) {
            throw new SQLException("OceanBase snapshot transaction changed");
        }
    }

    /**
     * 检查游标元组分量数与键列一致，并校验各键值符合声明的类型和取值范围。
     */
    private void validate(TypedCursor cursor) throws SQLException {
        if (cursor == null) {
            return;
        }
        if (cursor.values().size() != order.size()) {
            throw new SQLException("OceanBase cursor tuple shape changed");
        }
        for (int i = 0; i < order.size(); i++) {
            OceanbaseExactValues.validateKey(cursor.values().get(i), column(order.get(i)));
        }
    }

    /**
     * 推进游标并返回下一条数据或后续读取状态。
     */
    @Override
    public boolean next() throws SQLException {
        if (closed || Thread.currentThread().isInterrupted()) {
            throw new SQLException("OceanBase cursor interrupted or closed");
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
            values[i] = OceanbaseExactValues.read(result, i + 1, column(request.fields().get(i)));
        }
        var tuple = new ArrayList<TypedCursorValue>();
        for (String name : order) {
            tuple.add(OceanbaseExactValues.key(column(name), values[request.fields().indexOf(name)]));
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
            throw new IllegalStateException("OceanBase cursor has no row");
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
                if (!connection.getAutoCommit()) {
                    connection.rollback();
                }
                connection.setAutoCommit(true);
                connection.setReadOnly(previousReadOnly);
                connection.setTransactionIsolation(previousIsolation);
                if (previousTimeZone != null) {
                    try (var reset = connection.prepareStatement("SET SESSION time_zone=?")) {
                        reset.setQueryTimeout(timeout());
                        reset.setString(1, previousTimeZone);
                        reset.execute();
                    }
                }
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
            throw new SQLException("OceanBase cursor source cleanup was not confirmed");
        }
    }

    /**
     * 返回列。
     */
    private OceanbaseCursorMetadata.Column column(String name) {
        return shape.columns().get(name);
    }

    /**
     * 返回超时设置。
     */
    private int timeout() {
        return options.queryTimeoutSeconds();
    }

    /**
     * 返回当前投影。
     */
    private String projected(List<String> fields) throws SQLException {
        var expressions = new ArrayList<String>();
        for (String field : fields) {
            expressions.add(OceanbaseExactValues.projection(column(field)));
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
     * 按OceanBase方言转义并引用标识符。
     */
    private static String quote(String name) {
        return OceanbaseCursorMetadata.quote(name);
    }
}
