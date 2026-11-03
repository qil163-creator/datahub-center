package cn.iocoder.yudao.framework.dbswitch.dbsynch.tdengine;

import cn.iocoder.yudao.framework.dbswitch.common.util.BoundedRowCodec;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.recovery.*;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.NonTransactionalWriteExecution;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import cn.iocoder.yudao.framework.dbswitch.product.tdengine.*;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.*;

/** TDengine 没有组合键 DELETE。有界时间戳组重写前先写入日志。
 * 调用方独占目标 DML/DDL，并在恢复前证明旧执行器已停止。
 * 不按超时接管，不模拟事务，也不盲目重发。 */
final class TdengineGroupRewrite {

    private final DataSource source;

    private final String schema, table;

    private final List<String> fields, keys;

    private final TdengineJdbcSupport.Table metadata;

    private final GroupRewriteBinding binding;

    private final int timeout, timestampIndex;

    private final BoundedRowCodec rowCodec, keyCodec;

    private final String structure;

    /**
     * 校验构造参数并初始化 {@code TdengineGroupRewrite}。
     */
    TdengineGroupRewrite(DataSource source, String schema, String table, List<String> fields,
            TdengineJdbcSupport.Table metadata, GroupRewriteBinding binding, int timeout,
            TdengineNativeTables.RecoveryIdentity admittedIdentity) {
        this.source = source;
        this.schema = schema;
        this.table = table;
        this.fields = List.copyOf(fields);
        this.keys = metadata.keys();
        this.metadata = metadata;
        this.binding = Objects.requireNonNull(binding);
        this.timeout = timeout;
        this.timestampIndex = fields.indexOf(keys.getFirst());
        this.rowCodec = new BoundedRowCodec(fields.size(), binding.limits().maxBatchBytes());
        this.keyCodec = new BoundedRowCodec(keys.size(), binding.limits().maxBatchBytes());
        binding.checkOwnership();
        try (var connection = source.getConnection()) {
            var identity = TdengineNativeTables.recoveryIdentity(connection, schema, table, timeout);
            var currentMetadata = TdengineJdbcSupport.metadata(connection, schema, table, timeout);
            var after = TdengineNativeTables.recoveryIdentity(connection, schema, table, timeout);
            if (!identity.physical().equals(binding.physicalFingerprint()) || !identity.equals(after)
                    || !identity.equals(admittedIdentity) || !metadata.equals(currentMetadata)) {
                throw invalid();
            }
            this.structure = identity.structure();
        } catch (Exception failure) {
            throw new IllegalStateException("TDengine recovery physical identity preflight failed");
        }
    }

    /** 必须在比较器读取目标快照前运行，包括不存在后续 DELETE 回调的情况。 */
    void reconcilePending() {
        String after = null;
        do {
            binding.checkOwnership();
            var page = binding.store().pendingKeys(binding.scope(), binding.physicalFingerprint(), after, 64);
            if (!page.keys()
                    .isEmpty() && binding.resumeMode() != GroupRewriteBinding.ResumeMode.RECONCILE_AFTER_PRIOR_EXECUTOR_EXIT) {
                throw new IllegalStateException(
                        "TDengine pending group requires explicit reconciliation after the prior executor exited");
            }
            for (var key : page.keys()) {
                var record = binding.store().read(key).orElseThrow(TdengineGroupRewrite::invalid);
                if (record.phase() == GroupRewriteRecord.Phase.APPLIED) {
                    continue;
                }
                validate(record);
                if (!record.ownerToken().equals(binding.ownerToken())) {
                    record = binding.store().claimRecovery(key, record.revision(), binding.ownerToken())
                            .orElseThrow(TdengineGroupRewrite::invalid);
                }
                final var owned = record;
                // 即使当前进程没有发送，先前进程也可能已经发送目标写入。
                try {
                    NonTransactionalWriteExecution.execute(source, (connection, attempt) -> {
                        replay(connection, attempt, owned);
                        return 0;
                    });
                } catch (Exception failure) {
                    throw new UnconfirmedWriteException();
                }
            }
            after = page.nextAfter();
        } while (after != null);
    }

