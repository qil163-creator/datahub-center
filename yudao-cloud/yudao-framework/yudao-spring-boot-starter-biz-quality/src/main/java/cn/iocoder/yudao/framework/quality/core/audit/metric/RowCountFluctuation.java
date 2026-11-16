package cn.iocoder.yudao.framework.quality.core.audit.metric;

import cn.iocoder.yudao.framework.quality.core.audit.AuditConfigSupport;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 表行数波动判定：纯计算，不访问数据库，也不持有历史；历史基准或历史序列由调用方从检测结果中取出。
 *
 * <p>三类判定可组合：</p>
 * <ul>
 *     <li>固定区间：最少/最多行数，无需历史；</li>
 *     <li>环比、同比（PREVIOUS、DAY、WEEK、MONTH、YEAR）：与一个基准比较变化率，变化率 = (当前 − 基准) / 基准 × 100%，
 *     保留两位小数；基准为 0 时当前也为 0 视为无变化，当前大于 0 视为增幅无穷大；</li>
 *     <li>动态阈值（DYNAMIC）：取最近 N 次（可只取同一星期几）的历史行数，按 均值 ± k·标准差（SIGMA）或
 *     中位数 ± k·1.4826·中位数绝对偏差（MAD，抗离群值）计算预期区间；历史完全一致时按中心值的 1%（至少 1 行）作为容差；
 *     样本不足时只记录不判定。</li>
 * </ul>
 * <p>判定结果同时给出本次的预期区间（下限、上限，可能为空），供趋势图展示。全程使用 {@link BigDecimal}，不经过浮点。</p>
 */
public final class RowCountFluctuation {

    /** 对比方式 */
    public enum CompareMode {
        /** 环比：与同一规则上一次检测结果比较 */
        PREVIOUS("上次检测"),
        /** 同比：与 1 天前同一时段比较 */
        DAY("1 天前同时段"),
        /** 同比：与 7 天前同一时段比较 */
        WEEK("7 天前同时段"),
        /** 同比：与上月同日同一时段比较 */
        MONTH("上月同日同时段"),
        /** 同比：与去年同日同一时段比较 */
        YEAR("去年同日同时段"),
        /** 动态阈值：按历史均值与波动幅度计算预期区间 */
        DYNAMIC("动态阈值");

        private final String label;

        CompareMode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 动态阈值算法 */
    public enum DynamicMethod {
        /** 均值 ± k 倍标准差 */
        SIGMA,
        /** 中位数 ± k 倍（1.4826 × 中位数绝对偏差），对个别异常值不敏感 */
        MAD
    }

    /** 判定结论 */
    public enum Status {
        /** 通过 */
        PASS,
        /** 不通过 */
        FAIL,
        /** 没有可比较的历史（或动态阈值样本不足），且未违反固定区间：本次只记录行数 */
        NO_BASELINE
    }

    /**
     * 历史基准的取值窗口：PREVIOUS 取 before 之前最近一次；同比取 [from, to] 内最接近 target 的一次。
     *
     * @param before 上界（不含）
     * @param target 同比参考时间点
     * @param from 窗口下界（含）
     * @param to 窗口上界（含）
     */
    public record Window(LocalDateTime before, LocalDateTime target, LocalDateTime from, LocalDateTime to) {

        public boolean previous() {
            return target == null;
        }
    }

