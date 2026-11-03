package cn.iocoder.yudao.framework.dbswitch.dbsynch.recovery;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import cn.iocoder.yudao.framework.dbswitch.common.util.BoundedRowCodec;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTableDefinition;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 冻结且有界的整表映像。此机制刻意与 TD 的严格子集材料分开。
 * 清单为每页排序并计算哈希。产品在构造前校验原生键相等性和角色；
 * 本层还拒绝重复、缺失、NULL 键及存在别名引用的可变行。 */
public final class TableRewriteMaterial {

    public enum Image {BEFORE, AFTER}

    public static final int MAX_MANIFEST_BYTES = 2 * 1024 * 1024;

    private static final int MAGIC = 0x54525731, PAGE_MAGIC = 0x54525031;

    public static final class Page {

        private final Image image;

        private final int ordinal, rows;

        private final byte[] encoded;

        private final String hash;

        /**
         * 初始化 {@code Page}。
         */
        private Page(Image image, int ordinal, int rows, byte[] encoded) {
            this.image = image;
            this.ordinal = ordinal;
            this.rows = rows;
            this.encoded = encoded.clone();
            this.hash = GroupRewriteMaterial.sha256(encoded);
        }

        /**
         * 返回镜像。
         */
        public Image image() {
            return image;
        }

        /**
         * 返回列序号。
         */
        public int ordinal() {
            return ordinal;
        }

        /**
         * 返回数据行。
         */
        public int rows() {
            return rows;
        }

        /**
         * 返回当前编码载荷的防御性副本。
         */
        public byte[] encoded() {
            return encoded.clone();
        }

        /**
         * 返回字节数组。
         */
        public int bytes() {
            return encoded.length;
        }

        /**
         * 返回哈希。
         */
        public String hash() {
            return hash;
        }

        /**
         * 返回当前对象的文本表示。
         */
        @Override
        public String toString() {
            return "TableRewritePage[image=" + image + ",ordinal=" + ordinal + ",rows=" + rows + "]";
        }
    }

    private final String binding, schema, table;

    private final TableRewriteIdentity oldIdentity;

    private final NativeTableDefinition definition;

    private final List<String> keys;

    private final List<Page> pages;

    private final byte[] manifest;

    private final String hash;

    /**
     * 校验构造参数并初始化 {@code TableRewriteMaterial}。
     */
    private TableRewriteMaterial(String binding, String schema, String table, TableRewriteIdentity identity,
            NativeTableDefinition definition, List<String> keys, Iterable<Object[]> before, Iterable<Object[]> after,
            TableRewriteLimits limits) {
        GroupRewriteKey.fingerprint(binding);
        this.binding = binding;
        this.schema = name(schema);
        this.table = name(table);
        this.oldIdentity = Objects.requireNonNull(identity);
        this.definition = Objects.requireNonNull(definition);
        var fields = names(definition.fields());
        this.keys = names(keys);
        if (!fields.containsAll(this.keys) || definition.format().length() > 128
                || definition.document().getBytes(StandardCharsets.UTF_8).length > 1024 * 1024
                || !definition.document()
                .equals(new String(definition.document().getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8))) {
            throw invalid();
        }
        var built = new ArrayList<Page>();
        long[] bytes = {0};
        int[] rows = {0};
        paginate(Image.BEFORE, before, fields, built, bytes, rows, limits);
        paginate(Image.AFTER, after, fields, built, bytes, rows, limits);
        this.pages = List.copyOf(built);
        this.manifest = encodeManifest();
        if (manifest.length > MAX_MANIFEST_BYTES || manifest.length > limits.maxMaterialBytes() - bytes[0]) {
            throw invalid();
        }
        this.hash = GroupRewriteMaterial.sha256(manifest);
    }

    /**
     * 根据给定参数创建 {@code TableRewriteMaterial} 实例。
     */
    public static TableRewriteMaterial create(String binding, String schema, String table,
            TableRewriteIdentity identity,
            NativeTableDefinition definition, List<String> keys, Iterable<Object[]> before, Iterable<Object[]> after,
            TableRewriteLimits limits) {
        return new TableRewriteMaterial(binding, schema, table, identity, definition, keys,
                Objects.requireNonNull(before), Objects.requireNonNull(after), Objects.requireNonNull(limits));
    }

    /**
     * 返回绑定信息。
     */
    public String bindingFingerprint() {
        return binding;
    }

    /**
     * 返回模式。
     */
    public String schema() {
        return schema;
    }

    /**
     * 返回表。
     */
    public String table() {
        return table;
    }

    /**
     * 返回旧标识。
     */
    public TableRewriteIdentity oldIdentity() {
        return oldIdentity;
    }

    /**
     * 返回定义。
     */
    public NativeTableDefinition definition() {
        return definition;
    }

    /**
     * 返回字段集合。
     */
    public List<String> fields() {
        return definition.fields();
    }

