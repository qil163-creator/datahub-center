package cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursor;
import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursorValue;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import cn.iocoder.yudao.framework.dbswitch.core.util.MysqlCursorMetadata;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadSession;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;
import cn.iocoder.yudao.framework.dbswitch.product.mysql.MysqlCompatibleProfile;

import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;

/** 使用一个 InnoDB 可重复读快照，并以有数量上限的键集分页替代驱动专属流式标志。 */
final class MysqlCursorReader implements CursorReadSession {

    private final Connection connection;

    private final MysqlCompatibleProfile profile;

    private final MysqlCompatibleProfile.CursorReadHooks hooks;

    private final CursorReadRequest request;

    private final DbSwitchOptions options;

    private final String reference;

    private final List<String> order;

    private final Map<String, Integer> types = new HashMap<>();

    private final Map<String, String> typeNames = new HashMap<>();

    private final int pageSize;

    private String previousTimeZone;

    private PreparedStatement statement;

    private ResultSet result;

    private CursorKeyContract keys;

    private String schemaFingerprint;

    private TypedCursor upper, position, pageAfter;

    private Object[] row;

    private int pageRows;

    private boolean closed;

    private boolean ownsTransaction;

    private int previousIsolation;

    private boolean previousReadOnly;

    /**
     * 打开游标读取会话并准备当前读取范围。
     */
    static CursorReadSession open(DataSource source, MysqlCompatibleProfile profile, CursorReadRequest request,
            DbSwitchOptions options, int fetchSize) {
        MysqlCursorReader reader = null;
        try {
            var hooks = profile.cursorReadHooks(source, request, options.queryTimeoutSeconds());
            reader = new MysqlCursorReader(source.getConnection(), profile, hooks, request, options, fetchSize);
            reader.prepare();
            return reader;
        } catch (Exception failure) {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                }
            }
            throw new IllegalArgumentException("Typed cursor source, bounds or schema are unsupported");
        }
    }

    /**
     * 初始化 {@code MysqlCursorReader}。
     */
    private MysqlCursorReader(Connection connection, MysqlCompatibleProfile profile,
            MysqlCompatibleProfile.CursorReadHooks hooks,
            CursorReadRequest request, DbSwitchOptions options, int fetchSize) {
        this.connection = connection;
        this.profile = profile;
        this.hooks = hooks;
        this.request = request;
        this.options = options;
        reference = MysqlCursorMetadata.reference(request.schema(), request.table());
        order = request.orderFields();
        pageSize = Math.max(1, Math.min(fetchSize, request.limits().maxRows()));
    }

    /**
     * 核对源代次、目标关系和键列事实，固定读取上界并准备当前游标分页状态。
     */
    private void prepare() throws SQLException {
        if (!connection.getAutoCommit()) {
            throw new SQLException("Cursor requires an owned fresh connection");
        }
        previousIsolation = connection.getTransactionIsolation();
        previousReadOnly = connection.isReadOnly();
        ownsTransaction = true;
        MysqlCursorMetadata.verifyProduct(connection, profile, options.queryTimeoutSeconds());
        hooks.afterProductVerified(connection);
        try (var probe = connection.createStatement()) {
            probe.setQueryTimeout(options.queryTimeoutSeconds());
            try (var rows = probe.executeQuery("SELECT @@session.time_zone")) {
                if (!rows.next()) {
                    throw new SQLException("Cursor time zone is unavailable");
                }
                previousTimeZone = rows.getString(1);
            }
            probe.execute("SET SESSION time_zone='+00:00'");
        }
        if (hooks.readOnlyTransaction()) {
            connection.setReadOnly(true);
        }
        connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        connection.setAutoCommit(false);
        hooks.afterTransactionStarted(connection);
        // 打开关系时，先为当前事务获取元数据锁，再观测物理标识。
        try (var probe = connection.createStatement()) {
            probe.setQueryTimeout(options.queryTimeoutSeconds());
            try (var rows = probe.executeQuery(
                    "SELECT " + quoted(request.fields()) + " FROM " + reference + " WHERE 1=0")) {
                var metadata = rows.getMetaData();
                for (int i = 0; i < request.fields().size(); i++) {
                    String name = request.fields().get(i), nativeName = metadata.getColumnTypeName(i + 1);
                    int type = metadata.getColumnType(i + 1);
                    if (order.contains(name) && (type == Types.BIT || type == Types.BOOLEAN
                            || "java.lang.Boolean".equals(metadata.getColumnClassName(i + 1)))) {
                        throw new SQLException("Boolean-converted numeric cursor columns are unsupported");
                    }
                    if (!metadata.isSigned(i + 1)) {
                        type = switch (type) {
                            case Types.BIGINT -> Types.NUMERIC;
                            case Types.INTEGER -> Types.BIGINT;
                            case Types.SMALLINT -> Types.INTEGER;
                            default -> type;
                        };
                    }
                    if (nativeName.equalsIgnoreCase("TIMESTAMP")) {
                        type = Types.TIMESTAMP_WITH_TIMEZONE;
                    }
                    types.put(name, type);
                    typeNames.put(name, nativeName);
                }
            }
        }
        String identity = MysqlCursorMetadata.physicalIdentity(connection, profile, request.schema(), request.table(),
                options.queryTimeoutSeconds());
        var columns = MysqlCursorMetadata.columns(connection, request.schema(), request.table(),
                options.queryTimeoutSeconds());
        verifyPrimaryKey();
        keys = MysqlCursorMetadata.keyContract(connection, profile, request.schema(), request.table(),
                request.primaryKeys(), options.queryTimeoutSeconds());
        StringBuilder material = new StringBuilder("MYSQL_CURSOR_SCHEMA_V1").append('\0').append(identity).append('\0')
                .append(reference)
                .append('\0').append(keys.nativeFingerprint()).append('\0').append(request.watermark());
        String productMaterial = hooks.schemaMaterial();
        if (productMaterial != null) {
            material.append('\0').append(productMaterial);
        }
        for (String field : request.fields()) {
            var column = columns.get(field);
            if (column == null || order.contains(field) && column.nullable()) {
                throw new SQLException("Cursor column metadata is incomplete");
            }
            if (order.contains(field)) {
                int type = types.get(field);
                if (!List.of(Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.NUMERIC, Types.DECIMAL,
                        Types.DATE, Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE, Types.VARCHAR).contains(type)) {
                    throw new SQLException("Cursor column type is unsupported");
                }
                if (column.domain().numeric() != null && column.unsigned() != typeNames.get(field)
                        .toUpperCase(Locale.ROOT).contains("UNSIGNED")) {
                    throw new SQLException("Cursor signedness metadata disagrees");
                }
            }
            material.append('\0').append(column).append('\0').append(types.get(field)).append('\0')
                    .append(typeNames.get(field));
        }
        if (types.get(request.watermark()) == Types.VARCHAR) {
            throw new SQLException("Watermark must be numeric or temporal");
        }
        schemaFingerprint = DigestUtil.sha256Hex(material.toString());
        if (request.expectedSchemaFingerprint() != null && !request.expectedSchemaFingerprint()
                .equals(schemaFingerprint)) {
            throw new SQLException("Cursor source schema or physical table changed");
        }
        validate(request.lower());
        validate(request.fixedUpper());
        if (request.lower() != null) {
            try (var probe = connection.prepareStatement(
                    "SELECT 1 FROM " + reference + " WHERE " + quote(request.watermark()) + ">=? LIMIT 1")) {
                probe.setQueryTimeout(options.queryTimeoutSeconds());
                bind(probe, 1, request.lower().values().getFirst());
                try (var rows = probe.executeQuery()) {
                    if (!rows.next()) {
                        throw new SQLException("Source watermark moved backwards");
                    }
                }
            }
        }
        upper = request.fixedUpper();
        if (upper == null && !request.upperBoundFrozen()) {
            try (var probe = connection.createStatement()) {
                probe.setQueryTimeout(options.queryTimeoutSeconds());
                try (var rows = probe.executeQuery(
                        "SELECT " + quoted(order) + " FROM " + reference + " ORDER BY " + ordered(
                                "DESC") + " LIMIT 1")) {
                    if (rows.next()) {
                        upper = cursor(rows, true);
                    }
                }
            }
        }
        if (upper != null) {
            openPage();
        }
    }

    /**
     * 校验主键。
     */
    private void verifyPrimaryKey() throws SQLException {
        List<String> actual = new ArrayList<>();
        try (var probe = connection.prepareStatement(
                "SELECT COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=? AND TABLE_NAME=? AND CONSTRAINT_NAME='PRIMARY' ORDER BY ORDINAL_POSITION")) {
            probe.setQueryTimeout(options.queryTimeoutSeconds());
            probe.setString(1, request.schema());
            probe.setString(2, request.table());
            try (var rows = probe.executeQuery()) {
                while (rows.next()) {
                    actual.add(rows.getString(1));
                }
            }
        }
        if (!actual.equals(request.primaryKeys())) {
            throw new SQLException("Cursor requires the exact ordered primary key");
        }
    }

    /**
     * 按当前游标边界打开下一页结果集。
     */
    private void openPage() throws SQLException {
        hooks.beforePage(connection);
        if (result != null) {
            result.close();
        }
        if (statement != null) {
            statement.close();
        }
        String tuple = "(" + quoted(order) + ")", parameters = "(" + String.join(",",
                Collections.nCopies(order.size(), "?")) + ")";
        String sql = "SELECT " + quoted(
                request.fields()) + " FROM " + reference + " WHERE " + tuple + " <= " + parameters;
        if (request.lower() != null) {
            sql += " AND " + quote(request.watermark()) + ">=?";
        }
        if (pageAfter != null) {
            sql += " AND " + tuple + ">" + parameters;
        }
        sql += " ORDER BY " + ordered("ASC") + " LIMIT " + pageSize;
        statement = connection.prepareStatement(sql);
        statement.setQueryTimeout(options.queryTimeoutSeconds());
        int index = 1;
        for (var value : upper.values()) {
            bind(statement, index++, value);
        }
        if (request.lower() != null) {
            bind(statement, index++, request.lower().values().getFirst());
        }
        if (pageAfter != null) {
            for (var value : pageAfter.values()) {
                bind(statement, index++, value);
            }
        }
        result = statement.executeQuery();
        pageRows = 0;
    }

    /**
     * 将当前值绑定到预编译语句参数。
     */
    private static void bind(PreparedStatement statement, int index, TypedCursorValue value) throws SQLException {
        if (value.jdbcType() == Types.TIMESTAMP_WITH_TIMEZONE) {
            statement.setObject(index,
                    ((OffsetDateTime) value.jdbcValue()).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime(),
                    Types.TIMESTAMP);
        } else {
            statement.setObject(index, value.jdbcValue(), value.jdbcType());
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
            throw new SQLException("Cursor tuple shape changed");
        }
        for (int i = 0; i < order.size(); i++) {
            if (cursor.values().get(i).jdbcType() != types.get(order.get(i))
                    || !cursor.values().get(i).typeName().equals(typeNames.get(order.get(i)))) {
                throw new SQLException("Cursor tuple types changed");
            }
        }
    }

    /**
     * 返回游标。
     */
    private TypedCursor cursor(ResultSet rows, boolean projection) throws SQLException {
        List<TypedCursorValue> values = new ArrayList<>();
        for (int i = 0; i < order.size(); i++) {
            String name = order.get(i);
            int index = projection ? i + 1 : request.fields().indexOf(name) + 1;
            values.add(
                    TypedCursorValue.of(types.get(name), typeNames.get(name), readValue(rows, index, types.get(name))));
        }
        return new TypedCursor(1, values);
    }

    /**
     * 按当前类型约定读取单个值。
     */
    private static Object readValue(ResultSet rows, int index, int type) throws SQLException {
        return switch (type) {
            case Types.TIMESTAMP -> rows.getObject(index, LocalDateTime.class);
            case Types.TIMESTAMP_WITH_TIMEZONE -> {
                LocalDateTime value = rows.getObject(index, LocalDateTime.class);
                yield value == null ? null : value.atOffset(ZoneOffset.UTC);
            }
            case Types.DATE -> rows.getObject(index, LocalDate.class);
            default -> rows.getObject(index);
        };
    }

    /**
     * 推进游标并返回下一条数据或后续读取状态。
     */
    @Override
    public boolean next() throws SQLException {
        if (closed || Thread.currentThread().isInterrupted()) {
            throw new SQLException("Cursor read interrupted or closed");
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
        Object[] values = new Object[request.fields().size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = readValue(result, i + 1, types.get(request.fields().get(i)));
        }
        position = cursor(result, false);
        pageRows++;
        row = BatchValueConverter.convert(List.<Object[]>of(values), values.length, request.limits()).getFirst();
        return true;
    }

    /**
     * 返回表结构指纹。
     */
    @Override
    public String schemaFingerprint() {
        return schemaFingerprint;
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
            throw new IllegalStateException("Cursor has no row");
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
                hooks.afterTransactionReleased(connection);
                if (hooks.readOnlyTransaction()) {
                    connection.setReadOnly(previousReadOnly);
                }
                connection.setTransactionIsolation(previousIsolation);
                if (previousTimeZone != null) {
                    try (var reset = connection.prepareStatement("SET SESSION time_zone=?")) {
                        reset.setQueryTimeout(options.queryTimeoutSeconds());
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
            throw new SQLException("Cursor source cleanup was not confirmed");
        }
    }

    /**
     * 按既定比较规则排列当前键或字段集合。
     */
    private String ordered(String direction) {
        return String.join(",", order.stream().map(name -> quote(name) + " " + direction).toList());
    }

    /**
     * 返回按当前方言加引号后的标识符。
     */
    private static String quoted(List<String> fields) {
        return String.join(",", fields.stream().map(MysqlCursorReader::quote).toList());
    }

    /**
     * 按MySQL方言转义并引用标识符。
     */
    private static String quote(String name) {
        return MysqlCursorMetadata.quote(name);
    }
}
