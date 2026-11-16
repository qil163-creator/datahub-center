package cn.iocoder.yudao.module.governance.service.master.match;

import java.math.BigDecimal;
import java.util.*;

/**
 * 黄金记录建议：选出保留记录，并按字段取值规则从组内成员中为每个字段挑选值。
 * 建议值一律来自某个成员的原值，不做拼接或计算，便于审批时逐字段追溯来源。
 */
public final class MasterSurvivorship {

    private MasterSurvivorship() {
    }

    /**
     * @param survivor 保留记录在成员中的下标
     * @param values   黄金记录各字段的值
     * @param sources  各字段取值来源成员下标
     */
    public record Suggestion(int survivor, Map<String, Object> values, Map<String, Integer> sources) {
    }

    /** 保留记录：非空字段最多的成员，相同时取靠前的成员 */
    public static int survivor(List<Map<String, Object>> members, List<String> fields) {
        int best = 0;
        long bestFilled = -1;
        for (int i = 0; i < members.size(); i++) {
            Map<String, Object> row = members.get(i);
            long filled = fields.stream().filter(field -> !MasterSimilarity.blank(row.get(field))).count();
            if (filled > bestFilled) {
                best = i;
                bestFilled = filled;
            }
        }
        return best;
    }

    /**
     * 生成黄金记录建议。主键字段始终取保留记录的值。
     *
     * @param survivor 指定的保留记录下标；为 null 时自动选择
     */
    public static Suggestion suggest(List<Map<String, Object>> members, List<String> fields, Set<String> primaryKeys,
            Map<String, MasterMatchConfig.Survivorship> rules, Integer survivor) {
        if (members.isEmpty()) {
            throw new IllegalArgumentException("没有可合并的记录");
        }
        int keep = survivor == null ? survivor(members, fields) : survivor;
        if (keep < 0 || keep >= members.size()) {
            throw new IllegalArgumentException("保留记录不在组内");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, Integer> sources = new LinkedHashMap<>();
        for (String field : fields) {
            MasterMatchConfig.Survivorship rule = primaryKeys.contains(field) ? MasterMatchConfig.Survivorship.SURVIVOR
                    : rules.getOrDefault(field, MasterMatchConfig.Survivorship.SURVIVOR);
            int source = pick(members, field, keep, rule);
            values.put(field, members.get(source).get(field));
            sources.put(field, source);
        }
        return new Suggestion(keep, values, sources);
    }

    /** 按规则挑选取值来源成员；候选按“保留记录优先、其余按组内顺序”排列，用于打破平局 */
    static int pick(List<Map<String, Object>> members, String field, int keep,
            MasterMatchConfig.Survivorship rule) {
        List<Integer> order = new ArrayList<>();
        order.add(keep);
        for (int i = 0; i < members.size(); i++) {
            if (i != keep) {
                order.add(i);
            }
        }
        List<Integer> filled = order.stream()
                .filter(index -> !MasterSimilarity.blank(members.get(index).get(field))).toList();
        if (filled.isEmpty() || rule == MasterMatchConfig.Survivorship.SURVIVOR
                && !MasterSimilarity.blank(members.get(keep).get(field))) {
            return keep;
        }
        return switch (rule) {
            case SURVIVOR -> filled.getFirst();
            case MOST_COMPLETE -> filled.stream().max(Comparator.comparingInt(
                    (Integer index) -> String.valueOf(members.get(index).get(field)).trim().length())
                    .thenComparing(index -> -order.indexOf(index))).orElseThrow();
            case MOST_FREQUENT -> {
                Map<String, Integer> counts = new HashMap<>();
                filled.forEach(index -> counts.merge(
                        MasterSimilarity.normalizeExact(members.get(index).get(field)), 1, Integer::sum));
                yield filled.stream().max(Comparator.comparingInt((Integer index) -> counts.get(
                                MasterSimilarity.normalizeExact(members.get(index).get(field))))
                        .thenComparing(index -> -order.indexOf(index))).orElseThrow();
            }
            case MAX, MIN -> {
                Comparator<Integer> byValue = (x, y) -> compare(members.get(x).get(field), members.get(y).get(field));
                Comparator<Integer> stable = Comparator.comparing(order::indexOf);
                yield filled.stream().min(rule == MasterMatchConfig.Survivorship.MAX
                        ? byValue.reversed().thenComparing(stable) : byValue.thenComparing(stable)).orElseThrow();
            }
        };
    }

    /** 数值按大小比较，其余按文本比较（日期时间的 ISO 文本可直接比较） */
    static int compare(Object left, Object right) {
        BigDecimal a = MasterSimilarity.decimal(left);
        BigDecimal b = MasterSimilarity.decimal(right);
        if (a != null && b != null) {
            return a.compareTo(b);
        }
        return String.valueOf(left).compareTo(String.valueOf(right));
    }

}
