package cn.iocoder.yudao.framework.dbswitch.common.entity;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;

import java.util.List;
import java.util.Objects;

/** 冻结已声明的标识和行数据所有权。传入的回调仍须保证线程安全和确定性。 */
public final class FrozenRecordTransformer implements RecordTransformer {

    private final RecordTransformDefinition definition;

    private final RecordTransformer delegate;

    private final BatchLimits limits;

    /**
     * 校验构造参数并初始化 {@code FrozenRecordTransformer}。
     */
    public FrozenRecordTransformer(RecordTransformDefinition definition, RecordTransformer delegate,
            BatchLimits limits) {
        this.definition = Objects.requireNonNull(definition);
        this.delegate = Objects.requireNonNull(delegate);
        this.limits = Objects.requireNonNull(limits);
    }

    /**
     * 返回定义。
     */
    public RecordTransformDefinition definition() {
        return definition;
    }

    /**
     * 转换一行数据。
     */
    @Override
    public Object[] transform(String schema, String table, List<String> fields, Object[] record) {
        try {
            List<String> frozenFields = List.copyOf(fields);
            Object[] original = owned(
                    BatchValueConverter.convert(List.<Object[]>of(record), fields.size(), limits).getFirst());
            Object[] transformed = delegate.transform(schema, table, frozenFields, owned(original));
            // 返回 null 表示保留未修改的输入；本契约不允许静默过滤行。
            Object[] output = transformed == null ? original : transformed;
            return owned(BatchValueConverter.convert(List.<Object[]>of(output), fields.size(), limits).getFirst());
        } catch (RuntimeException failure) {
            throw new cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException(
                    "Record transformation failed before write");
        }
    }

    /**
     * 复制当前数据行及其可变字段，为当前处理器建立独立持有的数据。
     */
    private static Object[] owned(Object[] row) {
        Object[] copy = row.clone();
        for (int i = 0; i < copy.length; i++) {
            Object value = copy[i];
            if (value instanceof byte[] bytes) {
                copy[i] = bytes.clone();
            } else if (value instanceof java.util.Date date) {
                copy[i] = date.clone();
            } else if (value != null && !(value instanceof String || value instanceof Boolean || value instanceof Character
                    || value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                    || value instanceof Float || value instanceof Double || value instanceof java.math.BigInteger
                    || value instanceof java.math.BigDecimal || value instanceof java.util.UUID
                    || value instanceof java.time.LocalDate || value instanceof java.time.LocalTime
                    || value instanceof java.time.LocalDateTime || value instanceof java.time.OffsetDateTime
                    || value instanceof java.time.OffsetTime || value instanceof java.time.Instant
                    || value instanceof java.time.ZonedDateTime || value instanceof JdbcArrayValue)) {
                throw new IllegalArgumentException("Transformation requires an owned scalar value");
            }
        }
        return copy;
    }
}
