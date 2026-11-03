package cn.iocoder.yudao.framework.dbswitch.product.influxdb3;

import cn.iocoder.yudao.framework.dbswitch.common.util.BoundedRowCodec;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.*;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.recovery.*;

import javax.sql.DataSource;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

/** 中立日志背后的产品执行。控制面存储事务打开时，
 * 不发送目标请求。仅允许基于已持久化的原生代次恢复。 */
public final class InfluxTableRewriteTarget implements TableRewriteExecution.Target {

    private final DataSource source;

    private final TableRewriteLimits limits;

    private final int timeout;

    private final Runnable ownership;

    private volatile InfluxNativeIdentity.CacheResource pendingCache;

    /**
     * 校验构造参数并初始化 {@code InfluxTableRewriteTarget}。
     */
    public InfluxTableRewriteTarget(DataSource source, TableRewriteLimits limits, int timeout, Runnable ownership) {
        this.source = Objects.requireNonNull(source);
        this.limits = Objects.requireNonNull(limits);
        this.ownership = Objects.requireNonNull(ownership);
        if (timeout < 1) {
            throw new IllegalArgumentException();
        }
        this.timeout = timeout;
    }

    /**
     * 返回身份信息。
     */
    public static TableRewriteIdentity identity(InfluxNativeIdentity.Identity nativeId) {
        return new TableRewriteIdentity("influx-catalog-v1",
                nativeId.clusterUuid() + ":" + nativeId.databaseId() + ":" + nativeId.tableId());
    }

    /**
     * 根据当前输入构建并返回 {@code InfluxNativeIdentity.Identity}。
     */
    private static InfluxNativeIdentity.Identity nativeId(TableRewriteIdentity identity) {
        if (!identity.format().equals("influx-catalog-v1")) {
            throw invalid();
        }
        String[] parts = identity.document().split(":", -1);
        if (parts.length != 3) {
            throw invalid();
        }
        return new InfluxNativeIdentity.Identity(parts[0], Long.parseLong(parts[1]), Long.parseLong(parts[2]));
    }

    /**
     * 返回当前操作恢复时需要关联的资源信息。
     */
    public List<NativeRecoveryResource> recoveryResources(String schema, String table, TableRewriteIdentity original) {
        var resources = new ArrayList<NativeRecoveryResource>();
        if (original != null) {
            resources.add(new NativeRecoveryResource(
                    cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.INFLUXDB3, schema, table,
                    NativeRecoveryResource.Role.TARGET, original.fingerprint()));
        }
        var cache = pendingCache;
        if (cache != null) {
            resources.add(new NativeRecoveryResource(
                    cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.INFLUXDB3, cache.database(),
                    cache.recoveryName(), NativeRecoveryResource.Role.STAGING, cache.fingerprint()));
        }
        return List.copyOf(resources);
    }

    /**
     * 采集当前对象身份、结构或运行状态的快照。
     */
    public TableRewriteIdentity capture(InfluxNativeCatalog.Table table) throws Exception {
        try (var connection = source.getConnection()) {
            var session = session(connection);
            return identity(capture(session, table));
        }
    }

    /**
     * 采集当前对象身份、结构或运行状态的快照。
     */
    private InfluxNativeIdentity.Identity capture(InfluxNativeSession session,
            InfluxNativeCatalog.Table table) throws Exception {
        ownership.run();
        return InfluxNativeIdentity.capture(session, table,
                InfluxNativeIdentity.ManagementProbePermission.EXCLUSIVE_TARGET_REPLACEMENT,
                resource -> pendingCache = resource.orElse(null));
    }

    /**
     * 返回快照。
     */
    public List<Object[]> snapshot(InfluxNativeCatalog.Table table) throws Exception {
        try (var connection = source.getConnection()) {
            return snapshot(session(connection), table);
        }
    }

