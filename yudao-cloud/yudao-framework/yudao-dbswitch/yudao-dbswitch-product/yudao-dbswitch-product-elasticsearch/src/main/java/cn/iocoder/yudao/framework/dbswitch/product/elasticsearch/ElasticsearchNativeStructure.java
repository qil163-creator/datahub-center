package cn.iocoder.yudao.framework.dbswitch.product.elasticsearch;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.*;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static cn.iocoder.yudao.framework.dbswitch.product.elasticsearch.ElasticsearchStructureContract.*;
import static cn.iocoder.yudao.framework.dbswitch.product.elasticsearch.ElasticsearchStructureCatalog.*;

/** 原生结构独立于平面数据帧；所有变更均为单次、外部 DDL 计划。 */
public final class ElasticsearchNativeStructure {

    /**
     * 初始化 {@code ElasticsearchNativeStructure}。
     */
    private ElasticsearchNativeStructure() {
    }

    /**
     * 根据当前输入构建对象或结构的描述信息。
     */
    public static NativeTableDefinition describe(DataSource source, String index, long limit, int timeout) {
        index = ElasticsearchNativeSession.index(index);
        try (Connection connection = source.getConnection()) {
            var session = ElasticsearchNativeSession.open(connection, limit, timeout);
            JsonNode physical = observe(session, index, false);
            ObjectNode body = body(physical);
            validate(body);
            ObjectNode envelope = JSON.createObjectNode();
            envelope.set("profile", profile(session));
            envelope.put("sourceIdentity", identity(physical));
            envelope.set("body", body);
            // 数据权限不足只使 FULL 准入不可证明；结构-only 仍可使用目录事实。
            envelope.put("routingNone", routingNone(session, index));
            fresh(session, index, fingerprint(physical));
            return new NativeTableDefinition(ProductTypeEnum.ELASTICSEARCH, FORMAT, envelope.toString(), fields(body));
        } catch (SQLException failure) {
            throw new IllegalStateException("Elasticsearch source structure cannot be safely planned");
        }
    }

    /**
     * 读取或组装当前操作所使用的列定义。
     */
    public static List<ColumnDescription> columns(NativeTableDefinition definition) {
        ObjectNode body = definition(definition, Long.MAX_VALUE).body();
        try {
            return ElasticsearchMapping.columns(ElasticsearchMapping.parse(body.path("mappings")));
        } catch (SQLException structureOnly) {
            List<ColumnDescription> result = new ArrayList<>();
            for (String name : definition.fields()) {
                var column = new ColumnDescription();
                column.setFieldName(name);
                column.setLabelName(name);
                boolean id = name.equals("_id");
                column.setPk(id);
                column.setNullable(!id);
                column.setFieldType(id ? Types.VARCHAR : Types.OTHER);
                column.setFieldTypeName(
                        id ? "keyword" : body.path("mappings").path("properties").path(name).path("type")
                                .asText("object"));
                column.setFiledTypeClassName(id ? String.class.getName() : JsonNode.class.getName());
                column.setDbType(ProductTypeEnum.ELASTICSEARCH);
                result.add(column);
            }
            return List.copyOf(result);
        }
    }

    /**
     * 校验原生定义并生成目标索引结构操作计划。
     */
    public static NativeTargetPlan prepare(DataSource target, NativeTableDefinition definition, String index,
            Map<String, String> mapping, boolean replace, long limit, int timeout) {
        Evidence evidence = definition(definition, limit);
        return plan(target, evidence, map(evidence.body(), definition.fields(), mapping),
                ElasticsearchNativeSession.index(index), replace, false, limit, timeout);
    }

    /**
     * 返回当前演进。
     */
    public static NativeTargetPlan evolve(DataSource target, NativeTableDefinition definition, String index, long limit,
            int timeout) {
        Evidence evidence = definition(definition, limit);
        return plan(target, evidence, evidence.body(), ElasticsearchNativeSession.index(index), false, true, limit,
                timeout);
    }

