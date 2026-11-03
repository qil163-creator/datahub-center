package cn.iocoder.yudao.framework.dbswitch.dbwriter.atomic;

import java.io.*;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/**
 * 用于调用方自有持久暂存记录的带版本、有界、带类型载荷。不使用 Java 序列化、
 * 反射类型加载或隐式 JSON 数值及日期转换。它保证完整性，不提供加密。
 */
public final class AtomicBatchCodec {

    private static final int MAGIC = 0x44424131;

    private static final int MAX_BINARY = 17 * 1024 * 1024;

    private static final int MAX_CELL = 8 * 1024 * 1024;

    /**
     * 初始化 {@code AtomicBatchCodec}。
     */
    private AtomicBatchCodec() {
    }

    /**
     * 将当前数据编码为字符串。
     */
    public static String encode(AtomicBatchRequest request) {
        Objects.requireNonNull(request);
        try {
            var buffer = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(buffer)) {
                out.writeInt(MAGIC);
                out.writeInt(1);
                AtomicBatchTarget target = request.target();
                for (String text : List.of(target.schema(), target.table(), target.batchColumn(), target.rowColumn(),
                        target.statusColumn(), request.key().scope(), request.key().batchKey(),
                        request.payloadHash())) {
                    text(out, text);
                }
                out.writeInt(request.fields().size());
                for (String field : request.fields()) {
                    text(out, field);
                }
                out.writeInt(request.rowCount());
                for (Object[] row : request.rows()) {
                    for (Object value : row) {
                        writeValue(out, value);
                    }
                }
            }
            if (buffer.size() > MAX_BINARY) {
                throw invalid();
            }
            return Base64.getEncoder().encodeToString(buffer.toByteArray());
        } catch (IOException failure) {
            throw invalid();
        }
    }

    /**
     * 从编码数据恢复原子批次请求。
     */
    public static AtomicBatchRequest decode(String encoded) {
        if (encoded == null || encoded.length() > 4 * ((MAX_BINARY + 2) / 3)) {
            throw invalid();
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            if (bytes.length > MAX_BINARY) {
                throw invalid();
            }
            try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
                if (in.readInt() != MAGIC || in.readInt() != 1) {
                    throw invalid();
                }
                var target = new AtomicBatchTarget(text(in, 63), text(in, 63), text(in, 63), text(in, 63),
                        text(in, 63));
                var key = new AtomicBatchKey(text(in, 1024), text(in, 1024));
                String expectedHash = text(in, 64);
                int fieldCount = count(in, 256);
                if (fieldCount == 0) {
                    throw invalid();
                }
                var fields = new ArrayList<String>(fieldCount);
                for (int i = 0; i < fieldCount; i++) {
                    fields.add(text(in, 63));
                }
                AtomicBatchRequest.checkedFields(fields);
                int rowCount = count(in, 5000);
                var rows = new ArrayList<Object[]>(rowCount);
                for (int i = 0; i < rowCount; i++) {
                    var row = new Object[fieldCount];
                    for (int j = 0; j < fieldCount; j++) {
                        row[j] = readValue(in);
                    }
                    rows.add(row);
                }
                if (in.available() != 0) {
                    throw invalid();
                }
                var request = AtomicBatchRequest.create(target, key, fields, rows);
                if (!request.payloadHash().equals(expectedHash)) {
                    throw invalid();
                }
                return request;
            }
        } catch (IOException | RuntimeException failure) {
            throw invalid();
        }
    }

    /**
     * 写入值。
     */
    private static void writeValue(DataOutputStream out, Object value) throws IOException {
        if (value == null) {
            out.writeByte(0);
            return;
        }
        if (value instanceof String text) {
            out.writeByte(1);
            text(out, text);
        } else if (value instanceof Long number) {
            out.writeByte(2);
            out.writeLong(number);
        } else if (value instanceof BigDecimal decimal) {
            out.writeByte(3);
            text(out, decimal.toString());
        } else if (value instanceof Boolean bool) {
            out.writeByte(4);
            out.writeBoolean(bool);
        } else if (value instanceof byte[] bytes) {
            out.writeByte(5);
            out.writeInt(bytes.length);
            out.write(bytes);
        } else if (value instanceof UUID uuid) {
            out.writeByte(6);
            out.writeLong(uuid.getMostSignificantBits());
            out.writeLong(uuid.getLeastSignificantBits());
        } else if (value instanceof LocalDate date) {
            out.writeByte(7);
            text(out, date.toString());
        } else if (value instanceof LocalTime time) {
            out.writeByte(8);
            text(out, time.toString());
        } else if (value instanceof LocalDateTime time) {
            out.writeByte(9);
            text(out, time.toString());
        } else if (value instanceof OffsetDateTime time) {
            out.writeByte(10);
            text(out, time.toString());
        } else if (value instanceof OffsetTime time) {
            out.writeByte(11);
            text(out, time.toString());
        } else if (value instanceof Instant time) {
            out.writeByte(12);
            text(out, time.toString());
        } else {
            throw invalid();
        }
    }

    /**
     * 按当前类型约定读取单个值。
     */
    private static Object readValue(DataInputStream in) throws IOException {
        return switch (in.readUnsignedByte()) {
            case 0 -> null;
            case 1 -> text(in, MAX_CELL);
            case 2 -> in.readLong();
            case 3 -> new BigDecimal(text(in, MAX_CELL));
            case 4 -> {
                int bool = in.readUnsignedByte();
                if (bool > 1) {
                    throw invalid();
                }
                yield bool == 1;
            }
            case 5 -> data(in, MAX_CELL);
            case 6 -> new UUID(in.readLong(), in.readLong());
            case 7 -> LocalDate.parse(text(in, 64));
            case 8 -> LocalTime.parse(text(in, 64));
            case 9 -> LocalDateTime.parse(text(in, 128));
            case 10 -> OffsetDateTime.parse(text(in, 128));
            case 11 -> OffsetTime.parse(text(in, 64));
            case 12 -> Instant.parse(text(in, 128));
            default -> throw invalid();
        };
    }

    /**
     * 返回数量。
     */
    private static int count(DataInputStream in, int maximum) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > maximum) {
            throw invalid();
        }
        return count;
    }

    /**
     * 返回当前操作承载的数据。
     */
    private static byte[] data(DataInputStream in, int maximum) throws IOException {
        int length = count(in, maximum);
        if (length > in.available()) {
            throw invalid();
        }
        return in.readNBytes(length);
    }

    /**
     * 按当前行协议编码或解码文本。
     */
    private static String text(DataInputStream in, int maximum) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data(in, maximum))).toString();
    }

    /**
     * 按当前行协议编码或解码文本。
     */
    private static void text(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_CELL) {
            throw invalid();
        }
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid, unsupported or oversized atomic batch encoding");
    }
}
