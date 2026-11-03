package cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursor;
import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursorValue;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import cn.iocoder.yudao.framework.dbswitch.core.util.DorisSourceGeneration;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadSession;
import cn.iocoder.yudao.framework.dbswitch.product.doris.DorisCursorMetadata;
import cn.iocoder.yudao.framework.dbswitch.product.doris.DorisJdbcSupport;
import cn.iocoder.yudao.framework.dbswitch.product.doris.DorisNativeSchema;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 不持有读取快照的 Doris UNIQUE 键游标。Doris 没有行级事务或快照隔离，因此
 * 游标使用固定上界，按服务器自身 ORDER BY 执行有界键集分页，并在每页
 * 前后重新验证完整原生关系代次（集群、物理编号、分区代次和目录结构）；
 * 任何重建、TRUNCATE 或分区变化均拒绝计划。恢复运行会重读整个
 * 已应用水位组，使后来可见的相同水位行不会丢失。窗口内的原地 DML
 * 无法隔离：至少一次交付仅适用于某次运行将上界固定在其水位
 * 之上前已经可见的行，且只有幂等覆盖目标才能保证重放安全。
 */
final class DorisCursorReader implements CursorReadSession {

    private record Parameter(DorisNativeSchema.Column column, Object value) {

    }

    private final Connection connection;

    private final CursorReadRequest request;

    private final DbSwitchOptions options;

    private final int pageSize;

    private DorisCursorMetadata.Shape shape;

    private List<String> fields, order;