    /**
     * 返回快照。
     */
    private List<Object[]> snapshot(InfluxNativeSession session, InfluxNativeCatalog.Table table) throws Exception {
        var output = new ArrayList<Object[]>();
        long bytes = 0;
        var codec = new BoundedRowCodec(table.fields().size(), limits.page().maxBatchBytes());
        String projection = table.columns().stream()
                .map(c -> c.kind() == InfluxNativeCatalog.Kind.TIME ? "CAST(" + quote(
                        c.name()) + " AS BIGINT) AS " + quote(c.name()) : quote(c.name()))
                .collect(java.util.stream.Collectors.joining(","));
        String order = table.keys().stream().map(InfluxTableRewriteTarget::quote)
                .collect(java.util.stream.Collectors.joining(","));
        for (int offset = 0; ; ) {
            int size = Math.min(limits.page().maxRows(), limits.maxRows() - output.size() + 1);
            var rows = session.query("SELECT " + projection + " FROM " + quote(
                    table.name()) + " ORDER BY " + order + " LIMIT " + size + " OFFSET " + offset);
            if (!rows.isArray() || rows.size() > size) {
                throw invalid();
            }
            for (var row : rows) {
                if (!row.isObject() || output.size() >= limits.maxRows()) {
                    throw invalid();
                }
                var names = row.fieldNames();
                while (names.hasNext()) {
                    if (!table.fields().contains(names.next())) {
                        throw invalid();
                    }
                }
                var values = new Object[table.columns().size()];
                for (int index = 0; index < values.length; index++) {
                    var column = table.columns().get(index);
                    var value = row.get(column.name());
                    // Influx 3 JSON 省略 SQL NULL 字段。投影及完整原生
                    // 目录已固定；材料仍会拒绝缺失的键或时间。
                    if (value == null || value.isNull()) {
                        values[index] = null;
                        continue;
                    }
                    values[index] = switch (column.kind()) {
                        case TAG, STRING -> {
                            if (!value.isTextual()) {
                                throw invalid();
                            }
                            yield value.textValue();
                        }
                        case BOOLEAN -> {
                            if (!value.isBoolean()) {
                                throw invalid();
                            }
                            yield value.booleanValue();
                        }
                        case INT64 -> {
                            if (!value.isIntegralNumber() || !value.canConvertToLong()) {
                                throw invalid();
                            }
                            yield value.longValue();
                        }
                        case UINT64 -> {
                            if (!value.isIntegralNumber() || value.bigIntegerValue()
                                    .signum() < 0 || value.bigIntegerValue().bitLength() > 64) {
                                throw invalid();
                            }
                            yield value.bigIntegerValue();
                        }
                        case FLOAT64 -> {
                            if (!value.isNumber() || !Double.isFinite(value.doubleValue())) {
                                throw invalid();
                            }
                            yield value.doubleValue();
                        }
                        case TIME -> {
                            if (!value.isIntegralNumber() || !value.canConvertToLong()) {
                                throw invalid();
                            }
                            long nanos = value.longValue();
                            yield Instant.ofEpochSecond(Math.floorDiv(nanos, 1_000_000_000L),
                                    Math.floorMod(nanos, 1_000_000_000L));
                        }
                    };
                }
                bytes = Math.addExact(bytes, codec.encode(values).length);
                if (bytes > limits.maxMaterialBytes()) {
                    throw invalid();
                }
                output.add(values);
            }
            if (rows.size() < size) {
                break;
            }
            offset = Math.addExact(offset, rows.size());
        }
        return List.copyOf(output);
    }

    /**
     * 精确的原始原生标识、schema、完整前映像及排他 DML/DDL。
     */
    @Override
    public void verifyBefore(TableRewriteMaterial material) throws Exception {
        try (var connection = source.getConnection()) {
            var session = session(connection);
            var expected = InfluxNativeTables.exactDefinition(material.definition(), material.table());
            requireDefinition(session, material, expected);
            InfluxNativeTables.requireOrdinary(session, material.table());
            if (!material.oldIdentity().equals(identity(capture(session, expected)))) {
                throw invalid();
            }
            requireImage(material, TableRewriteMaterial.Image.BEFORE, snapshot(session, expected));
            if (!material.oldIdentity().equals(identity(capture(session, expected)))) {
                throw invalid();
            }
        }
    }

    /**
     * 重新核对目标事实后删除当前恢复材料指定的对象。
     */
    @Override
    public void drop(TableRewriteMaterial material) throws Exception {
        verifyBefore(material);
        try (var connection = source.getConnection()) {
            var session = session(connection);
            var expected = InfluxNativeTables.exactDefinition(material.definition(), material.table());
            if (!material.oldIdentity().equals(identity(capture(session, expected)))) {
                throw invalid();
            }
            ownership.run();
            session.json("DELETE", "/api/v3/configure/table?db=" + InfluxNativeSession.encode(
                    session.database()) + "&table=" + InfluxNativeSession.encode(material.table()), null, 200);
            if (InfluxNativeCatalog.find(session, material.table()) != null || !nativeId(
                    material.oldIdentity()).clusterUuid().equals(session.clusterIdentity())) {
                throw invalid();
            }
        }
    }

