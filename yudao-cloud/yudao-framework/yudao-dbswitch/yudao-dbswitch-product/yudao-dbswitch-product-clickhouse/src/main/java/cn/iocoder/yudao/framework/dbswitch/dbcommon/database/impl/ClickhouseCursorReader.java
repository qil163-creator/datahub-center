package cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursor;
import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursorValue;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import cn.iocoder.yudao.framework.dbswitch.core.util.ClickhouseSourceGeneration;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadSession;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.clickhouse.ClickhouseDatabaseSyncImpl;
import cn.iocoder.yudao.framework.dbswitch.product.clickhouse.ClickhouseCursorMetadata;
import cn.iocoder.yudao.framework.dbswitch.product.clickhouse.ClickhouseSql;
import cn.iocoder.yudao.framework.dbswitch.product.clickhouse.ClickhouseReadValues;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 不持有读取快照的 ClickHouse MergeTree 游标。ClickHouse 没有事务或快照隔离，因此
 * 游标使用固定上界，按表自身 ORDER BY（排序键）顺序执行有界键集分页。
 * 游标 ORDER BY 必须与原生排序键完全一致：这既确定物理顺序，也结合
 * 相同列的真实数据唯一性检查，证明键集分页不会漏行或重复行。每页
 * 都会重新验证部署的数据库代次绑定、表 UUID、排序键，以及准备时观察到的最旧活动片段
 * 仍然存在；并发 TRUNCATE/重载或合并消耗此片段时中止运行，
 * 避免交付顺序损坏的数据。恢复运行会重读整个已应用水位组，使后来可见的相同水位行
 * 不会丢失。窗口内的原地 DML 无法隔离：至少一次交付
 * 仅适用于某次运行将上界固定在其水位之上前已经可见的行，且只有幂等
 * 覆盖目标才能保证重放安全。
 */
final class ClickhouseCursorReader implements CursorReadSession {

    private record Parameter(ClickhouseCursorMetadata.Column column, Object value) {

    }

    private final Connection connection;

    private final CursorReadRequest request;

    private final DbSwitchOptions options;

    private final int pageSize;

    private ClickhouseCursorMetadata.Shape shape;

    private List<String> fields, order;

    private List<ClickhouseCursorMetadata.Column> orderColumns, fieldColumns;

    private CursorKeyContract keys;

    private String fingerprint;

    private TypedCursor upper, lower, position, pageAfter;

    private PreparedStatement statement;

    private ResultSet result;

    private Object[] row;

    private int pageRows;

    private boolean closed;

