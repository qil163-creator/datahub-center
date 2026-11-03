package cn.iocoder.yudao.framework.dbswitch.dbwriter.atomic;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;

import java.io.DataOutputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/**
 * 有界冻结标量载荷，采用带版本、带类型的 SHA-256 编码。列与行顺序均有意义。
 * 驱动对象、SQL 绑定回调、表达式、可变映射或任意 toString 值均不能进入计划。
 */
public final class AtomicBatchRequest {

    private final AtomicBatchTarget target;

    private final AtomicBatchKey key;

    private final List<String> fields;

    private final List<Object[]> rows;

    private final String payloadHash;

    /**
     * 校验构造参数并初始化 {@code AtomicBatchRequest}。
     */
    private AtomicBatchRequest(AtomicBatchTarget target, AtomicBatchKey key, List<String> fields, List<Object[]> rows) {
        this.target = Objects.requireNonNull(target);
        this.key = Objects.requireNonNull(key);
        this.fields = checkedFields(fields);
        var limits = BatchLimits.defaults();
        limits.requireStatementParameters(fields.size());
        this.rows = List.copyOf(BatchValueConverter.convert(rows, fields.size(), limits,
                (value, column) -> freeze(value)));
        int batch = fields.indexOf(target.batchColumn()), ordinal = fields.indexOf(target.rowColumn()),
                status = fields.indexOf(target.statusColumn());
        if (batch < 0 || ordinal < 0 || status < 0) {
            throw new IllegalArgumentException("The payload must explicitly include all technical columns");
        }
        var ordinals = new HashSet<Long>();
        for (Object[] row : this.rows) {
            if (!key.batchKey().equals(row[batch]) || !(row[ordinal] instanceof Long number)
                    || number < 1 || !ordinals.add(number)
                    || !(row[status] instanceof String text) || text.isBlank() || text.length() > 64) {
                throw new IllegalArgumentException("Invalid batch identity, row ordinal or row status");
            }
        }
        payloadHash = digest();
    }

    /**
     * 根据给定参数创建 {@code AtomicBatchRequest} 实例。
     */
    public static AtomicBatchRequest create(AtomicBatchTarget target, AtomicBatchKey key,
            List<String> fields, List<Object[]> rows) {
        return new AtomicBatchRequest(target, key, fields, rows);
    }

    /**
     * 返回目标端。
     */
    public AtomicBatchTarget target() {
        return target;
    }

    /**
     * 返回键。
     */
    public AtomicBatchKey key() {
        return key;
    }

    /**
     * 返回字段集合。
     */
    public List<String> fields() {
        return fields;
    }

    /**
     * 返回载荷哈希。
     */
    public String payloadHash() {
        return payloadHash;
    }

    /**
     * 读取响应中的非负整数行数，非法或缺失时返回未知值。
     */
    public int rowCount() {
        return rows.size();
    }

    /** 新建深拷贝，防止调用方在哈希后改变冻结计划。 */
    public List<Object[]> rows() {
        return rows.stream().map(row -> Arrays.stream(row).map(AtomicBatchRequest::freeze).toArray()).toList();
    }

    /**
     * 校验输入字段集合并返回受支持的列定义。
     */
    public static List<String> checkedFields(List<String> fields) {
        var copy = List.copyOf(fields);
        if (copy.isEmpty() || copy.size() > 256 || new HashSet<>(copy).size() != copy.size()) {
            throw new IllegalArgumentException("A bounded unique field list is required");
        }
        copy.forEach(AtomicBatchTarget::identifier);
        return copy;
    }

    /**
     * 复制并冻结当前配置或数据结构。
     */
    private static Object freeze(Object value) {
        if (value instanceof String text) {
            try {
                StandardCharsets.UTF_8.newEncoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .encode(java.nio.CharBuffer.wrap(text));
            } catch (java.nio.charset.CharacterCodingException invalid) {
                throw new IllegalArgumentException("Invalid scalar Unicode");
            }
            return text;
        }
        if (value == null || value instanceof Boolean || value instanceof UUID
                || value instanceof LocalDate || value instanceof LocalTime || value instanceof LocalDateTime
                || value instanceof OffsetDateTime || value instanceof OffsetTime || value instanceof Instant) {
            return value;
        }
        if (value instanceof byte[] bytes) {
            return bytes.clone();
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        if (value instanceof BigInteger integer) {
            return decimal(new BigDecimal(integer));
        }
        if (value instanceof BigDecimal decimal) {
            return decimal(decimal);
        }
        if (value instanceof Float number && Float.isFinite(number)) {
            return decimal(new BigDecimal(number.toString()));
        }
        if (value instanceof Double number && Double.isFinite(number)) {
            return decimal(BigDecimal.valueOf(number));
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        if (value instanceof java.sql.Time time) {
            return cn.iocoder.yudao.framework.dbswitch.common.util.JdbcValueConversions.toTime(time);
        }
        if (value instanceof java.util.Date date) {
            return date.toInstant();
        }
        throw new IllegalArgumentException("Unsupported atomic batch scalar type");
    }

    /**
     * 按当前值域约定解析或转换精确小数。
     */
    private static BigDecimal decimal(BigDecimal value) {
        value = value.stripTrailingZeros();
        if (value.precision() > 131072 || value.scale() < -16383 || value.scale() > 16383) {
            throw new IllegalArgumentException("Decimal exceeds the atomic numeric budget");
        }
        return value;
    }

    /**
     * 根据当前内容计算摘要，供一致性比较使用。
     */
    private String digest() {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var out = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
                text(out, "DBSWITCH_ATOMIC_BATCH_V1");
                for (String value : List.of(target.schema(), target.table(), target.batchColumn(), target.rowColumn(),
                        target.statusColumn(), key.scope(), key.batchKey())) {
                    text(out, value);
                }
                out.writeInt(fields.size());
                for (String field : fields) {
                    text(out, field);
                }
                out.writeInt(rows.size());
                for (Object[] row : rows) {
                    for (Object value : row) {
                        if (value == null) {
                            out.writeByte(0);
                            continue;
                        }
                        if (value instanceof byte[] bytes) {
                            out.writeByte(1);
                            out.writeInt(bytes.length);
                            out.write(bytes);
                        } else {
                            out.writeByte(2);
                            text(out, value.getClass().getName());
                            text(out, value.toString());
                        }
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Unable to hash atomic batch");
        }
    }

    /**
     * 按当前值域约定解析或转换文本。
     */
    private static void text(DataOutputStream out, String value) throws java.io.IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }
}
