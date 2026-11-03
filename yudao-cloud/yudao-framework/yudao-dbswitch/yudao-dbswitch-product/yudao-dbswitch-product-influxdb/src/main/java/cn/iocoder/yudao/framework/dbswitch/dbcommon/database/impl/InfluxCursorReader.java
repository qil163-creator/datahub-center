package cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl;

import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursor;
import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursorValue;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import cn.iocoder.yudao.framework.dbswitch.core.util.InfluxSourceGeneration;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadSession;
import cn.iocoder.yudao.framework.dbswitch.product.influxdb3.InfluxCursorMetadata;
import cn.iocoder.yudao.framework.dbswitch.product.influxdb3.InfluxNativeCatalog;
import cn.iocoder.yudao.framework.dbswitch.product.influxdb3.InfluxNativeSession;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

/**
 * 不持有读取快照的 InfluxDB3 Core 类型化游标。固定上界为运行开始时可见的最大有序键；
 * 有界键集分页按服务器的 UTF-8 字节序遍历 (time, tag...)，每页都
 * 完整读取，检查严格递增，并在释放数据行前重新验证部署的数据库代次及精确
 * 测量结构。完成时重新验证整体结构。恢复运行会重读
 * 最后已应用的时间戳组，使后来到达的相同时间戳行（另一组标签）不会丢失。
 * 后来到达且时间戳更旧的行不在游标范围内：至少一次交付仅适用于某次运行
 * 将上界固定在其时间戳之上前已经可见的行，且只有 UPSERT 目标原生的 (tagset, time) 字段合并
 * 才能保证重放安全。NULL 标签值无法表示为类型化游标键；运行会在发送
 * 包含该行的批次前失败。请求模式是传输范围 "iox"；部署的
 * 代次绑定指向受控连接池指定的数据库。
 */
final class InfluxCursorReader implements CursorReadSession {

    private record Page(Object[] values, TypedCursor position) {

    }

    private final DataSource source;

    private final Connection connection;

    private final InfluxNativeSession session;

    private final CursorReadRequest request;

    private final int timeout;

    private final int pageSize;

    private final ArrayDeque<Page> buffer = new ArrayDeque<>();

    private InfluxCursorMetadata.Shape shape;