    /**
     * 删除当前受管文件，或由包装器保留交由外部生命周期清理。
     */
    long delete(List<Object[]> requested) {
        return NonTransactionalWriteExecution.execute(source, (connection, attempt) -> {
            verifyIdentity(connection);
            var groups = new LinkedHashMap<Long, List<Object[]>>();
            for (var row : requested) {
                groups.computeIfAbsent(epoch(row), ignored -> new ArrayList<>()).add(row);
            }
            var planned = new ArrayList<GroupRewriteMaterial>();
            long retainedBytes = 0;
            // 首次目标请求前，校验每个完整组及总恢复容量。
            for (var entry : groups.entrySet()) {
                var before = readGroup(connection, entry.getKey());
                var present = index(before);
                for (var row : entry.getValue()) {
                    var observed = present.remove(key(row));
                    if (observed == null || !Arrays.equals(rowCodec.encode(observed), rowCodec.encode(row))) {
                        throw invalid();
                    }
                }
                var material = GroupRewriteMaterial.create(binding.fingerprint(), binding.physicalFingerprint(),
                        structure,
                        fields, keys, before, before.stream().filter(row -> present.containsKey(key(row))).toList(),
                        binding.limits());
                retainedBytes = Math.addExact(retainedBytes, material.encoded().length);
                if (retainedBytes > binding.limits().maxBatchBytes()) {
                    throw invalid();
                }
                planned.add(material);
            }
            long deleted = 0;
            for (var material : planned) {
                binding.checkOwnership();
                var key = groupKey(epoch(material.before().getFirst()));
                var previous = binding.store().read(key);
                if (previous.isPresent() && previous.get().phase() != GroupRewriteRecord.Phase.APPLIED) {
                    throw invalid();
                }
                var prepared = binding.store().prepare(key, binding.ownerToken(), material,
                        previous.map(GroupRewriteRecord::revision).orElse(-1L));
                var durable = binding.store().read(key).orElseThrow(TdengineGroupRewrite::invalid);
                if (durable.revision() != prepared.revision() || !durable.material().hash().equals(material.hash())
                        || durable.phase() != GroupRewriteRecord.Phase.PREPARED || !durable.ownerToken()
                        .equals(binding.ownerToken())) {
                    throw invalid();
                }
                replay(connection, attempt, durable);
                deleted += material.logicalDeletes();
            }
            return deleted;
        });
    }

    /**
     * 依据唯一键将数据更新到已有记录或插入新记录。
     */
    long upsert(List<Object[]> rows, boolean insert) {
        return NonTransactionalWriteExecution.execute(source, (connection, attempt) -> {
            verifyIdentity(connection);
            var initial = new LinkedHashMap<Long, Map<ByteBuffer, Object[]>>();
            long retainedBytes = 0;
            for (var row : rows) {
                long epoch = epoch(row);
                if (!initial.containsKey(epoch)) {
                    var group = readGroup(connection, epoch);
                    for (var existing : group) {
                        retainedBytes = Math.addExact(retainedBytes, rowCodec.encode(existing).length);
                    }
                    if (retainedBytes > binding.limits().maxBatchBytes()) {
                        throw invalid();
                    }
                    initial.put(epoch, index(group));
                }
                if (initial.get(epoch).containsKey(key(row)) == insert) {
                    throw invalid();
                }
            }
            long confirmed = 0;
            for (var row : rows) {
                verifyIdentity(connection);
                TdengineJdbcSupport.insert(connection, attempt, schema, table, fields, metadata,
                        Collections.singletonList(row), timeout);
                initial.get(epoch(row)).put(key(row), row);
                requireSame(readGroup(connection, epoch(row)), new ArrayList<>(initial.get(epoch(row)).values()));
                confirmed++;
            }
            return confirmed;
        });
    }

