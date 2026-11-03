package cn.iocoder.yudao.framework.dbswitch.dbwriter.tdengine;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.NonTransactionalWriteExecution;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.AbstractDatabaseWriter;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.BatchWriteResult;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.CommitAcknowledgement;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.IDatabaseUpsertWriter;
import cn.iocoder.yudao.framework.dbswitch.product.tdengine.TdengineCursorMetadata;
import cn.iocoder.yudao.framework.dbswitch.product.tdengine.TdengineJdbcSupport;
import cn.iocoder.yudao.framework.dbswitch.product.tdengine.TdengineNativeTables;
import cn.iocoder.yudao.framework.dbswitch.product.tdengine.TdengineTimestamp;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * TDengine 时间戳键覆盖。写入已有 (child, ts[, key]) 时，仅替换实际写入列，
 * 包括显式 NULL，因此重放已确认批次会收敛为相同行。没有事务：每行
 * 为一次自动提交请求，且必须精确确认一行；首次发送后的任何失败均为 UNKNOWN，
 * 此处绝不重试或回滚。首次发送前及最后一次确认后，重新检查目标代次
 * 和结构。超级表目标按 {@code tbname} 路由数据行：已有名称必须属于此
 * 超级表的子表，且 TAG 值完全一致（TDengine 在 {@code USING ... TAGS} 时默默保留旧 TAG）；
 * 缺失子表使用行中的 TAG 值创建并验证。绝不将普通整数猜测为时间戳。
 */
public final class TdengineUpsertWriter extends AbstractDatabaseWriter implements IDatabaseUpsertWriter {

    private TdengineCursorMetadata.Shape shape;

    private TdengineJdbcSupport.Table table;

    private List<String> fields, keys, dataFields;

    private List<TdengineCursorMetadata.Column> columns, tags;

    private String fingerprint;

    /**
     * 初始化 {@code TdengineUpsertWriter}及其父类状态。
     */
    public TdengineUpsertWriter(DataSource source) {
        super(source);
    }

    /**
     * 返回写入器对应的数据库产品名（用于日志输出）。
     */
    @Override
    protected String getDatabaseProductName() {
        return "TDengine";
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
        shape = null;
        fields = null;
        fingerprint = null;
        try {
            TdengineJdbcSupport.table(schema, table);
            if (names == null || names.isEmpty() || new HashSet<>(names).size() != names.size()) {
                throw new IllegalArgumentException();
            }
            TdengineCursorMetadata.Shape current;
            TdengineJdbcSupport.Table codecs;
            try (var connection = dataSource.getConnection()) {
                current = TdengineCursorMetadata.read(connection, schema, table, runtimeOptions.queryTimeoutSeconds());
                codecs = TdengineJdbcSupport.metadata(connection, schema, table, runtimeOptions.queryTimeoutSeconds());
            }
            if (!current.keys().equals(uniqueKeys) || !names.containsAll(uniqueKeys)) {
                throw new IllegalArgumentException();
            }
            var resolved = new ArrayList<TdengineCursorMetadata.Column>();
            for (String name : names) {
                if (TdengineCursorMetadata.TBNAME.equals(name)) {
                    if (!current.superTable()) {
                        throw new IllegalArgumentException();
                    }
                    resolved.add(null);
                    continue;
                }
                var column = current.column(name).orElseThrow(IllegalArgumentException::new);
                if (column.tag() && !current.superTable() || !TdengineCursorMetadata.supportedValueType(
                        column.type())) {
                    throw new IllegalArgumentException();
                }
                resolved.add(column);
            }
            // 子表路由需要每个 TAG 值：缺失 TAG 会按 NULL 创建，而非拒绝。
            if (current.superTable() && !names.containsAll(
                    current.tags().stream().map(TdengineCursorMetadata.Column::name).toList())) {
                throw new IllegalArgumentException();
            }
            // null 编解码器表示路由的子表名称。
            schemaName = schema;
            tableName = table;
            shape = current;
            columns = Collections.unmodifiableList(resolved);
            keys = List.copyOf(uniqueKeys);
            tags = current.tags();
            dataFields = names.stream().filter(name -> !TdengineCursorMetadata.TBNAME.equals(name)
                    && !current.column(name).orElseThrow().tag()).toList();
            this.table = current.superTable() ? new TdengineJdbcSupport.Table(codecs.columns(), codecs.keys(),
                    codecs.fractionalDigits(), "CHILD_TABLE") : codecs;
            if (codecs.fractionalDigits() != current.digits()) {
                throw new IllegalArgumentException();
            }
            dataFields.forEach(this.table::column);
            fingerprint = DigestUtil.sha256Hex(
                    "TDENGINE_UPSERT_TARGET_V1\0" + current.evidence() + '\0' + String.join("\0",
                            names) + '\0' + String.join("\0", uniqueKeys));
            fields = List.copyOf(names);
        } catch (Exception failure) {
            shape = null;
            fields = null;
            fingerprint = null;
            throw new IllegalArgumentException("TDengine UPSERT target, timestamp key or TAG routing is unsupported");
        }
    }

