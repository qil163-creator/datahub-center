package cn.iocoder.yudao.framework.quality.core.audit;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.IDatabaseInterface;
import cn.iocoder.yudao.framework.dbswitch.core.model.BoundedKeyComponentProjection;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static cn.iocoder.yudao.framework.quality.core.audit.AuditKeySnapshot.*;

/** 经实库验证产品的同查询复合键扫描；类型/值投影委托 DB Switch，不按协议兼容性猜测支持。 */
final class ProjectedCompositeKeySnapshot {

    /**
     * 初始化 {@code ProjectedCompositeKeySnapshot}。
     */
    private ProjectedCompositeKeySnapshot() {
    }

    /**
     * 按当前产品的类型化复合键协议扫描并生成受限摘要。
     */
    static Grouped read(Connection connection, AuditSqlGenerator generator, AuditRuleContext context,
            List<String> columns, String groupColumn, int maxKeys, int maxGroups, int timeout) throws SQLException {
        var original = read(connection, generator, context, columns, groupColumn, maxKeys, maxGroups, timeout, false);
        // 保留已发布的整数 SQL/摘要；只在明确类型不支持时整份重读，不复用旧计数。
        // 超限、超时和未知异常不触发回退。新投影由调用方显式选择，不改变旧 SPI。
        boolean unsupported = !original.complete() && "GROUP_KEY_UNSUPPORTED".equals(original.reasonCode())
                || original.complete() && original.groups().stream()
                .anyMatch(group -> "KEY_TYPE_UNSUPPORTED".equals(group.snapshot().reasonCode()));
        return supportsMixed(context.getProductType()) && unsupported
                ? read(connection, generator, context, columns, groupColumn, maxKeys, maxGroups, timeout,
                true) : original;
    }

