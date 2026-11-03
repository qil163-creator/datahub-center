package cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursor;
import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursorValue;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import cn.iocoder.yudao.framework.dbswitch.core.util.Gbase8aCursorMetadata;
import cn.iocoder.yudao.framework.dbswitch.core.util.Gbase8aSourceGeneration;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadSession;

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
 * 不持有读取快照的 GBase 8a 游标。GBase 8a 报告 REPEATABLE-READ，但在
 * 已打开事务内读取当前已提交数据（已在 8.6.2.43-R7-free 验证），因此游标使用固定上界及有界键集
 * 页，按水位优先的 ORDER BY 顺序读取，与 ClickHouse/OLAP 无快照读取器相同。GBase 8a 仅将
 * PRIMARY KEY 保留为元数据，绝不强制唯一性，因此必须实际验证有序
 * 列在数据层面的唯一性；EXPRESS 引擎拒绝行构造器，所以所有边界都按字典序 OR
 * 展开。每页重新校验部署代际固定信息及关系依据，并在交付任何行前比较服务器页
 * 顺序和 Java 键顺序。窗口内原地 DML 无法
 * 隔离：至少一次交付仅适用于运行将上界固定在行水位之上前已可见的行，
 * 且只有幂等覆盖目标才能保证重放安全。若 TRUNCATE/重载保留目录
 * 元数据不变，则无法观测：应停止并排空消费方，旋转外部代际。
 */
final class Gbase8aCursorReader implements CursorReadSession {

    private record Parameter(Gbase8aCursorMetadata.Column column, Object value) {

    }

    private final Connection connection;

    private final CursorReadRequest request;

    private final DbSwitchOptions options;

    private final int pageSize;

    private Gbase8aCursorMetadata.Shape shape;

    private List<String> fields, order;