    /**
     * 判定配置。
     *
     * @param compareMode 对比方式
     * @param maxIncreasePercent 允许的最大增幅（百分比，非负），为空表示不限
     * @param maxDecreasePercent 允许的最大降幅（百分比，0～100），为空表示不限
     * @param minRowCount 最少行数，为空表示不限
     * @param maxRowCount 最多行数，为空表示不限
     * @param windowHours 同比参考时间点前后允许的偏差小时数
     * @param dynamicMethod 动态阈值算法（仅 DYNAMIC）
     * @param sensitivity 动态阈值倍数 k（仅 DYNAMIC）
     * @param historySize 动态阈值取用的历史次数（仅 DYNAMIC）
     * @param minSamples 动态阈值开始判定所需的最少历史次数（仅 DYNAMIC）
     * @param sameWeekday 动态阈值是否只取与本次同一星期几的历史（仅 DYNAMIC）
     */
    public record Config(CompareMode compareMode, BigDecimal maxIncreasePercent, BigDecimal maxDecreasePercent,
                         Long minRowCount, Long maxRowCount, int windowHours, DynamicMethod dynamicMethod,
                         BigDecimal sensitivity, int historySize, int minSamples, boolean sameWeekday) {

        /** 是否为动态阈值 */
        public boolean dynamic() {
            return compareMode == CompareMode.DYNAMIC;
        }

        /**
         * 按本次检测时间计算环比、同比基准的取值窗口；动态阈值不使用。
         *
         * @param checkTime 本次检测时间
         * @return 取值窗口
         */
        public Window baselineWindow(LocalDateTime checkTime) {
            LocalDateTime target = switch (compareMode) {
                case PREVIOUS, DYNAMIC -> null;
                case DAY -> checkTime.minusDays(1);
                case WEEK -> checkTime.minusWeeks(1);
                case MONTH -> checkTime.minusMonths(1);
                case YEAR -> checkTime.minusYears(1);
            };
            if (target == null) {
                return new Window(checkTime, null, null, null);
            }
            return new Window(checkTime, target, target.minusHours(windowHours), target.plusHours(windowHours));
        }
    }

