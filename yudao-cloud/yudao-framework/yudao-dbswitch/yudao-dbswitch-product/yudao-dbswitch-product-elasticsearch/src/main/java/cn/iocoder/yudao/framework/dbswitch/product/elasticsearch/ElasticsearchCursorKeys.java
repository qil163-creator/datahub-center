package cn.iocoder.yudao.framework.dbswitch.product.elasticsearch;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursorValue;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Elasticsearch 的游标水位值域及排序依据。水位字段必须采用单一可排序的
 * doc-values 映射类型，且索引顺序可精确重放：keyword、整数族、unsigned_long、
 * date 或 date_nanos。其他所有类型、{@code _id} 水位（fielddata 默认禁用）、规范化或
 * 部分索引的 keyword 字段，以及容忍错误格式的字段，均在释放任何行前拒绝。单个
 * 索引内每个字段只有一种映射类型，所以不会出现混合类型水位；{@code _doc} 按每个索引稳定顺序
 * 区分同水位行，服务器返回的精确排序值被原样传回
 * search_after，因此客户端解析持久化值不会偏离服务器顺序。
 */
public final class ElasticsearchCursorKeys {

    /**
     * 初始化 {@code ElasticsearchCursorKeys}。
     */
    private ElasticsearchCursorKeys() {
    }

    public static final BigInteger UNSIGNED_LONG_LIMIT = BigInteger.ONE.shiftLeft(64);

    public enum Family {
        KEYWORD("keyword", Types.VARCHAR),
        INTEGER("integer", Types.INTEGER),
        LONG("long", Types.BIGINT),
        UNSIGNED_LONG("unsigned_long", Types.NUMERIC),
        DATE("date", Types.TIMESTAMP_WITH_TIMEZONE),
        DATE_NANOS("date_nanos", Types.TIMESTAMP_WITH_TIMEZONE);

        private final String typeName;

        private final int jdbcType;

        /**
         * 初始化 {@code Family}。
         */
        Family(String typeName, int jdbcType) {
            this.typeName = typeName;
            this.jdbcType = jdbcType;
        }

        /**
         * 返回类型名称。
         */
        public String typeName() {
            return typeName;
        }

        /**
         * 返回JDBC 类型。
         */
        public int jdbcType() {
            return jdbcType;
        }
    }

    /** 映射字段可排序的 doc-values 水位族；类型不可重放时为 null。 */
    public static Family familyOf(ElasticsearchMapping.Field field) {
        if (field == null) {
            return null;
        }
        return switch (field.type()) {
            case "keyword" -> Family.KEYWORD;
            case "byte", "short", "integer" -> Family.INTEGER;
            case "long" -> Family.LONG;
            case "unsigned_long" -> Family.UNSIGNED_LONG;
            case "date" -> Family.DATE;
            case "date_nanos" -> Family.DATE_NANOS;
            default -> null;
        };
    }

    /** 对此类型族精确解码持久化元组值；任何类型漂移均在构建查询前失败。 */
    public static Object decode(Family family, TypedCursorValue value) throws SQLException {
        if (family == null || value == null || value.jdbcType() != family.jdbcType()
                || !family.typeName().equals(value.typeName())) {
            throw new SQLException("Elasticsearch cursor tuple types changed");
        }
        try {
            return switch (family) {
                case KEYWORD -> value.jdbcValue();
                case INTEGER -> value.jdbcValue();
                case LONG -> value.jdbcValue();
                case UNSIGNED_LONG -> {
                    BigInteger number = ((BigDecimal) value.jdbcValue()).toBigIntegerExact();
                    if (number.signum() < 0 || number.compareTo(UNSIGNED_LONG_LIMIT) >= 0) {
                        throw new SQLException("Elasticsearch cursor tuple types changed");
                    }
                    yield number;
                }
                case DATE, DATE_NANOS -> ((OffsetDateTime) value.jdbcValue()).toInstant();
            };
        } catch (RuntimeException invalid) {
            throw new SQLException("Elasticsearch cursor tuple types changed");
        }
    }

    /** 将精确水位值编码为持久化的类型化元组值。 */
    public static TypedCursorValue value(Family family, Object watermark) {
        Object exact = switch (family) {
            case KEYWORD -> watermark;
            case INTEGER -> watermark;
            case LONG -> watermark;
            case UNSIGNED_LONG -> new BigDecimal((BigInteger) watermark);
            case DATE, DATE_NANOS -> OffsetDateTime.ofInstant((Instant) watermark, ZoneOffset.UTC);
        };
        return TypedCursorValue.of(family.jdbcType(), family.typeName(), exact);
    }

