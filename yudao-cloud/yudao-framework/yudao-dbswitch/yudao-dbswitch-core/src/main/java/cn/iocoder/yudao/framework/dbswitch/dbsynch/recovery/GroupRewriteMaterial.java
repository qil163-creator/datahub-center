package cn.iocoder.yudao.framework.dbswitch.dbsynch.recovery;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import cn.iocoder.yudao.framework.dbswitch.common.util.BoundedRowCodec;

import java.io.*;
import java.nio.ByteBuffer;
import java.security.*;
import java.util.*;

/** 不可变的前后行映像。后映像是严格且未修改的子集，所以恢复不能凭空生成更新。 */
public final class GroupRewriteMaterial {

    public static final int MAX_BYTES = 64 * 1024 * 1024;

    private static final int MAGIC = 0x47525731;

    private final String bindingFingerprint, physicalFingerprint, structureFingerprint, hash;

    private final List<String> fields, keys;

    private final List<byte[]> before, after;

    private final byte[] encoded;

    /**
     * 初始化 {@code GroupRewriteMaterial}。
     */
    private GroupRewriteMaterial(String binding, String physical, String structure, List<String> fields,
            List<String> keys,
            List<Object[]> beforeRows, List<Object[]> afterRows, BatchLimits limits) {
        GroupRewriteKey.fingerprint(binding);
        GroupRewriteKey.fingerprint(physical);
        GroupRewriteKey.fingerprint(structure);
        this.bindingFingerprint = binding;
        this.physicalFingerprint = physical;
        this.structureFingerprint = structure;
        this.fields = names(fields);
        this.keys = names(keys);
        if (!this.fields.containsAll(this.keys) || beforeRows.isEmpty() || afterRows.size() >= beforeRows.size()) {
            throw invalid();
        }
        var codec = new BoundedRowCodec(fields.size(), Math.min(MAX_BYTES, limits.maxBatchBytes()));
        this.before = frames(beforeRows, codec, limits);
        this.after = frames(afterRows, codec, limits);
        var positions = this.keys.stream().mapToInt(this.fields::indexOf).toArray();
        var keyCodec = new BoundedRowCodec(keys.size(), Math.min(MAX_BYTES, limits.maxBatchBytes()));
        var retained = new HashMap<ByteBuffer, byte[]>();
        for (byte[] frame : this.before) {
            var row = codec.decode(frame);
            var identity = new Object[positions.length];
            for (int index = 0; index < positions.length; index++) {
                identity[index] = canonicalKey(row[positions[index]]);
                if (identity[index] == null || identity[index] == cn.iocoder.yudao.framework.dbswitch.common.entity.MissingCellValue.INSTANCE
                        || identity[index] instanceof cn.iocoder.yudao.framework.dbswitch.common.entity.NativeCellValue nativeValue && !nativeValue.hasMatchingKey()) {
                    throw invalid();
                }
            }
            if (retained.put(ByteBuffer.wrap(keyCodec.encode(identity)), frame) != null) {
                throw invalid();
            }
        }
        for (byte[] frame : this.after) {
            var row = codec.decode(frame);
            var identity = new Object[positions.length];
            for (int index = 0; index < positions.length; index++) {
                identity[index] = canonicalKey(row[positions[index]]);
            }
            if (!Arrays.equals(retained.remove(ByteBuffer.wrap(keyCodec.encode(identity))), frame)) {
                throw invalid();
            }
        }
        this.encoded = encode(Math.min(MAX_BYTES, limits.maxBatchBytes()));
        this.hash = sha256(this.encoded);
    }

    /**
     * 根据给定参数创建 {@code GroupRewriteMaterial} 实例。
     */
    public static GroupRewriteMaterial create(String binding, String physical, String structure, List<String> fields,
            List<String> keys, List<Object[]> before, List<Object[]> after, BatchLimits limits) {
        Objects.requireNonNull(limits);
        Objects.requireNonNull(before);
        Objects.requireNonNull(after);
        return new GroupRewriteMaterial(binding, physical, structure, fields, keys, before, after, limits);
    }

