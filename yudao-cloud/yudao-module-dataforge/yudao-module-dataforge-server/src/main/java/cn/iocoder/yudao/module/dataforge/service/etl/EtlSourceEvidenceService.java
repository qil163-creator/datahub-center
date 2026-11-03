package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.util.TypedRowFingerprint;
import cn.iocoder.yudao.framework.dbswitch.core.service.*;
import cn.iocoder.yudao.framework.dbswitch.data.query.*;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.driver.ConnectionBudget;
import cn.iocoder.yudao.framework.etl.core.connection.*;
import cn.iocoder.yudao.framework.etl.core.model.*;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRowOrigin;
import cn.iocoder.yudao.framework.etl.core.variable.EtlVariableContext;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.*;
import cn.iocoder.yudao.module.dataforge.service.security.*;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.ETL_QUALITY_EVIDENCE_RESTRICTED;

/** 当前原来源完整性复核；只支持直接整表输入，重新读取并核对原始行。 */
@Service
public class EtlSourceEvidenceService {

    @Resource
    private EtlConnectionResolverAdapter connections;

    @Resource
    private ExecutionAccessService access;

    @Resource
    private DbSwitchRuntime runtime;

    public record Proof(int version, Long recordId, Long batchId, String qualityNode,
                        String planHash, String snapshotHash, EtlRowOrigin origin) {

    }

    /**
     * 固定ETL来源证据执行快照。
     *
     * @param record 运行记录
     * @param batch 目标批次信息
     * @param origin 原始
     * @param emitted 已输出
     * @return 固定ETL来源证据执行快照文本
     */
    public String freeze(EtlRecordDO record, EtlQualityBatchDO batch, EtlRowOrigin origin,
            Map<String, Object> emitted) {
        if (origin == null) {
            return null;
        }
        requireBinding(record, batch, origin);
        if (!origin.emittedHash().equals(TypedRowFingerprint.hash(emitted))) {
            throw restricted();
        }
        String proof = JsonUtils.toJsonString(new Proof(1, record.getId(), batch.getId(), batch.getNodeNo(),
                batch.getPlanHash(), DigestUtil.sha256Hex(record.getConfigJson()), origin));
        return proof.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 32_768 ? proof : null;
    }

