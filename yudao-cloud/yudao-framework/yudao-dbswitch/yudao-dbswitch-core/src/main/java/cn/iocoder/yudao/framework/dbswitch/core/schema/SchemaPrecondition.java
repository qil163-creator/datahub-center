package cn.iocoder.yudao.framework.dbswitch.core.schema;

import java.util.Objects;

/** 持久化计划所需的最小不可变依据；不序列化凭据或运行时列对象。 */
public record SchemaPrecondition(String schemaName, String tableName, String scopeFingerprint,
                                 String relationFingerprint, boolean tableExists, boolean hasData,
                                 String schemaFingerprint) {

    /**
     * 校验构造参数并初始化 {@code SchemaPrecondition}。
     */
    public SchemaPrecondition {
        Objects.requireNonNull(schemaName);
        Objects.requireNonNull(tableName);
        Objects.requireNonNull(scopeFingerprint);
        Objects.requireNonNull(schemaFingerprint);
    }

    /**
     * 返回当前匹配结果。
     */
    public boolean matches(SchemaObservation observation) {
        return equals(observation.precondition());
    }

    /**
     * 返回当前对象的文本表示。
     */
    @Override
    public String toString() {
        return "SchemaPrecondition[exists=" + tableExists + "]";
    }
}
