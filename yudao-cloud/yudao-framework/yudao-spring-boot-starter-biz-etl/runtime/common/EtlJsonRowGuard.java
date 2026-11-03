package cn.iocoder.yudao.framework.etl.flink.common;

import org.apache.flink.api.common.serialization.DeserializationSchema;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.core.JsonParser;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.DeserializationFeature;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.JsonNode;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.types.logical.DecimalType;
import org.apache.flink.table.types.logical.RowType;

import java.io.IOException;
import java.math.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 转换仍交给官方格式；守卫提前拒绝分数截断、数值溢出和 Decimal 隐式舍入。 */
public final class EtlJsonRowGuard implements DeserializationSchema<RowData> {
    private static final long serialVersionUID = 1L;
    private final DeserializationSchema<RowData> delegate;
    private final RowType shape;
    private transient ObjectMapper mapper;
    public EtlJsonRowGuard(DeserializationSchema<RowData> delegate, RowType shape) { this.delegate = delegate; this.shape = shape; }
    @Override public void open(InitializationContext context) throws Exception {
        delegate.open(context);
        mapper = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    @Override public RowData deserialize(byte[] message) throws IOException {
        try {
            if (message == null || message.length == 0 || message.length > 1_048_576) throw new IllegalArgumentException();
            JsonNode row = mapper.readTree(message);
            if (row == null || !row.isObject()) throw new IllegalArgumentException();
            for (var field : shape.getFields()) {
                JsonNode value = row.get(field.getName());
                if (value == null) throw new IllegalArgumentException();
                if (value.isNull()) continue;
                switch (field.getType().getTypeRoot()) {
                    case CHAR, VARCHAR -> { if (!value.isTextual()) throw new IllegalArgumentException(); }
                    case BOOLEAN -> { if (!value.isBoolean()) throw new IllegalArgumentException(); }
                    case INTEGER, BIGINT -> {
                        if (!value.asText().matches("-?(?:0|[1-9][0-9]*)")) throw new IllegalArgumentException();
                        BigInteger integer = new BigInteger(value.asText());
                        if (field.getType().getTypeRoot() == org.apache.flink.table.types.logical.LogicalTypeRoot.INTEGER) integer.intValueExact();
                        else integer.longValueExact();
                    }
                    case DOUBLE -> { if (!Double.isFinite(Double.parseDouble(value.asText()))) throw new IllegalArgumentException(); }
                    case DECIMAL -> {
                        var decimal = (DecimalType) field.getType();
                        BigDecimal number = new BigDecimal(value.asText()).setScale(decimal.getScale(), RoundingMode.UNNECESSARY);
                        if (number.precision() > decimal.getPrecision()) throw new IllegalArgumentException();
                    }
                    case DATE -> LocalDate.parse(value.textValue());
                    case TIMESTAMP_WITHOUT_TIME_ZONE -> {
                        LocalDateTime time = LocalDateTime.parse(value.textValue());
                        if (time.getNano() % 1_000_000 != 0) throw new IllegalArgumentException();
                    }
                    default -> throw new IllegalArgumentException();
                }
            }
            return delegate.deserialize(message);
        } catch (Exception invalid) { throw new IOException("消息格式、字段类型或精度与已声明合同不一致"); }
    }
    @Override public boolean isEndOfStream(RowData value) { return delegate.isEndOfStream(value); }
    @Override public TypeInformation<RowData> getProducedType() { return delegate.getProducedType(); }
}
