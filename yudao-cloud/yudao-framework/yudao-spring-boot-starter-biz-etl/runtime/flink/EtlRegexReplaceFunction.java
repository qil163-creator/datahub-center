package cn.iocoder.yudao.framework.etl.flink;

import cn.iocoder.yudao.framework.etl.core.executor.support.EtlRegexReplacement;
import org.apache.flink.table.functions.ScalarFunction;

import java.util.LinkedHashMap;
import java.util.Map;

/** Flink 受管正则函数；使用同一 ETL 规则实现，补足内置 REGEXP_REPLACE 的分组替换差异。 */
public final class EtlRegexReplaceFunction extends ScalarFunction {
    private transient Map<Key, EtlRegexReplacement.Plan> plans;
    private record Key(String search, boolean regex, boolean sensitive, boolean wholeWord, String replacement, boolean groups) { }

    public String eval(String source, String search, Boolean regex, Boolean sensitive, Boolean wholeWord,
                       String replacement, Boolean literalReplacement) {
        if (source == null) return null;
        boolean groups = Boolean.TRUE.equals(regex) && !Boolean.TRUE.equals(literalReplacement);
        String value = replacement == null ? "" : replacement;
        Key key = new Key(search, Boolean.TRUE.equals(regex), !Boolean.FALSE.equals(sensitive), Boolean.TRUE.equals(wholeWord), groups ? value : "", groups);
        EtlRegexReplacement.Plan plan = plan(key);
        return plan.apply(source, value, () -> {
            if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("受管正则函数已取消");
        });
    }

    private synchronized EtlRegexReplacement.Plan plan(Key key) {
        if (plans == null) plans = new LinkedHashMap<>(256, 0.75f, true);
        EtlRegexReplacement.Plan plan = plans.get(key);
        if (plan != null) return plan;
        plan = EtlRegexReplacement.compile(key.search(), key.regex(), key.sensitive(), key.wholeWord(), key.replacement(), key.groups());
        if (plans.size() >= 256) plans.remove(plans.keySet().iterator().next());
        plans.put(key, plan); return plan;
    }
}
