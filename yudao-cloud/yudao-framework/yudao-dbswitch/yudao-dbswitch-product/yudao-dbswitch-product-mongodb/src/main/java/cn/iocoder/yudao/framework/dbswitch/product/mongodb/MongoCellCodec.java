package cn.iocoder.yudao.framework.dbswitch.product.mongodb;

import cn.iocoder.yudao.framework.dbswitch.common.entity.MissingCellValue;
import cn.iocoder.yudao.framework.dbswitch.common.entity.NativeCellValue;
import cn.iocoder.yudao.framework.dbswitch.common.util.SingletonObject;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** 无损 BSON 所有权边界。匹配相等范围刻意小于 BSON 值传输范围。 */
public final class MongoCellCodec {

    public static final String FORMAT = "BSON_VALUE_V1";

    /**
     * 初始化 {@code MongoCellCodec}。
     */
    private MongoCellCodec() {
    }

    /**
     * 根据当前输入构建并返回 {@code NativeCellValue}。
     */
    public static NativeCellValue detach(MongoNativeSession session, byte[] cell,
            boolean identity) throws SQLException {
        type(cell);
        // 解码并重新编码一次，拒绝无效 BSON 信封，不信任任意字节。
        Map<String, byte[]> validated = session.cells(cell);
        if (validated.size() != 1 || !validated.containsKey("v") || !Arrays.equals(cell, validated.get("v"))) {
            throw new SQLException("MongoDB native cell envelope is not canonical BSON");
        }
        return new NativeCellValue(FORMAT, cell, matchingKey(session, cell));
    }

    /**
     * 将当前数据编码为字节。
     */
    public static byte[] encode(MongoNativeSession session, Object value, boolean identity) throws SQLException {
        if (value == MissingCellValue.INSTANCE) {
            throw new IllegalArgumentException("Missing MongoDB fields must be omitted by the document builder");
        }
        if (value instanceof NativeCellValue nativeValue) {
            if (!FORMAT.equals(nativeValue.format())) {
                throw new IllegalArgumentException("MongoDB requires BSON value evidence");
            }
            byte[] encoded = nativeValue.encoded();
            NativeCellValue verified = detach(session, encoded, identity);
            if (!Arrays.equals(verified.matchingKey(), nativeValue.matchingKey())) {
                throw new IllegalArgumentException("MongoDB native matching key disagrees with its encoded value");
            }
            return encoded;
        }
        Object canonical;
        if (value == null || value instanceof String || value instanceof Boolean) {
            canonical = value;
        } else if (value instanceof Byte || value instanceof Short || value instanceof Integer) {
            canonical = Map.of("$numberInt", value.toString());
        } else if (value instanceof Long) {
            canonical = Map.of("$numberLong", value.toString());
        } else if (value instanceof BigInteger || value instanceof BigDecimal) {
            canonical = Map.of("$numberDecimal", value.toString());
        } else if (value instanceof Float || value instanceof Double) {
            canonical = Map.of("$numberDouble", value.toString());
        } else if (value instanceof byte[] binary) {
            canonical = Map.of("$binary",
                    Map.of("base64", java.util.Base64.getEncoder().encodeToString(binary), "subType", "00"));
        } else if (value instanceof java.time.Instant instant) {
            if (instant.getNano() % 1_000_000 != 0) {
                throw new IllegalArgumentException("MongoDB BSON date requires exact millisecond precision");
            }
            canonical = Map.of("$date", Map.of("$numberLong", Long.toString(instant.toEpochMilli())));
        } else if (value instanceof java.util.Date date) {
            if (date instanceof java.sql.Timestamp timestamp && timestamp.getNanos() % 1_000_000 != 0) {
                throw new IllegalArgumentException("MongoDB BSON date requires exact millisecond precision");
            }
            canonical = Map.of("$date", Map.of("$numberLong", Long.toString(date.getTime())));
        } else {
            throw new IllegalArgumentException("MongoDB value requires an explicit native conversion");
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("v", canonical);
        try {
            byte[] encoded = session.encode(SingletonObject.OBJECT_MAPPER.writeValueAsString(envelope));
            detach(session, encoded, identity);
            return encoded;
        } catch (java.io.IOException failure) {
            throw new SQLException("MongoDB value cannot be encoded");
        }
    }

    /**
     * 返回类型。
     */
    private static int type(byte[] cell) throws SQLException {
        if (cell == null || cell.length < 8 || ByteBuffer.wrap(cell).order(ByteOrder.LITTLE_ENDIAN)
                .getInt() != cell.length
                || cell[5] != 'v' || cell[6] != 0 || cell[cell.length - 1] != 0) {
            throw new SQLException("MongoDB single-value BSON envelope is invalid");
        }
        return Byte.toUnsignedInt(cell[4]);
    }

    /**
     * 提取当前行用于匹配的键值。
     */
    private static byte[] matchingKey(MongoNativeSession session, byte[] cell) throws SQLException {
        int type = type(cell);
        ByteBuffer value = ByteBuffer.wrap(cell, 7, cell.length - 8).order(ByteOrder.LITTLE_ENDIAN);
        BigDecimal number = null;
        try {
            if (type == 0x10) {
                number = BigDecimal.valueOf(value.getInt());
            } else if (type == 0x12) {
                number = BigDecimal.valueOf(value.getLong());
            } else if (type == 0x13) {
                try {
                    number = session.decimal128(cell);
                } catch (SQLException specialDecimal) {
                    return null;
                }
            } else if (type == 0x01) {
                double binary = value.getDouble();
                // 在 2^53 内，每个整数 double 均有精确整数表示。带小数的
                // double、NaN/Infinity 和更大的值，需要已验证的 Mongo 数值键契约。
                if (Double.isFinite(binary) && binary == Math.rint(binary) && Math.abs(binary) <= 9007199254740992D) {
                    number = BigDecimal.valueOf((long) binary);
                } else {
                    return null;
                }
            }
        } catch (java.nio.BufferUnderflowException invalid) {
            throw new SQLException("MongoDB scalar BSON envelope is invalid");
        }
        if (number != null) {
            String canonical = number.signum() == 0 ? "0" : number.stripTrailingZeros().toString();
            return ("number\0" + canonical).getBytes(StandardCharsets.UTF_8);
        }
        // 全量传输持有所有合法 BSON 值。缺少已验证 Mongo 相等契约时，
        // 仅移除 DIFF 匹配依据，绝不用字节相同代替该依据。
        if (!java.util.Set.of(0x02, 0x05, 0x07, 0x08, 0x09, 0x11).contains(type)) {
            return null;
        }
        // 对受支持的非数值键，BSON 类型标记区分 string/ObjectId/date 等。
        // 载荷保持不变；集合的非 simple 排序规则单独拒绝。
        byte[] matching = new byte[cell.length - 6];
        matching[0] = (byte) type;
        System.arraycopy(cell, 7, matching, 1, cell.length - 7);
        return matching;
    }
}
