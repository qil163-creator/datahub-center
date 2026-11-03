package cn.iocoder.yudao.framework.etl.spark;

import cn.iocoder.yudao.framework.etl.core.executor.support.EtlRegexReplacement;
import org.apache.hadoop.hive.ql.exec.UDF;

/** Spark 受管正则适配，匹配与替换全部委托共享规则，保留原始 SQL NULL。 */
public final class EtlRegexReplaceFunction extends UDF {
    private transient Key previous;
    private transient EtlRegexReplacement.Plan plan;
    private record Key(String search, boolean regex, boolean sensitive, boolean wholeWord, String replacement, boolean groups) { }
    public String evaluate(String source, String search, Boolean regex, Boolean sensitive, Boolean wholeWord,
                           String replacement, Boolean literalReplacement) {
        if (source == null) return null;
        boolean groups = Boolean.TRUE.equals(regex) && !Boolean.TRUE.equals(literalReplacement);
        String value = replacement == null ? "" : replacement;
        var key = new Key(search, Boolean.TRUE.equals(regex), !Boolean.FALSE.equals(sensitive), Boolean.TRUE.equals(wholeWord), groups ? value : "", groups);
        return plan(key).apply(source, value, () -> {
            if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("受管正则函数已取消");
        });
    }
    private synchronized EtlRegexReplacement.Plan plan(Key key) {
        if (!key.equals(previous)) { plan = EtlRegexReplacement.compile(key.search(), key.regex(), key.sensitive(), key.wholeWord(), key.replacement(), key.groups()); previous = key; }
        return plan;
    }
}
