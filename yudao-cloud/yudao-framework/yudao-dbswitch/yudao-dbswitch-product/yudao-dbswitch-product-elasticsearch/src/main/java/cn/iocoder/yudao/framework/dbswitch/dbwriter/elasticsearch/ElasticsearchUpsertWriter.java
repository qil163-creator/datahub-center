package cn.iocoder.yudao.framework.dbswitch.dbwriter.elasticsearch;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.AbstractDatabaseWriter;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.BatchWriteResult;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.CommitAcknowledgement;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.IDatabaseUpsertWriter;
import cn.iocoder.yudao.framework.dbswitch.product.elasticsearch.ElasticsearchBulkExecutor;
import cn.iocoder.yudao.framework.dbswitch.product.elasticsearch.ElasticsearchCursorKeys;
import cn.iocoder.yudao.framework.dbswitch.product.elasticsearch.ElasticsearchMapping;
import cn.iocoder.yudao.framework.dbswitch.product.elasticsearch.ElasticsearchNativeSession;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * 通过幂等 bulk index 操作，对单文档 {@code _id} 键进行原生 upsert。Elasticsearch 没有
 * 其他唯一约束，所以仅接受 {@code _id} 语义。准备阶段验证单个具体物理
 * 索引，捕获其 UUID 和完整映射快照；首个命令执行前，在
 * 调用线程中编码整批并验证唯一性，每项 bulk 回执均与发送标识重新核对，
 * 所以被拒绝或未确认的项属于部分或未知结果，绝不静默成功。刷新后的 bulk
 * 确认是提交依据；确认行数按输入行计数，不静默重放任何批次。
 */
public final class ElasticsearchUpsertWriter extends AbstractDatabaseWriter implements IDatabaseUpsertWriter {

    private String fingerprint;

    private List<String> fields;

    private Map<String, ElasticsearchMapping.Field> mapping;

    /**
     * 初始化 {@code ElasticsearchUpsertWriter}及其父类状态。
     */
    public ElasticsearchUpsertWriter(DataSource source) {
        super(source);
    }

    /**
     * 返回写入器对应的数据库产品名（用于日志输出）。
     */
    @Override
    protected String getDatabaseProductName() {
        return "Elasticsearch";
    }

    /**
     * 写入准备：执行一条 WHERE 1=2 的探测查询（不取数据）， 从结果集元数据中缓存目标表“字段名 -> JDBC 类型”映射，供 write 阶段绑定参数类型使用； 查询失败或表无字段时抛出运行时异常。
     */
    @Override
    public void prepareWrite(String schema, String table, List<String> fields) {
        throw new IllegalArgumentException("Native UPSERT requires explicit unique keys");
    }

    /**
     * 不支持的键、索引或存储语义必须在发送任何数据语句前失败。
     */
    @Override
    public void prepareUpsert(String schema, String table, List<String> names, List<String> uniqueKeys) {
        fingerprint = null;
        fields = null;
        mapping = null;
        try {
            if (uniqueKeys == null || !List.of("_id").equals(uniqueKeys) || names == null || !names.contains("_id")
                    || new HashSet<>(names).size() != names.size()) {
                throw new IllegalArgumentException();
            }
            String index = ElasticsearchNativeSession.index(table);
            try (Connection connection = getDataSource().getConnection()) {
                var session = ElasticsearchNativeSession.open(connection, runtimeOptions.maxCacheBytes(),
                        runtimeOptions.queryTimeoutSeconds());
                var response = session.request("GET", "/" + index + "?flat_settings=true", null).requireSuccess();
                if (response.size() != 1 || !response.has(index)) {
                    throw new SQLException("Target is not one concrete physical index");
                }
                var physical = response.get(index);
                String uuid = physical.path("settings").path("index.uuid").asText();
                if (!uuid.matches("[A-Za-z0-9_-]{1,64}")) {
                    throw new SQLException("Native target physical identity is unavailable");
                }
                var resolved = ElasticsearchMapping.read(session, index);
                ElasticsearchMapping.requireUnroutedDocuments(session, index);
                if (!resolved.keySet().containsAll(names)) {
                    throw new SQLException("Upsert fields must exist in the target mapping");
                }
                this.schemaName = schema;
                this.tableName = table;
                this.columnType = Collections.emptyMap();
                this.fields = List.copyOf(names);
                this.mapping = resolved;
                fingerprint = DigestUtil.sha256Hex(
                        "ELASTICSEARCH_UPSERT_TARGET_V1\0" + uuid + '\0' + String.join("\0", names)
                                + "\0_id\0" + ElasticsearchCursorKeys.mappingFingerprint(physical.path("mappings")));
            }
        } catch (Exception failure) {
            fingerprint = null;
            fields = null;
            mapping = null;
            throw new IllegalArgumentException(
                    "Elasticsearch UPSERT target, identity key or index shape is unsupported");
        }
    }

    /**
     * 返回已观测目标表的结构指纹。
     */
    @Override
    public String targetSchemaFingerprint() {
        if (fields == null) {
            throw new IllegalStateException("Elasticsearch UPSERT target is not prepared");
        }
        return fingerprint;
    }

    /**
     * 精确有序键的类型与排序规则契约，是无损类型化游标重放的必要条件。
     */
    @Override
    public String keyContractFingerprint() {
        return ElasticsearchCursorKeys.idKeyFingerprint();
    }

    /**
     * 返回游标读写使用的结构化键契约。
     */
    @Override
    public CursorKeyContract keyContract() {
        targetSchemaFingerprint();
        return ElasticsearchCursorKeys.keyContract();
    }

    /**
     * 批量数据写入。
     */
    @Override
    public long write(List<String> names, List<Object[]> rows) {
        return writeWithReceipt(names, rows).acknowledgedRows();
    }

    /**
     * 可选原生单批提交见证；旧实现继续工作，但 null 不能解释为已取得提交/到数证明。
     */
    @Override
    public BatchWriteResult writeWithReceipt(List<String> names, List<Object[]> rows) {
        if (fields == null || !fields.equals(names)) {
            throw new BatchRejectedException("Elasticsearch UPSERT input fields differ from the prepared fields");
        }
        long acknowledged = ElasticsearchBulkExecutor.execute(getDataSource(), tableName, names, mapping, rows,
                ElasticsearchBulkExecutor.Operation.UPSERT, batchLimits, runtimeOptions);
        return new BatchWriteResult(acknowledged, null, CommitAcknowledgement.JDBC_COMMIT_RETURNED);
    }
}
