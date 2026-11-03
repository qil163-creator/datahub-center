package cn.iocoder.yudao.framework.dbswitch.core.schema;

import cn.iocoder.yudao.framework.dbswitch.core.model.TableSchemaMetadata;

import java.util.Objects;

/** 与实际服务器、数据库、角色、命名空间和关系标识绑定的最新观测。 */
public record SchemaObservation(String schemaName, String tableName, String scopeFingerprint,
                                String relationFingerprint, boolean hasData, TableSchemaMetadata metadata) {

    /**
     * 校验构造参数并初始化 {@code SchemaObservation}。
     */
    public SchemaObservation {
        Objects.requireNonNull(schemaName);
        Objects.requireNonNull(tableName);
        Objects.requireNonNull(scopeFingerprint);
        Objects.requireNonNull(metadata);
    }

    /**
     * 比较两份已观测状态是否一致。
     */
    public boolean sameState(SchemaObservation other) {
        return other != null && schemaName.equals(other.schemaName) && tableName.equals(other.tableName)
                && scopeFingerprint.equals(other.scopeFingerprint)
                && Objects.equals(relationFingerprint, other.relationFingerprint) && hasData == other.hasData
                && SchemaFingerprint.of(metadata).equals(SchemaFingerprint.of(other.metadata));
    }

    /**
     * 根据当前输入构建并返回 {@code SchemaPrecondition}。
     */
    public SchemaPrecondition precondition() {
        return new SchemaPrecondition(schemaName, tableName, scopeFingerprint, relationFingerprint,
                Boolean.TRUE.equals(metadata.existence().value()), hasData, SchemaFingerprint.of(metadata));
    }

    /**
     * 返回当前对象的文本表示。
     */
    @Override
    public String toString() {
        return "SchemaObservation[fresh=true, exists=" + metadata.existence().value() + "]";
    }
}