    /**
     * 要求资源不存在；创建并获取实际原生标识后才返回。
     */
    @Override
    public TableRewriteIdentity create(TableRewriteMaterial material) throws Exception {
        try (var connection = source.getConnection()) {
            var session = session(connection);
            var before = nativeId(material.oldIdentity());
            var expected = InfluxNativeTables.exactDefinition(material.definition(), material.table());
            if (!before.clusterUuid().equals(session.clusterIdentity()) || InfluxNativeCatalog.find(session,
                    material.table()) != null) {
                throw invalid();
            }
            ownership.run();
            session.json("POST", "/api/v3/configure/table",
                    InfluxNativeTables.createRequest(session.database(), expected), 200);
            requireDefinition(session, material, expected);
            var created = capture(session, expected);
            if (created.equals(before) || created.databaseId() != before.databaseId() || !created.clusterUuid()
                    .equals(before.clusterUuid())) {
                throw invalid();
            }
            return identity(created);
        }
    }

    /**
     * 检查此前已持久化的原生代际，绝不在此处接受替代代际。
     */
    @Override
    public void verifyGeneration(TableRewriteMaterial material, TableRewriteIdentity identity) throws Exception {
        try (var connection = source.getConnection()) {
            var session = session(connection);
            var expected = InfluxNativeTables.exactDefinition(material.definition(), material.table());
            requireDefinition(session, material, expected);
            if (!identity.equals(identity(capture(session, expected)))) {
                throw invalid();
            }
        }
    }

    /**
     * 在同一持有代际上重放页面，对此产品必须幂等。
     */
    @Override
    public void restore(TableRewriteMaterial material, TableRewriteIdentity identity,
            TableRewriteMaterial.Page page) throws Exception {
        var expected = InfluxNativeTables.exactDefinition(material.definition(), material.table());
        byte[] bytes = InfluxLineProtocol.batch(expected, material.fields(), material.rows(page, limits),
                limits.page().maxBatchBytes());
        try (var connection = source.getConnection()) {
            var session = session(connection);
            requireDefinition(session, material, expected);
            if (!identity.equals(identity(capture(session, expected)))) {
                throw invalid();
            }
            ownership.run();
            session.write(bytes);
        }
    }

    /**
     * APPLIED 前完整回读目标，确保值精确且没有额外行。
     */
    @Override
    public void verifyAfter(TableRewriteMaterial material, TableRewriteIdentity identity) throws Exception {
        try (var connection = source.getConnection()) {
            var session = session(connection);
            var expected = InfluxNativeTables.exactDefinition(material.definition(), material.table());
            requireDefinition(session, material, expected);
            if (!identity.equals(identity(capture(session, expected)))) {
                throw invalid();
            }
            requireImage(material, TableRewriteMaterial.Image.AFTER, snapshot(session, expected));
            if (!identity.equals(identity(capture(session, expected)))) {
                throw invalid();
            }
        }
    }

    /**
     * 校验并取得定义。
     */
    private void requireDefinition(InfluxNativeSession session, TableRewriteMaterial material,
            InfluxNativeCatalog.Table expected) throws Exception {
        var document = InfluxNativeSession.JSON.readTree(material.definition().document());
        if (!expected.equals(InfluxNativeCatalog.require(session, material.table())) || !InfluxNativeCatalog.text(
                document, "sourceCluster").equals(session.clusterIdentity())
                || !InfluxNativeCatalog.text(document, "sourceDatabase").equals(session.database())
                || !InfluxNativeCatalog.text(document, "retention").equals(InfluxNativeTables.retention(session))) {
            throw invalid();
        }
    }

    /**
     * 校验并取得镜像。
     */
    private void requireImage(TableRewriteMaterial material, TableRewriteMaterial.Image image, List<Object[]> actual) {
        var expected = new ArrayList<Object[]>();
        for (var page : material.pages()) {
            if (page.image() == image) {
                expected.addAll(material.rows(page, limits));
            }
        }
        if (!frames(expected, material.fields().size()).equals(frames(actual, material.fields().size()))) {
            throw invalid();
        }
    }

    /**
     * 按协议读取或生成数据交换帧。
     */
    private Set<ByteBuffer> frames(List<Object[]> rows, int fields) {
        var codec = new BoundedRowCodec(fields, limits.page().maxBatchBytes());
        var result = new HashSet<ByteBuffer>();
        for (var row : rows) {
            if (!result.add(ByteBuffer.wrap(codec.encode(row)))) {
                throw invalid();
            }
        }
        return result;
    }

    /**
     * 返回会话。
     */
    private InfluxNativeSession session(java.sql.Connection connection) throws SQLException {
        return InfluxNativeSession.open(source, connection,
                Math.min(limits.page().maxBatchBytes() * 2, 16L * 1024 * 1024), timeout);
    }

    /**
     * 按当前数据库方言转义并引用标识符。
     */
    private static String quote(String identifier) {
        InfluxNativeSession.identifier(identifier);
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static IllegalStateException invalid() {
        return new IllegalStateException("Influx recovery image, native generation or namespace precondition differs");
    }
}