    /**
     * 返回键集合。
     */
    public List<String> keys() {
        return keys;
    }

    /**
     * 返回分页集合。
     */
    public List<Page> pages() {
        return pages;
    }

    /**
     * 返回当前清单。
     */
    public byte[] manifest() {
        return manifest.clone();
    }

    /**
     * 返回哈希。
     */
    public String hash() {
        return hash;
    }

    /**
     * 返回数据行。
     */
    public long rows(Image image) {
        return pages.stream().filter(p -> p.image == image).mapToLong(Page::rows).sum();
    }

    /**
     * 返回数据行。
     */
    public List<Object[]> rows(Page page, TableRewriteLimits limits) {
        if (!pages.contains(page)) {
            throw invalid();
        }
        return decodePage(page.encoded, fields().size(), limits);
    }

    /**
     * 按当前产品语法为查询添加分页范围。
     */
    private void paginate(Image image, Iterable<Object[]> input, List<String> fields, List<Page> output,
            long[] totalBytes, int[] totalRows, TableRewriteLimits limits) {
        var codec = new BoundedRowCodec(fields.size(), limits.page().maxBatchBytes());
        var keyCodec = new BoundedRowCodec(keys.size(), limits.page().maxBatchBytes());
        int[] positions = keys.stream().mapToInt(fields::indexOf).toArray();
        var unique = new HashSet<ByteBuffer>();
        var frames = new ArrayList<byte[]>();
        int bytes = 8, ordinal = 0;
        for (Object[] row : input) {
            if (++totalRows[0] > limits.maxRows()) {
                throw invalid();
            }
            var owned = BatchValueConverter.convert(Collections.singletonList(row), fields.size(), limits.page())
                    .get(0);
            byte[] encoded = codec.encode(owned);
            var key = new Object[positions.length];
            for (int i = 0; i < positions.length; i++) {
                Object value = owned[positions[i]];
                if (value == null || value == cn.iocoder.yudao.framework.dbswitch.common.entity.MissingCellValue.INSTANCE) {
                    throw invalid();
                }
                key[i] = GroupRewriteMaterial.canonicalKey(value);
            }
            if (!unique.add(ByteBuffer.wrap(keyCodec.encode(key)))) {
                throw invalid();
            }
            if (encoded.length > limits.page().maxBatchBytes() - 12) {
                throw invalid();
            }
            if (!frames.isEmpty() && (frames.size() == limits.page().maxRows() || encoded.length + 4 > limits.page()
                    .maxBatchBytes() - bytes)) {
                addPage(image, ordinal++, frames, output, totalBytes, limits);
                frames.clear();
                bytes = 8;
            }
            frames.add(encoded);
            bytes = Math.addExact(bytes, Math.addExact(4, encoded.length));
        }
        if (!frames.isEmpty()) {
            addPage(image, ordinal, frames, output, totalBytes, limits);
        }
    }

