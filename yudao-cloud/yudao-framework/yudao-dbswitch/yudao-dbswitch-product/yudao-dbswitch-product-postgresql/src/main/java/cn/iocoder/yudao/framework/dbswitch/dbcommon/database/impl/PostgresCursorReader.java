package cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursor;
import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursorValue;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import cn.iocoder.yudao.framework.dbswitch.core.util.PostgresRelationIdentity;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadSession;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** PG16 普通表游标；数据库比较和 ORDER BY 使用相同原生类型及排序规则。 */
final class PostgresCursorReader implements CursorReadSession {

    private final Connection connection;

    private final CursorReadRequest request;

    private final DbSwitchOptions options;

    private final String reference;

    private final List<String> order;

    private final Map<String, Integer> types = new HashMap<>();

    private final Map<String, String> typeNames = new HashMap<>();

    private PreparedStatement statement;

    private ResultSet result;

    private String schemaFingerprint;

    private String keyFingerprint;

    private cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract keyContract;

    private TypedCursor upper;

    private Object[] row;

    private TypedCursor position;

    private boolean closed;

    /**
     * 打开游标读取会话并准备当前读取范围。
     */
    static CursorReadSession open(DataSource source, CursorReadRequest request, DbSwitchOptions options,
            int fetchSize) {
        Connection connection = null;
        try {
            connection = source.getConnection();
            var reader = new PostgresCursorReader(connection, request, options);
            reader.prepare(fetchSize);
            return reader;
        } catch (Exception failure) {
            if (connection != null) {
                try {
                    connection.rollback();
                } catch (Exception ignored) {
                }
                try {
                    connection.close();
                } catch (Exception ignored) {
                }
            }
            throw new IllegalArgumentException("Typed cursor source, bounds or schema are unsupported");
        }
    }

    /**
     * 初始化 {@code PostgresCursorReader}。
     */
    private PostgresCursorReader(Connection connection, CursorReadRequest request, DbSwitchOptions options) {
        this.connection = connection;
        this.request = request;
        this.options = options;
        reference = quote(request.schema()) + "." + quote(request.table());
        order = request.orderFields();
    }

