package cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursor;
import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursorValue;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import cn.iocoder.yudao.framework.dbswitch.core.util.TdengineSourceGeneration;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadSession;
import cn.iocoder.yudao.framework.dbswitch.product.tdengine.TdengineCursorMetadata;
import cn.iocoder.yudao.framework.dbswitch.product.tdengine.TdengineJdbcSupport;

import javax.sql.DataSource;
import java.math.BigInteger;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * 不持有读取快照的 TDengine 类型化游标。固定上界为运行开始时可见的最大有序键；
 * 有界键集分页按 TDengine 二进制顺序遍历 (ts[, tbname][, key])，每页都完整读取，
 * 检查严格递增，并在释放数据行前重新验证部署的数据库代次及物理关系。
 * 完成时重新验证整体结构。恢复运行会重读最后已应用的
 * 时间戳组，使后来到达的相同时间戳行（另一子表或键）不会丢失。
 * 后来到达且时间戳更旧的行不在游标范围内：至少一次交付仅适用于某次运行
 * 将上界固定在其时间戳之上前已经可见的行，且只有幂等覆盖目标才能保证重放安全。
 */
final class TdengineCursorReader implements CursorReadSession {

    private record Page(Object[] values, TypedCursor position) {

    }

    private final Connection connection;

    private final CursorReadRequest request;

    private final DbSwitchOptions options;

    private final int pageSize;

    private final ArrayDeque<Page> buffer = new ArrayDeque<>();

    private TdengineCursorMetadata.Shape shape;

    private List<TdengineCursorMetadata.Column> fields, order;

    private int[] orderIndexes;

    private CursorKeyContract keys;

    private String fingerprint, projection, ordered;

    private TypedCursor upper, lower, position, pageAfter;

    private Object[] row;

    private boolean exhausted, finished, closed;