    private List<DorisNativeSchema.Column> orderColumns, fieldColumns;

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
        DorisCursorReader reader = null;
        try {
            reader = new DorisCursorReader(source.getConnection(), request, options, fetchSize);
            reader.prepare();
            return reader;
        } catch (Exception failure) {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                }
            }
            throw new IllegalArgumentException("Doris cursor generation, shape, bounds or ordering is not verified");
        }
    }

    /**
     * 初始化 {@code DorisCursorReader}。
     */
    private DorisCursorReader(Connection connection, CursorReadRequest request, DbSwitchOptions options,
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
            throw new SQLException("Doris cursor requires an externally retained source generation pin");
        }
        DorisSourceGeneration.verify(connection, pin, request.schema(), timeout());
        shape = DorisCursorMetadata.read(connection, request.schema(), request.table(), timeout());
        if (!shape.catalog().unique() || !request.primaryKeys().equals(shape.keys()) || !shape.keys()
                .contains(request.watermark())) {
            throw new SQLException("Doris cursor requires the exact native UNIQUE keys and a key watermark");
        }
        fields = new ArrayList<>();
        fieldColumns = new ArrayList<>();
        for (String name : request.fields()) {
            var column = shape.column(name);
            if (!DorisCursorMetadata.supportedValueType(column)) {
                throw new SQLException("Doris cursor projection requires a verified codec");
            }
            fields.add(name);
            fieldColumns.add(column);
        }
        order = new ArrayList<>(request.orderFields());
        orderColumns = new ArrayList<>();
        for (String name : order) {
            var column = shape.column(name);
            if (!DorisCursorMetadata.supportedKeyType(column) || column.nullable()) {
                throw new SQLException("Doris cursor keys require non-null verified monotonic types");
            }
            orderColumns.add(column);
        }
        request.limits().requireStatementParameters(order.size() * (order.size() + 1) + 1);
        keys = shape.sourceContract();
        fingerprint = DigestUtil.sha256Hex(
                "DORIS_CURSOR_V1\0" + shape.relationEvidence() + '\0' + pin.fingerprint() + '\0'
                        + request.watermark() + '\0' + String.join("\0", order) + '\0' + String.join("\0", fields));
        if (request.expectedSchemaFingerprint() != null && !request.expectedSchemaFingerprint().equals(fingerprint)) {
            throw new SQLException("Doris cursor source generation, relation or shape changed");
        }
        validate(request.lower());
        validate(request.fixedUpper());
        lower = request.lower();
        // 新运行不得默默接受最大值已退到已应用水位之后的数据源。
        if (lower != null) {
            try (var probe = connection.prepareStatement("SELECT 1 FROM " + reference()
                    + " WHERE " + quote(request.watermark()) + ">=? LIMIT 1")) {
                probe.setQueryTimeout(timeout());
                DorisCursorMetadata.bind(probe, 1, column(request.watermark()),
                        cursorComponent(request.watermark(), lower.values().getFirst()));
                try (var rows = probe.executeQuery()) {
                    if (!rows.next()) {
                        throw new SQLException(
                                "Doris source watermark moved backwards; explicit reconciliation is required");
                    }
                }
            }
        }
        upper = request.fixedUpper();
        if (upper == null && !request.upperBoundFrozen()) {
            try (var probe = connection.createStatement()) {
                probe.setQueryTimeout(timeout());
                try (var raw = probe.executeQuery(
                        "SELECT " + quoted(order) + " FROM " + reference() + " ORDER BY " + ordered(
                                "DESC") + " LIMIT 1")) {
                    try (var rows = DorisJdbcSupport.typedRows(raw, orderColumns)) {
                        if (rows.next()) {
                            upper = cursor(orderValues(rows));
                        }
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
            sql.append(" AND ").append(quote(request.watermark())).append(">=?");
            parameters.add(new Parameter(column(request.watermark()),
                    cursorComponent(request.watermark(), lower.values().getFirst())));
        }
        if (pageAfter != null) {
            sql.append(" AND ").append(bound(pageAfter, false, parameters));
        }
        sql.append(" ORDER BY ").append(ordered("ASC")).append(" LIMIT ").append(pageSize);
        statement = connection.prepareStatement(sql.toString());
        statement.setQueryTimeout(timeout());
        int index = 1;
        for (var parameter : parameters) {
            DorisCursorMetadata.bind(statement, index++, parameter.column(), parameter.value());
        }
        result = DorisJdbcSupport.typedRows(statement.executeQuery(), fieldColumns);
        pageRows = 0;
    }

    /**
     * 重新读取当前对象事实并校验其与预期一致。
     */
    private void verifyCurrent() throws SQLException {
        var current = DorisCursorMetadata.read(connection, request.schema(), request.table(), timeout());
        if (!current.relationEvidence().equals(shape.relationEvidence())) {
            throw new SQLException("Doris relation changed during the cursor read");
        }
    }

    /**
     * 推进游标并返回下一条数据或后续读取状态。
     */
    @Override
    public boolean next() throws SQLException {
        if (closed || Thread.currentThread().isInterrupted()) {
            throw new SQLException("Doris cursor interrupted or closed");
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
            values[i] = DorisCursorMetadata.readValue(result, i + 1, fieldColumns.get(i));
        }
        var tuple = new ArrayList<TypedCursorValue>();
        for (int i = 0; i < order.size(); i++) {
            tuple.add(DorisCursorMetadata.cursorValue(orderColumns.get(i), values[fields.indexOf(order.get(i))]));
        }
        var current = new TypedCursor(1, tuple);
        // 键集正确性依赖服务器顺序；必须证明，不能假定。
        if (pageRows > 0 ? compare(position, current) >= 0 : pageAfter != null && compare(pageAfter, current) >= 0
                || compare(current, upper) > 0) {
            throw new SQLException("Doris page order or bound is not verified");
        }
        position = current;
        pageRows++;
        row = BatchValueConverter.convert(List.<Object[]>of(values), values.length, request.limits()).getFirst();
        return true;
    }

    /** 与服务器 ORDER BY 一致的 Java 比较：精确数值、本地日期时间、文本 UTF-8 字节序。 */
    private int compare(TypedCursor left, TypedCursor right) {
        for (int i = 0; i < order.size(); i++) {
            Object a = cursorComponent(order.get(i), left.values().get(i));
            Object b = cursorComponent(order.get(i), right.values().get(i));
            int comparison = a instanceof BigDecimal first ? first.compareTo((BigDecimal) b)
                    : a instanceof java.time.LocalDate first ? first.compareTo((java.time.LocalDate) b)
                    : a instanceof java.time.LocalDateTime first ? first.compareTo((java.time.LocalDateTime) b)
                    : a instanceof String first ? Arrays.compareUnsigned(first.getBytes(StandardCharsets.UTF_8),
                    ((String) b).getBytes(StandardCharsets.UTF_8))
                    : Long.compare(((Number) a).longValue(), ((Number) b).longValue());
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
            throw new SQLException("Doris cursor tuple shape changed");
        }
        for (int i = 0; i < order.size(); i++) {
            cursorComponent(orderColumns.get(i), value.values().get(i));
        }
    }

    /**
     * 根据列证据创建当前游标键分量的投影与比较定义。
     */
    private Object cursorComponent(DorisNativeSchema.Column column, TypedCursorValue value) {
        return DorisCursorMetadata.cursorComponent(column, value);
    }

    /**
     * 根据列证据创建当前游标键分量的投影与比较定义。
     */
    private Object cursorComponent(String name, TypedCursorValue value) {
        return DorisCursorMetadata.cursorComponent(column(name), value);
    }

    /** 按 ORDER BY 顺序生成字典序键集谓词：(k1 op v1) OR (k1 = v1 AND k2 op v2) ... */
    private String bound(TypedCursor tuple, boolean inclusiveUpper, List<Parameter> parameters) {
        var alternatives = new ArrayList<String>();
        for (int i = 0; i < order.size(); i++) {
            var terms = new ArrayList<String>();
            for (int j = 0; j <= i; j++) {
                String operator = j < i ? "=" : inclusiveUpper ? (i == order.size() - 1 ? "<=" : "<") : ">";
                terms.add(quote(order.get(j)) + operator + "?");
                parameters.add(new Parameter(orderColumns.get(j),
                        cursorComponent(orderColumns.get(j), tuple.values().get(j))));
            }
            alternatives.add("(" + String.join(" AND ", terms) + ")");
        }
        return "(" + String.join(" OR ", alternatives) + ")";
    }

    /**
     * 排列值集合。
     */
    private List<Object> orderValues(ResultSet rows) throws SQLException {
        var values = new ArrayList<Object>();
        for (int i = 0; i < order.size(); i++) {
            values.add(DorisCursorMetadata.readValue(rows, i + 1, orderColumns.get(i)));
        }
        return values;
    }

    /**
     * 返回游标。
     */
    private TypedCursor cursor(List<Object> values) {
        var tuple = new ArrayList<TypedCursorValue>();
        for (int i = 0; i < order.size(); i++) {
            tuple.add(DorisCursorMetadata.cursorValue(orderColumns.get(i), values.get(i)));
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
            throw new IllegalStateException("Doris cursor has no current row");
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
        // Doris 请求自动提交且不持有快照：关闭本方拥有的连接即完成全部清理。
        try {
            connection.close();
        } catch (Exception failure) {
            failed = true;
        }
        if (failed) {
            throw new SQLException("Doris cursor connection cleanup was not confirmed");
        }
    }

    /**
     * 返回列。
     */
    private DorisNativeSchema.Column column(String name) {
        return shape.column(name);
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
        return DorisNativeSchema.relation(request.schema(), request.table());
    }

    /**
     * 返回按当前方言加引号后的标识符。
     */
    private String quoted(List<String> names) {
        return DorisNativeSchema.names(names);
    }

    /**
     * 按既定比较规则排列当前键或字段集合。
     */
    private String ordered(String direction) {
        return String.join(",", order.stream().map(name -> quote(name) + " " + direction).toList());
    }

    /**
     * 按Doris方言转义并引用标识符。
     */
    private static String quote(String name) {
        return DorisNativeSchema.quote(name);
    }
}