    /**
     * 返回绑定指纹。
     */
    public String bindingFingerprint() {
        return bindingFingerprint;
    }

    /**
     * 返回物理结构指纹。
     */
    public String physicalFingerprint() {
        return physicalFingerprint;
    }

    /**
     * 返回结构指纹。
     */
    public String structureFingerprint() {
        return structureFingerprint;
    }

    /**
     * 返回哈希。
     */
    public String hash() {
        return hash;
    }

    /**
     * 返回字段集合。
     */
    public List<String> fields() {
        return fields;
    }

    /**
     * 返回键集合。
     */
    public List<String> keys() {
        return keys;
    }

    /**
     * 返回重写前材料包含的数据行数。
     */
    public int beforeCount() {
        return before.size();
    }

    /**
     * 返回重写后材料包含的数据行数。
     */
    public int afterCount() {
        return after.size();
    }

    /**
     * 返回当前重写材料中逻辑删除的行数。
     */
    public int logicalDeletes() {
        return before.size() - after.size();
    }

    /**
     * 返回当前编码载荷的防御性副本。
     */
    public byte[] encoded() {
        return encoded.clone();
    }

    /**
     * 返回当前之前。
     */
    public List<Object[]> before() {
        return decodeRows(before);
    }

    /**
     * 返回当前之后。
     */
    public List<Object[]> after() {
        return decodeRows(after);
    }

    /**
     * 解码数据行。
     */
    private List<Object[]> decodeRows(List<byte[]> frames) {
        var codec = new BoundedRowCodec(fields.size(), MAX_BYTES);
        return frames.stream().map(codec::decode).toList();
    }

    /**
     * 按协议读取或生成数据交换帧。
     */
    private List<byte[]> frames(List<Object[]> rows, BoundedRowCodec codec, BatchLimits limits) {
        var converted = BatchValueConverter.convert(rows, fields.size(), limits);
        var result = new ArrayList<byte[]>();
        long bytes = 0;
        for (var row : converted) {
            byte[] frame = codec.encode(row);
            bytes = Math.addExact(bytes, frame.length);
            if (bytes > Math.min(MAX_BYTES, limits.maxBatchBytes())) {
                throw invalid();
            }
            result.add(frame);
        }
        return List.copyOf(result);
    }

