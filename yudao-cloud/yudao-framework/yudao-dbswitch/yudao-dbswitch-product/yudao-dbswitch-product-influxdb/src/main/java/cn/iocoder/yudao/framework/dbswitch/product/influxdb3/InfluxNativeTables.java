package cn.iocoder.yudao.framework.dbswitch.product.influxdb3;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.*;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import com.fasterxml.jackson.databind.JsonNode;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** 显式 Core 表管理。API 成功不代表 JDBC 事务或原子替换。
 * 独占替换包含创建和删除临时最新值缓存，用于观察真实目录编号；
 * 除独占目标 DDL 外还需要管理权限。只读源
 * 元数据、追加到已有表及创建新表都不执行管理探测。 */
public final class InfluxNativeTables {

    private static final String FORMAT = "influxdb3-core-columns-v1";

    /**
     * 初始化 {@code InfluxNativeTables}。
     */
    private InfluxNativeTables() {
    }

    /**
     * 根据当前输入构建对象或结构的描述信息。
     */
    public static NativeTableDefinition describe(DataSource source, String schema, String name, long limit,
            int timeout) {
        InfluxNativeCatalog.schema(schema);
        try (var connection = source.getConnection()) {
            var session = InfluxNativeSession.open(source, connection, limit, timeout);
            var table = InfluxNativeCatalog.require(session, name);
            requireOrdinary(session, name);
            var envelope = InfluxNativeSession.JSON.createObjectNode();
            envelope.put("source", session.logicalTable(name));
            envelope.put("sourceDatabase", session.database());
            envelope.put("sourceTable", name);
            envelope.put("sourceCluster", session.clusterIdentity());
            envelope.set("columns", InfluxNativeSession.JSON.valueToTree(table.columns()));
            envelope.put("retention", retention(session));
            // 公开 Core 3.11 目录暴露列角色，不暴露创建编号或原始标签顺序。
            // 不得从逻辑端点或结构哈希伪造物理代次。
            return new NativeTableDefinition(ProductTypeEnum.INFLUXDB3, FORMAT, envelope.toString(), table.fields());
        } catch (Exception invalid) {
            throw new IllegalStateException("Influx native source structure cannot be safely captured");
        }
    }

