package cn.iocoder.yudao.framework.dbswitch.common.util;

import java.io.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

/** 带版本和大小上限的标量行指纹。绝不通过任意 toString 对未知对象或厂商对象计算哈希。 */
public final class TypedRowFingerprint {

    private static final int MAX_CELL_BYTES = 1024 * 1024;

    /**
     * 初始化 {@code TypedRowFingerprint}。
     */
    private TypedRowFingerprint() {
    }

    /** 刻意限制范围的不可变行定位信息；在所有 JSON 传输中，值均保持为文本。 */
    public record KeyValue(String type, String value) {

        /**
         * 校验构造参数并初始化 {@code KeyValue}。
         */
        public KeyValue {
            if (type == null || value == null || utf8(value).length > 4096) {
                throw new IllegalArgumentException("Invalid typed row key");
            }
            switch (type) {
                case "INTEGER" -> {
                    if (!Long.toString(Long.parseLong(value)).equals(value)) {
                        throw new IllegalArgumentException("Noncanonical integer key");
                    }
                }
                case "UUID" -> {
                    if (!UUID.fromString(value).toString().equals(value)) {
                        throw new IllegalArgumentException("Noncanonical UUID key");
                    }
                }
                case "TEXT" -> {
                    if (value.indexOf('\0') >= 0) {
                        throw new IllegalArgumentException("Invalid text key");
                    }
                }
                default -> throw new IllegalArgumentException("Unsupported row key type");
            }
        }

        /**
         * 读取或转换当前标量值。
         */
        public Object scalar() {
            return switch (type) {
                case "INTEGER" -> Long.valueOf(value);
                case "UUID" -> UUID.fromString(value);
                default -> value;
            };
        }

        /**
         * 根据给定参数创建 {@code KeyValue} 实例。
         */
        public static KeyValue of(Object value) {
            if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
                return new KeyValue("INTEGER", Long.toString(((Number) value).longValue()));
            }
            if (value instanceof UUID uuid) {
                return new KeyValue("UUID", uuid.toString());
            }
            if (value instanceof String text) {
                return new KeyValue("TEXT", text);
            }
            throw new IllegalArgumentException("Row identity requires non-null integer, text or UUID keys");
        }
    }

    /**
     * 根据当前内容计算摘要，供一致性比较使用。
     */
    public static String hash(Map<String, Object> row) {
        if (row == null || row.isEmpty() || row.size() > 256) {
            throw new IllegalArgumentException("Invalid row field count");
        }
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var out = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
                text(out, "DBSWITCH_TYPED_ROW_V1");
                out.writeInt(row.size());
                long bytes = 0;
                for (String field : new TreeSet<>(row.keySet())) {
                    if (field == null || field.isBlank() || field.length() > 128) {
                        throw new IllegalArgumentException("Invalid row field");
                    }
                    text(out, field);
                    var value = canonical(row.get(field));
                    bytes += value.data().length;
                    if (bytes > 8L * 1024 * 1024) {
                        throw new IllegalArgumentException("Row fingerprint byte budget exceeded");
                    }
                    text(out, value.type());
                    out.writeInt(value.data().length);
                    out.write(value.data());
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Unable to fingerprint row");
        }
    }

    private record Value(String type, byte[] data) {

    }

    /**
     * 将值整理为当前协议使用的规范形式。
     */
    private static Value canonical(Object value) {
        String type, text;
        if (value == null) {
            type = "NULL";
            text = "";
        } else if (value instanceof byte[] bytes) {
            if (bytes.length > MAX_CELL_BYTES) {
                throw new IllegalArgumentException("Cell byte budget exceeded");
            }
            return new Value("BYTES", bytes);
        } else if (value instanceof String s) {
            type = "TEXT";
            text = s;
        } else if (value instanceof Boolean b) {
            type = "BOOLEAN";
            text = b.toString();
        } else if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            type = "INTEGER";
            text = Long.toString(((Number) value).longValue());
        } else if (value instanceof BigInteger integer) {
            type = "DECIMAL";
            text = decimal(new BigDecimal(integer));
        } else if (value instanceof BigDecimal decimal) {
            type = "DECIMAL";
            text = decimal(decimal);
        } else if (value instanceof Float f && Float.isFinite(f)) {
            type = "FLOAT32";
            text = Float.toHexString(f);
        } else if (value instanceof Double d && Double.isFinite(d)) {
            type = "FLOAT64";
            text = Double.toHexString(d);
        } else if (value instanceof UUID uuid) {
            type = "UUID";
            text = uuid.toString();
        } else if (value instanceof java.sql.Timestamp timestamp) {
            type = "LOCAL_DATETIME";
            text = timestamp.toLocalDateTime().toString();
        } else if (value instanceof java.sql.Date date) {
            type = "DATE";
            text = date.toLocalDate().toString();
        } else if (value instanceof java.sql.Time time) {
            type = "TIME";
            text = JdbcValueConversions.toTime(time).toString();
        } else if (value instanceof LocalDate date) {
            type = "DATE";
            text = date.toString();
        } else if (value instanceof LocalTime time) {
            type = "TIME";
            text = time.toString();
        } else if (value instanceof LocalDateTime time) {
            type = "LOCAL_DATETIME";
            text = time.toString();
        } else if (value instanceof OffsetDateTime time) {
            type = "INSTANT";
            text = time.toInstant().toString();
        } else if (value instanceof Instant time) {
            type = "INSTANT";
            text = time.toString();
        } else if (value instanceof OffsetTime time) {
            type = "OFFSET_TIME";
            text = time.toString();
        } else {
            throw new IllegalArgumentException("Unsupported fingerprint scalar type");
        }
        byte[] bytes = utf8(text);
        if (bytes.length > MAX_CELL_BYTES) {
            throw new IllegalArgumentException("Cell byte budget exceeded");
        }
        return new Value(type, bytes);
    }

    /**
     * 按当前值域约定解析或转换精确小数。
     */
    private static String decimal(BigDecimal value) {
        if (value.precision() > 131072 || Math.abs((long) value.scale()) > 16383) {
            throw new IllegalArgumentException("Decimal budget exceeded");
        }
        return value.stripTrailingZeros().toPlainString();
    }

    /**
     * 按当前值域约定解析或转换文本。
     */
    private static void text(DataOutputStream out, String text) throws IOException {
        byte[] bytes = utf8(text);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    /**
     * 按 UTF-8 编码或解码当前文本。
     */
    private static byte[] utf8(String value) {
        try {
            var encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(value));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (java.nio.charset.CharacterCodingException failure) {
            throw new IllegalArgumentException("Malformed scalar Unicode");
        }
    }
}
