package cn.iocoder.yudao.framework.quality.core.clean.function;

import cn.iocoder.yudao.framework.quality.core.clean.*;

import java.math.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** 类型化空值填充；平均数来自完整输入集合，日期填充值在本次执行开始时固定。 */
public final class NullFillCleanFunction extends ConfiguredCleanFunction {

    public enum Kind {NUMBER, STRING, BOOLEAN, TIME}

    private final Kind kind;

    private final Clock clock;

    /**
     * 使用给定参数初始化 {@code NullFillCleanFunction}。
     */
    public NullFillCleanFunction(Kind kind) {
        this(kind, Clock.systemDefaultZone());
    }

    /**
     * 校验构造参数并初始化 {@code NullFillCleanFunction}及其父类状态。
     */
    public NullFillCleanFunction(Kind kind, Clock clock) {
        super(switch (kind) {
                    case NUMBER -> "NUMBER_FILL_NULL";
                    case STRING -> "STRING_FILL_NULL";
                    case BOOLEAN -> "BOOLEAN_FILL_NULL";
                    case TIME -> "TIME_FILL_NULL";
                },
                switch (kind) {
                    case NUMBER -> "数值空值填充";
                    case STRING -> "字符串空值填充";
                    case BOOLEAN -> "布尔空值填充";
                    case TIME -> "填补空时间为当前时间";
                },
                switch (kind) {
                    case NUMBER -> "{\"columns\":[\"value\"],\"mode\":\"FIXED\",\"value\":\"0\",\"scale\":2}";
                    case STRING -> "{\"columns\":[\"value\"],\"value\":\"未知\"}";
                    case BOOLEAN -> "{\"columns\":[\"value\"],\"value\":false}";
                    case TIME ->
                            "{\"columns\":[\"value\"],\"mode\":\"CURRENT\",\"outputFormat\":\"yyyy-MM-dd HH:mm:ss\"}";
                });
        this.kind = kind;
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * 未证明边界的扩展函数默认按集合级处理，不能自动进入逐行流水线。
     */
    @Override
    public ExecutionScope executionScope() {
        return kind == Kind.NUMBER ? ExecutionScope.ROW_SET : ExecutionScope.ROW;
    }

    /**
     * 规范化并校验已持久化的函数配置；实现必须拒绝未知键。
     */
    @Override
    public Map<String, Object> normalizeConfig(Map<String, Object> config) {
        var result = switch (kind) {
            case NUMBER -> checked(config, "mode", "value", "scale");
            case STRING, BOOLEAN -> checked(config, "value");
            case TIME -> checked(config, "mode", "value", "outputFormat");
        };
        switch (kind) {
            case NUMBER -> {
                result.put("mode", option(result, "mode", "FIXED", "FIXED", "MEAN"));
                result.put("value", decimal(result, "value", "0", false));
                result.put("scale", CleanFunctionConfigSupport.integer(result, "scale", 2, 0, 12));
            }
            case STRING -> result.put("value", text(result, "value", "未知", false, 1024, true));
            case BOOLEAN -> result.put("value", bool(result, "value", false));
            case TIME -> {
                String mode = option(result, "mode", "CURRENT", "CURRENT", "FIXED");
                result.put("mode", mode);
                result.put("outputFormat", outputFormat(result));
                if ("FIXED".equals(mode)) {
                    String value = text(result, "value", null, true, 128, false);
                    if (CleanDateSupport.parse(value, List.of()) == null) {
                        throw new IllegalArgumentException("固定时间不是有效日期时间");
                    }
                    result.put("value", value);
                } else if (result.containsKey("value")) {
                    result.put("value", text(result, "value", "", false, 128, true));
                }
            }
        }
        return result;
    }

    /**
     * 执行清洗。
     */
    @Override
    public List<Map<String, Object>> apply(List<Map<String, Object>> rows, Map<String, Object> input) {
        var config = normalizeConfig(input);
        String column = CleanValueSupport.firstColumn(config);
        Object replacement = config.get("value");
        if (kind == Kind.NUMBER) {
            replacement = new BigDecimal((String) replacement);
            if ("MEAN".equals(config.get("mode"))) {
                BigDecimal sum = BigDecimal.ZERO;
                int count = 0;
                for (var row : rows) {
                    CleanExecutionControl.checkpoint();
                    var number = number(row.get(column));
                    if (number != null) {
                        sum = sum.add(number);
                        count++;
                    }
                }
                if (count > 0) {
                    replacement = sum.divide(BigDecimal.valueOf(count), (Integer) config.get("scale"),
                            RoundingMode.HALF_UP);
                }
            }
        } else if (kind == Kind.TIME) {
            var date = "CURRENT".equals(config.get("mode")) ? ZonedDateTime.now(clock) : CleanDateSupport.parse(
                    replacement, List.of());
            replacement = DateTimeFormatter.ofPattern((String) config.get("outputFormat"), Locale.ROOT).format(date);
        }
        Object fixed = replacement;
        return CleanValueSupport.mapColumn(rows, column, value -> empty(value) ? fixed : value);
    }
}
