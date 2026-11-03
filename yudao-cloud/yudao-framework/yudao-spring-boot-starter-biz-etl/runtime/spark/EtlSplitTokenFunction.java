package cn.iocoder.yudao.framework.etl.spark;

import cn.iocoder.yudao.framework.etl.core.executor.support.EtlTokenSplit;
import org.apache.hadoop.hive.ql.exec.UDF;

/** Hive 类型适配保留 Java null 为 SQL NULL，避免 JAVA_METHOD 把缺失字段转成字面字符串。 */
public final class EtlSplitTokenFunction extends UDF {
    private transient Key previous;
    private transient EtlTokenSplit.Plan plan;
    private record Key(String separator, boolean regex, String wrapper) { }
    public String evaluate(String source, String separator, Boolean regex, String wrapper, Integer index, String trim) {
        if (index == null || index < 0 || index >= 200) throw new IllegalArgumentException("拆分字段序号超出范围");
        EtlTokenSplit.Plan current = plan(new Key(separator, Boolean.TRUE.equals(regex), wrapper)); String[] tokens = current.split(source);
        return index >= tokens.length ? null : current.clean(tokens[index], trim);
    }
    private synchronized EtlTokenSplit.Plan plan(Key key) {
        if (!key.equals(previous)) { plan = EtlTokenSplit.compile(key.separator(), key.regex(), key.wrapper()); previous = key; }
        return plan;
    }
}