    private List<Gbase8aCursorMetadata.Column> orderColumns, fieldColumns;

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
        Gbase8aCursorReader reader = null;
        try {
            reader = new Gbase8aCursorReader(source.getConnection(), request, options, fetchSize);
            reader.prepare();
            return reader;
        } catch (Exception failure) {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                }
            }
            throw new IllegalArgumentException("GBase 8a cursor generation, shape, bounds or ordering is not verified");
        }
    }

    /**
     * 初始化 {@code Gbase8aCursorReader}。
     */
    private Gbase8aCursorReader(Connection connection, CursorReadRequest request, DbSwitchOptions options,
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
            throw new SQLException("GBase 8a cursor requires an externally retained source generation pin");
        }
        Gbase8aSourceGeneration.verify(connection, pin, request.schema(), timeout());
        shape = Gbase8aCursorMetadata.read(connection, request.schema(), request.table(), timeout());
        order = new ArrayList<>(request.orderFields());
        orderColumns = new ArrayList<>();
        for (String name : order) {
            var column = shape.column(name);
            if (column == null) {
                throw new SQLException("GBase 8a cursor order column is missing");
            }
            if (column.nullable()) {
                throw new SQLException("GBase 8a cursor ordered columns must be non-null");
            }
            if (!Gbase8aCursorMetadata.supportedKeyType(column)) {
                throw new SQLException("GBase 8a cursor key type is unsupported");
            }
            orderColumns.add(column);
        }
        // GBase 8a 绝不强制 PRIMARY KEY 唯一性：必须根据实际数据证明有序列非空且唯一。
        Gbase8aCursorMetadata.requireUniqueKeys(connection,
                Gbase8aCursorMetadata.reference(request.schema(), request.table()),
                order, timeout());
        fields = new ArrayList<>();
        fieldColumns = new ArrayList<>();
        for (String name : request.fields()) {
            var column = shape.column(name);
            if (column == null) {
                throw new SQLException("GBase 8a cursor projection column is missing");
            }
            fields.add(name);
            fieldColumns.add(column);
        }
        request.limits().requireStatementParameters(order.size() * (order.size() + 1) + 1);
        keys = new CursorKeyContract(cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.GBASE8A,
                DigestUtil.sha256Hex(shape.keyEvidence(request.primaryKeys())),
                request.primaryKeys().stream().map(name -> Gbase8aCursorMetadata.domain(shape.column(name))).toList());
        fingerprint = DigestUtil.sha256Hex(
                "GBASE8A_CURSOR_V1\0" + shape.relationEvidence() + '\0' + shape.columnEvidence()
                        + '\0' + pin.fingerprint() + '\0' + request.watermark() + '\0' + String.join("\0", order)
                        + '\0' + String.join("\0", fields));
        if (request.expectedSchemaFingerprint() != null && !request.expectedSchemaFingerprint().equals(fingerprint)) {
            throw new SQLException("GBase 8a cursor source generation, relation or shape changed");
        }
        validate(request.lower());
        validate(request.fixedUpper());
        lower = request.lower();
        // 新运行不得静默接受最大值已回退到已应用水位之后的源。
        if (lower != null) {
            try (var probe = connection.prepareStatement("SELECT 1 FROM " + reference()
                    + " WHERE " + quote(request.watermark()) + ">=? LIMIT 1")) {
                probe.setQueryTimeout(timeout());
                Gbase8aCursorMetadata.bind(probe, 1, orderColumns.getFirst(),
                        component(orderColumns.getFirst(), lower.values().getFirst()));
                try (var rows = probe.executeQuery()) {
                    if (!rows.next()) {
                        throw new SQLException(
                                "GBase 8a source watermark moved backwards; explicit reconciliation is required");
                    }
                }
            }
        }
        upper = request.fixedUpper();
        if (upper == null && !request.upperBoundFrozen()) {
            try (var probe = connection.createStatement()) {
                probe.setQueryTimeout(timeout());
                try (var rows = probe.executeQuery("SELECT " + quoted(order) + " FROM " + reference()
                        + " ORDER BY " + ordered("DESC") + " LIMIT 1")) {
                    if (rows.next()) {
                        var values = new ArrayList<Object>();
                        for (int i = 0; i < order.size(); i++) {
                            values.add(Gbase8aCursorMetadata.readValue(rows, i + 1, orderColumns.get(i)));
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
            sql.append(" AND ").append(quote(request.watermark())).append(">=?");
            parameters.add(new Parameter(orderColumns.getFirst(),
                    component(orderColumns.getFirst(), lower.values().getFirst())));
        }
        if (pageAfter != null) {
            sql.append(" AND ").append(bound(pageAfter, false, parameters));
        }
        sql.append(" ORDER BY ").append(ordered("ASC")).append(" LIMIT ").append(pageSize);
        statement = connection.prepareStatement(sql.toString());
        statement.setQueryTimeout(timeout());
        int index = 1;
        for (var parameter : parameters) {
            Gbase8aCursorMetadata.bind(statement, index++, parameter.column(), parameter.value());
        }
        result = statement.executeQuery();
        pageRows = 0;
    }

    /** 部署固定信息和关系依据必须在每页持续有效。 */
    private void verifyCurrent() throws SQLException {
        Gbase8aSourceGeneration.verify(connection, request.sourceGenerationPin(), request.schema(), timeout());
        var current = Gbase8aCursorMetadata.read(connection, request.schema(), request.table(), timeout());
        if (!current.relationEvidence().equals(shape.relationEvidence())) {
            throw new SQLException("GBase 8a relation changed during the cursor read");
        }
    }

    /**
     * 推进游标并返回下一条数据或后续读取状态。
     */
    @Override
    public boolean next() throws SQLException {
        if (closed || Thread.currentThread().isInterrupted()) {
            throw new SQLException("GBase 8a cursor interrupted or closed");
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
            values[i] = Gbase8aCursorMetadata.readValue(result, i + 1, fieldColumns.get(i));
        }
        var tuple = new ArrayList<TypedCursorValue>();
        for (String name : order) {
            tuple.add(Gbase8aCursorMetadata.cursorValue(shape.column(name), values[fields.indexOf(name)]));
        }
        var current = new TypedCursor(1, tuple);
        // 键集正确性依赖服务器排序；应验证，而不是假设。
        if (pageRows > 0 ? compare(position, current) >= 0 : pageAfter != null && compare(pageAfter, current) >= 0
                || compare(current, upper) > 0) {
            throw new SQLException("GBase 8a page order or bound is not verified");
        }
        position = current;
        pageRows++;
        row = BatchValueConverter.convert(List.<Object[]>of(values), values.length, request.limits()).getFirst();
        return true;
    }

    /** 与服务器 ORDER BY 一致的 Java 比较：精确数值、民用时间，以及文本的 UTF-8 字节顺序。 */
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
            throw new SQLException("GBase 8a cursor tuple shape changed");
        }
        for (int i = 0; i < order.size(); i++) {
            component(orderColumns.get(i), value.values().get(i));
        }
    }

    /**
     * 返回当前分量。
     */
    private Object component(Gbase8aCursorMetadata.Column column, TypedCursorValue value) {
        return Gbase8aCursorMetadata.cursorComponent(column, value);
    }

    /** 按 ORDER BY 顺序展开的字典序键集谓词：(k1 op v1) OR (k1 = v1 AND k2 op v2) ... */
    private String bound(TypedCursor tuple, boolean inclusiveUpper, List<Parameter> parameters) {
        var alternatives = new ArrayList<String>();
        for (int i = 0; i < order.size(); i++) {
            var terms = new ArrayList<String>();
            for (int j = 0; j <= i; j++) {
                String operator = j < i ? "=" : inclusiveUpper ? (i == order.size() - 1 ? "<=" : "<") : ">";
                terms.add(quote(order.get(j)) + operator + "?");
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
            tuple.add(Gbase8aCursorMetadata.cursorValue(orderColumns.get(i), values.get(i)));
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
            throw new IllegalStateException("GBase 8a cursor has no current row");
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
        // GBase 8a 语句自动提交，不持有快照：关闭自有连接即完成全部清理。
        try {
            connection.close();
        } catch (Exception failure) {
            failed = true;
        }
        if (failed) {
            throw new SQLException("GBase 8a cursor connection cleanup was not confirmed");
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
        return Gbase8aCursorMetadata.reference(request.schema(), request.table());
    }

    /**
     * 返回按当前方言加引号后的标识符。
     */
    private String quoted(List<String> names) {
        return String.join(",", names.stream().map(Gbase8aCursorReader::quote).toList());
    }

    /**
     * 按既定比较规则排列当前键或字段集合。
     */
    private String ordered(String direction) {
        return String.join(",", order.stream().map(name -> quote(name) + " " + direction).toList());
    }

    /**
     * 按当前数据库方言转义并引用标识符。
     */
    private static String quote(String name) {
        return Gbase8aCursorMetadata.quote(name);
    }
}