    /**
     * 打开游标读取会话并准备当前读取范围。
     */
    static CursorReadSession open(DataSource source, CursorReadRequest request, DbSwitchOptions options,
            int fetchSize) {
        if (request == null || options == null) {
            throw new IllegalArgumentException("ClickHouse cursor request and options are required");
        }
        ClickhouseCursorReader reader = null;
        try {
            reader = new ClickhouseCursorReader(source.getConnection(), request, options, fetchSize);
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
                    "ClickHouse cursor generation, shape, bounds or ordering is not verified");
        }
    }

    /**
     * 初始化 {@code ClickhouseCursorReader}。
     */
    private ClickhouseCursorReader(Connection connection, CursorReadRequest request, DbSwitchOptions options,
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
            throw new SQLException("ClickHouse cursor requires an externally retained source generation pin");
        }
        ClickhouseSourceGeneration.verify(connection, pin, request.schema(), timeout());
        shape = ClickhouseCursorMetadata.read(connection, request.schema(), request.table(), timeout());
        order = new ArrayList<>(request.orderFields());
        orderColumns = new ArrayList<>();
        // 键集顺序必须与原生排序键完全一致：物理顺序加经过证明的唯一全序。
        if (shape.sortingKey().isEmpty() || !order.equals(shape.sortingKey())
                || !shape.sortingKey().getFirst().equals(request.watermark())
                || !shape.sortingKey().containsAll(request.primaryKeys())) {
            throw new SQLException(
                    "ClickHouse cursor requires the watermark-first native sorting key as its exact ORDER BY");
        }
        // ClickHouse 没有唯一约束：必须在实际数据上证明有序列的非空唯一性。
        try {
            ClickhouseDatabaseSyncImpl.requireUniqueKeys(connection,
                    ClickhouseSql.table(request.schema(), request.table()),
                    order, timeout());
        } catch (SQLException | IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new SQLException("ClickHouse cursor ordered columns are not proven unique", failure);
        }
        fields = new ArrayList<>();
        fieldColumns = new ArrayList<>();
        for (String name : request.fields()) {
            var column = shape.column(name);
            if (!ClickhouseCursorMetadata.supportedValueType(column)) {
                throw new SQLException("ClickHouse cursor projection requires a verified codec");
            }
            fields.add(name);
            fieldColumns.add(column);
        }
        for (String name : order) {
            orderColumns.add(shape.column(name));
        }
        request.limits().requireStatementParameters(order.size() * (order.size() + 1) + 1);
        keys = shape.sourceContract();
        fingerprint = DigestUtil.sha256Hex(
                "CLICKHOUSE_CURSOR_V1\0" + shape.relationEvidence() + '\0' + pin.fingerprint() + '\0'
                        + request.watermark() + '\0' + String.join("\0", order) + '\0' + String.join("\0", fields));
        if (request.expectedSchemaFingerprint() != null && !request.expectedSchemaFingerprint().equals(fingerprint)) {
            throw new SQLException("ClickHouse cursor source generation, relation or shape changed");
        }
        validate(request.lower());
        validate(request.fixedUpper());
        lower = request.lower();
        // 新运行不得默默接受最大值已退到已应用水位之后的数据源。
        if (lower != null) {
            try (var probe = connection.prepareStatement("SELECT 1 FROM " + reference()
                    + " WHERE " + quote(request.watermark()) + ">=" + ClickhouseCursorMetadata.placeholder(
                    orderColumns.getFirst())
                    + " LIMIT 1")) {
                probe.setQueryTimeout(timeout());
                ClickhouseCursorMetadata.bind(probe, 1, orderColumns.getFirst(),
                        component(orderColumns.getFirst(), lower.values().getFirst()));
                try (var rows = probe.executeQuery()) {
                    if (!rows.next()) {
                        throw new SQLException(
                                "ClickHouse source watermark moved backwards; explicit reconciliation is required");
                    }
                }
            }
        }
        upper = request.fixedUpper();
        if (upper == null && !request.upperBoundFrozen()) {
            try (var probe = connection.createStatement()) {
                probe.setQueryTimeout(timeout());
                try (var rows = probe.executeQuery(guardValues("SELECT " + quoted(order) + " FROM " + reference()
                        + " ORDER BY " + ordered("DESC") + " LIMIT 1", orderColumns))) {
                    ClickhouseReadValues.verifyCursor(rows.getMetaData(), orderColumns);
                    if (rows.next()) {
                        var values = new ArrayList<Object>();
                        for (int i = 0; i < order.size(); i++) {
                            values.add(ClickhouseCursorMetadata.readValue(rows, i + 1, orderColumns.get(i)));
                        }
                        upper = cursor(values);
                    }
                }
            }
        }
    }

    /**
     * 按当前游标边界打开下一页结果集。
     */
    private void openPage() throws SQLException {
        verifyCurrent();
        if (result != null) {
            result.close();
        }
        if (statement != null) {
            statement.close();
        }
        result = null;
        statement = null;
        var parameters = new ArrayList<Parameter>();
        var sql = new StringBuilder("SELECT ").append(quoted(fields)).append(" FROM ").append(reference())
                .append(" WHERE ")
                .append(bound(upper, true, parameters));
        if (lower != null) {
            sql.append(" AND ").append(quote(request.watermark())).append(">=")
                    .append(ClickhouseCursorMetadata.placeholder(orderColumns.getFirst()));
            parameters.add(new Parameter(orderColumns.getFirst(),
                    component(orderColumns.getFirst(), lower.values().getFirst())));
        }
        if (pageAfter != null) {
            sql.append(" AND ").append(bound(pageAfter, false, parameters));
        }
        sql.append(" ORDER BY ").append(ordered("ASC")).append(" LIMIT ").append(pageSize);
        statement = connection.prepareStatement(guardValues(sql.toString(), fieldColumns));
        statement.setQueryTimeout(timeout());
        int index = 1;
        for (var parameter : parameters) {
            ClickhouseCursorMetadata.bind(statement, index++, parameter.column(), parameter.value());
        }
        result = statement.executeQuery();
        ClickhouseReadValues.verifyCursor(result.getMetaData(), fieldColumns);
        pageRows = 0;
    }

    /** 部署的固定绑定、关系 UUID、排序键和最旧活动片段必须在每页保持有效。 */
    private void verifyCurrent() throws SQLException {
        ClickhouseSourceGeneration.verify(connection, request.sourceGenerationPin(), request.schema(), timeout());
        try (var statement = connection.prepareStatement(
                "SELECT engine, toString(uuid), sorting_key FROM system.tables WHERE database=? AND name=? AND is_temporary=0 LIMIT 2")) {
            statement.setQueryTimeout(timeout());
            statement.setMaxRows(2);
            statement.setString(1, request.schema());
            statement.setString(2, request.table());
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || !shape.engine().equals(rows.getString(1)) || !shape.tableUuid()
                        .equals(rows.getString(2))
                        || !shape.sortingKeySql().equals(rows.getString(3)) || rows.next()) {
                    throw new SQLException("ClickHouse relation changed during the cursor read");
                }
            }
        }
        try (var statement = connection.prepareStatement(
                "SELECT count() FROM system.parts WHERE database=? AND table=? AND name=? AND active")) {
            statement.setQueryTimeout(timeout());
            statement.setMaxRows(2);
            statement.setString(1, request.schema());
            statement.setString(2, request.table());
            statement.setString(3, shape.minPart());
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || rows.getLong(1) != 1) {
                    throw new SQLException("ClickHouse parts changed during the cursor read");
                }
            }
        }
        try (var statement = connection.prepareStatement(
                "SELECT count() FROM system.mutations WHERE database=? AND table=? AND is_done=0")) {
            statement.setQueryTimeout(timeout());
            statement.setMaxRows(2);
            statement.setString(1, request.schema());
            statement.setString(2, request.table());
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || rows.getLong(1) != 0) {
                    throw new SQLException("ClickHouse mutations are not visible during the cursor read");
                }
            }
        }
    }

    /**
     * 推进游标并返回下一条数据或后续读取状态。
     */
    @Override
    public boolean next() throws SQLException {
        if (closed || Thread.currentThread().isInterrupted()) {
            throw new SQLException("ClickHouse cursor interrupted or closed");
        }
        if (upper == null) {
            return false;
        }
        if (result == null) {
            openPage();
        }
        if (!result.next()) {
            if (pageRows < pageSize) {
                verifyCurrent();
                row = null;
                position = null;
                return false;
            }
            pageAfter = position;
            openPage();
            if (!result.next()) {
                verifyCurrent();
                row = null;
                position = null;
                return false;
            }
        }
        var values = new Object[fields.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = ClickhouseCursorMetadata.readValue(result, i + 1, fieldColumns.get(i));
        }
        var tuple = new ArrayList<TypedCursorValue>();
        for (String name : order) {
            tuple.add(ClickhouseCursorMetadata.cursorValue(shape.column(name),
                    values[fields.indexOf(name)]));
        }
        var current = new TypedCursor(1, tuple);
        // 键集正确性依赖服务器顺序；必须证明，不能假定。
        if ((pageRows > 0 ? compare(position, current) >= 0 : pageAfter != null && compare(pageAfter, current) >= 0)
                || compare(current, upper) > 0) {
            throw new SQLException("ClickHouse page order or bound is not verified");
        }
        position = current;
        pageRows++;
        row = BatchValueConverter.convert(List.<Object[]>of(values), values.length, request.limits()).getFirst();
        return true;
    }

    /** 与服务器 ORDER BY 一致的 Java 比较：精确数值、本地日期时间、文本 UTF-8 字节序。 */
    private int compare(TypedCursor left, TypedCursor right) {
        for (int i = 0; i < order.size(); i++) {
            Object a = component(orderColumns.get(i), left.values().get(i));
            Object b = component(orderColumns.get(i), right.values().get(i));
            int comparison = a instanceof BigDecimal first ? first.compareTo((BigDecimal) b)
                    : a instanceof LocalDate first ? first.compareTo((LocalDate) b)
                    : a instanceof LocalDateTime first ? first.compareTo((LocalDateTime) b)
                    : a instanceof String first ? Arrays.compareUnsigned(first.getBytes(StandardCharsets.UTF_8),
                    ((String) b).getBytes(StandardCharsets.UTF_8))
                    : new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString()));
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    /**
     * 检查游标元组分量数与键列一致，并校验各键值符合声明的类型和取值范围。
     */
    private void validate(TypedCursor value) throws SQLException {
        if (value == null) {
            return;
        }
        if (value.values().size() != order.size()) {
            throw new SQLException("ClickHouse cursor tuple shape changed");
        }
        for (int i = 0; i < order.size(); i++) {
            component(orderColumns.get(i), value.values().get(i));
        }
    }

    /**
     * 返回当前分量。
     */
    private Object component(ClickhouseCursorMetadata.Column column, TypedCursorValue value) {
        return ClickhouseCursorMetadata.cursorComponent(column, value);
    }

    /** 按 ORDER BY 顺序生成字典序键集谓词：(k1 op v1) OR (k1 = v1 AND k2 op v2) ... */
    private String bound(TypedCursor tuple, boolean inclusiveUpper, List<Parameter> parameters) {
        var alternatives = new ArrayList<String>();
        for (int i = 0; i < order.size(); i++) {
            var terms = new ArrayList<String>();
            for (int j = 0; j <= i; j++) {
                String operator = j < i ? "=" : inclusiveUpper ? (i == order.size() - 1 ? "<=" : "<") : ">";
                terms.add(quote(order.get(j)) + operator + ClickhouseCursorMetadata.placeholder(orderColumns.get(j)));
                parameters.add(
                        new Parameter(orderColumns.get(j), component(orderColumns.get(j), tuple.values().get(j))));
            }
            alternatives.add("(" + String.join(" AND ", terms) + ")");
        }
        return "(" + String.join(" OR ", alternatives) + ")";
    }

    /**
     * 返回游标。
     */
    private TypedCursor cursor(List<Object> values) {
        var tuple = new ArrayList<TypedCursorValue>();
        for (int i = 0; i < order.size(); i++) {
            tuple.add(ClickhouseCursorMetadata.cursorValue(orderColumns.get(i), values.get(i)));
        }
        return new TypedCursor(1, tuple);
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
            throw new IllegalStateException("ClickHouse cursor has no current row");
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
        // ClickHouse 请求自动提交且不持有快照：关闭本方拥有的连接即完成全部清理。
        try {
            connection.close();
        } catch (Exception failure) {
            failed = true;
        }
        if (failed) {
            throw new SQLException("ClickHouse cursor connection cleanup was not confirmed");
        }
    }

    /**
     * 返回超时设置。
     */
    private int timeout() {
        return options.queryTimeoutSeconds();
    }

    /**
     * 检查游标读取中的文本和浮点字段，并拒绝超出已验证值域的数据。
     */
    private String guardValues(String sql, List<ClickhouseCursorMetadata.Column> columns) {
        var text = columns.stream().filter(column -> ClickhouseReadValues.stringType(column.type()))
                .map(ClickhouseCursorMetadata.Column::name).toList();
        var floating = columns.stream().filter(column -> ClickhouseReadValues.floatingType(column.type()))
                .map(ClickhouseCursorMetadata.Column::name).toList();
        return ClickhouseReadValues.guardedSql(sql, text, floating);
    }

    /**
     * 生成当前操作引用的限定关系名。
     */
    private String reference() {
        return ClickhouseSql.table(request.schema(), request.table());
    }

    /**
     * 返回按当前方言加引号后的标识符。
     */
    private String quoted(List<String> names) {
        return ClickhouseSql.columns(names);
    }

    /**
     * 按既定比较规则排列当前键或字段集合。
     */
    private String ordered(String direction) {
        return String.join(",", order.stream().map(name -> quote(name) + " " + direction).toList());
    }

    /**
     * 按ClickHouse方言转义并引用标识符。
     */
    private static String quote(String name) {
        return ClickhouseSql.quote(name);
    }
}
