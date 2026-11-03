package cn.iocoder.yudao.framework.etl.core.script;

import java.io.*;

import cn.iocoder.yudao.framework.dbswitch.common.util.BoundedRowCodec;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.util.*;

/** 有界且类型明确的 IPC；不使用 Java 反序列化、类名或调用方选定的可执行参数。 */
final class EtlScriptProtocol {

    static final int INIT = 1, BATCH = 2, STOP = 3;

    static final int OK = 0, FAILED = 1, CHECK_ERRORS = 2;

    private static final int MAX_DEPTH = 16;

    /**
     * 初始化 {@code EtlScriptProtocol}。
     */
    private EtlScriptProtocol() {
    }

    @FunctionalInterface
    interface Encoder {

        /**
         * 检查脚本 IPC 字节预算后将数据写入缓冲区。
         */
        void write(DataOutputStream out) throws IOException;
    }

    /**
     * 将当前数据编码为字节。
     */
    static byte[] encode(int maximum, Encoder encoder) throws IOException {
        var bytes = new BoundedBytes(maximum);
        try (var output = new DataOutputStream(bytes)) {
            encoder.write(output);
        }
        return bytes.toByteArray();
    }

    /**
     * 写入帧。
     */
    static void writeFrame(OutputStream stream, byte[] bytes) throws IOException {
        var out = new DataOutputStream(stream);
        out.writeInt(bytes.length);
        out.write(bytes);
        out.flush();
    }

    /**
     * 读取帧。
     */
    static DataInputStream readFrame(InputStream stream, int maximum) throws IOException {
        var in = new DataInputStream(stream);
        int length = in.readInt();
        if (length < 1 || length > maximum) {
            throw new IOException("Invalid script frame size");
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new DataInputStream(new ByteArrayInputStream(bytes));
    }

    /**
     * 写入文本。
     */
    static void writeText(DataOutputStream out, String text, int maximum) throws IOException {
        if (text == null || text.length() > maximum) {
            throw new IOException("Invalid script text size");
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maximum) {
            throw new IOException("Invalid script text size");
        }
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    /**
     * 读取文本。
     */
    static String readText(DataInputStream in, int maximum) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > maximum || length > in.available()) {
            throw new IOException("Invalid script text size");
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    /**
     * 写入数据行。
     */
    static void writeRows(DataOutputStream out, List<Map<String, Object>> rows, int maximumRows, int maximumBytes)
            throws IOException {
        if (rows == null) {
            rows = List.of();
        }
        if (rows.size() > maximumRows) {
            throw new IOException("Script row limit exceeded");
        }
        out.writeInt(rows.size());
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?>)) {
                throw new IOException("Script must return object rows");
            }
            writeValue(out, row, 0, maximumBytes);
        }
    }

    /**
     * 读取数据行。
     */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> readRows(DataInputStream in, int maximumRows,
            int maximumBytes) throws IOException {
        int count = readCount(in, maximumRows);
        List<Map<String, Object>> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Object value = readValue(in, 0, maximumBytes);
            if (!(value instanceof Map<?, ?>)) {
                throw new IOException("Script must return object rows");
            }
            rows.add((Map<String, Object>) value);
        }
        return rows;
    }

    /**
     * 写入值。
     */
    private static void writeValue(DataOutputStream out, Object value, int depth, int maximumBytes) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("Script value nesting limit exceeded");
        }
        if (value instanceof List<?> list) {
            if (list.size() > 10_000) {
                throw new IOException("Script collection limit exceeded");
            }
            out.writeByte(3);
            out.writeInt(list.size());
            for (Object item : list) {
                writeValue(out, item, depth + 1, maximumBytes);
            }
        } else if (value instanceof Map<?, ?> map) {
            if (map.size() > 4096) {
                throw new IOException("Script field limit exceeded");
            }
            out.writeByte(4);
            out.writeInt(map.size());
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IOException("Script field name must be a string");
                }
                writeText(out, key, 4096);
                writeValue(out, entry.getValue(), depth + 1, maximumBytes);
            }
        } else if (value != null && value.getClass() == Date.class) {
            out.writeByte(2);
            out.writeLong(((Date) value).getTime());
        } else {
            // 复用 DB Switch 已验证的标量/原生编解码器。尤其不得将 Long、小数或时间强制转换为 JSON。
            byte[] bytes = new BoundedRowCodec(1, maximumBytes).encode(new Object[]{value});
            out.writeByte(1);
            out.writeInt(bytes.length);
            out.write(bytes);
        }
    }

    /**
     * 按当前类型约定读取单个值。
     */
    private static Object readValue(DataInputStream in, int depth, int maximumBytes) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("Script value nesting limit exceeded");
        }
        try {
            return switch (in.readUnsignedByte()) {
                case 1 -> {
                    int length = readCount(in, maximumBytes);
                    byte[] bytes = new byte[length];
                    in.readFully(bytes);
                    yield new BoundedRowCodec(1, maximumBytes).decode(bytes)[0];
                }
                case 2 -> new Date(in.readLong());
                case 3 -> {
                    int count = readCount(in, 10_000);
                    List<Object> list = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        list.add(readValue(in, depth + 1, maximumBytes));
                    }
                    yield list;
                }
                case 4 -> {
                    int count = readCount(in, 4096);
                    Map<String, Object> map = new LinkedHashMap<>();
                    for (int i = 0; i < count; i++) {
                        String key = readText(in, 4096);
                        if (map.containsKey(key)) {
                            throw new IOException("Duplicate script field");
                        }
                        map.put(key, readValue(in, depth + 1, maximumBytes));
                    }
                    yield map;
                }
                default -> throw new IOException("Unsupported script value type");
            };
        } catch (RuntimeException exception) {
            throw new IOException("Invalid script value");
        }
    }

    /**
     * 读取数量。
     */
    private static int readCount(DataInputStream in, int maximum) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > maximum || count > in.available()) {
            throw new IOException("Invalid script collection size");
        }
        return count;
    }

    /**
     * 校验并取得终点。
     */
    static void requireEnd(DataInputStream in) throws IOException {
        if (in.available() != 0) {
            throw new IOException("Unexpected script frame content");
        }
    }

    private static final class BoundedBytes extends ByteArrayOutputStream {

        private final int maximum;

        /**
         * 初始化 {@code BoundedBytes}及其父类状态。
         */
        private BoundedBytes(int maximum) {
            super(Math.min(maximum, 4096));
            this.maximum = maximum;
        }

        /**
         * 检查脚本 IPC 字节预算后将数据写入缓冲区。
         */
        @Override
        public synchronized void write(int value) {
            if (count >= maximum) {
                throw new IllegalStateException("Script IPC byte limit exceeded");
            }
            super.write(value);
        }

        /**
         * 检查脚本 IPC 字节预算后将数据写入缓冲区。
         */
        @Override
        public synchronized void write(byte[] bytes, int offset, int length) {
            if (length > maximum - count) {
                throw new IllegalStateException("Script IPC byte limit exceeded");
            }
            super.write(bytes, offset, length);
        }
    }
}
