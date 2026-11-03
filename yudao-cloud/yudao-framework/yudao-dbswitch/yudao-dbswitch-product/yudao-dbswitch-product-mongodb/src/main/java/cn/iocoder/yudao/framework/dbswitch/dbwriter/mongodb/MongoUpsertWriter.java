package cn.iocoder.yudao.framework.dbswitch.dbwriter.mongodb;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.AbstractDatabaseWriter;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.BatchWriteResult;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.CommitAcknowledgement;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.IDatabaseUpsertWriter;
import cn.iocoder.yudao.framework.dbswitch.product.mongodb.MongoBulkExecutor;
import cn.iocoder.yudao.framework.dbswitch.product.mongodb.MongoPreparedTarget;
import cn.iocoder.yudao.framework.dbswitch.product.mongodb.MongoReplies;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * 对单个 {@code _id} 键进行原生 BSON upsert。准备阶段证明普通集合没有第二个唯一
 * 索引，并捕获物理代际；首个命令执行前，在调用线程中编码整批
 * 并验证唯一性，发送的更新按 BSON 类型和值匹配标识，因此数值相等但
 * 类型不同的标识，要么精确匹配，要么在折叠值的 _id 索引上明确失败，绝不
 * 静默替换。多数节点且写入日志后的命令确认是提交依据：确认行数按
 * 输入行计数，任何写入错误均为部分或未知结果，绝不静默成功。
 */
public final class MongoUpsertWriter extends AbstractDatabaseWriter implements IDatabaseUpsertWriter {

    private static final String ID_KEY_FINGERPRINT = DigestUtil.sha256Hex("MONGODB_ID_KEY_V1");

    private MongoPreparedTarget prepared;

    private String fingerprint;

    private List<String> fields;

    /**
     * 初始化 {@code MongoUpsertWriter}及其父类状态。
     */
    public MongoUpsertWriter(DataSource source) {
        super(source);
    }

    /**
     * 返回写入器对应的数据库产品名（用于日志输出）。
     */
    @Override
    protected String getDatabaseProductName() {
        return "MongoDB";
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
        prepared = null;
        fields = null;
        fingerprint = null;
        try {
            if (uniqueKeys == null || !List.of("_id").equals(uniqueKeys) || names == null || !names.contains("_id")) {
                throw new IllegalArgumentException();
            }
            MongoPreparedTarget target = MongoPreparedTarget.prepare(getDataSource(), schema, table, names);
            requireSingleUniqueKey(getDataSource(), schema, table);
            this.schemaName = schema;
            this.tableName = table;
            this.columnType = java.util.Collections.emptyMap();
            this.prepared = target;
            this.fields = List.copyOf(names);
            fingerprint = DigestUtil.sha256Hex(
                    "MONGODB_UPSERT_TARGET_V1\0" + target.uuid() + '\0' + String.join("\0", names)
                            + "\0_id\0" + (target.frame() ? "FRAME" : "FIELDS"));
        } catch (Exception failure) {
            prepared = null;
            fields = null;
            fingerprint = null;
            throw new IllegalArgumentException(
                    "MongoDB UPSERT target, identity key or collection shape is unsupported");
        }
    }

    /** 仅允许一个唯一键：第二个唯一索引可能拒绝有序 upsert 批次中的部分行，因此在任何写入前拒绝。 */
    private static void requireSingleUniqueKey(DataSource source, String schema, String table) throws SQLException {
        try (Connection connection = source.getConnection()) {
            var session = cn.iocoder.yudao.framework.dbswitch.product.mongodb.MongoNativeSession.open(connection,
                    131072, 15);
            var reply = MongoReplies.success(session, session.command(schema,
                    session.encode(
                            "{\"listIndexes\":\"" + table + "\",\"cursor\":{\"batchSize\":65},\"maxTimeMS\":5000}")));
            var cursor = reply.path("cursor");
            var indexes = cursor == null ? null : cursor.path("firstBatch");
            if (cursor == null || MongoReplies.integer(
                    cursor.path("id")) != 0 || indexes == null || !indexes.isArray() || indexes.isEmpty()) {
                throw new SQLException("MongoDB index shape is not verified");
            }
            for (var index : indexes) {
                if (index.path("name").asText().equals("_id_")) {
                    continue;
                }
                if (Boolean.TRUE.equals(index.path("unique").asBoolean())) {
                    throw new SQLException("MongoDB UPSERT requires a single _id unique key");
                }
            }
        }
    }

    /**
     * 返回已观测目标表的结构指纹。
     */
    @Override
    public String targetSchemaFingerprint() {
        if (fields == null) {
            throw new IllegalStateException("MongoDB UPSERT target is not prepared");
        }
        return fingerprint;
    }

    /**
     * 精确有序键的类型与排序规则契约，是无损类型化游标重放的必要条件。
     */
    @Override
    public String keyContractFingerprint() {
        return ID_KEY_FINGERPRINT;
    }

    /**
     * 返回游标读写使用的结构化键契约。
     */
    @Override
    public CursorKeyContract keyContract() {
        targetSchemaFingerprint();
        return new CursorKeyContract(cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.MONGODB,
                ID_KEY_FINGERPRINT, List.of(CursorKeyContract.Domain.nativeOnly()));
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
        if (prepared == null || fields == null || !fields.equals(names)) {
            throw new cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException(
                    "MongoDB UPSERT input fields differ from the prepared fields");
        }
        long acknowledged = MongoBulkExecutor.execute(getDataSource(), prepared, rows,
                MongoBulkExecutor.Operation.UPSERT, batchLimits, runtimeOptions);
        return new BatchWriteResult(acknowledged, null, CommitAcknowledgement.JDBC_COMMIT_RETURNED);
    }
}