    /**
     * 判定结果。
     *
     * @param status 结论
     * @param changePercent 变化率（百分比，两位小数）；无基准、动态阈值或基准为 0 且当前大于 0 时为空
     * @param lowerBound 本次预期区间下限（含），为空表示不限
     * @param upperBound 本次预期区间上限（含），为空表示不限
     * @param message 中文说明，供检测结果展示
     */
    public record Verdict(Status status, BigDecimal changePercent, BigDecimal lowerBound, BigDecimal upperBound,
                          String message) {
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal MAD_SCALE = new BigDecimal("1.4826");
    private static final MathContext MATH = MathContext.DECIMAL128;

    /** 判定配置的全部键 */
    private static final List<String> KEYS = List.of("compareMode", "maxIncreasePercent", "maxDecreasePercent",
            "minRowCount", "maxRowCount", "windowHours", "dynamicMethod", "sensitivity", "historySize", "minSamples",
            "sameWeekday");

    private RowCountFluctuation() {
    }

    /**
     * 规范化并校验配置：未知键拒绝；百分比与行数以十进制字符串提交；至少配置一种判定条件。
     *
     * @param input 原始配置
     * @return 规范化后的配置
     */
    public static Map<String, Object> normalize(Map<String, Object> input) {
        Map<String, Object> config = AuditConfigSupport.config(input, KEYS.toArray(String[]::new));
        // 表单清空的可选项以空串提交，视为未配置
        config.entrySet().removeIf(entry -> entry.getValue() instanceof String text && text.isBlank());
        Map<String, Object> result = new LinkedHashMap<>();
        String mode = AuditConfigSupport.choice(config.get("compareMode"), "compareMode", "PREVIOUS",
                "PREVIOUS", "DAY", "WEEK", "MONTH", "YEAR", "DYNAMIC");
        result.put("compareMode", mode);
        boolean dynamic = "DYNAMIC".equals(mode);
        BigDecimal increase = percent(config.get("maxIncreasePercent"), "maxIncreasePercent", null);
        BigDecimal decrease = percent(config.get("maxDecreasePercent"), "maxDecreasePercent", HUNDRED);
        Long min = count(config.get("minRowCount"), "minRowCount");
        Long max = count(config.get("maxRowCount"), "maxRowCount");
        if (dynamic && (increase != null || decrease != null)) {
            throw new IllegalArgumentException("动态阈值按历史自动计算区间，不能同时配置增降幅上限");
        }
        if (!dynamic) {
            for (String key : List.of("dynamicMethod", "sensitivity", "historySize", "minSamples", "sameWeekday")) {
                if (config.containsKey(key)) {
                    throw new IllegalArgumentException(key + " 仅适用于动态阈值");
                }
            }
        }
        if (!dynamic && increase == null && decrease == null && min == null && max == null) {
            throw new IllegalArgumentException("请至少配置增幅上限、降幅上限、最少行数或最多行数之一");
        }
        if (min != null && max != null && min > max) {
            throw new IllegalArgumentException("最少行数不能大于最多行数");
        }
        putIfPresent(result, "maxIncreasePercent", increase == null ? null : increase.toPlainString());
        putIfPresent(result, "maxDecreasePercent", decrease == null ? null : decrease.toPlainString());
        putIfPresent(result, "minRowCount", min == null ? null : String.valueOf(min));
        putIfPresent(result, "maxRowCount", max == null ? null : String.valueOf(max));
        if (dynamic) {
            result.put("dynamicMethod", AuditConfigSupport.choice(config.get("dynamicMethod"), "dynamicMethod",
                    "MAD", "MAD", "SIGMA"));
            BigDecimal k = config.get("sensitivity") == null ? new BigDecimal("3")
                    : new BigDecimal(AuditConfigSupport.decimal(config.get("sensitivity"), "sensitivity"));
            if (k.signum() <= 0 || k.compareTo(BigDecimal.TEN) > 0 || k.stripTrailingZeros().scale() > 2) {
                throw new IllegalArgumentException("sensitivity 须在 (0,10] 之间且不超过两位小数");
            }
            result.put("sensitivity", plain(k));
            int history = config.get("historySize") == null ? 30
                    : AuditConfigSupport.integer(config.get("historySize"), "historySize", 3, 365);
            int samples = config.get("minSamples") == null ? Math.min(7, history)
                    : AuditConfigSupport.integer(config.get("minSamples"), "minSamples", 3, 365);
            if (samples > history) {
                throw new IllegalArgumentException("最少样本数不能大于历史次数");
            }
            result.put("historySize", history);
            result.put("minSamples", samples);
            result.put("sameWeekday", AuditConfigSupport.bool(config, "sameWeekday", false));
        } else {
            result.put("windowHours", config.get("windowHours") == null ? 12
                    : AuditConfigSupport.integer(config.get("windowHours"), "windowHours", 1, 72));
        }
        return result;
    }

    /**
     * 从规则参数中只取判定配置的键（规则参数还可能携带容差、主键等与本判定无关的通用键）。
     *
     * @param parameters 规则参数
     * @return 判定配置
     */
    public static Map<String, Object> pick(Map<String, Object> parameters) {
        Map<String, Object> picked = new LinkedHashMap<>();
        if (parameters != null) {
            for (String key : KEYS) {
                if (parameters.get(key) != null) {
                    picked.put(key, parameters.get(key));
                }
            }
        }
        return picked;
    }

    /**
     * 读取配置。
     *
     * @param input 原始或已规范化配置
     * @return 判定配置
     */
    public static Config parse(Map<String, Object> input) {
        Map<String, Object> config = normalize(input);
        boolean dynamic = "DYNAMIC".equals(config.get("compareMode"));
        return new Config(CompareMode.valueOf((String) config.get("compareMode")),
                decimalOrNull(config.get("maxIncreasePercent")), decimalOrNull(config.get("maxDecreasePercent")),
                longOrNull(config.get("minRowCount")), longOrNull(config.get("maxRowCount")),
                dynamic ? 12 : (Integer) config.get("windowHours"),
                dynamic ? DynamicMethod.valueOf((String) config.get("dynamicMethod")) : null,
                dynamic ? new BigDecimal((String) config.get("sensitivity")) : null,
                dynamic ? (Integer) config.get("historySize") : 0,
                dynamic ? (Integer) config.get("minSamples") : 0,
                dynamic && Boolean.TRUE.equals(config.get("sameWeekday")));
    }

    /**
     * 环比、同比判定。
     *
     * @param config 判定配置
     * @param current 本次行数
     * @param baseline 基准行数；为空表示没有可用基准
     * @param baselineTime 基准的检测时间；为空表示没有可用基准
     * @return 判定结果
     */
    public static Verdict evaluate(Config config, long current, Long baseline, LocalDateTime baselineTime) {
        StringBuilder message = new StringBuilder("当前 ").append(current).append(" 行");
        String rangeViolation = rangeViolation(config, current);
        boolean compares = config.maxIncreasePercent() != null || config.maxDecreasePercent() != null;
        BigDecimal lower = config.minRowCount() == null ? null : BigDecimal.valueOf(config.minRowCount());
        BigDecimal upper = config.maxRowCount() == null ? null : BigDecimal.valueOf(config.maxRowCount());
        if (baseline == null || !compares) {
            if (compares) {
                message.append("；").append(config.compareMode().label()).append("没有可比较的历史结果，本次只记录行数");
            }
            if (rangeViolation != null) {
                return new Verdict(Status.FAIL, null, lower, upper, message.append("；").append(rangeViolation).toString());
            }
            return new Verdict(compares ? Status.NO_BASELINE : Status.PASS, null, lower, upper, message.toString());
        }
        BigDecimal base = BigDecimal.valueOf(baseline);
        if (config.maxDecreasePercent() != null) {
            lower = max(lower, base.multiply(HUNDRED.subtract(config.maxDecreasePercent())).divide(HUNDRED, 2,
                    RoundingMode.HALF_UP));
        }
        if (config.maxIncreasePercent() != null && baseline > 0) {
            upper = min(upper, base.multiply(HUNDRED.add(config.maxIncreasePercent())).divide(HUNDRED, 2,
                    RoundingMode.HALF_UP));
        } else if (config.maxIncreasePercent() != null) {
            upper = min(upper, BigDecimal.ZERO);
        }
        message.append("，").append(config.compareMode().label());
        if (baselineTime != null) {
            message.append("（").append(TIME.format(baselineTime)).append("）");
        }
        message.append(" ").append(baseline).append(" 行");
        BigDecimal change = null;
        String fluctuation = null;
        if (baseline == 0) {
            if (current == 0) {
                change = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
                message.append("，无变化");
            } else {
                message.append("，由 0 增加");
                if (config.maxIncreasePercent() != null) {
                    fluctuation = "基准为 0，增幅超过上限 " + config.maxIncreasePercent().toPlainString() + "%";
                }
            }
        } else {
            change = BigDecimal.valueOf(current - baseline).multiply(HUNDRED)
                    .divide(BigDecimal.valueOf(baseline), 2, RoundingMode.HALF_UP);
            message.append("，变化 ").append(change.signum() > 0 ? "+" : "").append(change.toPlainString()).append("%");
            if (change.signum() > 0 && config.maxIncreasePercent() != null
                    && change.compareTo(config.maxIncreasePercent()) > 0) {
                fluctuation = "增幅超过上限 " + config.maxIncreasePercent().toPlainString() + "%";
            } else if (change.signum() < 0 && config.maxDecreasePercent() != null
                    && change.negate().compareTo(config.maxDecreasePercent()) > 0) {
                fluctuation = "降幅超过上限 " + config.maxDecreasePercent().toPlainString() + "%";
            }
        }
        return finish(change, lower, upper, message, rangeViolation, fluctuation);
    }

    /**
     * 动态阈值判定。
     *
     * @param config 判定配置（compareMode 为 DYNAMIC）
     * @param current 本次行数
     * @param history 历史行数（顺序不限），不含本次
     * @return 判定结果
     */
    public static Verdict evaluateDynamic(Config config, long current, List<BigDecimal> history) {
        if (!config.dynamic()) {
            throw new IllegalArgumentException("非动态阈值配置");
        }
        StringBuilder message = new StringBuilder("当前 ").append(current).append(" 行");
        String rangeViolation = rangeViolation(config, current);
        BigDecimal fixedLower = config.minRowCount() == null ? null : BigDecimal.valueOf(config.minRowCount());
        BigDecimal fixedUpper = config.maxRowCount() == null ? null : BigDecimal.valueOf(config.maxRowCount());
        int samples = history == null ? 0 : history.size();
        if (samples < config.minSamples()) {
            message.append("；动态阈值学习中（历史样本 ").append(samples).append("/").append(config.minSamples())
                    .append("），本次只记录行数");
            if (rangeViolation != null) {
                return new Verdict(Status.FAIL, null, fixedLower, fixedUpper,
                        message.append("；").append(rangeViolation).toString());
            }
            return new Verdict(Status.NO_BASELINE, null, fixedLower, fixedUpper, message.toString());
        }
        List<BigDecimal> values = new ArrayList<>(history);
        BigDecimal center;
        BigDecimal spread;
        String label;
        if (config.dynamicMethod() == DynamicMethod.SIGMA) {
            center = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(samples), MATH);
            BigDecimal mean = center;
            BigDecimal variance = values.stream().map(v -> v.subtract(mean).pow(2, MATH))
                    .reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(samples), MATH);
            spread = variance.sqrt(MATH);
            label = "均值 ";
        } else {
            center = median(values);
            BigDecimal middle = center;
            spread = median(values.stream().map(v -> v.subtract(middle).abs()).toList()).multiply(MAD_SCALE, MATH);
            label = "中位数 ";
        }
        if (spread.signum() == 0) {
            spread = center.abs().multiply(new BigDecimal("0.01"), MATH).max(BigDecimal.ONE);
        }
        BigDecimal width = spread.multiply(config.sensitivity(), MATH);
        BigDecimal lower = center.subtract(width).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        BigDecimal upper = center.add(width).setScale(2, RoundingMode.HALF_UP);
        message.append("，近 ").append(samples).append(" 次").append(config.sameWeekday() ? "同星期" : "")
                .append(label).append(plain(center.setScale(2, RoundingMode.HALF_UP)))
                .append("，预期区间 ").append(plain(lower)).append("～").append(plain(upper));
        String fluctuation = null;
        BigDecimal value = BigDecimal.valueOf(current);
        if (value.compareTo(lower) < 0) {
            fluctuation = "低于动态阈值下限";
        } else if (value.compareTo(upper) > 0) {
            fluctuation = "高于动态阈值上限";
        }
        return finish(null, max(fixedLower, lower), min(fixedUpper, upper), message, rangeViolation, fluctuation);
    }

