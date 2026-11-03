package cn.iocoder.yudao.framework.etl.flink;

import cn.iocoder.yudao.framework.etl.core.executor.support.EtlTokenSplit;
import org.apache.flink.table.functions.ScalarFunction;

import java.util.LinkedHashMap;
import java.util.Map;

/** 受管字段拆分函数，复用 Java 拆分契约并限制配置缓存大小。 */
public final class EtlSplitTokenFunction extends ScalarFunction {
    private transient Map<Key, EtlTokenSplit.Plan> plans;
    private record Key(String separator, boolean regex, String wrapper) { }
    public String eval(String source, String separator, Boolean regex, String wrapper, Integer index, String trim) {
        if (index == null || index < 0 || index >= 200) throw new IllegalArgumentException("拆分字段序号超出范围");
        if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("受管字段拆分已取消");
        EtlTokenSplit.Plan plan = plan(new Key(separator, Boolean.TRUE.equals(regex), wrapper)); String[] tokens = plan.split(source);
        return index >= tokens.length ? null : plan.clean(tokens[index], trim);
    }
    private synchronized EtlTokenSplit.Plan plan(Key key) {
        if (plans == null) plans = new LinkedHashMap<>(256, 0.75f, true);
        EtlTokenSplit.Plan plan = plans.get(key); if (plan != null) return plan;
        plan = EtlTokenSplit.compile(key.separator(), key.regex(), key.wrapper());
        if (plans.size() >= 256) plans.remove(plans.keySet().iterator().next()); plans.put(key, plan); return plan;
    }
}
