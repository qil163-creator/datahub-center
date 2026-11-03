package cn.iocoder.yudao.framework.dbswitch.product.influxdb3;

import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.*;
import java.util.function.Consumer;

/** Core 3.11 管理探测。创建临时最新值缓存是显式元数据写入，
 * 绝不能用于实现普通只读元数据。服务器已提交的目录响应
 * 包含数据库和表编号；端点或模式摘要都不是代次见证。 */
public final class InfluxNativeIdentity {

    public enum ManagementProbePermission {DISABLED, EXCLUSIVE_TARGET_REPLACEMENT, EXCLUSIVE_SCHEMA_EVOLUTION}

    public record Identity(String clusterUuid, long databaseId, long tableId) {

        /**
         * 校验构造参数并初始化 {@code Identity}。
         */
        public Identity {
            UUID.fromString(clusterUuid);
            if (databaseId < 0 || databaseId > 0xffff_ffffL || tableId < 0 || tableId > 0xffff_ffffL) {
                throw new IllegalArgumentException("Invalid Influx catalog identity");
            }
        }

        /**
         * 根据当前内容计算结构指纹，供一致性比较使用。
         */
        public String fingerprint() {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest((clusterUuid + '\0' + databaseId + '\0' + tableId).getBytes(StandardCharsets.UTF_8)));
            } catch (java.security.NoSuchAlgorithmException impossible) {
                throw new AssertionError(impossible);
            }
        }

        /**
         * 返回当前对象的文本表示。
         */
        @Override
        public String toString() {
            return "InfluxIdentity[confirmed]";
        }
    }

    /** 非空观察事件保留清理证据；空 Optional 表示已验证移除。
     * 表名是资源的显式组成部分：仅有缓存名不构成删除授权。 */
    public record CacheResource(String database, String table, String cacheName, Identity observedIdentity,
                                Long cacheId) {

        /**
         * 校验构造参数并初始化 {@code CacheResource}。
         */
        public CacheResource {
            InfluxNativeSession.identifier(database);
            InfluxNativeSession.identifier(table);
            InfluxNativeSession.identifier(cacheName);
            if ((observedIdentity == null) != (cacheId == null) || cacheId != null && (cacheId < 0 || cacheId > 0xffff_ffffL)) {
                throw new IllegalArgumentException("Invalid Influx cache identity");
            }
        }

        /**
         * 根据当前内容计算结构指纹，供一致性比较使用。
         */
        public String fingerprint() {
            if (observedIdentity == null) {
                return null;
            }
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest((observedIdentity.fingerprint() + "\0last-cache\0" + cacheId).getBytes(
                                StandardCharsets.UTF_8)));
            } catch (java.security.NoSuchAlgorithmException impossible) {
                throw new AssertionError(impossible);
            }
        }

        /**
         * 根据对象名称生成长度受限的恢复资源标识。
         */
        public String recoveryName() {
            String value = "last-cache/" + InfluxNativeSession.encode(table) + "/" + InfluxNativeSession.encode(
                    cacheName);
            if (value.length() > 1024) {
                throw new IllegalArgumentException("Influx cache recovery resource exceeds its name budget");
            }
            return value;
        }

        /**
         * 返回当前对象的文本表示。
         */
        @Override
        public String toString() {
            return "InfluxCacheResource[identityConfirmed=" + (observedIdentity != null) + "]";
        }
    }

    /**
     * 初始化 {@code InfluxNativeIdentity}。
     */
    private InfluxNativeIdentity() {
    }

    /**
     * 采集当前对象身份、结构或运行状态的快照。
     */
    public static Identity capture(InfluxNativeSession session, InfluxNativeCatalog.Table table,
            ManagementProbePermission permission, Consumer<Optional<CacheResource>> observer) throws SQLException {
        if (permission != ManagementProbePermission.EXCLUSIVE_TARGET_REPLACEMENT
                && permission != ManagementProbePermission.EXCLUSIVE_SCHEMA_EVOLUTION) {
            throw new IllegalArgumentException(
                    "Influx management identity probes require explicit exclusive DDL permission");
        }
        Objects.requireNonNull(session);
        Objects.requireNonNull(table);
        Objects.requireNonNull(observer);
        String field = table.columns().stream()
                .filter(c -> c.kind() != InfluxNativeCatalog.Kind.TAG && c.kind() != InfluxNativeCatalog.Kind.TIME)
                .map(InfluxNativeCatalog.Column::name).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Influx identity probes need one ordinary field"));
        String cluster = session.clusterIdentity(), name = "__dbswitch_identity_" + UUID.randomUUID().toString()
                .replace("-", "");
        CacheResource resource = new CacheResource(session.database(), table.name(), name, null, null);
        resource.recoveryName();
        if (!absent(session, table.name(), name)) {
            throw new SQLException("Influx generated cache resource is already occupied");
        }
        var request = InfluxNativeSession.JSON.createObjectNode().put("db", session.database())
                .put("table", table.name()).put("name", name).put("count", 1).put("ttl", 1);
        // 显式空键生成一个全局条目，而不是每个标签组合一个条目。
        // 即使独占 DDL 期间仍有普通 DML，此方式也能限制探测缓存。
        request.putArray("key_columns");
        request.putArray("value_columns").add(field);
        observer.accept(Optional.of(resource)); // 发送任何管理请求前，保留精确名称
        boolean created = false, removed = false;
        Identity identity = null;
        Exception failure = null;
        try {
            var response = session.json("POST", "/api/v3/configure/last_cache", request, 201);
            var database = response.path("catalog_batch").path("Database");
            var ops = database.path("ops");
            if (!database.path("database_name").isTextual() || !session.database()
                    .equals(database.path("database_name").textValue())
                    || !ops.isArray() || ops.size() != 1 || !ops.get(0).has("CreateLastCache")) {
                throw new SQLException("Influx cache commit evidence is incomplete");
            }
            var cache = ops.get(0).path("CreateLastCache");
            if (!table.name().equals(cache.path("table").asText()) || !name.equals(
                    cache.path("name").asText()) || !cluster.equals(session.clusterIdentity())) {
                throw new SQLException("Influx cache commit evidence has a different resource");
            }
            created = true;
            identity = new Identity(cluster, unsignedId(database.path("database_id")),
                    unsignedId(cache.path("table_id")));
            observer.accept(Optional.of(
                    new CacheResource(session.database(), table.name(), name, identity, unsignedId(cache.path("id")))));
        } catch (Exception invalid) {
            failure = invalid;
        } finally {
            // POST 响应丢失或发生冲突，不能证明所有权。绝不删除他人的缓存。
            if (created) {
                try {
                    session.json("DELETE", "/api/v3/configure/last_cache",
                            InfluxNativeSession.JSON.createObjectNode().put("db", session.database())
                                    .put("table", table.name()).put("name", name), 200);
                    if (!absent(session, table.name(), name)) {
                        throw new SQLException("Influx identity cache removal was not observed");
                    }
                    removed = true;
                    observer.accept(Optional.empty());
                } catch (Exception cleanup) {
                    failure = cleanup;
                }
            }
        }
        if (failure != null || !removed || identity == null) {
            throw new UnconfirmedWriteException();
        }
        return identity;
    }

    /**
     * 读取非负的原生目录对象编号并拒绝非法数值。
     */
    private static long unsignedId(com.fasterxml.jackson.databind.JsonNode node) throws SQLException {
        if (!node.isIntegralNumber() || !node.canConvertToLong()) {
            throw new SQLException("Influx catalog ID is invalid");
        }
        long value = node.longValue();
        if (value < 0 || value > 0xffff_ffffL) {
            throw new SQLException("Influx catalog ID is out of range");
        }
        return value;
    }

    /**
     * 返回当前缺失标记。
     */
    private static boolean absent(InfluxNativeSession session, String table, String cache) throws SQLException {
        var rows = session.query("SELECT name FROM system.last_caches WHERE \"table\"=" + InfluxNativeSession.literal(
                table) + " AND name=" + InfluxNativeSession.literal(cache) + " LIMIT 2");
        if (!rows.isArray() || rows.size() > 1) {
            throw new SQLException("Influx cache resource identity is ambiguous");
        }
        return rows.isEmpty();
    }
}