    /**
     * 返回已观测目标表的结构指纹。
     */
    @Override
    public String targetSchemaFingerprint() {
        if (fields == null) {
            throw new IllegalStateException("TDengine UPSERT target is not prepared");
        }
        return fingerprint;
    }

    /**
     * 精确有序键的类型与排序规则契约，是无损类型化游标重放的必要条件。
     */
    @Override
    public String keyContractFingerprint() {
        return keyContract().nativeFingerprint();
    }

    /**
     * 返回游标读写使用的结构化键契约。
     */
    @Override
    public CursorKeyContract keyContract() {
        if (fields == null) {
            throw new IllegalStateException("TDengine UPSERT target is not prepared");
        }
        return shape.targetContract();
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
            throw new BatchRejectedException("TDengine UPSERT input fields differ from the prepared fields");
        }
        var converted = BatchValueConverter.convert(rows, fields.size(), batchLimits,
                (value, index) -> convert(value, columns.get(index)));
        if (converted.isEmpty()) {
            return new BatchWriteResult(0, null);
        }
        // 借用任何连接前，按物理叶表分组，并证明每行一个键、每个子表一组 TAG。
        var groups = new LinkedHashMap<String, List<Object[]>>();
        var evidence = new HashMap<String, List<String>>();
        var literals = new HashMap<String, String>();
        var seen = new HashSet<List<Object>>();
        for (var row : converted) {
            var key = new ArrayList<Object>();
            for (String name : keys) {
                Object value = row[fields.indexOf(name)];
                if (value == null) {
                    throw new BatchRejectedException("TDengine UPSERT key values must not be NULL");
                }
                key.add(value instanceof byte[] bytes ? HexFormat.of().formatHex(bytes) : value);
            }
            if (!seen.add(key)) {
                throw new BatchRejectedException("TDengine UPSERT batch repeats a native key");
            }
            String leaf = shape.superTable() ? (String) row[fields.indexOf(TdengineCursorMetadata.TBNAME)] : tableName;
            var data = dataFields.stream().map(name -> row[fields.indexOf(name)]).toArray();
            groups.computeIfAbsent(leaf, ignored -> new ArrayList<>()).add(data);
            if (shape.superTable()) {
                var values = new ArrayList<String>();
                try {
                    for (var tag : tags) {
                        values.add(TdengineCursorMetadata.tagEvidence(row[fields.indexOf(tag.name())], tag,
                                shape.digits()));
                    }
                    var previous = evidence.putIfAbsent(leaf, List.copyOf(values));
                    if (previous != null && !previous.equals(values)) {
                        throw new BatchRejectedException("TDengine child rows carry different TAG values");
                    }
                    // 发送任何内容前完成渲染：TAG 无法精确表示时拒绝整个批次。
                    if (previous == null) {
                        literals.put(leaf, String.join(",", tags.stream().map(tag ->
                                TdengineNativeTables.tagLiteral(row[fields.indexOf(tag.name())], shape.precision(),
                                        tag.type())).toList()));
                    }
                } catch (BatchRejectedException rejected) {
                    throw rejected;
                } catch (RuntimeException invalid) {
                    throw new BatchRejectedException("TDengine TAG value cannot be routed exactly");
                }
            }
        }
        int timeout = runtimeOptions.queryTimeoutSeconds();
        long written = NonTransactionalWriteExecution.execute(dataSource, (connection, attempt) -> {
            TdengineJdbcSupport.requireValueDriver(connection);
            verifyTarget(connection);
            var existing = shape.superTable() ? children(connection, groups.keySet()) : Map.<String, List<String>>of();
            for (var leaf : groups.keySet()) {
                if (existing.containsKey(leaf) && !existing.get(leaf).equals(evidence.get(leaf))) {
                    throw new IllegalArgumentException(
                            "TDengine existing child TAG values differ from the routed rows");
                }
            }
            long confirmed = 0;
            for (var group : groups.entrySet()) {
                if (shape.superTable() && !existing.containsKey(group.getKey())) {
                    try (var statement = connection.createStatement()) {
                        statement.setQueryTimeout(timeout);
                        attempt.sending();
                        statement.execute("CREATE TABLE IF NOT EXISTS " + TdengineJdbcSupport.table(schemaName,
                                group.getKey()) + " USING "
                                + TdengineJdbcSupport.table(schemaName, tableName) + " TAGS (" + literals.get(
                                group.getKey()) + ")");
                    }
                    if (!evidence.get(group.getKey())
                            .equals(children(connection, Set.of(group.getKey())).get(group.getKey()))) {
                        throw new IllegalStateException("TDengine created child TAG values are not confirmed");
                    }
                }
                confirmed += TdengineJdbcSupport.insert(connection, attempt, schemaName, group.getKey(), dataFields,
                        table, group.getValue(), timeout);
            }
            // 已确认行绑定到发送前已验证的代次、结构及子表 TAG。
            verifyTarget(connection);
            if (shape.superTable() && !children(connection, groups.keySet()).equals(evidence)) {
                throw new IllegalStateException("TDengine child routing changed while writing");
            }
            return confirmed;
        });
        if (written != converted.size()) {
            throw new cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException();
        }
        return new BatchWriteResult(written, null, CommitAcknowledgement.JDBC_COMMIT_RETURNED);
    }

    /**
     * 按目标类型约定转换当前输入值。
     */
    private Object convert(Object value, TdengineCursorMetadata.Column column) {
        if (value == null) {
            return null;
        }
        if (column == null) {
            if (!(value instanceof String name) || !name.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")) {
                throw new IllegalArgumentException("TDengine child name is not an ordinary routed identifier");
            }
            return name;
        }
        if (column.timestamp()) {
            if (value instanceof TdengineTimestamp timestamp) {
                if (timestamp.digits() != shape.digits()) {
                    throw new IllegalArgumentException("TDengine timestamp precision differs from the target");
                }
                return timestamp.epoch();
            }
            if (value instanceof Timestamp || value instanceof Instant || value instanceof OffsetDateTime || value instanceof LocalDateTime) {
                return TdengineJdbcSupport.timestamp(value, shape.digits());
            }
            throw new IllegalArgumentException(
                    "TDengine timestamp requires a typed temporal value or native epoch with its precision");
        }
        return TdengineJdbcSupport.convert(value, column.jdbc(), shape.digits());
    }

    /**
     * 校验目标端。
     */
    private void verifyTarget(Connection connection) throws Exception {
        var current = TdengineCursorMetadata.read(connection, schemaName, tableName,
                runtimeOptions.queryTimeoutSeconds());
        if (!current.evidence().equals(shape.evidence())) {
            throw new IllegalStateException("TDengine UPSERT target generation or structure changed");
        }
    }

    /** 指定子表的当前 TAG 证据；拒绝不属于此超级表的同名关系。 */
    private Map<String, List<String>> children(Connection connection, Collection<String> names) throws Exception {
        var result = new HashMap<String, List<String>>();
        var list = new ArrayList<>(names);
        int timeout = runtimeOptions.queryTimeoutSeconds();
        for (int start = 0; start < list.size(); start += 256) {
            var chunk = list.subList(start, Math.min(list.size(), start + 256));
            String in = String.join(",", chunk.stream().map(TdengineCursorMetadata::literal).toList());
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(timeout);
                try (var rows = statement.executeQuery(
                        "SELECT table_name,`type`,COALESCE(stable_name,'') FROM information_schema.ins_tables WHERE db_name="
                                + TdengineCursorMetadata.literal(schemaName) + " AND table_name IN (" + in + ")")) {
                    while (rows.next()) {
                        if (!"CHILD_TABLE".equals(rows.getString(2)) || !tableName.equals(rows.getString(3))) {
                            throw new IllegalArgumentException(
                                    "TDengine routed child name belongs to another relation");
                        }
                    }
                }
                try (var rows = statement.executeQuery("SELECT TAGS tbname," + TdengineJdbcSupport.columns(
                        tags.stream().map(TdengineCursorMetadata.Column::name).toList())
                        + " FROM " + TdengineJdbcSupport.table(schemaName,
                        tableName) + " WHERE tbname IN (" + in + ")")) {
                    while (rows.next()) {
                        String name = new String(rows.getBytes(1), java.nio.charset.StandardCharsets.UTF_8);
                        var values = new ArrayList<String>();
                        for (int i = 0; i < tags.size(); i++) {
                            values.add(TdengineCursorMetadata.tagEvidence(rows.getObject(i + 2), tags.get(i),
                                    shape.digits()));
                        }
                        if (!chunk.contains(name) || result.put(name, List.copyOf(values)) != null) {
                            throw new IllegalStateException("TDengine child TAG evidence is ambiguous");
                        }
                    }
                }
            }
        }
        return result;
    }
}
