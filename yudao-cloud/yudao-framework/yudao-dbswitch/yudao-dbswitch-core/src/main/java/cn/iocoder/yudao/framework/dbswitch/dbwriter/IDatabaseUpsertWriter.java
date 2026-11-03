package cn.iocoder.yudao.framework.dbswitch.dbwriter;

import java.util.List;

/** 对明确验证且非空的唯一键，使用原生原子冲突处理。 */
public interface IDatabaseUpsertWriter extends IDatabaseWriter {

    /**
     * Trusted cursor orchestration calls this before preparation, including when no target pin was configured.
     * Products without a stable native TRUNCATE/restore identity may require a deployed target generation.
     * Ordinary standalone UPSERT does not invoke this cursor-only hook. A supplied pin must never be ignored.
     */
    default void configureCursorTargetGeneration(
            cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin pin) {
        if (pin != null) {
            throw new UnsupportedOperationException(
                    "Managed cursor target generation is not implemented by this writer");
        }
    }

    /** 不支持的键、索引或存储语义必须在发送任何数据语句前失败。 */
    void prepareUpsert(String schema, String table, List<String> fields, List<String> uniqueKeys);

    /** prepare 时捕获的结构及标识，并在每个目标事务中重新检查。 */
    String targetSchemaFingerprint();

    /** 精确有序键的类型与排序规则契约，是无损类型化游标重放的必要条件。 */
    default String keyContractFingerprint() {
        throw new UnsupportedOperationException("Typed cursor key contract is unsupported");
    }

    /**
     * 返回游标读写使用的结构化键契约。
     */
    default cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract keyContract() {
        throw new UnsupportedOperationException("Structured cursor target key evidence is unavailable");
    }

    /**
     * 批量写入预处理。
     */
    @Override
    default void prepareWrite(String schema, String table, List<String> fields) {
        throw new IllegalArgumentException("Native UPSERT requires explicit unique keys");
    }
}