    /**
     * 核对ETL来源证据。
     *
     * @param record 运行记录
     * @param batch 目标批次信息
     * @param encoded 已编码
     * @param actor 操作人编号
     * @return 处理后的键值映射
     */
    public Map<String, Object> verify(EtlRecordDO record, EtlQualityBatchDO batch, String encoded, Long actor) {
        if (actor == null || encoded == null || encoded.getBytes(
                java.nio.charset.StandardCharsets.UTF_8).length > 32_768) {
            throw restricted();
        }
        try {
            Proof proof = JsonUtils.parseObject(encoded, Proof.class);
            if (proof == null || proof.version() != 1 || !Objects.equals(proof.recordId(),
                    record.getId()) || !Objects.equals(proof.batchId(), batch.getId())
                    || !Objects.equals(proof.qualityNode(), batch.getNodeNo()) || !Objects.equals(proof.planHash(),
                    batch.getPlanHash())
                    || !Objects.equals(proof.snapshotHash(), DigestUtil.sha256Hex(record.getConfigJson()))) {
                throw restricted();
            }
            var origin = proof.origin();
            requireBinding(record, batch, origin);
            access.requireUserPermission(actor, "dataforge:etl:query");
            access.requireDatabase(origin.connectionId());
            var connection = connections.resolve(origin.connectionId());
            requireConnection(connection, origin);
            var lookup = RestrictedRowLookupPlan.create(ProductTypeEnum.POSTGRESQL, origin.schema(), origin.table(),
                    origin.keys());
            String original = lookup.authorizationSql();
            var bound = lookup.bindAuthorized(original);
            String scope = "etl-evidence:" + actor;
            var operation = runtime.queries().register(scope, UUID.randomUUID().toString(), UUID.randomUUID(),
                    new OperationBudget(Duration.ofSeconds(15), Duration.ofSeconds(15), Duration.ofSeconds(3),
                            2, 256 * 1024, 128 * 1024, 2));
            List<Map<String, Object>> rows = new ArrayList<>();
            QueryRuntime.Termination termination;
            try {
                operation.bindWorker();
                try (var session = operation.openSession(EtlRowOriginCapture.spec(connection),
                        ConnectionBudget.Role.WORKLOAD);
                     var cursor = session.query(scope, bound.sql(), bound.parameters())) {
                    var page = cursor.fetch(scope, cursor.id(), 2);
                    for (var values : page.rows()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 0; i < page.columns().size(); i++) {
                            String label = page.columns().get(i).getLabelName();
                            if (row.containsKey(label)) {
                                throw restricted();
                            }
                            row.put(label, values.get(i));
                        }
                        rows.add(row);
                    }
                }
            } finally {
                operation.workerExited();
                termination = operation.awaitTermination();
            }
            if (termination != QueryRuntime.Termination.CONFIRMED || rows.size() != 1
                    || !origin.sourceHash().equals(TypedRowFingerprint.hash(rows.getFirst()))) {
                throw restricted();
            }
            for (var key : origin.keys().entrySet()) {
                if (!key.getValue().equals(TypedRowFingerprint.KeyValue.of(rows.getFirst().get(key.getKey())))) {
                    throw restricted();
                }
            }
            requireConnection(connections.resolve(origin.connectionId()), origin);
            return new LinkedHashMap<>(rows.getFirst());
        } catch (Exception failure) {
            throw restricted();
        }
    }

    /**
     * 校验连接。
     *
     * @param connection 数据库连接
     * @param origin 原始
     */
    private void requireConnection(EtlConnectionInfo connection, EtlRowOrigin origin) {
        if (connection == null || connection.getProductType() != ProductTypeEnum.POSTGRESQL
                || !Objects.equals(connection.getConfigurationRevision(), origin.configurationRevision())
                || !origin.connectionHash().equals(EtlRowOriginCapture.connectionHash(connection))) {
            throw restricted();
        }
    }

    /**
     * 校验绑定。
     *
     * @param record 运行记录
     * @param batch 目标批次信息
     * @param origin 原始
     */
    private void requireBinding(EtlRecordDO record, EtlQualityBatchDO batch, EtlRowOrigin origin) {
        if (origin == null || !Objects.equals(batch.getRecordId(), record.getId())) {
            throw restricted();
        }
        var snapshot = JSONUtil.parseObj(record.getConfigJson());
        String nodeKey = snapshot.containsKey("executionNodes") ? "executionNodes" : "nodes";
        String edgeKey = snapshot.containsKey("executionEdges") ? "executionEdges" : "edges";
        var nodes = EtlGraphConverter.toNodeDefs(EtlSubflowService.maps(snapshot.get(nodeKey)));
        var edges = EtlGraphConverter.toEdgeDefs(EtlSubflowService.maps(snapshot.get(edgeKey)));
        var dag = EtlDag.build(nodes, edges);
        var quality = dag.getNode(batch.getNodeNo());
        if (quality == null || !"quality-check".equals(quality.getToolCode())
                || !dag.getDataUpstreamList(quality.getNo()).equals(List.of(origin.sourceNode()))) {
            throw restricted();
        }
        var source = dag.getNode(origin.sourceNode());
        if (source == null || !"read-db".equals(source.getToolCode()) || !dag.getDataUpstreamList(source.getNo())
                .isEmpty()) {
            throw restricted();
        }
        Map<String, String> parameters = EtlRunParameterResolver.resolve(
                snapshot.getJSONArray("parameters"), snapshot.getJSONObject("parameterValues"));
        var config = JSONUtil.parseObj(new EtlVariableContext(parameters).resolveJson(source.getNodeJson()));
        if (config.getInt("ifSql", 0) != 0 || !Objects.equals(config.getLong("databaseId"), origin.connectionId())) {
            throw restricted();
        }
        var fake = new EtlConnectionInfo();
        fake.setProductType(ProductTypeEnum.POSTGRESQL);
        fake.setSchema(origin.schema());
        var table = EtlTableReference.resolve(fake, config.getStr("tableName"), config.getStr("schemaName"));
        var expected = RestrictedSelectGuard.parseTableReference(ProductTypeEnum.POSTGRESQL, table.quoted(),
                origin.schema());
        if (!origin.schema().equals(expected.getSchemaName()) || !origin.table().equals(expected.getTableName())) {
            throw restricted();
        }
    }

    /**
     * 获取ETL来源证据的受限。
     *
     * @return 运行时异常
     */
    private RuntimeException restricted() {
        return exception(ETL_QUALITY_EVIDENCE_RESTRICTED);
    }
}
