package cn.iocoder.yudao.framework.dbswitch.common.util;

import cn.iocoder.yudao.framework.dbswitch.common.entity.JdbcArrayValue;

import java.io.*;
import java.math.*;
import java.nio.*;
import java.nio.charset.*;
import java.time.*;
import java.util.*;

/** 带版本和大小上限的行格式，使用明确的标量或原生类型标记；不使用 Java 序列化或反射加载类。 */
public final class BoundedRowCodec {

    private final int columns;

    private final int maxBytes;

    /**
     * 初始化 {@code BoundedRowCodec}。
     */
    public BoundedRowCodec(int columns, long maxBytes) {
        if (columns < 1 || columns > 65535 || maxBytes < 1 || maxBytes > Integer.MAX_VALUE - 64L) {
            throw invalid();
        }
        this.columns = columns;
        this.maxBytes = (int) maxBytes;
    }

    /**
     * 将当前数据编码为字节。
     */
    public byte[] encode(Object[] row) {
        if (row == null || row.length != columns) {
            throw invalid();
        }
        try {
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(new FilterOutputStream(bytes) {
                private long written;

                /**
                 * 写入前累计字节占用，负值或超过单行上限时拒绝继续编码。
                 */
                private void reserve(int count) throws IOException {
                    if (count < 0 || count > maxBytes - written) {
                        throw new IOException("Row limit");
                    }
                    written += count;
                }

                /**
                 * 按行编码协议将当前值写入输出流。
                 */
                @Override
                public void write(int value) throws IOException {
                    reserve(1);
                    this.out.write(value);
                }

                /**
                 * 按行编码协议将当前值写入输出流。
                 */
                @Override
                public void write(byte[] b, int offset, int length) throws IOException {
                    reserve(length);
                    this.out.write(b, offset, length);
                }
            })) {
                out.writeInt(0x44465231);
                out.writeInt(columns);
                for (Object value : row) {
                    write(out, value, false);
                }
            }
            return bytes.toByteArray();
        } catch (IOException | RuntimeException failure) {
            throw invalid();
        }
    }

    /**
     * 从编码数据恢复对象。
     */
    public Object[] decode(byte[] bytes) {
        if (bytes == null || bytes.length > maxBytes) {
            throw invalid();
        }
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != 0x44465231 || in.readInt() != columns) {
                throw invalid();
            }
            var row = new Object[columns];
            for (int i = 0; i < columns; i++) {
                row[i] = read(in, false);
            }
            if (in.available() != 0) {
                throw invalid();
            }
            return row;
        } catch (IOException | RuntimeException failure) {
            throw invalid();
        }
    }

    /**
     * 按行编码协议将当前值写入输出流。
     */
    private void write(DataOutputStream out, Object value, boolean nested) throws IOException {
        if (value == null) {
            out.writeByte(0);
        } else if (value instanceof String v) {
            out.writeByte(1);
            text(out, v);
        } else if (value instanceof Byte v) {
            out.writeByte(2);
            out.writeByte(v);
        } else if (value instanceof Short v) {
            out.writeByte(3);
            out.writeShort(v);
        } else if (value instanceof Integer v) {
            out.writeByte(4);
            out.writeInt(v);
        } else if (value instanceof Long v) {
            out.writeByte(5);
            out.writeLong(v);
        } else if (value instanceof BigInteger v) {
            out.writeByte(6);
            binary(out, v.toByteArray());
        } else if (value instanceof BigDecimal v) {
            out.writeByte(7);
            out.writeInt(v.scale());
            binary(out, v.unscaledValue().toByteArray());
        } else if (value instanceof Float v) {
            if (!Float.isFinite(v)) {
                throw invalid();
            }
            out.writeByte(8);
            out.writeFloat(v);
        } else if (value instanceof Double v) {
            if (!Double.isFinite(v)) {
                throw invalid();
            }
            out.writeByte(9);
            out.writeDouble(v);
        } else if (value instanceof Boolean v) {
            out.writeByte(10);
            out.writeBoolean(v);
        } else if (value instanceof byte[] v) {
            out.writeByte(11);
            binary(out, v);
        } else if (value instanceof UUID v) {
            out.writeByte(12);
            out.writeLong(v.getMostSignificantBits());
            out.writeLong(v.getLeastSignificantBits());
        } else if (value instanceof java.sql.Timestamp v) {
            out.writeByte(13);
            out.writeLong(v.getTime());
            out.writeInt(v.getNanos());
        } else if (value instanceof java.sql.Date v) {
            out.writeByte(14);
            out.writeLong(v.getTime());
        } else if (value instanceof java.sql.Time v) {
            out.writeByte(15);
            out.writeLong(v.getTime());
        } else if (value instanceof LocalDate v) {
            out.writeByte(16);
            out.writeLong(v.toEpochDay());
        } else if (value instanceof LocalTime v) {
            out.writeByte(17);
            out.writeLong(v.toNanoOfDay());
        } else if (value instanceof LocalDateTime v) {
            out.writeByte(18);
            text(out, v.toString());
        } else if (value instanceof OffsetDateTime v) {
            out.writeByte(19);
            text(out, v.toString());
        } else if (value instanceof OffsetTime v) {
            out.writeByte(20);
            text(out, v.toString());
        } else if (value instanceof Instant v) {
            out.writeByte(21);
            out.writeLong(v.getEpochSecond());
            out.writeInt(v.getNano());
        } else if (value instanceof ZonedDateTime v) {
            out.writeByte(22);
            text(out, v.toString());
        } else if (value instanceof JdbcArrayValue v && !nested) {
            out.writeByte(23);
            text(out, v.baseTypeName());
            out.writeInt(v.baseJdbcType());
            out.writeInt(v.elements().size());
            for (Object element : v.elements()) {
                write(out, element, true);
            }
        } else if (value instanceof cn.iocoder.yudao.framework.dbswitch.common.entity.NativeCellValue v && !nested) {
            out.writeByte(24);
            text(out, v.format());
            binary(out, v.encoded());
            binary(out, v.hasMatchingKey() ? v.matchingKey() : new byte[0]);
        } else if (value == cn.iocoder.yudao.framework.dbswitch.common.entity.MissingCellValue.INSTANCE && !nested) {
            out.writeByte(25);
        } else {
            throw invalid();
        }
    }

    /**
     * 按行编码协议从输入流解码当前值。
     */
    private Object read(DataInputStream in, boolean nested) throws IOException {
        return switch (in.readUnsignedByte()) {
            case 0 -> null;
            case 1 -> text(in);
            case 2 -> in.readByte();
            case 3 -> in.readShort();
            case 4 -> in.readInt();
            case 5 -> in.readLong();
            case 6 -> new BigInteger(binary(in));
            case 7 -> {
                int scale = in.readInt();
                yield new BigDecimal(new BigInteger(binary(in)), scale);
            }
            case 8 -> {
                float v = in.readFloat();
                if (!Float.isFinite(v)) {
                    throw invalid();
                }
                yield v;
            }
            case 9 -> {
                double v = in.readDouble();
                if (!Double.isFinite(v)) {
                    throw invalid();
                }
                yield v;
            }
            case 10 -> {
                int v = in.readUnsignedByte();
                if (v > 1) {
                    throw invalid();
                }
                yield v == 1;
            }
            case 11 -> binary(in);
            case 12 -> new UUID(in.readLong(), in.readLong());
            case 13 -> {
                var v = new java.sql.Timestamp(in.readLong());
                v.setNanos(in.readInt());
                yield v;
            }
            case 14 -> new java.sql.Date(in.readLong());
            case 15 -> new java.sql.Time(in.readLong());
            case 16 -> LocalDate.ofEpochDay(in.readLong());
            case 17 -> LocalTime.ofNanoOfDay(in.readLong());
            case 18 -> LocalDateTime.parse(text(in));
            case 19 -> OffsetDateTime.parse(text(in));
            case 20 -> OffsetTime.parse(text(in));
            case 21 -> Instant.ofEpochSecond(in.readLong(), in.readInt());
            case 22 -> ZonedDateTime.parse(text(in));
            case 23 -> {
                if (nested) {
                    throw invalid();
                }
                String type = text(in);
                int jdbc = in.readInt();
                int count = length(in);
                if (count > in.available()) {
                    throw invalid();
                }
                var elements = new ArrayList<Object>(count);
                for (int i = 0; i < count; i++) {
                    elements.add(read(in, true));
                }
                yield new JdbcArrayValue(type, jdbc, elements);
            }
            case 24 -> {
                if (nested) {
                    throw invalid();
                }
                String format = text(in);
                byte[] payload = binary(in);
                byte[] key = binary(in);
                yield new cn.iocoder.yudao.framework.dbswitch.common.entity.NativeCellValue(format, payload,
                        key.length == 0 ? null : key);
            }
            case 25 -> {
                if (nested) {
                    throw invalid();
                }
                yield cn.iocoder.yudao.framework.dbswitch.common.entity.MissingCellValue.INSTANCE;
            }
            default -> throw invalid();
        };
    }

    /**
     * 按当前行协议编码或解码文本。
     */
    private void text(DataOutputStream out, String value) throws IOException {
        var encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value));
        if (encoded.remaining() > maxBytes) {
            throw invalid();
        }
        var bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        binary(out, bytes);
    }

    /**
     * 按当前行协议编码或解码文本。
     */
    private String text(DataInputStream in) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(binary(in))).toString();
    }

    /**
     * 按当前行协议编码或解码二进制。
     */
    private void binary(DataOutputStream out, byte[] value) throws IOException {
        if (value.length > maxBytes) {
            throw invalid();
        }
        out.writeInt(value.length);
        out.write(value);
    }

    /**
     * 按当前行协议编码或解码二进制。
     */
    private byte[] binary(DataInputStream in) throws IOException {
        int length = length(in);
        if (length > in.available()) {
            throw invalid();
        }
        return in.readNBytes(length);
    }

    /**
     * 返回长度。
     */
    private int length(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > maxBytes) {
            throw invalid();
        }
        return length;
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Unsupported, malformed or oversized difference row encoding");
    }
}