    /**
     * 返回计划。
     */
    public static NativeTargetPlan plan(DataSource target, NativeTableDefinition definition, String schema, String name,
            Map<String, String> mapping, boolean replace, long limit, int timeout) {
        InfluxNativeCatalog.schema(schema);
        InfluxNativeSession.identifier(name);
        if (definition.product() != ProductTypeEnum.INFLUXDB3 || !definition.format().equals(FORMAT)) {
            throw new IllegalArgumentException("Influx native table creation requires native Core structure evidence");
        }
        final InfluxNativeCatalog.Table expected, previous;
        final String sourceLogical, sourceRetention, targetCluster;
        try {
            JsonNode envelope = InfluxNativeSession.JSON.readTree(definition.document());
            var columns = new ArrayList<InfluxNativeCatalog.Column>();
            if (!envelope.path("columns").isArray() || envelope.path("columns").size() > 500) {
                throw new IllegalArgumentException();
            }
            for (var column : envelope.path("columns")) {
                columns.add(new InfluxNativeCatalog.Column(InfluxNativeCatalog.text(column, "name"),
                        InfluxNativeCatalog.Kind.valueOf(InfluxNativeCatalog.text(column, "kind"))));
            }
            var original = new InfluxNativeCatalog.Table(name, columns);
            if (!original.fields().equals(definition.fields()) || new HashSet<>(
                    original.fields()).size() != original.fields().size()
                    || mapping == null || !original.fields().containsAll(mapping.keySet()) || !mapping.keySet()
                    .containsAll(original.keys())
                    || new HashSet<>(mapping.values()).size() != mapping.size() || !"time".equals(
                    mapping.get("time"))) {
                throw new IllegalArgumentException();
            }
            var mapped = new ArrayList<InfluxNativeCatalog.Column>();
            for (var column : columns) {
                if (mapping.containsKey(column.name())) {
                    mapped.add(new InfluxNativeCatalog.Column(mapping.get(column.name()), column.kind()));
                }
            }
            mapped.sort(Comparator.comparing(InfluxNativeCatalog.Column::name));
            expected = new InfluxNativeCatalog.Table(name, mapped);
            if (mapped.stream().noneMatch(
                    c -> c.kind() != InfluxNativeCatalog.Kind.TAG && c.kind() != InfluxNativeCatalog.Kind.TIME)) {
                throw new IllegalArgumentException("Influx native tables require at least one ordinary field");
            }
            sourceLogical = InfluxNativeCatalog.text(envelope, "source");
            sourceRetention = InfluxNativeCatalog.text(envelope, "retention");
            try (var connection = target.getConnection()) {
                var session = InfluxNativeSession.open(target, connection, limit, timeout);
                if (sourceLogical.equals(session.logicalTable(name))) {
                    throw new IllegalArgumentException("Influx source and target are the same native relation");
                }
                targetCluster = session.clusterIdentity();
                if (targetCluster.equals(InfluxNativeCatalog.text(envelope, "sourceCluster"))
                        && session.database().equals(InfluxNativeCatalog.text(envelope, "sourceDatabase"))
                        && name.equals(InfluxNativeCatalog.text(envelope, "sourceTable"))) {
                    throw new IllegalArgumentException("Influx native target resolves to its physical source scope");
                }
                if (!sourceRetention.equals(retention(session))) {
                    throw new IllegalArgumentException("Influx source and target retention policies differ");
                }
                previous = InfluxNativeCatalog.find(session, name);
                if (previous != null) {
                    requireOrdinary(session, name);
                    if (!replace) {
                        compatible(previous, expected);
                    }
                }
            }
        } catch (Exception invalid) {
            throw new IllegalStateException("Influx native target preflight failed before any mutation");
        }
        String digest = hash(
                definition.document() + "\n" + expected + "\n" + previous + "\n" + replace + "\n" + targetCluster + "\n" + (previous != null && replace ? "exclusive-replace-with-temporary-cache-identity-probe" : "no-management-probe"));
        return new NativeTargetPlan() {
            private final AtomicBoolean used = new AtomicBoolean();

            private volatile InfluxNativeIdentity.Identity originalIdentity, replacementIdentity;

            private volatile InfluxNativeIdentity.CacheResource pendingCache;

            /**
             * 返回摘要。
             */
            @Override
            public String digest() {
                return digest;
            }

            /**
             * 说明当前目标计划是否会替换已有对象。
             */
            @Override
            public boolean replacesExisting() {
                return previous != null && replace;
            }

            /**
             * 说明当前 DDL 路径是否要求调用方独占目标结构变更。
             */
            @Override
            public boolean requiresExclusiveDdlOwnership() {
                return replacesExisting();
            }

            /**
             * 返回当前操作恢复时需要关联的资源信息。
             */
            @Override
            public List<NativeRecoveryResource> recoveryResources() {
                var resources = new ArrayList<NativeRecoveryResource>();
                resources.add(new NativeRecoveryResource(ProductTypeEnum.INFLUXDB3, schema, name,
                        NativeRecoveryResource.Role.TARGET,
                        originalIdentity == null ? null : originalIdentity.fingerprint()));
                if (replacementIdentity != null) {
                    resources.add(new NativeRecoveryResource(ProductTypeEnum.INFLUXDB3, schema, name,
                            NativeRecoveryResource.Role.STAGING, replacementIdentity.fingerprint()));
                }
                var cache = pendingCache;
                if (cache != null) {
                    resources.add(new NativeRecoveryResource(ProductTypeEnum.INFLUXDB3, cache.database(),
                            cache.recoveryName(), NativeRecoveryResource.Role.STAGING, cache.fingerprint()));
                }
                return List.copyOf(resources);
            }

            /**
             * 采集当前对象身份、结构或运行状态的快照。
             */
            private InfluxNativeIdentity.Identity capture(InfluxNativeSession session,
                    InfluxNativeCatalog.Table table) throws java.sql.SQLException {
                return InfluxNativeIdentity.capture(session, table,
                        InfluxNativeIdentity.ManagementProbePermission.EXCLUSIVE_TARGET_REPLACEMENT,
                        observed -> pendingCache = observed.orElse(null));
            }

            /**
             * 在核验当前定义后应用 InfluxDB 原生表操作。
             */
            @Override
            public void execute() {
                if (!used.compareAndSet(false, true)) {
                    throw new IllegalStateException("Influx native structure plan is single use");
                }
                boolean attempted = false;
                try (var connection = target.getConnection()) {
                    var session = InfluxNativeSession.open(target, connection, limit, timeout);
                    if (!targetCluster.equals(session.clusterIdentity()) || !Objects.equals(previous,
                            InfluxNativeCatalog.find(session, name)) || !sourceRetention.equals(retention(session))) {
                        throw new IllegalStateException();
                    }
                    if (previous != null) {
                        requireOrdinary(session, name);
                        if (!replace) {
                            return;
                        }
                        // 执行前调用方必须遵守 requiresExclusiveDdlOwnership。仅有
                        // 此破坏性目标计划启用显式临时缓存探测；
                        // 源捕获、仅创建及普通追加仍使用只读元数据。
                        originalIdentity = capture(session, previous);
                        if (!previous.equals(InfluxNativeCatalog.require(session, name)) || !originalIdentity.equals(
                                capture(session, previous))) {
                            throw new IllegalStateException();
                        }
                        attempted = true;
                        session.json("DELETE", "/api/v3/configure/table?db=" + InfluxNativeSession.encode(
                                session.database()) + "&table=" + InfluxNativeSession.encode(name), null);
                        if (InfluxNativeCatalog.find(session, name) != null) {
                            throw new IllegalStateException();
                        }
                    }
                    attempted = true;
                    session.json("POST", "/api/v3/configure/table", createRequest(session.database(), expected));
                    if (!expected.equals(InfluxNativeCatalog.require(session, name))) {
                        throw new IllegalStateException();
                    }
                    if (originalIdentity != null) {
                        replacementIdentity = capture(session, expected);
                        if (originalIdentity.equals(
                                replacementIdentity) || originalIdentity.databaseId() != replacementIdentity.databaseId()) {
                            throw new IllegalStateException();
                        }
                    }
                } catch (Exception invalid) {
                    if (attempted || invalid instanceof UnconfirmedWriteException) {
                        throw new UnconfirmedWriteException();
                    }
                    throw new IllegalStateException("Influx target changed before native structure execution");
                }
            }
        };
    }