    /**
     * 按当前产品的类型化复合键协议扫描并生成受限摘要。
     */
    private static Grouped read(Connection connection, AuditSqlGenerator generator, AuditRuleContext context,
            List<String> columns, String groupColumn, int maxKeys, int maxGroups, int timeout,
            boolean mixed) throws SQLException {
        if (!validCompositeColumns(columns) || groupColumn != null && !groupColumn.matches(
                "[A-Za-z_][A-Za-z0-9_]{0,63}")) {
            throw new UnsupportedOperationException("Unsupported composite key columns");
        }
        if (maxKeys <= 0 || maxKeys > MAX_KEYS || maxGroups <= 0 || maxGroups > MAX_KEYS || timeout <= 0) {
            throw new IllegalArgumentException("Invalid composite key bounds");
        }
        var product = context.getProductType();
        if (mixed && !supportsMixed(product)) {
            throw new UnsupportedOperationException("Unverified mixed key product");
        }
        if (product != ProductTypeEnum.MYSQL && product != ProductTypeEnum.DORIS && product != ProductTypeEnum.MARIADB
                && product != ProductTypeEnum.CLICKHOUSE && product != ProductTypeEnum.OPENGAUSS && product != ProductTypeEnum.STARROCKS
                && product != ProductTypeEnum.TIDB && product != ProductTypeEnum.OCEANBASE && product != ProductTypeEnum.TDENGINE
                && product != ProductTypeEnum.INFLUXDB3 && product != ProductTypeEnum.HIVE && product != ProductTypeEnum.SQLSERVER
                && product != ProductTypeEnum.ORACLE && product != ProductTypeEnum.GREENPLUM
                && product != ProductTypeEnum.HIGHGO && product != ProductTypeEnum.DM
                && product != ProductTypeEnum.KINGBASE && product != ProductTypeEnum.OSCAR
                && product != ProductTypeEnum.UXDB) {
            throw new UnsupportedOperationException("Unverified composite key product");
        }
        var database = context.database();
        List<String> expressions = new ArrayList<>();
        for (String column : columns) {
            expressions.add(quote(product, column));
        }
        // 原列保持原生类型；附加精确身份在聚合前拆分排序规则或 LOB 前缀相同的不同键。
        for (String column : columns) {
            expressions.add(projection(database, connection, null, column, mixed).groupSql());
        }
        String scope = null;
        BoundedKeyComponentProjection group = null;
        if (groupColumn != null) {
            group = projection(database, connection, "k", "key" + expressions.size(), mixed);
            expressions.add(quote(product, groupColumn));
            scope = projection(database, connection, null, groupColumn, mixed).groupSql();
        }
        String inner = database.limitGeneratedSelect(generator.buildCompositeKeyCountSql(context, expressions, scope),
                maxKeys + 1);
        List<BoundedKeyComponentProjection> projections = new ArrayList<>();
        StringBuilder selected = new StringBuilder(
                "SELECT k." + quote(product, "totalCount") + ", k." + quote(product, "errorCount"));
        for (int i = 0; i < columns.size(); i++) {
            var projection = projection(database, connection, "k", "key" + i, mixed);
            projections.add(projection);
            selected.append(", ").append(projection.valueSql()).append(", ").append(projection.typeSql());
        }
        if (group != null) {
            selected.append(", ").append(group.valueSql()).append(", ").append(group.typeSql());
        }
        String sql = selected.append(" FROM (").append(inner).append(") k").toString();
        LinkedHashMap<String, List<EncodedKey>> groups = new LinkedHashMap<>();
        if (group == null) {
            groups.put("", new ArrayList<>());
        }
        Integer groupType = null;
        int scanned = 0;
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(maxKeys + 1);
            try (var rows = statement.executeQuery(sql)) {
                int[] types = new int[columns.size()];
                int tupleType = Types.STRUCT;
                boolean rowWitness = projections.stream()
                        .anyMatch(BoundedKeyComponentProjection::requiresRowTypeWitness);
                int[] witnessedTypes = null;
                for (int i = 0; i < types.length; i++) {
                    types[i] = projections.get(i).jdbcType(rows.getMetaData(), 4 + 2 * i);
                    if (types[i] == 0) {
                        tupleType = 0;
                    }
                }
                int groupValueColumn = 3 + types.length * 2;
                if (group != null) {
                    groupType = group.jdbcType(rows.getMetaData(), groupValueColumn + 1);
                    if (groupType == 0 && !group.requiresRowTypeWitness()) {
                        return incomplete(sql, "GROUP_KEY_UNSUPPORTED");
                    }
                    if (group.requiresRowTypeWitness()) {
                        groupType = null;
                    }
                }
                while (rows.next()) {
                    if (++scanned > maxKeys) {
                        return incomplete(sql, "KEY_LIMIT_EXCEEDED");
                    }
                    if (rowWitness) {
                        tupleType = Types.STRUCT;
                        for (int i = 0; i < types.length; i++) {
                            types[i] = projections.get(i).jdbcType(rows, 4 + 2 * i);
                            if (types[i] == 0) {
                                tupleType = 0;
                            }
                        }
                        if (witnessedTypes != null && !java.util.Arrays.equals(witnessedTypes, types)) {
                            throw new SQLException("Inconsistent native tuple types", "22000");
                        }
                        if (witnessedTypes == null) {
                            witnessedTypes = types.clone();
                        }
                    }
                    String key = "";
                    if (group != null) {
                        if (group.requiresRowTypeWitness()) {
                            int witnessed = group.jdbcType(rows, groupValueColumn + 1);
                            if (witnessed == 0 || groupType != null && groupType != witnessed) {
                                return incomplete(sql, "GROUP_KEY_UNSUPPORTED");
                            }
                            groupType = witnessed;
                        }
                        byte[] value = group.readBytes(rows, groupValueColumn);
                        if (value == null || value.length > MAX_KEY_BYTES) {
                            return incomplete(sql, "GROUP_KEY_UNSUPPORTED");
                        }
                        key = utf8(value);
                        if (key.isBlank() || key.length() > MAX_GROUP_KEY_LENGTH || key.indexOf('\0') >= 0) {
                            return incomplete(sql, "GROUP_KEY_UNSUPPORTED");
                        }
                    }
                    if (!groups.containsKey(key) && groups.size() >= maxGroups) {
                        return incomplete(sql, "GROUP_LIMIT_EXCEEDED");
                    }
                    long total = rows.getLong(1);
                    boolean invalid = rows.wasNull();
                    long fail = rows.getLong(2);
                    if (invalid || rows.wasNull() || total <= 0 || fail < 0 || fail > total) {
                        throw new SQLException("Invalid tuple group counts", "22000");
                    }
                    byte[] tuple = tupleType == 0 ? null : tuple(rows, types, projections);
                    groups.computeIfAbsent(key, ignored -> new ArrayList<>())
                            .add(new EncodedKey(tuple, total, fail, tupleType));
                }
            }
            List<Group> snapshots = new ArrayList<>();
            for (var entry : groups.entrySet()) {
                snapshots.add(new Group(entry.getKey(), sortedEncodedSnapshot(sql, entry.getValue(),
                        mixed ? mixedAlgorithm(product) : projectedCompositeAlgorithm(context))));
            }
            String encoding = group == null || groupType == null ? null : groupType == Types.VARCHAR || groupType == Types.LONGVARCHAR
                    ? projectedTextGroupEncoding(context) : projectedIntegerGroupEncoding(context);
            return new Grouped(sql, true, null, snapshots, encoding, groupType);
        } catch (ArithmeticException ex) {
            throw new SQLException("Tuple count overflow", "22003", ex);
        }
    }

    /**
     * 返回当前投影。
     */
    private static BoundedKeyComponentProjection projection(IDatabaseInterface database, Connection connection,
            String alias, String column, boolean mixed) throws SQLException {
        return mixed ? database.textAndIntegerKeyComponentProjection(connection, alias, column, MAX_KEY_BYTES)
                : database.keyComponentProjection(connection, alias, column, MAX_KEY_BYTES);
    }

    /**
     * 判断是否支持混合。
     */
    private static boolean supportsMixed(ProductTypeEnum product) {
        return product == ProductTypeEnum.TIDB || product == ProductTypeEnum.CLICKHOUSE || product == ProductTypeEnum.OPENGAUSS
                || product == ProductTypeEnum.STARROCKS || product == ProductTypeEnum.GREENPLUM;
    }

    /**
     * 返回当前产品使用的带类型复合键编码算法。
     */
    private static String mixedAlgorithm(ProductTypeEnum product) {
        return switch (product) {
            case TIDB -> TIDB_TYPED_COMPOSITE_ALGORITHM;
            case CLICKHOUSE -> CLICKHOUSE_TYPED_COMPOSITE_ALGORITHM;
            case OPENGAUSS -> OPENGAUSS_TYPED_COMPOSITE_ALGORITHM;
            case STARROCKS -> STARROCKS_TYPED_COMPOSITE_ALGORITHM;
            case GREENPLUM -> GREENPLUM_TYPED_COMPOSITE_ALGORITHM;
            default -> throw new UnsupportedOperationException("Unverified mixed key product");
        };
    }

    /**
     * 返回当前元组。
     */
    private static byte[] tuple(ResultSet rows, int[] types,
            List<BoundedKeyComponentProjection> projections) throws SQLException {
        ByteBuffer frame = ByteBuffer.allocate(MAX_KEY_BYTES);
        frame.putInt(types.length);
        for (int i = 0; i < types.length; i++) {
            byte[] bytes = projections.get(i).readBytes(rows, 3 + i * 2);
            if (bytes == null || bytes.length > MAX_KEY_BYTES || frame.remaining() < 8 + bytes.length) {
                return null;
            }
            if (types[i] == Types.VARCHAR || types[i] == Types.LONGVARCHAR) {
                utf8(bytes);
            }
            frame.putInt(types[i]).putInt(bytes.length).put(bytes);
        }
        return java.util.Arrays.copyOf(frame.array(), frame.position());
    }

    /**
     * 按 UTF-8 编码或解码当前文本。
     */
    private static String utf8(byte[] bytes) throws SQLException {
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException ex) {
            throw new SQLException("Invalid UTF-8 group identity", "22021", ex);
        }
    }

    /**
     * 按当前数据库方言转义并引用标识符。
     */
    private static String quote(ProductTypeEnum product, String column) {
        return RestrictedSelectGuard.quotePhysicalIdentifier(product, column);
    }

    /**
     * 返回元数据证据不完整时的类型映射结果。
     */
    private static Grouped incomplete(String sql, String reason) {
        return new Grouped(sql, false, reason, List.of());
    }
}