    /**
     * 根据恢复材料重放当前目标对象或数据操作。
     */
    private void replay(Connection connection, NonTransactionalWriteExecution.Attempt attempt,
            GroupRewriteRecord record) throws Exception {
        validate(record);
        verifyIdentity(connection);
        var material = record.material();
        long epoch = epoch(material.before().getFirst());
        var current = readGroup(connection, epoch);
        if (record.phase() == GroupRewriteRecord.Phase.PREPARED) {
            requireSame(current, material.before());
            record = advance(record, GroupRewriteRecord.Phase.DELETE_INTENT);
        }
        if (record.phase() == GroupRewriteRecord.Phase.DELETE_INTENT) {
            requireSubset(current, material.before());
            if (!current.isEmpty()) {
                verifyIdentity(connection);
                try (var statement = connection.createStatement()) {
                    statement.setQueryTimeout(timeout);
                    binding.checkOwnership();
                    attempt.sending();
                    long affected = statement.executeUpdate("DELETE FROM " + TdengineJdbcSupport.table(schema, table)
                            + " WHERE " + TdengineJdbcSupport.quote(keys.getFirst()) + "=" + epoch);
                    if (affected != current.size()) {
                        throw invalid();
                    }
                }
            }
            if (!readGroup(connection, epoch).isEmpty()) {
                throw invalid();
            }
            record = advance(record, GroupRewriteRecord.Phase.RESTORE_INTENT);
            current = List.of();
        }
        if (record.phase() == GroupRewriteRecord.Phase.RESTORE_INTENT) {
            requireSubset(current, material.after());
            var already = index(current);
            for (var row : material.after()) {
                if (!already.containsKey(key(row))) {
                    verifyIdentity(connection);
                    TdengineJdbcSupport.insert(connection, attempt, schema, table, fields, metadata,
                            Collections.singletonList(row), timeout);
                }
            }
            verifyIdentity(connection);
            requireSame(readGroup(connection, epoch), material.after());
            advance(record, GroupRewriteRecord.Phase.APPLIED);
        }
    }

    /**
     * 根据当前状态推进游标或操作阶段。
     */
    private GroupRewriteRecord advance(GroupRewriteRecord record, GroupRewriteRecord.Phase next) {
        binding.checkOwnership();
        return binding.store().transition(record.key(), binding.ownerToken(), record.revision(), record.phase(), next)
                .orElseThrow(TdengineGroupRewrite::invalid);
    }

    /**
     * 检查 TDengine 分组重写记录、恢复材料及资源绑定一致。
     */
    private void validate(GroupRewriteRecord record) {
        var material = record.material();
        if (!record.key().scope().equals(binding.scope()) || !record.key().resource()
                .equals(binding.physicalFingerprint())
                || !material.physicalFingerprint()
                .equals(binding.physicalFingerprint()) || !material.bindingFingerprint().equals(binding.fingerprint())
                || !material.structureFingerprint().equals(structure) || !material.fields()
                .equals(fields) || !material.keys().equals(keys)) {
            throw invalid();
        }
        var before = material.before();
        long expected = epoch(before.getFirst());
        if (!record.key().equals(groupKey(expected))) {
            throw invalid();
        }
        for (var row : before) {
            if (epoch(row) != expected) {
                throw invalid();
            }
            // 发送目标请求前，仍按当前精确原生编解码器校验持久化数据。
            for (int i = 0; i < row.length; i++) {
                if (i != timestampIndex) {
                    var normalized = TdengineJdbcSupport.convert(row[i], metadata.column(fields.get(i)),
                            metadata.fractionalDigits());
                    if (!Objects.deepEquals(row[i], normalized)) {
                        throw invalid();
                    }
                }
            }
        }
        GroupRewriteMaterial.decode(material.encoded(), binding.limits());
    }