    /**
     * 判断当前类型或结构能否按目标契约兼容处理。
     */
    private static void compatible(InfluxNativeCatalog.Table actual, InfluxNativeCatalog.Table expected) {
        if (!actual.keys().equals(expected.keys())) {
            throw new IllegalArgumentException("Influx tag identity differs");
        }
        for (var column : expected.columns()) {
            if (!column.equals(actual.column(column.name()))) {
                throw new IllegalArgumentException("Influx column roles or types differ");
            }
        }
    }

    /**
     * 创建请求。
     */
    static JsonNode createRequest(String database, InfluxNativeCatalog.Table table) {
        var body = InfluxNativeSession.JSON.createObjectNode().put("db", database).put("table", table.name());
        var tags = body.putArray("tags");
        var fields = body.putArray("fields");
        for (var column : table.columns()) {
            switch (column.kind()) {
                case TAG -> tags.add(column.name());
                case TIME -> {
                }
                default -> fields.addObject().put("name", column.name()).put("type", switch (column.kind()) {
                    case INT64 -> "int64";
                    case UINT64 -> "uint64";
                    case FLOAT64 -> "float64";
                    case STRING -> "utf8";
                    case BOOLEAN -> "bool";
                    default -> throw new IllegalArgumentException();
                });
            }
        }
        return body;
    }

    /**
     * 根据已验证的原生定义计算逻辑资源身份。
     */
    public static String logicalResource(NativeTableDefinition definition, String table) throws Exception {
        exactDefinition(definition, table);
        var envelope = InfluxNativeSession.JSON.readTree(definition.document());
        return hash(InfluxNativeCatalog.text(envelope, "sourceCluster") + '\0' + InfluxNativeCatalog.text(envelope,
                "sourceDatabase") + '\0' + table);
    }

    /**
     * 检查原生定义所属产品、格式及目标名称一致。
     */
    public static InfluxNativeCatalog.Table exactDefinition(NativeTableDefinition definition,
            String table) throws Exception {
        if (definition.product() != ProductTypeEnum.INFLUXDB3 || !FORMAT.equals(definition.format())) {
            throw new IllegalArgumentException("Influx native Core evidence required");
        }
        var document = InfluxNativeSession.JSON.readTree(definition.document());
        var columns = new ArrayList<InfluxNativeCatalog.Column>();
        if (!document.path("columns").isArray() || document.path("columns").size() > 500) {
            throw new IllegalArgumentException();
        }
        for (var column : document.path("columns")) {
            columns.add(new InfluxNativeCatalog.Column(InfluxNativeCatalog.text(column, "name"),
                    InfluxNativeCatalog.Kind.valueOf(InfluxNativeCatalog.text(column, "kind"))));
        }
        var result = new InfluxNativeCatalog.Table(table, columns);
        if (!result.fields().equals(definition.fields()) || new HashSet<>(result.fields()).size() != columns.size()
                || columns.stream().filter(c -> c.kind() == InfluxNativeCatalog.Kind.TIME).count() != 1) {
            throw new IllegalArgumentException();
        }
        return result;
    }

    /**
     * 返回当前保留期。
     */
    static String retention(InfluxNativeSession session) throws Exception {
        var body = InfluxNativeSession.JSON.createObjectNode().put("db", session.database())
                .put("q", "SHOW RETENTION POLICIES").put("format", "json");
        var rows = session.json("POST", "/api/v3/query_influxql", body);
        if (!rows.isArray() || rows.size() != 1 || !"autogen".equals(rows.get(0).path("name").asText())) {
            throw new IllegalStateException();
        }
        return InfluxNativeCatalog.text(rows.get(0), "duration");
    }

    /**
     * 校验并取得普通。
     */
    static void requireOrdinary(InfluxNativeSession session, String table) throws Exception {
        // 结构替换时不得默默丢弃缓存或触发器配置。
        for (String name : List.of("last_caches", "distinct_caches", "processing_engine_triggers")) {
            var rows = session.query("SELECT * FROM system." + name + " LIMIT 501");
            if (!rows.isArray() || rows.size() > 500) {
                throw new IllegalStateException();
            }
            for (var row : rows) {
                if (name.equals("processing_engine_triggers") || row.path("table_name").asText()
                        .equals(table) || row.path("table").asText().equals(table)) {
                    throw new IllegalArgumentException(
                            "Influx table resources require an explicit native transfer plan");
                }
                if (!row.has("table_name") && !row.has("table")) {
                    throw new IllegalArgumentException("Influx cache ownership could not be proven");
                }
            }
        }
    }

    /**
     * 根据当前内容计算摘要，供一致性比较使用。
     */
    private static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }
}