    /**
     * 添加分页。
     */
    private static void addPage(Image image, int ordinal, List<byte[]> frames, List<Page> output, long[] totalBytes,
            TableRewriteLimits limits) {
        if (output.size() >= limits.maxPages()) {
            throw invalid();
        }
        try (var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes)) {
            out.writeInt(PAGE_MAGIC);
            out.writeInt(frames.size());
            for (byte[] frame : frames) {
                out.writeInt(frame.length);
                out.write(frame);
            }
            out.flush();
            byte[] encoded = bytes.toByteArray();
            totalBytes[0] = Math.addExact(totalBytes[0], encoded.length);
            if (encoded.length > limits.page().maxBatchBytes() || totalBytes[0] > limits.maxMaterialBytes()) {
                throw invalid();
            }
            output.add(new Page(image, ordinal, frames.size(), encoded));
        } catch (IOException failure) {
            throw invalid();
        }
    }

    /**
     * 编码清单。
     */
    private byte[] encodeManifest() {
        try (var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);
            out.writeUTF(binding);
            out.writeUTF(schema);
            out.writeUTF(table);
            out.writeUTF(oldIdentity.format());
            text(out, oldIdentity.document());
            out.writeUTF(definition.product().name());
            out.writeUTF(definition.format());
            text(out, definition.document());
            writeNames(out, fields());
            writeNames(out, keys);
            out.writeInt(pages.size());
            for (Page page : pages) {
                out.writeByte(page.image.ordinal());
                out.writeInt(page.ordinal);
                out.writeInt(page.rows);
                out.writeInt(page.bytes());
                out.writeUTF(page.hash);
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException failure) {
            throw invalid();
        }
    }

    /** 存储按清单顺序提供页面；交换、丢弃、注入或截断页面均默认拒绝。 */
    public static TableRewriteMaterial decode(byte[] manifest, List<byte[]> payloads, TableRewriteLimits limits) {
        if (manifest == null || manifest.length > MAX_MANIFEST_BYTES || payloads == null || payloads.size() > limits.maxPages()) {
            throw invalid();
        }
        try (var in = new DataInputStream(new ByteArrayInputStream(manifest))) {
            if (in.readInt() != MAGIC) {
                throw invalid();
            }
            String binding = in.readUTF(), schema = in.readUTF(), table = in.readUTF();
            var identity = new TableRewriteIdentity(in.readUTF(), text(in, 16384));
            var product = ProductTypeEnum.valueOf(in.readUTF());
            String format = in.readUTF(), document = text(in, 1024 * 1024);
            var fields = readNames(in);
            var keys = readNames(in);
            int count = in.readInt();
            if (count != payloads.size() || count < 0 || count > limits.maxPages()) {
                throw invalid();
            }
            var before = new ArrayList<Object[]>();
            var after = new ArrayList<Object[]>();
            int[] ordinals = {0, 0};
            int previous = 0;
            long bytes = manifest.length;
            for (int i = 0; i < count; i++) {
                int image = in.readUnsignedByte(), ordinal = in.readInt(), rows = in.readInt(), length = in.readInt();
                String hash = in.readUTF();
                if (image > 1 || image < previous || ordinal != ordinals[image]++ || rows < 1 || rows > limits.page()
                        .maxRows()) {
                    throw invalid();
                }
                previous = image;
                byte[] page = Objects.requireNonNull(payloads.get(i));
                bytes = Math.addExact(bytes, page.length);
                if (page.length != length || page.length > limits.page()
                        .maxBatchBytes() || bytes > limits.maxMaterialBytes()
                        || !GroupRewriteMaterial.sha256(page).equals(hash)) {
                    throw invalid();
                }
                var decoded = decodePage(page, fields.size(), limits);
                if (decoded.size() != rows) {
                    throw invalid();
                }
                (image == 0 ? before : after).addAll(decoded);
                if ((long) before.size() + after.size() > limits.maxRows()) {
                    throw invalid();
                }
            }
            if (in.available() != 0) {
                throw invalid();
            }
            var material = create(binding, schema, table, identity,
                    new NativeTableDefinition(product, format, document, fields), keys, before, after, limits);
            if (!Arrays.equals(manifest, material.manifest)) {
                throw invalid();
            }
            return material;
        } catch (IOException | RuntimeException failure) {
            throw invalid();
        }
    }

    /**
     * 解码分页。
     */
    private static List<Object[]> decodePage(byte[] bytes, int fields, TableRewriteLimits limits) {
        if (bytes.length > limits.page().maxBatchBytes()) {
            throw invalid();
        }
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != PAGE_MAGIC) {
                throw invalid();
            }
            int count = in.readInt();
            if (count < 1 || count > limits.page().maxRows() || count > in.available() / 4) {
                throw invalid();
            }
            var codec = new BoundedRowCodec(fields, limits.page().maxBatchBytes());
            var rows = new ArrayList<Object[]>();
            for (int i = 0; i < count; i++) {
                int length = in.readInt();
                if (length < 1 || length > in.available()) {
                    throw invalid();
                }
                rows.add(codec.decode(in.readNBytes(length)));
            }
            if (in.available() != 0) {
                throw invalid();
            }
            return rows;
        } catch (IOException | RuntimeException failure) {
            throw invalid();
        }
    }

    /**
     * 校验当前模式名与对象名符合原生标识符约定。
     */
    private static List<String> names(List<String> input) {
        var result = List.copyOf(input);
        if (result.isEmpty() || result.size() > 4096 || new HashSet<>(result).size() != result.size()) {
            throw invalid();
        }
        result.forEach(TableRewriteMaterial::name);
        return result;
    }

    /**
     * 返回名称。
     */
    private static String name(String name) {
        if (name == null || name.isBlank() || name.length() > 1024 || name.chars()
                .anyMatch(Character::isISOControl) || !name.equals(
                new String(name.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8))) {
            throw invalid();
        }
        return name;
    }

    /**
     * 写入名称集合。
     */
    private static void writeNames(DataOutputStream out, List<String> names) throws IOException {
        out.writeInt(names.size());
        for (String name : names) {
            out.writeUTF(name);
        }
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
        for (int i = 0; i < count; i++) {
            names.add(in.readUTF());
        }
        return names(names);
    }

    /**
     * 按当前值域约定解析或转换文本。
     */
    private static void text(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    /**
     * 按当前值域约定解析或转换文本。
     */
    private static String text(DataInputStream in, int max) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > max || length > in.available()) {
            throw invalid();
        }
        return new String(in.readNBytes(length), StandardCharsets.UTF_8);
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid, altered or oversized table recovery material");
    }

    /**
     * 返回当前对象的文本表示。
     */
    @Override
    public String toString() {
        return "TableRewriteMaterial[pages=" + pages.size() + ",before=" + rows(Image.BEFORE) + ",after=" + rows(
                Image.AFTER) + "]";
    }
}