    /**
     * 校验身份信息。
     */
    private void verifyIdentity(Connection connection) throws Exception {
        binding.checkOwnership();
        var current = TdengineNativeTables.recoveryIdentity(connection, schema, table, timeout);
        if (!current.physical().equals(binding.physicalFingerprint()) || !current.structure().equals(structure)) {
            throw invalid();
        }
        binding.checkOwnership();
    }

    /**
     * 读取分组。
     */
    private List<Object[]> readGroup(Connection connection, long epoch) throws Exception {
        binding.checkOwnership();
        var rows = new ArrayList<Object[]>();
        long bytes = 0;
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            try (var result = statement.executeQuery("SELECT " + TdengineJdbcSupport.columns(fields) + " FROM "
                    + TdengineJdbcSupport.table(schema, table) + " WHERE " + TdengineJdbcSupport.quote(
                    keys.getFirst()) + "=" + epoch
                    + " LIMIT " + Math.addExact((long) binding.limits().maxRows(), 1))) {
                while (result.next()) {
                    if (rows.size() == binding.limits().maxRows()) {
                        throw invalid();
                    }
                    Object[] row = new Object[fields.size()];
                    for (int i = 0; i < row.length; i++) {
                        row[i] = TdengineJdbcSupport.convert(result.getObject(i + 1), metadata.column(fields.get(i)),
                                metadata.fractionalDigits());
                    }
                    cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter.convert(
                            Collections.singletonList(row), fields.size(), binding.limits());
                    bytes = Math.addExact(bytes, rowCodec.encode(row).length);
                    if (bytes > binding.limits().maxBatchBytes()) {
                        throw invalid();
                    }
                    rows.add(row);
                }
            }
        }
        index(rows);
        return rows;
    }

    /**
     * 返回索引。
     */
    private Map<ByteBuffer, Object[]> index(List<Object[]> rows) {
        var index = new LinkedHashMap<ByteBuffer, Object[]>();
        for (var row : rows) {
            if (index.put(key(row), row) != null) {
                throw invalid();
            }
        }
        return index;
    }

    /**
     * 返回键。
     */
    private ByteBuffer key(Object[] row) {
        Object[] key = new Object[keys.size()];
        for (int i = 0; i < key.length; i++) {
            key[i] = GroupRewriteMaterial.canonicalKey(row[fields.indexOf(keys.get(i))]);
            if (key[i] == null) {
                throw invalid();
            }
        }
        return ByteBuffer.wrap(keyCodec.encode(key));
    }

    /**
     * 校验并取得子集。
     */
    private void requireSubset(List<Object[]> subset, List<Object[]> superset) {
        var expected = index(superset);
        for (var row : subset) {
            var matched = expected.remove(key(row));
            if (matched == null || !Arrays.equals(rowCodec.encode(matched), rowCodec.encode(row))) {
                throw invalid();
            }
        }
    }

    /**
     * 校验并取得相同。
     */
    private void requireSame(List<Object[]> first, List<Object[]> second) {
        if (first.size() != second.size()) {
            throw invalid();
        }
        requireSubset(first, second);
    }

    /**
     * 返回当前纪元。
     */
    private long epoch(Object[] row) {
        if (!(row[timestampIndex] instanceof Long value)) {
            throw invalid();
        }
        return value;
    }

    /**
     * 根据当前输入构建并返回 {@code GroupRewriteKey}。
     */
    private GroupRewriteKey groupKey(long epoch) {
        return new GroupRewriteKey(binding.scope(), binding.physicalFingerprint(), GroupRewriteMaterial.sha256(
                ("TDENGINE_TIMESTAMP_GROUP_V1\0" + epoch).getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static IllegalStateException invalid() {
        return new IllegalStateException(
                "TDengine group state cannot be reconciled with its durable recovery evidence");
    }
}