    /**
     * 返回计划。
     */
    private static NativeTargetPlan plan(DataSource target, Evidence evidence, ObjectNode desired, String index,
            boolean replace, boolean evolve, long limit, int timeout) {
        final JsonNode previous;
        final ObjectNode deployment;
        String suffix = UUID.randomUUID().toString().replace("-", "");
        final String staging = "dbswitch_stage_" + suffix, backup = "dbswitch_backup_" + suffix;
        try (Connection connection = target.getConnection()) {
            var session = ElasticsearchNativeSession.open(connection, limit, timeout);
            deployment = profile(session);
            if (!deployment.equals(evidence.profile())) {
                throw new IllegalArgumentException("Native structure requires matching server and plugin versions");
            }
            previous = observe(session, index, true);
            if (previous != null && (replace || evolve)) {
                validate(body(previous));
            }
            if (previous != null && !evolve && identity(previous).equals(evidence.identity())) {
                throw new IllegalArgumentException("Source and target must be different physical indices");
            }
            if (evolve && previous == null) {
                throw new IllegalArgumentException("Evolution requires an existing physical index");
            }
            if (evolve) {
                additive(body(previous), desired);
            } else if (previous != null && !replace) {
                compatible(body(previous), desired);
            }
            if (previous != null && replace && !previous.path("aliases").equals(desired.path("aliases"))) {
                throw new IllegalArgumentException("Replacement must preserve all existing target aliases exactly");
            }
            deployment(session, index, staging, backup, desired);
        } catch (SQLException failure) {
            throw new IllegalStateException("Elasticsearch preflight failed before any mutation");
        }
        String prior = previous == null ? null : fingerprint(previous);
        String digest = fingerprint(
                JSON.createObjectNode().put("index", index).put("replace", replace).put("evolve", evolve)
                        .put("previous", prior).set("definition", desired));
        return new NativeTargetPlan() {
            private final AtomicBoolean used = new AtomicBoolean();

            private volatile String stageIdentity, backupIdentity;

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
                return previous == null || replace || evolve && !body(previous).equals(desired);
            }

            /**
             * 返回当前操作恢复时需要关联的资源信息。
             */
            @Override
            public List<NativeRecoveryResource> recoveryResources() {
                var values = new ArrayList<NativeRecoveryResource>();
                values.add(resource(index, NativeRecoveryResource.Role.TARGET,
                        previous == null ? null : identity(previous)));
                values.add(resource(staging, NativeRecoveryResource.Role.STAGING, stageIdentity));
                if (replacesExisting()) {
                    values.add(resource(backup, NativeRecoveryResource.Role.STAGING, backupIdentity));
                }
                return List.copyOf(values);
            }

            /**
             * 在数据搬运前检查源端与目标端的兼容条件。
             */
            @Override
            public void preflightDataTransfer() {
                dataFrame(desired);
                if (!evidence.routingNone()) {
                    throw new IllegalArgumentException(
                            "Source routing is present or could not be observed before data transfer");
                }
            }

            /**
             * 校验计划仅使用一次，在重新核对目标事实后应用原生结构操作。
             */
            @Override
            public void execute() {
                if (!used.compareAndSet(false, true)) {
                    throw new IllegalStateException("Native target plan is single use");
                }
                boolean attempted = false;
                try (Connection connection = target.getConnection()) {
                    var session = ElasticsearchNativeSession.open(connection, limit, timeout);
                    fresh(session, index, prior);
                    if (!deployment.equals(profile(session))) {
                        throw new IllegalStateException("Deployment changed after planning");
                    }
                    deployment(session, index, staging, backup, desired);
                    if (previous != null && !replace && !evolve || evolve && body(previous).equals(desired)) {
                        return;
                    }
                    // 私有别名验证 filter/routing，避免提前改变真实别名的可见范围。
                    ObjectNode staged = desired.deepCopy(), stageAliases = JSON.createObjectNode();
                    var aliases = desired.path("aliases").elements();
                    int number = 0;
                    while (aliases.hasNext()) {
                        stageAliases.set(staging + "_alias_" + number++, aliases.next().deepCopy());
                    }
                    staged.set("aliases", stageAliases);
                    attempted = true;
                    ack(session.request("PUT", "/" + staging, staged.toString()).requireSuccess());
                    JsonNode stage = observe(session, staging, false);
                    stageIdentity = identity(stage);
                    exact(body(stage), staged);
                    fresh(session, index, prior);
                    deployment(session, index, staging, backup, desired);
                    if (replacesExisting()) {
                        JsonNode blocked = session.request("PUT", "/" + index + "/_block/write", null).requireSuccess();
                        ack(blocked);
                        if (!blocked.path("shards_acknowledged").asBoolean(false) || blocked.path("indices").size() != 1
                                || !blocked.path("indices").get(0).path("name").asText().equals(index)
                                || !blocked.path("indices").get(0).path("blocked").asBoolean(false)) {
                            throw new SQLException("Write block was not confirmed on every shard");
                        }
                        blockedOriginal(previous, observe(session, index, false));
                        shards(session.request("POST", "/" + index + "/_refresh", null).requireSuccess());
                        long documents = count(session, index);
                        ObjectNode clone = JSON.createObjectNode();
                        clone.set("aliases", JSON.createObjectNode());
                        clone.set("settings", JSON.createObjectNode().put("index.number_of_replicas", 0)
                                .put("index.blocks.write", true));
                        ack(session.request("POST", "/" + index + "/_clone/" + backup, clone.toString())
                                .requireSuccess());
                        JsonNode health = session.request("GET",
                                        "/_cluster/health/" + backup + "?wait_for_status=yellow&timeout=" + timeout + "s", null)
                                .requireSuccess();
                        if (health.path("timed_out").asBoolean(true) || health.path("status").asText().equals("red")) {
                            throw new SQLException("Recovery clone shards are unavailable");
                        }
                        JsonNode saved = observe(session, backup, false);
                        backupIdentity = identity(saved);
                        if (!saved.path("mappings").equals(previous.path("mappings")) || count(session,
                                backup) != documents) {
                            throw new SQLException("Recovery clone cannot be verified");
                        }
                        blockedOriginal(previous, observe(session, index, false));
                        deployment(session, index, staging, backup, desired);
                        ack(session.request("DELETE", "/" + index, null).requireSuccess());
                    }
                    if (evolve) {
                        fresh(session, index, prior);
                        ack(session.request("PUT", "/" + index + "/_mapping", desired.path("mappings").toString())
                                .requireSuccess());
                    } else {
                        ack(session.request("PUT", "/" + index, desired.toString()).requireSuccess());
                    }
                    JsonNode actual = observe(session, index, false);
                    exact(body(actual), desired);
                    if (evolve && !identity(actual).equals(identity(previous))) {
                        throw new SQLException("Mapping update replaced the physical index");
                    }
                    if (!identity(observe(session, staging, false)).equals(stageIdentity)) {
                        throw new SQLException("Staging identity changed");
                    }
                    ack(session.request("DELETE", "/" + staging, null).requireSuccess());
                    // 恢复副本始终保留。调用方核对 resource UUID 后自行决策，绝不自动重放。
                } catch (SQLException | RuntimeException failure) {
                    if (attempted) {
                        throw new UnconfirmedWriteException();
                    }
                    throw new IllegalStateException("Native target could not be revalidated before mutation");
                }
            }

            /**
             * 返回当前对象的文本表示。
             */
            @Override
            public String toString() {
                return "Elasticsearch native target plan[digest=" + digest + "]";
            }
        };
    }

    /**
     * 返回当前资源。
     */
    private static NativeRecoveryResource resource(String name, NativeRecoveryResource.Role role, String id) {
        return new NativeRecoveryResource(ProductTypeEnum.ELASTICSEARCH, "es", name, role, id);
    }
}
