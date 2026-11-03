package cn.iocoder.yudao.framework.dbswitch.product.influxdb3;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.*;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Core 3.12 加法式原生模式变更。保留现有行和原生表身份。
 * 调用方从准备到完成始终拥有目标 DDL。目录没有只读
 * 表代次见证；执行阶段像替换操作一样显式使用临时缓存探测。
 * 这不是服务器端乐观模式 CAS，也不是原子元数据事务。 */
public final class InfluxNativeEvolution {

    /**
     * 初始化 {@code InfluxNativeEvolution}。
     */
    private InfluxNativeEvolution() {
    }

    /**
     * 返回计划。
     */
    public static NativeTargetPlan plan(DataSource target, NativeTableDefinition desired,
            String schema, String name, long limit, int timeout) {
        InfluxNativeCatalog.schema(schema);
        InfluxNativeSession.identifier(name);
        final InfluxNativeCatalog.Table expected, previous, additions;
        final String cluster, retention;
        try {
            if (desired.document().getBytes(StandardCharsets.UTF_8).length > limit) {
                throw new IllegalArgumentException("Influx native definition exceeds its byte budget");
            }
            var envelope = InfluxNativeSession.JSON.readTree(desired.document());
            envelope.fieldNames().forEachRemaining(key -> {
                if (!Set.of("source", "sourceDatabase", "sourceTable", "sourceCluster", "columns", "retention")
                        .contains(key)) {
                    throw new IllegalArgumentException("Influx native structure option requires explicit adaptation");
                }
            });
            for (var column : envelope.path("columns")) {
                if (!column.isObject() || column.size() != 2 || !column.has("name") || !column.has("kind")) {
                    throw new IllegalArgumentException("Influx native column option requires explicit adaptation");
                }
            }
            var columns = new ArrayList<>(InfluxNativeTables.exactDefinition(desired, name).columns());
            columns.sort(Comparator.comparing(InfluxNativeCatalog.Column::name));
            expected = new InfluxNativeCatalog.Table(name, columns);
            if (!expected.column("time")
                    .equals(new InfluxNativeCatalog.Column("time", InfluxNativeCatalog.Kind.TIME))) {
                throw new IllegalArgumentException("Influx time identity cannot be renamed");
            }
            retention = InfluxNativeCatalog.text(InfluxNativeSession.JSON.readTree(desired.document()), "retention");
            try (var connection = target.getConnection()) {
                var session = InfluxNativeSession.open(target, connection, limit, timeout);
                session.requireSchemaEvolutionVersion();
                cluster = session.clusterIdentity();
                previous = InfluxNativeCatalog.require(session, name);
                InfluxNativeTables.requireOrdinary(session, name);
                if (!retention.equals(InfluxNativeTables.retention(session))) {
                    throw new IllegalArgumentException("Influx retention cannot be changed by table evolution");
                }
                var added = new ArrayList<InfluxNativeCatalog.Column>();
                for (var column : previous.columns()) {
                    if (!column.equals(expected.column(column.name()))) {
                        throw new IllegalArgumentException(
                                "Influx table evolution cannot remove, rename or retype existing columns");
                    }
                }
                for (var column : expected.columns()) {
                    if (!previous.fields().contains(column.name())) {
                        added.add(column);
                    }
                }
                additions = new InfluxNativeCatalog.Table(name, added);
                if (!previous.equals(InfluxNativeCatalog.require(session, name))) {
                    throw new IllegalStateException("Influx structure changed during preparation");
                }
            }
        } catch (Exception invalid) {
            throw new IllegalStateException("Influx native evolution preflight failed before any mutation", invalid);
        }
        final String digest = hash(
                cluster + '\0' + schema + '\0' + name + '\0' + previous + '\0' + expected + '\0' + retention);
        return new NativeTargetPlan() {
            private final AtomicBoolean used = new AtomicBoolean();

            private volatile InfluxNativeIdentity.Identity identity;

            private volatile InfluxNativeIdentity.CacheResource pending;

            /**
             * 返回摘要。
             */
            public String digest() {
                return digest;
            }

            /**
             * 说明当前目标计划是否会替换已有对象。
             */
            public boolean replacesExisting() {
                return false;
            }

            /**
             * 说明当前 DDL 路径是否要求调用方独占目标结构变更。
             */
            public boolean requiresExclusiveDdlOwnership() {
                return true;
            }

            /**
             * 返回当前操作恢复时需要关联的资源信息。
             */
            public List<NativeRecoveryResource> recoveryResources() {
                var resources = new ArrayList<NativeRecoveryResource>();
                resources.add(new NativeRecoveryResource(ProductTypeEnum.INFLUXDB3, schema, name,
                        NativeRecoveryResource.Role.TARGET, identity == null ? null : identity.fingerprint()));
                var cache = pending;
                if (cache != null) {
                    resources.add(new NativeRecoveryResource(ProductTypeEnum.INFLUXDB3,
                            cache.database(), cache.recoveryName(), NativeRecoveryResource.Role.STAGING,
                            cache.fingerprint()));
                }
                return List.copyOf(resources);
            }

            /**
             * 采集当前对象身份、结构或运行状态的快照。
             */
            private InfluxNativeIdentity.Identity capture(InfluxNativeSession session,
                    InfluxNativeCatalog.Table table) throws java.sql.SQLException {
                return InfluxNativeIdentity.capture(session, table,
                        InfluxNativeIdentity.ManagementProbePermission.EXCLUSIVE_SCHEMA_EVOLUTION,
                        observed -> pending = observed.orElse(null));
            }

            /**
             * 应用已校验的 InfluxDB 原生结构演进语句。
             */
            public void execute() {
                if (!used.compareAndSet(false, true)) {
                    throw new IllegalStateException("Influx evolution plan is single use");
                }
                boolean attempted = false;
                try (var connection = target.getConnection()) {
                    var session = InfluxNativeSession.open(target, connection, limit, timeout);
                    session.requireSchemaEvolutionVersion();
                    if (!cluster.equals(session.clusterIdentity()) || !previous.equals(
                            InfluxNativeCatalog.require(session, name))
                            || !retention.equals(InfluxNativeTables.retention(session))) {
                        throw new IllegalStateException("Influx target changed before evolution");
                    }
                    InfluxNativeTables.requireOrdinary(session, name);
                    if (additions.columns().isEmpty()) {
                        return;
                    }
                    identity = capture(session, previous);
                    if (!previous.equals(InfluxNativeCatalog.require(session, name)) || !identity.equals(
                            capture(session, previous))) {
                        throw new IllegalStateException("Influx target generation changed before evolution");
                    }
                    attempted = true;
                    session.json("PATCH", "/api/v3/configure/table",
                            InfluxNativeTables.createRequest(session.database(), additions), 200);
                    if (!expected.equals(InfluxNativeCatalog.require(session, name)) || !identity.equals(
                            capture(session, expected))) {
                        throw new IllegalStateException("Influx evolution read-back did not match");
                    }
                    InfluxNativeTables.requireOrdinary(session, name);
                    if (!retention.equals(InfluxNativeTables.retention(session))) {
                        throw new IllegalStateException();
                    }
                } catch (Exception invalid) {
                    if (attempted || invalid instanceof UnconfirmedWriteException) {
                        throw new UnconfirmedWriteException();
                    }
                    throw new IllegalStateException("Influx native evolution rejected before table alteration",
                            invalid);
                }
            }
        };
    }

    /**
     * 根据当前内容计算摘要，供一致性比较使用。
     */
    private static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
