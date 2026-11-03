package cn.iocoder.yudao.framework.dbswitch.product.influxdb3;

import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursorValue;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.sql.Types;
import java.util.*;

/**
 * 用于类型化游标和原生 (tagset, time) 键写入的 InfluxDB3 Core 测量结构：引擎证据、服务器
 * 目录 UUID、数据库名，以及 {@code system.influxdb_schema} 中的精确列角色。测量没有
 * 传统主键：有序游标键先是纳秒时间戳，再按目录顺序排列所有标签列，
 * 行由 (tagset, time) 唯一确定。只读目录证据无法观察同一服务器上
 * 结构相同的表重建：运维人员必须停止并排空消费方，然后轮换外部保留的
 * 代次，再恢复消费（与 TDengine 游标的运维契约相同）。
 */
public final class InfluxCursorMetadata {

    public static final String TIME = "time";

    public static final String TIME_TYPE = "TIMESTAMP(NS)";

    public static final String TAG_TYPE = "INFLUX_TAG";

    private static final Set<InfluxNativeCatalog.Kind> VALUE_KINDS = Set.of(InfluxNativeCatalog.Kind.TAG,
            InfluxNativeCatalog.Kind.INT64, InfluxNativeCatalog.Kind.UINT64, InfluxNativeCatalog.Kind.FLOAT64,
            InfluxNativeCatalog.Kind.STRING, InfluxNativeCatalog.Kind.BOOLEAN);

    /**
     * 初始化 {@code InfluxCursorMetadata}。
     */
    private InfluxCursorMetadata() {
    }

    public record Shape(String database, String table, String cluster, String engine,
                        InfluxNativeCatalog.Table nativeTable) {

        /**
         * 校验构造参数并初始化 {@code Shape}。
         */
        public Shape {
            InfluxNativeSession.identifier(database);
            InfluxNativeSession.identifier(table);
            UUID.fromString(cluster);
            Objects.requireNonNull(engine);
            Objects.requireNonNull(nativeTable);
            if (!table.equals(nativeTable.name())) {
                throw new IllegalArgumentException("Influx cursor table evidence differs");
            }
        }

        /** 精确有序物理键：按目录顺序排列每个标签，再接纳秒时间戳。游标分页
         *  顺序将水位放在首位；此顺序与平台主键报告一致。 */
        public List<String> keys() {
            var keys = new ArrayList<String>();
            nativeTable.columns().stream().filter(c -> c.kind() == InfluxNativeCatalog.Kind.TAG)
                    .forEach(c -> keys.add(c.name()));
            keys.add(TIME);
            return List.copyOf(keys);
        }

        /** 完整物理结构和代次证据；纳入源、目标指纹。 */
        public String evidence() {
            var text = new StringBuilder("INFLUX_CURSOR_RELATION_V1").append('\0').append(engine).append('\0')
                    .append(cluster)
                    .append('\0').append(database).append('\0').append(table);
            for (var column : nativeTable.columns()) {
                text.append('\0').append(column.name()).append(':').append(column.kind());
            }
            return text.toString();
        }

        /** 有序键相等证据；不包含列名，使显式重命名仍然可行。 */
        public String keyEvidence() {
            var text = new StringBuilder("INFLUX_CURSOR_KEY_V1").append('\0').append(engine);
            for (var key : keys()) {
                if (!TIME.equals(key)) {
                    text.append('\0').append(key);
                }
            }
            text.append('\0').append(TIME_TYPE);
            return sha256Hex(text.toString());
        }

        /** 源值域：时间戳是精确有符号纳秒纪元计数；标签仅在相同原生契约下重放。 */
        public CursorKeyContract sourceContract() {
            var domains = new ArrayList<CursorKeyContract.Domain>();
            for (var key : keys()) {
                domains.add(TIME.equals(key) ? CursorKeyContract.Domain.integer(64,
                        false) : CursorKeyContract.Domain.nativeOnly());
            }
            return new CursorKeyContract(ProductTypeEnum.INFLUXDB3, keyEvidence(), domains);
        }

        /** 目标值域仅接受原生类型：其他产品的整数不携带 InfluxDB3 纳秒单位。 */
        public CursorKeyContract targetContract() {
            return new CursorKeyContract(ProductTypeEnum.INFLUXDB3, keyEvidence(),
                    Collections.nCopies(keys().size(), CursorKeyContract.Domain.nativeOnly()));
        }
    }

    /**
     * 按当前游标位置读取受限数量的数据行。
     */
    public static Shape read(InfluxNativeSession session, String database, String table) throws SQLException {
        InfluxNativeSession.identifier(database);
        InfluxNativeSession.identifier(table);
        String engine = engine(session);
        var nativeTable = InfluxNativeCatalog.find(session, table);
        if (nativeTable == null) {
            throw new SQLException("Influx native relation is absent or not visible");
        }
        if (!database.equals(session.database())) {
            throw new SQLException("Influx cursor database differs from its transport");
        }
        if (nativeTable.keys().size() > 32) {
            throw new SQLException("Influx cursor key exceeds its column budget");
        }
        if (nativeTable.columns().stream()
                .anyMatch(c -> c.kind() != InfluxNativeCatalog.Kind.TIME && !VALUE_KINDS.contains(c.kind()))) {
            throw new SQLException("Influx cursor requires a verified column codec");
        }
        return new Shape(database, table, session.clusterIdentity(), engine, nativeTable);
    }