    private List<InfluxNativeCatalog.Column> fields, order;

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
        InfluxCursorReader reader = null;
        try {
            reader = new InfluxCursorReader(source, request, options, fetchSize);
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
                    "InfluxDB3 cursor generation, shape, bounds or ordering is not verified");
        }
    }

    /**
     * 初始化 {@code InfluxCursorReader}。
     */
    private InfluxCursorReader(DataSource source, CursorReadRequest request, DbSwitchOptions options,
            int fetchSize) throws SQLException {
        this.source = source;
        this.request = request;
        this.timeout = options.queryTimeoutSeconds();
        pageSize = Math.max(1, Math.min(fetchSize, request.limits().maxRows()));
        connection = source.getConnection();
        session = InfluxNativeSession.open(source, connection,
                Math.min(Math.max(options.maxCacheBytes(), request.limits().maxBatchBytes() * 4), 16L * 1024 * 1024),
                timeout);
    }

    /**
     * 核对源代次、目标关系和键列事实，固定读取上界并准备当前游标分页状态。
     */
    private void prepare() throws SQLException {
        InfluxNativeCatalog.schema(request.schema());
        var pin = request.sourceGenerationPin();
        if (pin == null) {
            throw new SQLException("InfluxDB3 cursor requires an externally retained source generation pin");
        }
        InfluxSourceGeneration.verify(source, connection, pin, session.database(), timeout);
        shape = InfluxCursorMetadata.read(session, session.database(), request.table());
        if (!InfluxCursorMetadata.TIME.equals(request.watermark()) || !request.primaryKeys().equals(shape.keys())) {
            throw new SQLException(
                    "InfluxDB3 cursor requires the nanosecond time watermark and the exact native tag key");
        }
        fields = new ArrayList<>();
        for (String name : request.fields()) {
            fields.add(shape.nativeTable().column(name));
        }
        var names = request.orderFields();
        order = new ArrayList<>();
        orderIndexes = new int[names.size()];
        for (int i = 0; i < names.size(); i++) {
            orderIndexes[i] = request.fields().indexOf(names.get(i));
            order.add(fields.get(orderIndexes[i]));
        }
        request.limits().requireStatementParameters(1);
        projection = String.join(",", request.fields().stream().map(name -> name.equals(InfluxCursorMetadata.TIME)
                ? "CAST(\"time\" AS BIGINT) AS \"time\"" : InfluxNativeSession.quote(name)).toList());
        ordered = String.join(",", names.stream().map(InfluxNativeSession::quote).toList());
        keys = shape.sourceContract();
        fingerprint = InfluxCursorMetadata.sha256Hex(
                "INFLUX_CURSOR_V1\0" + shape.evidence() + '\0' + pin.fingerprint() + '\0'
                        + String.join("\0", request.fields()) + '\0' + String.join("\0", names));
        if (request.expectedSchemaFingerprint() != null && !request.expectedSchemaFingerprint().equals(fingerprint)) {
            throw new SQLException("InfluxDB3 cursor source generation, relation or shape changed");
        }
        validate(request.lower());
        validate(request.fixedUpper());
        lower = request.lower();
        // 新运行不得默默接受最大值已退到已应用水位之后的数据源。
        if (lower != null) {
            var rows = session.query("SELECT CAST(\"time\" AS BIGINT) AS \"time\" FROM " + reference()
                    + " WHERE CAST(\"time\" AS BIGINT)>=" + component(lower, 0) + " LIMIT 1");
            if (!rows.isArray() || rows.isEmpty()) {
                throw new SQLException(
                        "InfluxDB3 source watermark moved backwards; explicit reconciliation is required");
            }
        }
        upper = request.fixedUpper();
        if (upper == null && !request.upperBoundFrozen()) {
            String keyProjection = String.join(",",
                    request.orderFields().stream().map(name -> name.equals(InfluxCursorMetadata.TIME)
                            ? "CAST(\"time\" AS BIGINT) AS \"time\"" : InfluxNativeSession.quote(name)).toList());
            String descending = String.join(",",
                    request.orderFields().stream().map(name -> InfluxNativeSession.quote(name) + " DESC").toList());
            var rows = session.query(
                    "SELECT " + keyProjection + " FROM " + reference() + " ORDER BY " + descending + " LIMIT 1");
            if (!rows.isArray() || rows.size() > 1) {
                throw new SQLException("InfluxDB3 upper bound evidence is ambiguous");
            }
            if (rows.size() == 1) {
                var entry = rows.get(0);
                var values = new Object[order.size()];
                for (int i = 0; i < values.length; i++) {
                    values[i] = InfluxCursorMetadata.value(order.get(i), entry.get(request.orderFields().get(i)));
                }
                upper = cursor(values);
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
            throw new SQLException("InfluxDB3 cursor interrupted or closed");
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
        var sql = new StringBuilder("SELECT ").append(projection).append(" FROM ").append(reference())
                .append(" WHERE CAST(\"time\" AS BIGINT)<=").append(component(upper, 0)).append(" AND ")
                .append(bound(upper, true));
        // 重放最后已应用的时间戳组：相同时间戳行可能在先前上界确定后才可见。
        if (lower != null) {
            sql.append(" AND CAST(\"time\" AS BIGINT)>=").append(component(lower, 0));
        }
        if (pageAfter != null) {
            sql.append(" AND CAST(\"time\" AS BIGINT)>=").append(component(pageAfter, 0)).append(" AND ")
                    .append(bound(pageAfter, false));
        }
        sql.append(" ORDER BY ").append(ordered).append(" LIMIT ").append(pageSize);
        var rows = session.query(sql.toString());
        if (!rows.isArray() || rows.size() > pageSize) {
            throw new SQLException("InfluxDB3 page exceeded its bound");
        }
        int read = 0;
        TypedCursor previous = pageAfter;
        // 拒绝一页时清空缓冲区：任何未经验证的行都不能进入目标批次。
        try {
            for (var entry : rows) {
                if (++read > pageSize || !entry.isObject()) {
                    throw new SQLException("InfluxDB3 page exceeded its bound or is not a row");
                }
                // Influx JSON 省略 SQL NULL 字段；因此缺失的键列是 NULL 键，并在下面拒绝。
                var values = new Object[fields.size()];
                for (int i = 0; i < values.length; i++) {
                    values[i] = InfluxCursorMetadata.value(fields.get(i), entry.get(request.fields().get(i)));
                }
                var keyValues = new Object[order.size()];
                for (int i = 0; i < keyValues.length; i++) {
                    keyValues[i] = values[orderIndexes[i]];
                }
                var current = cursor(keyValues);
                // 键集正确性依赖服务器顺序；必须证明，不能假定。
                if (previous != null && compare(previous, current) >= 0 || compare(current, upper) > 0) {
                    throw new SQLException("InfluxDB3 page order or bound is not verified");
                }
                buffer.add(new Page(values, current));
                previous = current;
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
        finished = true;
    }

    /**
     * 重新读取当前对象事实并校验其与预期一致。
     */
    private void verifyCurrent() throws SQLException {
        InfluxSourceGeneration.verify(source, connection, request.sourceGenerationPin(), session.database(), timeout);
        var current = InfluxCursorMetadata.read(session, session.database(), request.table());
        if (!current.evidence().equals(shape.evidence())) {
            throw new SQLException("InfluxDB3 relation changed during the cursor read");
        }
    }

    /**
     * 返回游标。
     */
    private TypedCursor cursor(Object[] values) {
        var result = new ArrayList<TypedCursorValue>();
        for (int i = 0; i < values.length; i++) {
            if (values[i] == null) {
                throw new IllegalStateException("InfluxDB3 cursor key is NULL");
            }
            result.add(InfluxCursorMetadata.cursorValue(order.get(i), values[i]));
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
            throw new IllegalArgumentException("InfluxDB3 cursor tuple shape changed");
        }
        for (int i = 0; i < order.size(); i++) {
            InfluxCursorMetadata.cursorComponent(order.get(i), value.values().get(i));
        }
    }

    /** 已验证分量的原生字面值；标签值始终作为 SQL 字符串字面值内联。 */
    private String component(TypedCursor tuple, int index) {
        Object value = InfluxCursorMetadata.cursorComponent(order.get(index), tuple.values().get(index));
        return order.get(index)
                .kind() == InfluxNativeCatalog.Kind.TIME ? value.toString() : InfluxNativeSession.literal(
                (String) value);
    }

    /** 按 ORDER BY 顺序生成字典序键集谓词：(k1 op v1) OR (k1 = v1 AND k2 op v2) ... */
    private String bound(TypedCursor tuple, boolean inclusiveUpper) {
        var alternatives = new ArrayList<String>();
        for (int i = 0; i < order.size(); i++) {
            var terms = new ArrayList<String>();
            for (int j = 0; j <= i; j++) {
                String operator = j < i ? "=" : inclusiveUpper ? (i == order.size() - 1 ? "<=" : "<") : ">";
                terms.add(term(j, operator, tuple));
            }
            alternatives.add("(" + String.join(" AND ", terms) + ")");
        }
        return "(" + String.join(" OR ", alternatives) + ")";
    }

    /**
     * 生成或解析当前表达式中的单个运算项。
     */
    private String term(int index, String operator, TypedCursor tuple) {
        var column = order.get(index);
        if (column.kind() == InfluxNativeCatalog.Kind.TIME) {
            return "CAST(\"time\" AS BIGINT)" + operator + component(tuple, index);
        }
        return InfluxNativeSession.quote(column.name()) + operator + component(tuple, index);
    }

    /** 与服务器一致的 Java 比较：精确的有符号纳秒纪元计数及无符号 UTF-8 字节标签顺序。 */
    private int compare(TypedCursor left, TypedCursor right) {
        for (int i = 0; i < order.size(); i++) {
            Object a = InfluxCursorMetadata.cursorComponent(order.get(i), left.values().get(i));
            Object b = InfluxCursorMetadata.cursorComponent(order.get(i), right.values().get(i));
            int result = order.get(i).kind() == InfluxNativeCatalog.Kind.TIME ? Long.compare((Long) a, (Long) b)
                    : Arrays.compareUnsigned(((String) a).getBytes(StandardCharsets.UTF_8),
                    ((String) b).getBytes(StandardCharsets.UTF_8));
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
            throw new IllegalStateException("InfluxDB3 cursor has no current row");
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
        // InfluxDB3 请求无状态且不持有快照：关闭本方拥有的连接即完成全部清理。
        try {
            connection.close();
        } catch (Exception failure) {
            throw new SQLException("InfluxDB3 cursor connection cleanup was not confirmed");
        }
    }

    /**
     * 生成当前操作引用的限定关系名。
     */
    private String reference() {
        return InfluxNativeSession.quote(request.table());
    }
}