    private static Verdict finish(BigDecimal change, BigDecimal lower, BigDecimal upper, StringBuilder message,
                                  String rangeViolation, String fluctuation) {
        if (rangeViolation != null || fluctuation != null) {
            if (rangeViolation != null) {
                message.append("；").append(rangeViolation);
            }
            if (fluctuation != null) {
                message.append("；").append(fluctuation);
            }
            return new Verdict(Status.FAIL, change, lower, upper, message.toString());
        }
        return new Verdict(Status.PASS, change, lower, upper, message.toString());
    }

    private static String rangeViolation(Config config, long current) {
        if (config.minRowCount() != null && current < config.minRowCount()) {
            return "低于最少行数 " + config.minRowCount();
        }
        if (config.maxRowCount() != null && current > config.maxRowCount()) {
            return "超过最多行数 " + config.maxRowCount();
        }
        return null;
    }

    private static BigDecimal median(List<BigDecimal> values) {
        List<BigDecimal> sorted = values.stream().sorted().toList();
        int size = sorted.size();
        return size % 2 == 1 ? sorted.get(size / 2)
                : sorted.get(size / 2 - 1).add(sorted.get(size / 2)).divide(BigDecimal.valueOf(2), MATH);
    }

    private static BigDecimal max(BigDecimal a, BigDecimal b) {
        return a == null ? b : b == null ? a : a.max(b);
    }

