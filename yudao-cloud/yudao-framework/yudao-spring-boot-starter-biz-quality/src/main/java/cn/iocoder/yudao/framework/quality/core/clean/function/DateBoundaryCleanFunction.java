package cn.iocoder.yudao.framework.quality.core.clean.function;

import cn.iocoder.yudao.framework.quality.core.clean.*;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** 使用可注入时钟固定本批时间，执行未来、超前间隔及历史下界修正。 */
public final class DateBoundaryCleanFunction extends ConfiguredCleanFunction {

    public enum Kind {FUTURE, FUTURE_OFFSET, HISTORICAL}

    private final Kind kind;

    private final Clock clock;

    /**
     * 使用给定参数初始化 {@code DateBoundaryCleanFunction}。
     */
    public DateBoundaryCleanFunction(Kind kind) {
        this(kind, Clock.systemDefaultZone());
    }

    /**
     * 校验构造参数并初始化 {@code DateBoundaryCleanFunction}及其父类状态。
     */
    public DateBoundaryCleanFunction(Kind kind, Clock clock) {
        super(switch (kind) {
                    case FUTURE -> "FIX_FUTURE_DATE";
                    case FUTURE_OFFSET -> "CLAMP_FUTURE_OFFSET";
                    case HISTORICAL -> "FIX_HISTORICAL_DATE";
                },
                switch (kind) {
                    case FUTURE -> "修正未来时间值";
                    case FUTURE_OFFSET -> "校正超前时间差值";
                    case HISTORICAL -> "修正历史时间边界";
                },
                switch (kind) {
                    case FUTURE ->
                            "{\"columns\":[\"value\"],\"replacement\":\"CURRENT\",\"outputFormat\":\"yyyy-MM-dd HH:mm:ss\"}";
                    case FUTURE_OFFSET ->
                            "{\"columns\":[\"value\"],\"amount\":7,\"unit\":\"DAY\",\"outputFormat\":\"yyyy-MM-dd HH:mm:ss\"}";
                    case HISTORICAL ->
                            "{\"columns\":[\"value\"],\"boundary\":\"2000-01-01\",\"outputFormat\":\"yyyy-MM-dd HH:mm:ss\"}";
                });
        this.kind = kind;
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * 规范化并校验已持久化的函数配置；实现必须拒绝未知键。
     */
    @Override
    public Map<String, Object> normalizeConfig(Map<String, Object> config) {
        var result = switch (kind) {
            case FUTURE -> checked(config, "replacement", "boundary", "inputFormats", "outputFormat");
            case FUTURE_OFFSET -> checked(config, "amount", "unit", "inputFormats", "outputFormat");
            case HISTORICAL -> checked(config, "boundary", "inputFormats", "outputFormat");
        };
        result.put("inputFormats", formats(result));
        result.put("outputFormat", outputFormat(result));
        if (kind == Kind.FUTURE_OFFSET) {
            if (!result.containsKey("amount")) {
                throw new IllegalArgumentException("超前修正必须配置 amount");
            }
            result.put("amount", CleanFunctionConfigSupport.integer(result, "amount", -1, 0, 100_000));
            result.put("unit", option(result, "unit", "DAY", "SECOND", "MINUTE", "HOUR", "DAY", "MONTH", "YEAR"));
        } else {
            String replacement = kind == Kind.FUTURE ? option(result, "replacement", "CURRENT", "CURRENT",
                    "BOUNDARY") : "BOUNDARY";
            if (kind == Kind.FUTURE) {
                result.put("replacement", replacement);
            }
            if ("BOUNDARY".equals(replacement) || result.containsKey("boundary")) {
                String boundary = text(result, "boundary", null, true, 128, false);
                if (CleanDateSupport.parse(boundary, List.of()) == null) {
                    throw new IllegalArgumentException("时间边界必须是有效日期时间");
                }
                result.put("boundary", boundary);
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
        ZonedDateTime now = ZonedDateTime.now(clock);
        ZonedDateTime boundary = kind == Kind.FUTURE_OFFSET
                ? now.plus((Integer) config.get("amount"), unit((String) config.get("unit")))
                : kind == Kind.HISTORICAL || "BOUNDARY".equals(config.get("replacement"))
                ? CleanDateSupport.parse(config.get("boundary"), List.of()) : now;
        ZonedDateTime threshold = kind == Kind.FUTURE ? now : boundary;
        String replacement = DateTimeFormatter.ofPattern((String) config.get("outputFormat"), Locale.ROOT)
                .format(boundary);
        return CleanValueSupport.mapColumn(rows, CleanValueSupport.firstColumn(config), value -> {
            if (empty(value)) {
                return value;
            }
            var date = CleanDateSupport.parse(value, formats(config));
            if (date == null) {
                return value;
            }
            boolean outside = kind == Kind.HISTORICAL ? date.toInstant()
                    .isBefore(threshold.toInstant()) : date.toInstant().isAfter(threshold.toInstant());
            return outside ? replacement : value;
        });
    }

    /**
     * 返回当前单位。
     */
    private static ChronoUnit unit(String unit) {
        return switch (unit) {
            case "SECOND" -> ChronoUnit.SECONDS;
            case "MINUTE" -> ChronoUnit.MINUTES;
            case "HOUR" -> ChronoUnit.HOURS;
            case "DAY" -> ChronoUnit.DAYS;
            case "MONTH" -> ChronoUnit.MONTHS;
            default -> ChronoUnit.YEARS;
        };
    }
}
