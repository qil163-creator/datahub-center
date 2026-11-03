package cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursor;
import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursorValue;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.util.HiveSourceGeneration;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadSession;
import cn.iocoder.yudao.framework.dbswitch.product.hive.HiveCursorMetadata;
import cn.iocoder.yudao.framework.dbswitch.product.hive.HiveJdbcWriteSupport;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Hive 4.1 目录标识键集：不存在快照，所以每页都是对固定
 * 物理标识的一次服务器查询。第一页固定上界（打开时的最后元组）；恢复时回退已应用水位
 * 分组，再按 Hive 自身比较顺序，以严格大于的键集向前分页。
 * 按契约，仅在其页面读取后才变得可见的行，位于游标窗口之外。
 */
final class HiveCursorReader implements CursorReadSession {

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

    private int pageRows;

    private boolean closed;

    /**
     * 打开游标读取会话并准备当前读取范围。
     */
    static CursorReadSession open(DataSource source, CursorReadRequest request, DbSwitchOptions options,
            int fetchSize) {
        HiveCursorReader reader = null;
        try {
            reader = new HiveCursorReader(source.getConnection(), request, options, fetchSize);
            reader.prepare();
            return reader;
        } catch (Exception failure) {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                }
            }
            throw new IllegalArgumentException("Hive cursor generation, schema or ordering is not verified", failure);
        }
    }

    /**
     * 初始化 {@code HiveCursorReader}。
     */
    private HiveCursorReader(Connection connection, CursorReadRequest request, DbSwitchOptions options, int fetchSize) {
        this.connection = connection;
        this.request = request;
        this.options = options;
        order = request.orderFields();
        pageSize = Math.max(1, Math.min(fetchSize, request.limits().maxRows()));
    }

    /**
     * 核对源代次、目标关系和键列事实，固定读取上界并准备当前游标分页状态。
     */
    private void prepare() throws Exception {
        HiveCursorMetadata.product(connection);
        if (!connection.getAutoCommit() || request.sourceGenerationPin() == null) {
            throw new SQLException("Hive cursor requires an idle owned connection and a retained deployment pin");
        }
        HiveJdbcWriteSupport.session(connection, timeout());
        HiveSourceGeneration.verify(connection, request.sourceGenerationPin(), request.schema(), timeout());
        var shape = HiveCursorMetadata.read(connection, request.schema(), request.table(), timeout());
        columns = shape.columns();
        keys = HiveCursorMetadata.keys(shape, request.primaryKeys());
        for (String name : request.fields()) {
            if (!columns.containsKey(name)) {
                throw new SQLException("Hive cursor projection is incomplete");
            }
        }
        var watermark = columns.get(request.watermark());
        if (watermark == null) {
            throw new SQLException("Hive cursor watermark is absent");
        }
        HiveCursorMetadata.requireWatermark(watermark);
        for (String name : order) {
            var column = columns.get(name);
            if (column == null) {
                throw new SQLException("Hive cursor order is incomplete");
            }
            HiveCursorMetadata.requireOrdered(column);
        }
        request.limits().requireStatementParameters(order.size() * (order.size() + 1) + 1);
        fingerprint = DigestUtil.sha256Hex(
                "HIVE_CURSOR_V1\0" + shape.fingerprint() + '\0' + String.join("\0", request.fields())
                        + '\0' + String.join("\0", order) + '\0' + request.sourceGenerationPin().fingerprint());
        if (request.expectedSchemaFingerprint() != null && !request.expectedSchemaFingerprint().equals(fingerprint)) {
            throw new SQLException("Hive cursor source identity, generation or schema changed");
        }
        validate(request.lower());
        validate(request.fixedUpper());
        String nulls = order.stream().map(name -> HiveJdbcWriteSupport.quote(name) + " IS NULL")
                .collect(Collectors.joining(" OR "));
        try (var s = connection.createStatement()) {
            s.setQueryTimeout(timeout());
            s.setMaxRows(1);
            try (var r = s.executeQuery("SELECT 1 FROM " + reference() + " WHERE " + nulls + " LIMIT 1")) {
                if (r.next()) {
                    throw new SQLException("Hive cursor watermark or keys contain NULL");
                }
            }
        }
        if (request.lower() != null) {
            try (var p = connection.prepareStatement("SELECT 1 FROM " + reference() + " WHERE "
                    + HiveCursorMetadata.comparison(columns.get(request.watermark()), ">=") + " LIMIT 1")) {
                p.setQueryTimeout(timeout());
                HiveCursorMetadata.bind(p, 1, request.lower().values().getFirst().jdbcValue(),
                        columns.get(request.watermark()));
                try (var r = p.executeQuery()) {
                    if (!r.next()) {
                        throw new SQLException("Hive source watermark moved backwards");
                    }
                }
            }
        }
        upper = request.fixedUpper();
        if (upper == null && !request.upperBoundFrozen()) {
            try (var s = connection.createStatement()) {
                s.setQueryTimeout(timeout());
                s.setMaxRows(1);
                try (var r = s.executeQuery("SELECT " + fields(order) + " FROM " + reference()
                        + " ORDER BY " + ordered(order, "DESC") + " LIMIT 1")) {
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
                HiveCursorMetadata.bind(p, index++, bound.values().get(j).jdbcValue(), columns.get(order.get(j)));
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
        String sql = "SELECT " + fields(request.fields()) + " FROM " + reference() + " WHERE " + bounds(true);
        if (request.lower() != null) {
            sql += " AND " + HiveCursorMetadata.comparison(columns.get(request.watermark()), ">=");
        }
        if (pageAfter != null) {
            sql += " AND " + bounds(false);
        }
        sql += " ORDER BY " + ordered(order, "ASC") + " LIMIT " + pageSize;
        statement = connection.prepareStatement(sql);
        statement.setQueryTimeout(timeout());
        statement.setFetchSize(pageSize);
        int index = bindBound(statement, 1, upper);
        if (request.lower() != null) {
            HiveCursorMetadata.bind(statement, index++, request.lower().values().getFirst().jdbcValue(),
                    columns.get(request.watermark()));
        }
        if (pageAfter != null) {
            bindBound(statement, index, pageAfter);
        }
        result = statement.executeQuery();
        pageRows = 0;
    }

    /** 字典序边界：冻结上界元组的最后元素包含等号，页后元组使用严格比较。 */
    private String bounds(boolean upperBound) {
        var alternatives = new ArrayList<String>();
        for (int i = 0; i < order.size(); i++) {
            var terms = new ArrayList<String>();
            for (int j = 0; j < i; j++) {
                terms.add(HiveCursorMetadata.equality(columns.get(order.get(j))));
            }
            String operator = upperBound ? (i == order.size() - 1 ? "<=" : "<") : ">";
            terms.add(HiveCursorMetadata.comparison(columns.get(order.get(i)), operator));
            alternatives.add("(" + String.join(" AND ", terms) + ")");
        }
        return "(" + String.join(" OR ", alternatives) + ")";
    }

    /**
     * 检查游标元组分量数与键列一致，并校验各键值符合声明的类型和取值范围。
     */
    private void validate(TypedCursor value) throws SQLException {
        if (value == null) {
            return;
        }
        if (value.values().size() != order.size()) {
            throw new SQLException("Hive cursor tuple changed");
        }
        for (int i = 0; i < order.size(); i++) {
            var column = columns.get(order.get(i));
            var v = value.values().get(i);
            if (v.jdbcType() != HiveCursorMetadata.persistedType(column) || !v.typeName()
                    .equals(column.getFieldTypeName())) {
                throw new SQLException("Hive cursor key type changed");
            }
            v.jdbcValue(); // 精确解码持久化标量；无效值域在此处失败，早于读取任何页面。
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
            values.add(TypedCursorValue.of(HiveCursorMetadata.persistedType(c), c.getFieldTypeName(),
                    HiveCursorMetadata.read(r, index, c, request.limits().maxCellBytes())));
        }
        return new TypedCursor(1, values);
    }

    /**
     * 推进游标并返回下一条数据或后续读取状态。
     */
    @Override
    public boolean next() throws SQLException {
        if (closed || Thread.currentThread().isInterrupted()) {
            throw new SQLException("Hive cursor interrupted or closed");
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
            values[i] = HiveCursorMetadata.read(result, i + 1, columns.get(request.fields().get(i)),
                    request.limits().maxCellBytes());
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
        return HiveCursorMetadata.relation(request.schema(), request.table());
    }

    /**
     * 返回字段集合。
     */
    private static String fields(List<String> names) {
        return HiveJdbcWriteSupport.columns(names);
    }

    /**
     * 按既定比较规则排列当前键或字段集合。
     */
    private static String ordered(List<String> names, String direction) {
        return names.stream().map(name -> HiveJdbcWriteSupport.quote(name) + " " + direction)
                .collect(Collectors.joining(","));
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
            throw new IllegalStateException("Hive cursor has no row");
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
            connection.close();
        } catch (Exception e) {
            failed = true;
        }
        if (failed) {
            throw new SQLException("Hive cursor physical cleanup was not confirmed");
        }
    }
}