    /**
     * 将当前数据编码为字节。
     */
    private byte[] encode(long maximum) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(new FilterOutputStream(bytes) {
                long count;

                /**
                 * 累计重写恢复材料的编码字节数，拒绝负值或超过材料大小上限。
                 */
                void reserve(int amount) throws IOException {
                    if (amount < 0 || amount > maximum - count) {
                        throw new IOException("Material limit");
                    }
                    count += amount;
                }

                /**
                 * 在材料字节预算检查后向底层输出流写入当前数据。
                 */
                @Override
                public void write(int value) throws IOException {
                    reserve(1);
                    this.out.write(value);
                }

                /**
                 * 在材料字节预算检查后向底层输出流写入当前数据。
                 */
                @Override
                public void write(byte[] value, int offset, int length) throws IOException {
                    reserve(length);
                    this.out.write(value, offset, length);
                }
            })) {
                out.writeInt(MAGIC);
                out.writeUTF(bindingFingerprint);
                out.writeUTF(physicalFingerprint);
                out.writeUTF(structureFingerprint);
                out.writeInt(fields.size());
                for (String name : fields) {
                    out.writeUTF(name);
                }
                out.writeInt(keys.size());
                for (String name : keys) {
                    out.writeUTF(name);
                }
                write(out, before);
                write(out, after);
            }
            return bytes.toByteArray();
        } catch (IOException | RuntimeException failure) {
            throw invalid();
        }
    }

    /**
     * 在材料字节预算检查后向底层输出流写入当前数据。
     */
    private static void write(DataOutputStream out, List<byte[]> rows) throws IOException {
        out.writeInt(rows.size());
        for (byte[] row : rows) {
            out.writeInt(row.length);
            out.write(row);
        }
    }

    /**
     * 从编码数据恢复分组重写材料。
     */
    public static GroupRewriteMaterial decode(byte[] bytes, BatchLimits limits) {
        if (bytes == null || bytes.length > Math.min(MAX_BYTES, limits.maxBatchBytes())) {
            throw invalid();
        }
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC) {
                throw invalid();
            }
            String binding = in.readUTF(), physical = in.readUTF(), structure = in.readUTF();
            var fields = readNames(in);
            var keys = readNames(in);
            var codec = new BoundedRowCodec(fields.size(), Math.min(MAX_BYTES, limits.maxBatchBytes()));
            var before = read(in, codec, limits);
            var after = read(in, codec, limits);
            if (in.available() != 0) {
                throw invalid();
            }
            var material = create(binding, physical, structure, fields, keys, before, after, limits);
            if (!Arrays.equals(bytes, material.encoded)) {
                throw invalid();
            }
            return material;
        } catch (IOException | RuntimeException failure) {
            throw invalid();
        }
    }

    /**
     * 读取当前输入中的数据并按约定结构返回。
     */
    private static List<Object[]> read(DataInputStream in, BoundedRowCodec codec,
            BatchLimits limits) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > limits.maxRows() || count > in.available() / 4) {
            throw invalid();
        }
        var rows = new ArrayList<Object[]>(count);
        for (int index = 0; index < count; index++) {
            int length = in.readInt();
            if (length < 1 || length > in.available() || length > Math.min(MAX_BYTES, limits.maxBatchBytes())) {
                throw invalid();
            }
            rows.add(codec.decode(in.readNBytes(length)));
        }
        return rows;
    }

    /**
     * 读取名称集合。
     */
    private static List<String> readNames(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 1 || count > 4096 || count > in.available() / 2) {
            throw invalid();
        }
        var names = new ArrayList<String>();
        for (int index = 0; index < count; index++) {
            names.add(in.readUTF());
        }
        return names(names);
    }

    /**
     * 校验当前模式名与对象名符合原生标识符约定。
     */
    private static List<String> names(List<String> values) {
        var names = List.copyOf(values);
        if (names.isEmpty() || names.size() > 4096 || new HashSet<>(names).size() != names.size()) {
            throw invalid();
        }
        for (String name : names) {
            if (name.isBlank() || name.length() > 1024 || name.chars().anyMatch(Character::isISOControl)) {
                throw invalid();
            }
        }
        return names;
    }

    /**
     * 计算输入内容的 SHA-256 摘要。
     */
    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** 匹配标识独立于所保留的原生载荷和十进制表示。 */
    public static Object canonicalKey(Object value) {
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof java.time.OffsetDateTime timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof cn.iocoder.yudao.framework.dbswitch.common.entity.NativeCellValue nativeValue) {
            if (!nativeValue.hasMatchingKey()) {
                throw invalid();
            }
            return new cn.iocoder.yudao.framework.dbswitch.common.entity.NativeCellValue(nativeValue.format(),
                    nativeValue.matchingKey(), nativeValue.matchingKey());
        }
        if (value instanceof java.math.BigDecimal decimal) {
            return decimal.stripTrailingZeros();
        }
        if (value instanceof java.math.BigInteger integer) {
            return new java.math.BigDecimal(integer).stripTrailingZeros();
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return java.math.BigDecimal.valueOf(((Number) value).longValue()).stripTrailingZeros();
        }
        return value;
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid or oversized group recovery material");
    }

    /**
     * 返回当前对象的文本表示。
     */
    @Override
    public String toString() {
        return "GroupRewriteMaterial[before=" + beforeCount() + ",after=" + afterCount() + ",bytes=" + encoded.length + "]";
    }
}