    /** 解码服务器为单个命中返回的规范排序值；null 或不符合约定的结构均默认拒绝。 */
    public static Object sortValue(Family family, JsonNode node) throws SQLException {
        if (family == null || node == null || node.isNull()) {
            throw new SQLException("Elasticsearch cursor sort evidence is not verified");
        }
        try {
            return switch (family) {
                case KEYWORD -> {
                    if (!node.isTextual()) {
                        throw new SQLException("Elasticsearch cursor sort evidence is not verified");
                    }
                    yield node.textValue();
                }
                case INTEGER -> {
                    if (!node.isIntegralNumber()) {
                        throw new SQLException("Elasticsearch cursor sort evidence is not verified");
                    }
                    yield node.bigIntegerValue().intValueExact();
                }
                case LONG -> {
                    if (!node.isIntegralNumber()) {
                        throw new SQLException("Elasticsearch cursor sort evidence is not verified");
                    }
                    yield node.bigIntegerValue().longValueExact();
                }
                case UNSIGNED_LONG -> {
                    if (!node.isIntegralNumber()) {
                        throw new SQLException("Elasticsearch cursor sort evidence is not verified");
                    }
                    BigInteger number = node.bigIntegerValue();
                    if (number.signum() < 0 || number.compareTo(UNSIGNED_LONG_LIMIT) >= 0) {
                        throw new SQLException("Elasticsearch cursor sort evidence is not verified");
                    }
                    yield number;
                }
                case DATE -> {
                    if (!node.isIntegralNumber()) {
                        throw new SQLException("Elasticsearch cursor sort evidence is not verified");
                    }
                    yield Instant.ofEpochMilli(node.bigIntegerValue().longValueExact());
                }
                case DATE_NANOS -> {
                    if (!node.isIntegralNumber()) {
                        throw new SQLException("Elasticsearch cursor sort evidence is not verified");
                    }
                    long nanos = node.bigIntegerValue().longValueExact();
                    yield Instant.ofEpochSecond(Math.floorDiv(nanos, 1_000_000_000L),
                            Math.floorMod(nanos, 1_000_000_000L));
                }
            };
        } catch (ArithmeticException invalid) {
            throw new SQLException("Elasticsearch cursor sort evidence is not verified");
        }
    }

    /** 范围边界的 JSON 值：保持字段自身类型的精度，绝不使用截断数值载体。 */
    public static Object bound(Family family, Object watermark) {
        return switch (family) {
            case KEYWORD -> watermark;
            case INTEGER -> watermark;
            case LONG -> watermark;
            case UNSIGNED_LONG -> ((BigInteger) watermark).toString();
            case DATE, DATE_NANOS -> ((Instant) watermark).toString();
        };
    }

    /** 与服务器一致的顺序：数值、绝对时刻或 UTF-8 字节顺序（Lucene 词项顺序）。 */
    public static int compare(Family family, Object left, Object right) {
        return switch (family) {
            case KEYWORD -> compareBytes((String) left, (String) right);
            case INTEGER -> Integer.compare((Integer) left, (Integer) right);
            case LONG -> Long.compare((Long) left, (Long) right);
            case UNSIGNED_LONG -> ((BigInteger) left).compareTo((BigInteger) right);
            case DATE, DATE_NANOS -> ((Instant) left).compareTo((Instant) right);
        };
    }

    /** {@code _id} 同值排序按 UTF-8 字节顺序；对于合法 Java 字符串，与 Lucene 词项顺序一致。 */
    public static int compareId(String left, String right) {
        return compareBytes(left, right);
    }

    /**
     * 比较字节数组。
     */
    private static int compareBytes(String left, String right) {
        byte[] a = left.getBytes(StandardCharsets.UTF_8), b = right.getBytes(StandardCharsets.UTF_8);
        int length = Math.min(a.length, b.length);
        for (int i = 0; i < length; i++) {
            int compared = Byte.toUnsignedInt(a[i]) - Byte.toUnsignedInt(b[i]);
            if (compared != 0) {
                return compared;
            }
        }
        return a.length - b.length;
    }

    /** 读取器和写入器共用的单个 {@code _id} upsert 键契约；绝不绑定水位族。 */
    public static String idKeyFingerprint() {
        return DigestUtil.sha256Hex("ELASTICSEARCH_ID_KEY_V1");
    }

    /**
     * 返回游标读写使用的结构化键契约。
     */
    public static CursorKeyContract keyContract() {
        return new CursorKeyContract(ProductTypeEnum.ELASTICSEARCH, idKeyFingerprint(),
                List.of(CursorKeyContract.Domain.nativeOnly()));
    }

    /** 水位字段定义预检查：值必须完整索引，且排序与存储值完全一致。 */
    public static void requireWatermarkField(JsonNode definition) throws SQLException {
        if (definition == null || !definition.isObject()) {
            throw new SQLException("Elasticsearch cursor watermark mapping is not verified");
        }
        if (definition.has("ignore_above") || definition.has("normalizer") || definition.has("time_series_metric")
                || definition.path("ignore_malformed").asBoolean(false)
                || definition.path("index").isBoolean() && !definition.path("index").asBoolean()
                || definition.path("doc_values").isBoolean() && !definition.path("doc_values").asBoolean()) {
            throw new SQLException("Elasticsearch cursor watermark mapping is not verified");
        }
    }

    /** 对完整映射观测计算确定性 SHA-256；键顺序不影响指纹。 */
    public static String mappingFingerprint(JsonNode mapping) throws SQLException {
        try {
            String canonical = new ObjectMapper().writeValueAsString(canonicalValue(mapping, 0));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.io.IOException | NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    /**
     * 将当前值整理为稳定比较使用的规范形式。
     */
    private static Object canonicalValue(JsonNode value, int depth) throws SQLException {
        if (depth > 32) {
            throw new SQLException("Elasticsearch mapping nesting exceeds the verified bound");
        }
        if (value == null || value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isObject()) {
            Map<String, Object> entries = new TreeMap<>();
            var fields = value.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                entries.put(field.getKey(), canonicalValue(field.getValue(), depth + 1));
            }
            return entries;
        }
        if (value.isArray()) {
            List<Object> items = new ArrayList<>();
            for (var item : value) {
                items.add(canonicalValue(item, depth + 1));
            }
            return items;
        }
        if (value.isTextual()) {
            String text = value.textValue();
            if (text.indexOf('\0') >= 0 || text.length() > 65536) {
                throw new SQLException("Elasticsearch mapping text is invalid");
            }
            return text;
        }
        if (value.isIntegralNumber()) {
            return value.bigIntegerValue().toString();
        }
        if (value.isFloatingPointNumber()) {
            return value.decimalValue().toPlainString();
        }
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        throw new SQLException("Elasticsearch mapping evidence is invalid");
    }
}
