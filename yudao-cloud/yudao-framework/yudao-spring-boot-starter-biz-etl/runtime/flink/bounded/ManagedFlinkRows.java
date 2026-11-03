package cn.iocoder.yudao.framework.etl.flink.bounded;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.core.JsonParser;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.*;
import org.apache.flink.table.data.*;
import org.apache.flink.table.types.logical.*;

import java.math.*;
import java.time.*;
import java.util.*;

/** Flink 内部行的明确标量合同：整数和小数禁止截断、舍入或经浮点中转。 */
public final class ManagedFlinkRows {
    private final RowType type;
    private final ObjectMapper mapper = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public ManagedFlinkRows(RowType type) { this.type = type; }
    public RowData decode(String json) {
        try {
            if (json == null || json.length() > 1_048_576) throw new IllegalArgumentException();
            JsonNode object = mapper.readTree(json);
            if (object == null || !object.isObject()) throw new IllegalArgumentException();
            var row = new GenericRowData(type.getFieldCount());
            for (int index = 0; index < type.getFieldCount(); index++) {
                var field = type.getFields().get(index); JsonNode value = object.get(field.getName());
                if (value == null || value.isNull()) {
                    if (value == null || !field.getType().isNullable()) throw new IllegalArgumentException();
                    row.setField(index, null); continue;
                }
                row.setField(index, value(value, field.getType()));
            }
            return row;
        } catch (Exception failure) { throw new IllegalArgumentException("输入行不满足已声明字段、精度或空值合同"); }
    }
    private static Object value(JsonNode value, LogicalType type) {
        String text = value.asText();
        return switch (type.getTypeRoot()) {
            case CHAR, VARCHAR -> { if (!value.isTextual()) throw new IllegalArgumentException(); yield StringData.fromString(text); }
            case BOOLEAN -> { if (!Set.of("true", "false").contains(text)) throw new IllegalArgumentException(); yield Boolean.parseBoolean(text); }
            case TINYINT -> integer(text).byteValueExact();
            case SMALLINT -> integer(text).shortValueExact();
            case INTEGER -> integer(text).intValueExact();
            case BIGINT -> integer(text).longValueExact();
            case FLOAT -> { float number = Float.parseFloat(text); if (!Float.isFinite(number) || number == 0 && new BigDecimal(text).signum() != 0) throw new IllegalArgumentException(); yield number; }
            case DOUBLE -> { double number = Double.parseDouble(text); if (!Double.isFinite(number) || number == 0 && new BigDecimal(text).signum() != 0) throw new IllegalArgumentException(); yield number; }
            case DECIMAL -> {
                var decimal = (DecimalType) type; BigDecimal number = new BigDecimal(text).setScale(decimal.getScale(), RoundingMode.UNNECESSARY);
                if (number.precision() > decimal.getPrecision()) throw new IllegalArgumentException();
                DecimalData result = DecimalData.fromBigDecimal(number, decimal.getPrecision(), decimal.getScale());
                if (result == null) throw new IllegalArgumentException(); yield result;
            }
            case BINARY, VARBINARY -> { if (!value.isTextual() || text.length() > 1_398_104) throw new IllegalArgumentException(); yield Base64.getDecoder().decode(text); }
            case DATE -> Math.toIntExact(LocalDate.parse(text).toEpochDay());
            case TIMESTAMP_WITHOUT_TIME_ZONE -> {
                String normalized = text.replace(' ', 'T'); LocalDateTime time;
                try { time = LocalDateTime.parse(normalized); }
                catch (java.time.format.DateTimeParseException ignored) { time = LocalDateTime.ofInstant(OffsetDateTime.parse(normalized).toInstant(), ZoneOffset.UTC); }
                int precision = ((TimestampType) type).getPrecision();
                if (time.getNano() % (long) Math.pow(10, 9 - precision) != 0) throw new IllegalArgumentException();
                yield TimestampData.fromLocalDateTime(time);
            }
            default -> throw new IllegalArgumentException();
        };
    }
    private static BigInteger integer(String value) {
        if (!value.matches("[+-]?[0-9]+(?:\\.0+)?")) throw new IllegalArgumentException();
        return new BigDecimal(value).toBigIntegerExact();
    }
    public Map<String, String> values(RowData row) {
        if (row.getArity() != type.getFieldCount()) throw new IllegalArgumentException("输出行字段数量不一致");
        var result = new LinkedHashMap<String, String>();
        for (int index = 0; index < type.getFieldCount(); index++) {
            var field = type.getFields().get(index); LogicalType shape = field.getType();
            Object value = RowData.createFieldGetter(shape, index).getFieldOrNull(row);
            if (value == null) {
                if (!shape.isNullable()) throw new IllegalArgumentException("输出字段不允许空值");
                result.put(field.getName(), null); continue;
            }
            String text = switch (shape.getTypeRoot()) {
                case CHAR, VARCHAR, BOOLEAN, TINYINT, SMALLINT, INTEGER, BIGINT, FLOAT, DOUBLE -> value.toString();
                case DECIMAL -> ((DecimalData) value).toBigDecimal().toPlainString();
                case BINARY, VARBINARY -> Base64.getEncoder().encodeToString((byte[]) value);
                case DATE -> LocalDate.ofEpochDay((Integer) value).toString();
                case TIMESTAMP_WITHOUT_TIME_ZONE -> ((TimestampData) value).toLocalDateTime().toString();
                default -> throw new IllegalArgumentException("输出字段类型尚未适配");
            };
            result.put(field.getName(), text);
        }
        return result;
    }
}