    /**
     * 返回当前引擎。
     */
    public static String engine(InfluxNativeSession session) throws SQLException {
        var rows = session.query("SELECT version()");
        if (!rows.isArray() || rows.size() != 1 || !rows.get(0).isObject() || !rows.get(0).get("version()")
                .isTextual()) {
            throw new SQLException("Influx cursor engine evidence is absent or ambiguous");
        }
        String engine = rows.get(0).get("version()").textValue();
        if (!engine.matches("Apache DataFusion [0-9.]+.*")) {
            throw new SQLException("Influx cursor engine is not a verified DataFusion build");
        }
        return engine;
    }

    /** 投影列的精确源值；时间列投影为 CAST(time AS BIGINT)。 */
    public static Object value(InfluxNativeCatalog.Column column, JsonNode node) throws SQLException {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            return switch (column.kind()) {
                case TIME -> {
                    if (!node.isIntegralNumber() || !node.canConvertToLong()) {
                        throw invalid();
                    }
                    yield node.longValue();
                }
                case TAG, STRING -> {
                    if (!node.isTextual()) {
                        throw invalid();
                    }
                    yield node.textValue();
                }
                case INT64 -> {
                    if (!node.isIntegralNumber() || !node.canConvertToLong()) {
                        throw invalid();
                    }
                    yield node.longValue();
                }
                case UINT64 -> {
                    if (!node.isIntegralNumber() || node.bigIntegerValue().signum() < 0 || node.bigIntegerValue()
                            .bitLength() > 64) {
                        throw invalid();
                    }
                    yield node.bigIntegerValue();
                }
                case FLOAT64 -> {
                    if (!node.isNumber() || !Double.isFinite(node.doubleValue())) {
                        throw invalid();
                    }
                    yield node.doubleValue();
                }
                case BOOLEAN -> {
                    if (!node.isBoolean()) {
                        throw invalid();
                    }
                    yield node.booleanValue();
                }
            };
        } catch (SQLException invalid) {
            throw invalid;
        } catch (RuntimeException invalid) {
            throw invalid();
        }
    }

    /** 单个有序键列（时间或标签）的类型化游标分量。 */
    public static TypedCursorValue cursorValue(InfluxNativeCatalog.Column column, Object value) {
        if (column.kind() == InfluxNativeCatalog.Kind.TIME) {
            if (!(value instanceof Long epoch)) {
                throw new IllegalArgumentException("Influx cursor time is not an exact nanosecond epoch count");
            }
            return TypedCursorValue.of(Types.BIGINT, TIME_TYPE, epoch);
        }
        if (column.kind() != InfluxNativeCatalog.Kind.TAG || !(value instanceof String tag) || !tagLiteralValid(tag)) {
            throw new IllegalArgumentException("Influx cursor tag key cannot be expressed as a verified SQL literal");
        }
        return TypedCursorValue.of(Types.VARCHAR, TAG_TYPE, tag);
    }

    /** 校验持久化分量：精确纳秒纪元计数，或作为 SQL 字面值绑定的标签值。 */
    public static Object cursorComponent(InfluxNativeCatalog.Column column, TypedCursorValue value) {
        if (column.kind() == InfluxNativeCatalog.Kind.TIME) {
            if (value == null || value.jdbcType() != Types.BIGINT || !TIME_TYPE.equals(value.typeName())) {
                throw new IllegalArgumentException("Influx cursor time type changed");
            }
            return (Long) value.jdbcValue();
        }
        if (column.kind() != InfluxNativeCatalog.Kind.TAG || value == null || value.jdbcType() != Types.VARCHAR || !TAG_TYPE.equals(
                value.typeName())) {
            throw new IllegalArgumentException("Influx cursor tag type changed");
        }
        String tag = (String) value.jdbcValue();
        if (!tagLiteralValid(tag)) {
            throw new IllegalArgumentException("Influx cursor tag value is outside the verified literal contract");
        }
        return tag;
    }

    /**
     * 检查标签文本非空且满足长度与控制字符限制。
     */
    private static boolean tagLiteralValid(String tag) {
        return !tag.isEmpty() && tag.length() <= 1024 && tag.chars().noneMatch(Character::isISOControl);
    }

    /**
     * 按 UTF-8 内容计算 SHA-256 并返回十六进制摘要。
     */
    public static String sha256Hex(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static SQLException invalid() {
        return new SQLException("Influx cursor value cannot be represented in its declared native column type");
    }
}