    /**
     * 打开游标读取会话并准备当前读取范围。
     */
    static CursorReadSession open(DataSource source, CursorReadRequest request, DbSwitchOptions options,
            int fetchSize) {
        TdengineCursorReader reader = null;
        try {
            reader = new TdengineCursorReader(source.getConnection(), request, options, fetchSize);
            reader.prepare();
            return reader;
        } catch (Exception failure) {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                }
            }
            throw new IllegalArgumentException("TDengine cursor generation, shape, bounds or ordering is not verified");
        }
    }

    /**
     * 初始化 {@code TdengineCursorReader}。
     */
    private TdengineCursorReader(Connection connection, CursorReadRequest request, DbSwitchOptions options,
            int fetchSize) {
        this.connection = connection;
        this.request = request;
        this.options = options;
        pageSize = Math.max(1, Math.min(fetchSize, request.limits().maxRows()));
    }

    /**
     * 核对源代次、目标关系和键列事实，固定读取上界并准备当前游标分页状态。
     */
    private void prepare() throws SQLException {
        var pin = request.sourceGenerationPin();
        if (pin == null) {
            throw new SQLException("TDengine cursor requires an externally retained source generation pin");
        }
        TdengineSourceGeneration.verify(connection, pin, request.schema(), timeout());
        shape = TdengineCursorMetadata.read(connection, request.schema(), request.table(), timeout());
        if (!request.watermark().equals(shape.timestamp().name()) || !request.primaryKeys().equals(shape.keys())
                || shape.compositeKey().filter(key -> !TdengineCursorMetadata.supportedKeyType(key.type()))
                .isPresent()) {
            throw new SQLException("TDengine cursor requires the primary timestamp watermark and the exact native key");
        }
        fields = new ArrayList<>();
        for (String name : request.fields()) {
            if (TdengineCursorMetadata.TBNAME.equals(name)) {
                if (!shape.superTable()) {
                    throw new SQLException("Only a supertable cursor projects its child name");
                }
                fields.add(null);
                continue;
            }
            var column = shape.column(name)
                    .orElseThrow(() -> new SQLException("TDengine cursor projection is incomplete"));
            if (column.tag() && !shape.superTable() || !TdengineCursorMetadata.supportedValueType(column.type())) {
                throw new SQLException("TDengine cursor projection requires a verified data or supertable TAG codec");
            }
            fields.add(column);
        }
        var names = request.orderFields();
        order = new ArrayList<>();
        orderIndexes = new int[names.size()];
        for (int i = 0; i < names.size(); i++) {
            orderIndexes[i] = request.fields().indexOf(names.get(i));
            order.add(fields.get(orderIndexes[i]));
        }
        request.limits().requireStatementParameters(2 * order.size() + 1);
        projection = String.join(",", request.fields().stream().map(name -> {
            var column = TdengineCursorMetadata.TBNAME.equals(name) ? null : shape.column(name).orElseThrow();
            return column == null ? TdengineCursorMetadata.TBNAME : column.timestamp() ? "CAST(" + quote(
                    name) + " AS BIGINT)" : quote(name);
        }).toList());
        ordered = String.join(",",
                names.stream().map(name -> TdengineCursorMetadata.TBNAME.equals(name) ? name : quote(name)).toList());
        keys = shape.sourceContract();
        fingerprint = DigestUtil.sha256Hex("TDENGINE_CURSOR_V1\0" + shape.evidence() + '\0' + pin.fingerprint() + '\0'
                + String.join("\0", request.fields()) + '\0' + String.join("\0", names));
        if (request.expectedSchemaFingerprint() != null && !request.expectedSchemaFingerprint().equals(fingerprint)) {
            throw new SQLException("TDengine cursor source generation, relation or shape changed");
        }
        validate(request.lower());
        validate(request.fixedUpper());
        lower = request.lower();
        // 新运行不得默默接受最大值已退到已应用水位之后的数据源。
        if (lower != null) {
            try (var probe = connection.createStatement()) {
                probe.setQueryTimeout(timeout());
                try (var rows = probe.executeQuery(
                        "SELECT CAST(" + quote(shape.timestamp().name()) + " AS BIGINT) FROM " + reference()
                                + " WHERE " + quote(shape.timestamp().name()) + ">=" + component(lower,
                                0) + " LIMIT 1")) {
                    if (!rows.next()) {
                        throw new SQLException(
                                "TDengine source watermark moved backwards; explicit reconciliation is required");
                    }
                }
            }
        }
        upper = request.fixedUpper();
        if (upper == null && !request.upperBoundFrozen()) {
            try (var probe = connection.createStatement()) {
                probe.setQueryTimeout(timeout());
                String keyProjection = String.join(",",
                        names.stream().map(name -> TdengineCursorMetadata.TBNAME.equals(name) ? name
                                : shape.timestamp().name().equals(name) ? "CAST(" + quote(name) + " AS BIGINT)" : quote(
                                name)).toList());
                String descending = String.join(",", names.stream()
                        .map(name -> (TdengineCursorMetadata.TBNAME.equals(name) ? name : quote(name)) + " DESC")
                        .toList());
                try (var rows = probe.executeQuery(
                        "SELECT " + keyProjection + " FROM " + reference() + " ORDER BY " + descending + " LIMIT 1")) {
                    if (rows.next()) {
                        var values = new Object[order.size()];
                        for (int i = 0; i < values.length; i++) {
                            values[i] = value(rows, i + 1, order.get(i));
                        }
                        upper = cursor(values);
                    }
                }
            }
        }
        if (upper == null) {
            exhausted = true;
        }
    }

    /**
     * 推进游标并返回下一条数据或后续读取状态。
     */
    @Override
    public boolean next() throws SQLException {
        if (closed || Thread.currentThread().isInterrupted()) {
            throw new SQLException("TDengine cursor interrupted or closed");
        }
        if (buffer.isEmpty() && !exhausted) {
            fetch();
        }
        var next = buffer.poll();
        if (next == null) {
            finish();
            row = null;
            position = null;
            return false;
        }
        row = BatchValueConverter.convert(List.<Object[]>of(next.values()), next.values().length, request.limits())
                .getFirst();
        position = next.position();
        return true;
    }

    /**
     * 重放最后已应用的时间戳组：相同时间戳行可能在先前上界确定后才可见。
     */
    private void fetch() throws SQLException {
        verifyCurrent();
        var parameters = new ArrayList<String>();
        String timestamp = quote(shape.timestamp().name());
        var sql = new StringBuilder("SELECT ").append(projection).append(" FROM ").append(reference()).append(" WHERE ")
                .append(timestamp).append("<=").append(component(upper, 0)).append(" AND ")
                .append(bound(upper, true, parameters));
        // 重放最后已应用的时间戳组：相同时间戳行可能在先前上界确定后才可见。
        if (lower != null) {
            sql.append(" AND ").append(timestamp).append(">=").append(component(lower, 0));
        }
        if (pageAfter != null) {
            sql.append(" AND ").append(timestamp).append(">=").append(component(pageAfter, 0))
                    .append(" AND ").append(bound(pageAfter, false, parameters));
        }
        sql.append(" ORDER BY ").append(ordered).append(" LIMIT ").append(pageSize);
        int read = 0;
        TypedCursor previous = pageAfter;
        // 子表名称使用参数绑定；叶表页仅含已验证数值字面值，无需参数协议。
        try (var statement = parameters.isEmpty() ? connection.createStatement() : connection.prepareStatement(
                sql.toString())) {
            statement.setQueryTimeout(timeout());
            if (statement instanceof java.sql.PreparedStatement prepared) {
                for (int i = 0; i < parameters.size(); i++) {
                    prepared.setString(i + 1, parameters.get(i));
                }
            }
            try (var rows = statement instanceof java.sql.PreparedStatement prepared ? prepared.executeQuery() : statement.executeQuery(
                    sql.toString())) {
                while (rows.next()) {
                    if (++read > pageSize) {
                        throw new SQLException("TDengine page exceeded its bound");
                    }
                    var values = new Object[fields.size()];
                    for (int i = 0; i < values.length; i++) {
                        values[i] = value(rows, i + 1, fields.get(i));
                    }
                    var keyValues = new Object[order.size()];
                    for (int i = 0; i < keyValues.length; i++) {
                        keyValues[i] = values[orderIndexes[i]];
                    }
                    var current = cursor(keyValues);
                    // 键集正确性依赖服务器顺序；必须证明，不能假定。
                    if (previous != null && compare(previous, current) >= 0 || compare(current, upper) > 0) {
                        throw new SQLException("TDengine page order or bound is not verified");
                    }
                    buffer.add(new Page(values, current));
                    previous = current;
                }
            }
        } catch (SQLException | RuntimeException failure) {
            buffer.clear();
            throw failure;
        }
        verifyCurrent();
        if (read < pageSize) {
            exhausted = true;
        }
        if (previous != null) {
            pageAfter = previous;
        }
    }

    /**
     * 完成当前操作并汇总最终状态。
     */
    private void finish() throws SQLException {
        if (finished) {
            return;
        }
        verifyCurrent();
        var current = TdengineCursorMetadata.read(connection, request.schema(), request.table(), timeout());
        if (!current.evidence().equals(shape.evidence())) {
            throw new SQLException("TDengine relation changed during the cursor read");
        }
        finished = true;
    }

    /**
     * 重新读取当前对象事实并校验其与预期一致。
     */
    private void verifyCurrent() throws SQLException {
        TdengineSourceGeneration.verify(connection, request.sourceGenerationPin(), request.schema(), timeout());
        String sql = shape.superTable()
                ? "SELECT CAST(uid AS VARCHAR(64)) FROM information_schema.ins_stables WHERE db_name=" + TdengineCursorMetadata.literal(
                request.schema())
                + " AND stable_name=" + TdengineCursorMetadata.literal(request.table()) + " LIMIT 2"
                : "SELECT CAST(uid AS VARCHAR(64)) FROM information_schema.ins_tables WHERE db_name=" + TdengineCursorMetadata.literal(
                request.schema())
                + " AND table_name=" + TdengineCursorMetadata.literal(request.table()) + " LIMIT 2";
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout());
            statement.setMaxRows(2);
            try (var rows = statement.executeQuery(sql)) {
                if (!rows.next() || !shape.uid().equals(rows.getString(1)) || rows.next()) {
                    throw new SQLException("TDengine physical relation changed during the cursor read");
                }
            }
        }
    }

    /**
     * 返回值。
     */
    private Object value(ResultSet rows, int index, TdengineCursorMetadata.Column column) throws SQLException {
        if (column != null) {
            return TdengineCursorMetadata.read(rows, index, column, shape.digits());
        }
        Object name = rows.getObject(index);
        if (!(name instanceof byte[] bytes)) {
            throw new SQLException("TDengine child name is not a native byte string");
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException invalid) {
            throw new SQLException("TDengine child name is not valid UTF-8");
        }
    }

    /**
     * 返回游标。
     */
    private TypedCursor cursor(Object[] values) {
        var result = new ArrayList<TypedCursorValue>();
        for (int i = 0; i < values.length; i++) {
            if (values[i] == null) {
                throw new IllegalStateException("TDengine cursor key is NULL");
            }
            result.add(TdengineCursorMetadata.cursorValue(order.get(i), values[i], shape.digits()));
        }
        return new TypedCursor(1, result);
    }

    /**
     * 检查游标元组分量数与键列一致，并校验各键值符合声明的类型和取值范围。
     */
    private void validate(TypedCursor value) {
        if (value == null) {
            return;
        }
        if (value.values().size() != order.size()) {
            throw new IllegalArgumentException("TDengine cursor tuple shape changed");
        }
        for (int i = 0; i < order.size(); i++) {
            TdengineCursorMetadata.cursorComponent(order.get(i), value.values().get(i), shape.digits());
        }
    }

    /** 已验证数值分量的原生字面值；子表名称始终使用绑定参数。 */
    private String component(TypedCursor tuple, int index) {
        Object value = TdengineCursorMetadata.cursorComponent(order.get(index), tuple.values().get(index),
                shape.digits());
        if (value instanceof String) {
            throw new IllegalStateException("TDengine child names are bound, never inlined");
        }
        return value.toString();
    }

    /** 按 ORDER BY 顺序生成字典序键集谓词：(k1 op v1) OR (k1 = v1 AND k2 op v2) ... */
    private String bound(TypedCursor tuple, boolean inclusiveUpper, List<String> parameters) {
        var alternatives = new ArrayList<String>();
        for (int i = 0; i < order.size(); i++) {
            var terms = new ArrayList<String>();
            for (int j = 0; j <= i; j++) {
                String operator = j < i ? "=" : inclusiveUpper ? (i == order.size() - 1 ? "<=" : "<") : ">";
                terms.add(term(j, operator, tuple, parameters));
            }
            alternatives.add("(" + String.join(" AND ", terms) + ")");
        }
        return "(" + String.join(" OR ", alternatives) + ")";
    }

    /**
     * 生成或解析当前表达式中的单个运算项。
     */
    private String term(int index, String operator, TypedCursor tuple, List<String> parameters) {
        var column = order.get(index);
        if (column == null) {
            parameters.add(
                    (String) TdengineCursorMetadata.cursorComponent(null, tuple.values().get(index), shape.digits()));
            return TdengineCursorMetadata.TBNAME + operator + "?";
        }
        return quote(column.name()) + operator + component(tuple, index);
    }

    /** 与 TDengine 一致的 Java 比较：有符号、无符号整数及子表名的无符号 UTF-8 字节。 */
    private int compare(TypedCursor left, TypedCursor right) {
        for (int i = 0; i < order.size(); i++) {
            Object a = TdengineCursorMetadata.cursorComponent(order.get(i), left.values().get(i), shape.digits());
            Object b = TdengineCursorMetadata.cursorComponent(order.get(i), right.values().get(i), shape.digits());
            int result = a instanceof String first ? Arrays.compareUnsigned(first.getBytes(StandardCharsets.UTF_8),
                    ((String) b).getBytes(StandardCharsets.UTF_8))
                    : a instanceof BigInteger first ? first.compareTo((BigInteger) b) : Long.compare(
                    ((Number) a).longValue(), ((Number) b).longValue());
            if (result != 0) {
                return result;
            }
        }
        return 0;
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
            throw new IllegalStateException("TDengine cursor has no current row");
        }
        return row.clone();
    }

    /**
     * 关闭游标读取状态并释放其持有的结果集及相关资源。
     */
    @Override
    public void close() throws SQLException {
        if (closed) {
            return;
        }
        closed = true;
        buffer.clear();
        // TDengine 请求自动提交且不持有快照：关闭本方拥有的连接即完成全部清理。
        try {
            connection.close();
        } catch (Exception failure) {
            throw new SQLException("TDengine cursor connection cleanup was not confirmed");
        }
    }

    /**
     * 生成当前操作引用的限定关系名。
     */
    private String reference() {
        return TdengineJdbcSupport.table(request.schema(), request.table());
    }

    /**
     * 返回超时设置。
     */
    private int timeout() {
        return options.queryTimeoutSeconds();
    }

    /**
     * 按TDengine方言转义并引用标识符。
     */
    private static String quote(String name) {
        return TdengineJdbcSupport.quote(name);
    }
}