    /**
     * 核对源代次、目标关系和键列事实，固定读取上界并准备当前游标分页状态。
     */
    private void prepare(int fetchSize) throws SQLException {
        var metadata = connection.getMetaData();
        if (!connection.getAutoCommit() || !"PostgreSQL".equals(metadata.getDatabaseProductName())
                || metadata.getDatabaseMajorVersion() != 16) {
            throw new SQLException("Cursor requires a fresh PostgreSQL 16 connection");
        }
        connection.setReadOnly(true);
        connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        connection.setAutoCommit(false);
        String relation = PostgresRelationIdentity.storageFingerprint(connection, reference, true);
        if (relation == null) {
            throw new SQLException("Cursor source relation is unsupported");
        }
        verifyPrimaryKey();
        keyContract = cn.iocoder.yudao.framework.dbswitch.core.util.PostgresCursorKeyContract.describe(connection,
                reference, request.primaryKeys());
        keyFingerprint = keyContract.nativeFingerprint();
        StringBuilder material = new StringBuilder("PG_CURSOR_SCHEMA_V1").append('\0').append(relation).append('\0')
                .append(keyFingerprint);
        try (var probe = connection.createStatement()) {
            probe.setQueryTimeout(options.queryTimeoutSeconds());
            try (var rows = probe.executeQuery(
                    "SELECT " + quoted(request.fields()) + " FROM ONLY " + reference + " WHERE 1=2")) {
                var columns = rows.getMetaData();
                for (int i = 0; i < request.fields().size(); i++) {
                    String name = request.fields().get(i);
                    String typeName = columns.getColumnTypeName(i + 1);
                    types.put(name,
                            "timestamptz".equals(typeName) ? Types.TIMESTAMP_WITH_TIMEZONE : columns.getColumnType(
                                    i + 1));
                    typeNames.put(name, typeName);
                    material.append('\0').append(name).append(':').append(columns.getColumnType(i + 1))
                            .append(':').append(columns.getColumnTypeName(i + 1)).append(':')
                            .append(columns.getPrecision(i + 1))
                            .append(':').append(columns.getScale(i + 1));
                }
            }
        }
        try (var probe = connection.prepareStatement(
                "SELECT a.attname,a.attnotnull,a.atttypid,a.atttypmod,a.attcollation,"
                        + "coalesce(c.collversion,''),coalesce(pg_catalog.pg_collation_actual_version(c.oid),'') "
                        + "FROM pg_catalog.pg_attribute a LEFT JOIN pg_catalog.pg_collation c ON c.oid=a.attcollation "
                        + "WHERE a.attrelid=pg_catalog.to_regclass(?) AND a.attnum>0 AND NOT a.attisdropped ORDER BY a.attnum")) {
            probe.setQueryTimeout(options.queryTimeoutSeconds());
            probe.setString(1, reference);
            try (var rows = probe.executeQuery()) {
                while (rows.next()) {
                    String name = rows.getString(1);
                    if (order.contains(name) && !rows.getBoolean(2)) {
                        throw new SQLException("Nullable cursor columns are unsupported");
                    }
                    if (request.fields().contains(name)) {
                        for (int i = 1; i <= 7; i++) {
                            material.append('\0').append(rows.getString(i));
                        }
                    }
                }
            }
        }
        int watermarkType = types.get(request.watermark());
        if (!List.of(Types.INTEGER, Types.SMALLINT, Types.BIGINT, Types.NUMERIC, Types.DECIMAL,
                Types.DATE, Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE).contains(watermarkType)) {
            throw new SQLException("Watermark must use a verified numeric or temporal type");
        }
        material.append('\0').append(request.watermark()).append('\0').append(String.join("\0", request.primaryKeys()));
        schemaFingerprint = DigestUtil.sha256Hex(material.toString());
        if (request.expectedSchemaFingerprint() != null && !request.expectedSchemaFingerprint()
                .equals(schemaFingerprint)) {
            throw new SQLException("Cursor source schema or relation changed");
        }
        validate(request.lower());
        validate(request.fixedUpper());
        // 新快照不得隐式接受恢复或截断后最大值回退的源端。
        if (request.lower() != null) {
            try (var probe = connection.prepareStatement("SELECT EXISTS (SELECT 1 FROM ONLY " + reference
                    + " WHERE " + quote(request.watermark()) + " >= ?)")) {
                probe.setQueryTimeout(options.queryTimeoutSeconds());
                var lower = request.lower().values().getFirst();
                probe.setObject(1, lower.jdbcValue(), lower.jdbcType());
                try (var rows = probe.executeQuery()) {
                    if (!rows.next() || !rows.getBoolean(1)) {
                        throw new SQLException("Source watermark moved backwards; explicit reconciliation is required");
                    }
                }
            }
        }
        upper = request.fixedUpper();
        if (upper == null && !request.upperBoundFrozen()) {
            try (var probe = connection.createStatement()) {
                probe.setQueryTimeout(options.queryTimeoutSeconds());
                try (var rows = probe.executeQuery("SELECT " + quoted(order) + " FROM ONLY " + reference
                        + " ORDER BY " + ordered("DESC") + " LIMIT 1")) {
                    if (rows.next()) {
                        upper = cursor(rows, true);
                    }
                }
            }
        }
        String sql = "SELECT " + quoted(request.fields()) + " FROM ONLY " + reference + " WHERE "
                + (upper == null ? "false" : "(" + quoted(order) + ") <= (" + placeholders(order.size()) + ")");
        // 快照恢复保守地重读最后一个水位组，包括新提交的同水位键。
        if (request.lower() != null) {
            sql += " AND " + quote(request.watermark()) + " >= ?";
        }
        sql += " ORDER BY " + ordered("ASC");
        statement = connection.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        statement.setQueryTimeout(options.queryTimeoutSeconds());
        statement.setFetchSize(Math.max(1, Math.min(fetchSize, request.limits().maxRows())));
        int index = 1;
        if (upper != null) {
            for (var value : upper.values()) {
                statement.setObject(index++, value.jdbcValue(), value.jdbcType());
            }
        }
        if (request.lower() != null) {
            var value = request.lower().values().getFirst();
            statement.setObject(index, value.jdbcValue(), value.jdbcType());
        }
        result = statement.executeQuery();
    }

