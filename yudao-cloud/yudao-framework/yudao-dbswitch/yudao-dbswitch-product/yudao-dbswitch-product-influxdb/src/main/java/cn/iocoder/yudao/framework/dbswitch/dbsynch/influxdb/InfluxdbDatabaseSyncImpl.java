package cn.iocoder.yudao.framework.dbswitch.dbsynch.influxdb;

import cn.iocoder.yudao.framework.dbswitch.common.config.*;
import cn.iocoder.yudao.framework.dbswitch.common.util.*;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.*;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.*;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.recovery.*;
import cn.iocoder.yudao.framework.dbswitch.product.influxdb3.*;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** 共享比较会暂存完整后镜像；任何暂存行都不代表目标确认。 */
public final class InfluxdbDatabaseSyncImpl implements IDatabaseSynchronize {

    private final DataSource source;

    private DbSwitchOptions options = DbSwitchOptions.defaults();

    private BatchLimits batch = BatchLimits.defaults();

    private TableRewriteBindingProvider provider;

    private Difference prepared;

    private volatile List<NativeRecoveryResource> preparationResources = List.of();

    /**
     * 校验构造参数并初始化 {@code InfluxdbDatabaseSyncImpl}。
     */
    public InfluxdbDatabaseSyncImpl(DataSource source) {
        this.source = Objects.requireNonNull(source);
    }

    /**
     * 获取数据源对象。
     */
    @Override
    public DataSource getDataSource() {
        return source;
    }

    /**
     * 在 prepare 前配置新操作；自定义选项不得被静默忽略。
     */
    @Override
    public void configure(DbSwitchOptions options) {
        this.options = Objects.requireNonNull(options);
    }

    /**
     * 设置写入批次的行数、字节数及绑定参数限制。
     */
    @Override
    public void configureBatchLimits(BatchLimits batch) {
        this.batch = Objects.requireNonNull(batch);
    }

    /**
     * 绑定表重写提供器。
     */
    @Override
    public void bindTableRewriteProvider(TableRewriteBindingProvider provider) {
        this.provider = Objects.requireNonNull(provider);
    }

    /**
     * 说明当前同步路径是否允许使用未声明唯一约束的匹配键。
     */
    @Override
    public boolean supportsUnconstrainedMatchKeys() {
        return true;
    }