    private static BigDecimal min(BigDecimal a, BigDecimal b) {
        return a == null ? b : b == null ? a : a.min(b);
    }

    private static String plain(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return (stripped.scale() < 0 ? stripped.setScale(0, RoundingMode.UNNECESSARY) : stripped).toPlainString();
    }

    private static BigDecimal percent(Object value, String name, BigDecimal max) {
        if (value == null) {
            return null;
        }
        BigDecimal decimal = new BigDecimal(AuditConfigSupport.decimal(value, name));
        if (decimal.signum() < 0 || decimal.stripTrailingZeros().scale() > 2
                || (max != null && decimal.compareTo(max) > 0) || decimal.compareTo(new BigDecimal("1000000")) > 0) {
            throw new IllegalArgumentException(name + " 须为不超过两位小数的非负百分比"
                    + (max == null ? "" : "，且不超过 " + max.toPlainString()));
        }
        return new BigDecimal(plain(decimal));
    }

    private static Long count(Object value, String name) {
        if (value == null) {
            return null;
        }
        BigDecimal decimal = new BigDecimal(AuditConfigSupport.decimal(value, name));
        try {
            long count = decimal.longValueExact();
            if (count < 0) {
                throw new IllegalArgumentException(name + " 须为非负整数");
            }
            return count;
        } catch (ArithmeticException notInteger) {
            throw new IllegalArgumentException(name + " 须为非负整数");
        }
    }

    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private static BigDecimal decimalOrNull(Object value) {
        return value == null ? null : new BigDecimal((String) value);
    }

    private static Long longOrNull(Object value) {
        return value == null ? null : Long.valueOf((String) value);
    }

}