    /**
     * 校验主键。
     */
    private void verifyPrimaryKey() throws SQLException {
        String sql = "SELECT a.attname,con.condeferrable,i.indisvalid,i.indisready,opc.opcdefault,"
                + "i.indcollation[(k.ordinality-1)::int]=a.attcollation "
                + "FROM pg_catalog.pg_constraint con JOIN pg_catalog.pg_index i ON i.indexrelid=con.conindid "
                + "CROSS JOIN LATERAL unnest(con.conkey) WITH ORDINALITY k(attnum,ordinality) "
                + "JOIN pg_catalog.pg_attribute a ON a.attrelid=con.conrelid AND a.attnum=k.attnum "
                + "JOIN pg_catalog.pg_opclass opc ON opc.oid=i.indclass[(k.ordinality-1)::int] "
                + "WHERE con.conrelid=pg_catalog.to_regclass(?) AND con.contype='p' ORDER BY k.ordinality";
        List<String> keys = new ArrayList<>();
        try (var probe = connection.prepareStatement(sql)) {
            probe.setQueryTimeout(options.queryTimeoutSeconds());
            probe.setString(1, reference);
            try (var rows = probe.executeQuery()) {
                while (rows.next()) {
                    if (rows.getBoolean(2) || !rows.getBoolean(3) || !rows.getBoolean(4) || !rows.getBoolean(
                            5) || !rows.getBoolean(6)) {
                        throw new SQLException("Cursor key ordering is unsupported");
                    }
                    keys.add(rows.getString(1));
                }
            }
        }
        if (!keys.equals(request.primaryKeys())) {
            throw new SQLException("Cursor requires the exact ordered primary key");
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
            throw new SQLException("Cursor tuple shape changed");
        }
        for (int i = 0; i < order.size(); i++) {
            if (value.values().get(i).jdbcType() != types.get(order.get(i))
                    || !value.values().get(i).typeName().equals(typeNames.get(order.get(i)))) {
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
            Object value = readValue(rows, index, types.get(name), typeNames.get(name));
            values.add(TypedCursorValue.of(types.get(name), typeNames.get(name), value));
        }
        return new TypedCursor(1, values);
    }

    /**
     * 推进游标并返回下一条数据或后续读取状态。
     */
    @Override
    public boolean next() throws SQLException {
        if (closed || Thread.currentThread().isInterrupted()) {
            throw new SQLException("Cursor read interrupted or closed");
        }
        if (!result.next()) {
            row = null;
            position = null;
            return false;
        }
        Object[] values = new Object[request.fields().size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = readValue(result, i + 1, types.get(request.fields().get(i)),
                    typeNames.get(request.fields().get(i)));
        }
        position = cursor(result, false);
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
     * 返回键指纹。
     */
    @Override
    public String keyContractFingerprint() {
        return keyFingerprint;
    }

    /**
     * 返回游标键契约。
     */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract keyContract() {
        return keyContract;
    }

    /**
     * 返回上界。
     */
    @Override
    public TypedCursor upperBound() {
        return upper;
    }

    /**
     * 返回数据行。
     */
    @Override
    public Object[] row() {
        if (row == null) {
            throw new IllegalStateException("Cursor has no current row");
        }
        return row.clone();
    }

    /**
     * 返回位置。
     */
    @Override
    public TypedCursor position() {
        return position;
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
            connection.rollback();
            connection.setAutoCommit(true);
            connection.setReadOnly(false);
        } catch (Exception failure) {
            failed = true;
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
     * 按当前类型约定读取单个值。
     */
    private static Object readValue(ResultSet rows, int index, int type, String typeName) throws SQLException {
        return switch (type) {
            case Types.TIMESTAMP -> rows.getObject(index, java.time.LocalDateTime.class);
            case Types.TIMESTAMP_WITH_TIMEZONE -> rows.getObject(index, OffsetDateTime.class);
            case Types.DATE -> rows.getObject(index, java.time.LocalDate.class);
            case Types.TIME -> "time".equals(typeName) ? readTime(rows, index) : rows.getObject(index);
            default -> rows.getObject(index);
        };
    }

    /**
     * 读取时间。
     */
    private static java.time.LocalTime readTime(ResultSet rows, int index) throws SQLException {
        String text = rows.getString(index);
        if (text == null) {
            return null;
        }
        // PG 时间上界 24:00:00 没有精确的 JDBC 时间载体，pgjdbc 将其映射为 LocalTime.MAX。
        if (text.startsWith("24:")) {
            throw new SQLException("PostgreSQL TIME 24:00:00 has no exact JDBC carrier");
        }
        try {
            return java.time.LocalTime.parse(text);
        } catch (RuntimeException invalid) {
            throw new SQLException("PostgreSQL TIME text is not representable");
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
    private static String quoted(List<String> names) {
        return String.join(",", names.stream().map(PostgresCursorReader::quote).toList());
    }

    /**
     * 按字段数量及绑定类型生成参数占位符集合。
     */
    private static String placeholders(int size) {
        return String.join(",", java.util.Collections.nCopies(size, "?"));
    }

    /**
     * 按PostgreSQL方言转义并引用标识符。
     */
    private static String quote(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }
}