    /**
     * 校验目标表及列映射并准备批次写入资源。
     */
    @Override
    public void prepare(String schema, String table, List<String> fields, List<String> keys) {
        prepared = null;
        preparationResources = List.of();
        InfluxNativeCatalog.schema(schema);
        if (provider == null) {
            throw new IllegalArgumentException(
                    "Influx DIFF requires durable table recovery admission and exclusive target ownership");
        }
        try {
            var definition = InfluxNativeTables.describe(source, schema, table, batch.maxBatchBytes(),
                    options.queryTimeoutSeconds());
            var expected = InfluxNativeTables.exactDefinition(definition, table);
            if (fields == null || fields.isEmpty() || fields.size() != new HashSet<>(
                    fields).size() || !expected.fields().containsAll(fields)
                    || !fields.containsAll(expected.keys()) || !new HashSet<>(expected.keys()).equals(
                    new HashSet<>(keys)) || keys.size() != expected.keys().size()) {
                throw invalid();
            }
            String logical = InfluxNativeTables.logicalResource(definition, table);
            provider.authorizeManagementProbe(schema, table, logical);
            TableRewriteIdentity identity;
            try (var connection = source.getConnection()) {
                var session = InfluxNativeSession.open(source, connection, batch.maxBatchBytes(),
                        options.queryTimeoutSeconds());
                identity = InfluxTableRewriteTarget.identity(InfluxNativeIdentity.capture(session, expected,
                        InfluxNativeIdentity.ManagementProbePermission.EXCLUSIVE_TARGET_REPLACEMENT,
                        resource -> preparationResources = resource.map(cache -> List.of(new NativeRecoveryResource(
                                cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.INFLUXDB3,
                                cache.database(), cache.recoveryName(), NativeRecoveryResource.Role.STAGING,
                                cache.fingerprint()))).orElseGet(List::of)));
            }
            var binding = Objects.requireNonNull(provider.resolve(schema, table, logical, identity,
                    GroupRewriteMaterial.sha256(definition.document().getBytes(StandardCharsets.UTF_8))));
            binding.checkOwnership();
            if (!binding.identity().equals(identity)) {
                throw invalid();
            }
            String after = null;
            int scanned = 0;
            do {
                if (++scanned > binding.limits().maxPages()) {
                    throw invalid();
                }
                var page = binding.store().pending(binding.scope(), logical, after, 256);
                if (!page.keys().isEmpty()) {
                    throw new IllegalStateException(
                            "Influx pending table rewrite requires explicit recovery; a new DIFF cannot claim it");
                }
                after = page.nextAfter();
            } while (after != null);
            var target = new InfluxTableRewriteTarget(source, binding.limits(), options.queryTimeoutSeconds(),
                    binding::checkOwnership);
            List<Object[]> rows;
            try {
                rows = target.snapshot(expected);
                if (!identity.equals(target.capture(expected))) {
                    throw invalid();
                }
            } finally {
                preparationResources = target.recoveryResources(schema, table, identity);
            }
            prepared = new Difference(schema, table, logical, definition, expected, List.copyOf(fields), binding,
                    target, rows);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "Influx whole-table difference preflight failed before target data changes");
        }
    }

    /**
     * 返回已经准备完成的全表差异计算计划。
     */
    @Override
    public WholeTableDifference wholeTableDifference() {
        if (prepared == null) {
            throw new IllegalStateException("Influx DIFF is not prepared");
        }
        return prepared;
    }

    /**
     * 即使已准入的管理探测之后 prepare 失败，仍保留精确原生资源。
     */
    @Override
    public List<NativeRecoveryResource> recoveryResources() {
        return prepared == null ? preparationResources : prepared.recoveryResources();
    }

    /**
     * 显式恢复已认证且已认领的材料；绝不重新读取或比对源。
     */
    @Override
    public void reconcileTableRewrite(TableRewriteBinding binding, TableRewriteRecord record) {
        prepared = null;
        preparationResources = List.of();
        Objects.requireNonNull(binding);
        Objects.requireNonNull(record);
        binding.checkOwnership();
        if (!binding.scope().equals(record.key().scope()) || !binding.ownerToken().equals(record.ownerToken())
                || !binding.fingerprint().equals(record.material().bindingFingerprint()) || !binding.identity()
                .equals(record.material().oldIdentity())) {
            throw invalid();
        }
        var target = new InfluxTableRewriteTarget(source, binding.limits(), options.queryTimeoutSeconds(),
                binding::checkOwnership);
        try {
            new TableRewriteExecution(binding.store(), binding::checkOwnership).reconcileClaimed(record, target);
        } finally {
            preparationResources = target.recoveryResources(record.material().schema(), record.material().table(),
                    binding.identity());
        }
    }

    /**
     * 执行参数化数据插入操作。
     */
    @Override
    public long executeInsert(List<Object[]> rows) {
        throw stagedOnly();
    }

    /**
     * 批量数据Update。
     */
    @Override
    public long executeUpdate(List<Object[]> rows) {
        throw stagedOnly();
    }

    /**
     * 批量数据Delete。
     */
    @Override
    public long executeDelete(List<Object[]> rows) {
        throw stagedOnly();
    }

    private final class Difference implements WholeTableDifference {

        private final String schema, table, logical;

        private final NativeTableDefinition definition;

        private final InfluxNativeCatalog.Table nativeTable;

        private final List<String> incoming;

        private final TableRewriteBinding binding;

        private final InfluxTableRewriteTarget target;

        private final BoundedRowCodec rowsCodec, keysCodec;

        private final int[] keyPositions;

        private final LinkedHashMap<ByteBuffer, byte[]> before = new LinkedHashMap<>(), after = new LinkedHashMap<>();

        private long beforeBytes, afterBytes, changed;

        private boolean sealed;

        /**
         * 初始化 {@code Difference}。
         */
        Difference(String schema, String table, String logical, NativeTableDefinition definition,
                InfluxNativeCatalog.Table nativeTable, List<String> incoming,
                TableRewriteBinding binding, InfluxTableRewriteTarget target, List<Object[]> rows) {
            this.schema = schema;
            this.table = table;
            this.logical = logical;
            this.definition = definition;
            this.nativeTable = nativeTable;
            this.incoming = incoming;
            this.binding = binding;
            this.target = target;
            rowsCodec = new BoundedRowCodec(definition.fields().size(), binding.limits().page().maxBatchBytes());
            keysCodec = new BoundedRowCodec(nativeTable.keys().size(), binding.limits().page().maxBatchBytes());
            keyPositions = nativeTable.keys().stream().mapToInt(definition.fields()::indexOf).toArray();
            for (var row : rows) {
                byte[] encoded = rowsCodec.encode(row);
                var key = key(row);
                if (before.put(key, encoded) != null) {
                    throw invalid();
                }
                after.put(key, encoded);
                beforeBytes = Math.addExact(beforeBytes, encoded.length);
                afterBytes = beforeBytes;
            }
            capacity();
        }

        /**
         * 准备当前阶段的数据或目标状态。
         */
        @Override
        public void stage(Change change, List<Object[]> records) {
            if (sealed) {
                throw invalid();
            }
            binding.checkOwnership();
            Objects.requireNonNull(change);
            for (Object[] record : records) {
                var owned = BatchValueConverter.convert(Collections.singletonList(record), incoming.size(),
                        binding.limits().page()).getFirst();
                var supplied = new Object[definition.fields().size()];
                for (int i = 0; i < incoming.size(); i++) {
                    supplied[definition.fields().indexOf(incoming.get(i))] = owned[i];
                }
                normalize(supplied);
                ByteBuffer key = key(supplied);
                byte[] prior = after.get(key);
                if (change == Change.DELETE) {
                    if (prior == null) {
                        throw invalid();
                    }
                    after.remove(key);
                    afterBytes -= prior.length;
                } else {
                    if (change == Change.INSERT && prior != null || change == Change.UPDATE && prior == null) {
                        throw invalid();
                    }
                    Object[] value = change == Change.UPDATE ? rowsCodec.decode(prior) : supplied;
                    if (change == Change.UPDATE) {
                        for (int i = 0; i < incoming.size(); i++) {
                            value[definition.fields().indexOf(incoming.get(i))] = supplied[definition.fields()
                                    .indexOf(incoming.get(i))];
                        }
                    }
                    InfluxLineProtocol.batch(nativeTable, definition.fields(), Collections.singletonList(value),
                            binding.limits().page().maxBatchBytes());
                    byte[] encoded = rowsCodec.encode(value);
                    after.put(key, encoded);
                    afterBytes = Math.addExact(afterBytes - (prior == null ? 0 : prior.length), encoded.length);
                }
                changed = Math.incrementExact(changed);
                capacity();
            }
        }

        /**
         * 将值集合整理为当前协议使用的规范形式。
         */
        private void normalize(Object[] values) {
            int index = definition.fields().indexOf("time");
            long nanos = InfluxLineProtocol.nanos(values[index]);
            values[index] = Instant.ofEpochSecond(Math.floorDiv(nanos, 1_000_000_000L),
                    Math.floorMod(nanos, 1_000_000_000L));
            for (int i = 0; i < values.length; i++) {
                if (values[i] != null && nativeTable.columns().get(i).kind() == InfluxNativeCatalog.Kind.UINT64) {
                    Object value = values[i];
                    values[i] = value instanceof java.math.BigInteger integer ? integer : value instanceof java.math.BigDecimal decimal ? decimal.toBigIntegerExact() :
                            value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long ? java.math.BigInteger.valueOf(
                                    ((Number) value).longValue()) : value;
                }
            }
        }

        /**
         * 返回键。
         */
        private ByteBuffer key(Object[] row) {
            var key = new Object[keyPositions.length];
            for (int i = 0; i < key.length; i++) {
                key[i] = row[keyPositions[i]];
                if (key[i] == null) {
                    throw new IllegalArgumentException("Influx DIFF currently requires non-null native tag/time keys");
                }
            }
            return ByteBuffer.wrap(keysCodec.encode(key));
        }

        /**
         * 检查恢复前后数据集合未超过行数和材料字节上限。
         */
        private void capacity() {
            if ((long) before.size() + after.size() > binding.limits()
                    .maxRows() || beforeBytes + afterBytes > binding.limits().maxMaterialBytes()) {
                throw new IllegalArgumentException("Influx whole-table recovery image exceeds its explicit capacity");
            }
        }

        /**
         * 对当前恢复材料进行封装或加密并返回可保存的载荷。
         */
        @Override
        public Prepared seal() {
            if (sealed) {
                throw invalid();
            }
            sealed = true;
            binding.checkOwnership();
            long count = changed;
            if (count == 0) {
                return new Prepared() {
                    /**
                     * 返回当前操作影响的数据或结构变更数量。
                     */
                    public long changeCount() {
                        return 0;
                    }

                    /**
                     * 根据当前内容计算摘要，供一致性比较使用。
                     */
                    public String digest() {
                        return GroupRewriteMaterial.sha256(definition.document().getBytes(StandardCharsets.UTF_8));
                    }

                    /**
                     * 根据当前同步类型及恢复绑定应用 InfluxDB 数据变更并记录确定结果。
                     */
                    public long execute() {
                        binding.checkOwnership();
                        return 0;
                    }
                };
            }
            var material = TableRewriteMaterial.create(binding.fingerprint(), schema, table, binding.identity(),
                    definition, nativeTable.keys(), before.values().stream().map(rowsCodec::decode).toList(),
                    after.values().stream().map(rowsCodec::decode).toList(), binding.limits());
            try {
                target.verifyBefore(material);
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Exception failure) {
                throw invalid();
            }
            var key = new TableRewriteKey(binding.scope(), logical,
                    GroupRewriteMaterial.sha256(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8)));
            binding.store().prepare(key, binding.ownerToken(), material);
            var used = new AtomicBoolean();
            return new Prepared() {
                /**
                 * 返回数量。
                 */
                public long changeCount() {
                    return count;
                }

                /**
                 * 根据当前内容计算摘要，供一致性比较使用。
                 */
                public String digest() {
                    return material.hash();
                }

                /**
                 * 根据当前同步类型及恢复绑定应用 InfluxDB 数据变更并记录确定结果。
                 */
                public long execute() {
                    if (!used.compareAndSet(false, true)) {
                        throw invalid();
                    }
                    new TableRewriteExecution(binding.store(), binding::checkOwnership).executePrepared(key,
                            binding.ownerToken(), target);
                    return count;
                }
            };
        }

        /**
         * 返回当前操作恢复时需要关联的资源信息。
         */
        @Override
        public List<NativeRecoveryResource> recoveryResources() {
            return target.recoveryResources(schema, table, binding.identity());
        }
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static IllegalStateException invalid() {
        return new IllegalStateException("Influx difference projection, key or recovery snapshot is inconsistent");
    }

    /**
     * 根据当前输入构建并返回 {@code UnsupportedOperationException}。
     */
    private static UnsupportedOperationException stagedOnly() {
        return new UnsupportedOperationException(
                "Influx changes require the complete durable whole-table difference lifecycle");
    }
}

