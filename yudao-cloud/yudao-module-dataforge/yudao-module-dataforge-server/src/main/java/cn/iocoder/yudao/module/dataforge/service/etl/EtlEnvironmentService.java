package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.*;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.etl.*;
import cn.iocoder.yudao.module.dataforge.service.security.*;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;

/** 平台内环境配置与不可变运行绑定；数据库解析、标识符和执行复用 DB Switch。 */
@Service
public class EtlEnvironmentService {

    @Resource
    private EtlEnvironmentMapper environments;

    @Resource
    private EtlEnvironmentBindingMapper bindings;

    @Resource
    private EtlPipelineMapper pipelines;

    @Resource
    private ExecutionAccessService access;

    @Resource
    private EtlConnectionResolverAdapter connections;

    /**
     * 查看当前平台全部环境。
     *
     * @return 查看当前平台全部环境结果集合
     */
    public List<EtlEnvironmentRespVO> list() {
        return environments.selectList(
                        new LambdaQueryWrapperX<EtlEnvironmentDO>().orderByAsc(EtlEnvironmentDO::getCode))
                .stream().map(v -> BeanUtils.toBean(v, EtlEnvironmentRespVO.class)).toList();
    }

    /**
     * 列出绑定。
     *
     * @param environmentId 环境编号
     * @return 绑定结果集合
     */
    public List<EtlEnvironmentBindingRespVO> listBindings(Long environmentId) {
        require(environmentId, false);
        return bindings.selectList(new LambdaQueryWrapperX<EtlEnvironmentBindingDO>()
                        .eq(EtlEnvironmentBindingDO::getEnvironmentId, environmentId)
                        .orderByAsc(EtlEnvironmentBindingDO::getLogicalKey))
                .stream().map(v -> BeanUtils.toBean(v, EtlEnvironmentBindingRespVO.class)).toList();
    }

    /**
     * 创建或按修订号更新环境。
     *
     * @param req 请求参数
     * @return 生成或保存的记录编号
     */
    public Long save(EtlEnvironmentSaveReqVO req) {
        var value = BeanUtils.toBean(req, EtlEnvironmentDO.class);
        try {
            if (req.getId() == null) {
                value.setRevision(0);
                if (environments.insert(value) != 1) {
                    changed();
                }
            } else {
                require(req.getId(), false);
                if (req.getRevision() == null) {
                    changed();
                }
                value.setRevision(Math.addExact(req.getRevision(), 1));
                if (environments.update(value, Wrappers.lambdaUpdate(EtlEnvironmentDO.class)
                        .eq(EtlEnvironmentDO::getId, req.getId())
                        .eq(EtlEnvironmentDO::getRevision, req.getRevision())) != 1) {
                    changed();
                }
            }
        } catch (DuplicateKeyException duplicate) {
            throw exception(ETL_PIPELINE_GRAPH_INVALID, "环境代码已存在");
        }
        return value.getId();
    }

    /**
     * 删除未被引用的环境。
     *
     * @param id 记录编号
     * @param revision 修订号
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id, Integer revision) {
        require(id, true);
        if (bindings.selectCount(
                new LambdaQueryWrapperX<EtlEnvironmentBindingDO>().eq(EtlEnvironmentBindingDO::getEnvironmentId,
                        id)) != 0
                || pipelines.selectCount(
                new LambdaQueryWrapperX<EtlPipelineDO>().eq(EtlPipelineDO::getEnvironmentId, id)) != 0) {
            throw exception(ETL_PIPELINE_GRAPH_INVALID, "环境仍有连接绑定或流程引用，请先解除引用");
        }
        if (revision == null || environments.delete(Wrappers.lambdaQuery(EtlEnvironmentDO.class)
                .eq(EtlEnvironmentDO::getId, id).eq(EtlEnvironmentDO::getRevision, revision)) != 1) {
            changed();
        }
    }

    /**
     * 保存绑定。
     *
     * @param req 请求参数
     * @param actor 操作人编号
     * @return 生成或保存的记录编号
     */
    @Transactional(rollbackFor = Exception.class)
    public Long saveBinding(EtlEnvironmentBindingSaveReqVO req, Long actor) {
        // 所有远程鉴权在本地行锁之前完成；实际表权限在冻结与每次执行时再复核。
        access.requireUserPermission(actor, "dataforge:etl:save");
        access.requireDatabase(req.getDatabaseId());
        require(req.getEnvironmentId(), true);
        var value = BeanUtils.toBean(req, EtlEnvironmentBindingDO.class);
        try {
            if (req.getId() == null) {
                value.setRevision(0);
                if (bindings.insert(value) != 1) {
                    changed();
                }
            } else {
                if (req.getRevision() == null) {
                    changed();
                }
                value.setRevision(Math.addExact(req.getRevision(), 1));
                if (bindings.update(value, Wrappers.lambdaUpdate(EtlEnvironmentBindingDO.class)
                        .eq(EtlEnvironmentBindingDO::getId, req.getId())
                        .eq(EtlEnvironmentBindingDO::getEnvironmentId, req.getEnvironmentId())
                        .eq(EtlEnvironmentBindingDO::getRevision, req.getRevision())) != 1) {
                    changed();
                }
            }
        } catch (DuplicateKeyException duplicate) {
            throw exception(ETL_PIPELINE_GRAPH_INVALID, "环境内逻辑连接键已存在");
        }
        return value.getId();
    }

    /**
     * 按修订号删除连接绑定，引用此修订的运行将被阻止。
     *
     * @param id 记录编号
     * @param revision 修订号
     */
    public void deleteBinding(Long id, Integer revision) {
        if (revision == null || bindings.delete(Wrappers.lambdaQuery(EtlEnvironmentBindingDO.class)
                .eq(EtlEnvironmentBindingDO::getId, id).eq(EtlEnvironmentBindingDO::getRevision, revision)) != 1) {
            changed();
        }
    }

    /**
     * 校验选择。
     *
     * @param environmentId 环境编号
     */
    public void validateSelection(Long environmentId) {
        if (environmentId != null) {
            enabled(environmentId);
        }
    }

    public record Resolved(List<EtlNodeDef> nodes, Map<String, Object> bindings) {

    }

    /**
     * 固定ETL环境执行快照。
     *
     * @param environmentId 环境编号
     * @param nodes 节点
     * @param actor 操作人编号
     * @return 已解析
     */
    public Resolved freeze(Long environmentId, List<EtlNodeDef> nodes, Long actor) {
        return resolve(environmentId, nodes, actor, null);
    }

    /** 历史版本复用原绑定；任何修订、停用、删除或数据源身份变化都要求重新发布。 */
    public Resolved reuse(Long environmentId, List<EtlNodeDef> nodes, Long actor, Object frozen) {
        return resolve(environmentId, nodes, actor, frozen == null ? JSONUtil.createObj() : JSONUtil.parseObj(frozen));
    }

    /**
     * 解析ETL环境配置或身份。
     *
     * @param environmentId 环境编号
     * @param nodes 节点
     * @param actor 操作人编号
     * @param frozen 冻结
     * @return 已解析
     */
    private Resolved resolve(Long environmentId, List<EtlNodeDef> nodes, Long actor, JSONObject frozen) {
        List<EtlNodeDef> resolved = new ArrayList<>();
        Map<String, Object> proofs = new LinkedHashMap<>();
        EtlEnvironmentDO environment = environmentId == null ? null : enabled(environmentId);
        for (var original : nodes) {
            var node = BeanUtils.toBean(original, EtlNodeDef.class);
            var config = JSONUtil.parseObj(node.getNodeJson());
            String key = config.getStr("logicalDatabaseKey");
            if (key == null || key.isBlank()) {
                resolved.add(node);
                continue;
            }
            if (environment == null || !key.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}")
                    || !Set.of("read-db", "managed-db-output").contains(node.getToolCode())) {
                throw exception(ETL_PIPELINE_GRAPH_INVALID, "逻辑连接必须选择环境，且仅支持表输入和受管目标输出");
            }
            if ("read-db".equals(node.getToolCode()) && config.getInt("ifSql", 0) != 0) {
                throw exception(ETL_PIPELINE_GRAPH_INVALID, "逻辑环境连接仅支持整表输入，SQL 模式需使用明确物理连接");
            }
            var binding = bindings.selectOne(new LambdaQueryWrapperX<EtlEnvironmentBindingDO>()
                    .eq(EtlEnvironmentBindingDO::getEnvironmentId, environmentId)
                    .eq(EtlEnvironmentBindingDO::getLogicalKey, key));
            if (binding == null) {
                throw exception(ETL_PIPELINE_GRAPH_INVALID, "环境缺少逻辑连接：" + key);
            }
            if (actor == null) {
                throw exception(OPERATION_PERMISSION_DENIED);
            }
            access.requireDatabase(binding.getDatabaseId());
            var connection = connections.resolve(binding.getDatabaseId());
            String referenceText = config.containsKey("bindingHash") ? connection.getProductType().quoteSchemaTableName(
                    config.getStr("schemaName"), config.getStr("tableName")) : config.getStr("tableName");
            var reference = RestrictedSelectGuard.parseTableReference(connection.getProductType(), referenceText,
                    binding.getSchemaName());
            if (!Objects.equals(binding.getSchemaName(), reference.getSchemaName())) {
                throw exception(ETL_PIPELINE_GRAPH_INVALID, "节点的表模式与环境连接绑定不一致");
            }
            String table = connection.getProductType()
                    .quoteSchemaTableName(reference.getSchemaName(), reference.getTableName());
            Map<String, Object> proof = new LinkedHashMap<>();
            proof.put("environmentId", environment.getId());
            proof.put("environmentRevision", environment.getRevision());
            proof.put("bindingId", binding.getId());
            proof.put("bindingRevision", binding.getRevision());
            proof.put("logicalKey", key);
            proof.put("databaseId", binding.getDatabaseId());
            proof.put("schemaName", binding.getSchemaName());
            proof.put("identityHash",
                    DigestUtil.sha256Hex(JsonUtils.toJsonString(Arrays.asList(connection.getProductType().name(),
                            connection.getJdbcUrl(), connection.getUsername(), connection.getSchema()))));
            proof.put("bindingHash", DigestUtil.sha256Hex(JsonUtils.toJsonString(proof)));
            var stored = frozen == null ? null : frozen.getJSONObject(node.getNo());
            if (frozen != null && (stored == null || !stored.keySet().equals(proof.keySet())
                    || proof.entrySet().stream()
                    .anyMatch(e -> !Objects.equals(String.valueOf(e.getValue()), stored.getStr(e.getKey()))))) {
                throw exception(ETL_PIPELINE_GRAPH_INVALID, "已冻结环境绑定发生变化，请核查后重新发布");
            }
            config.set("databaseId", binding.getDatabaseId());
            config.set("schemaName", binding.getSchemaName());
            config.set("tableName", table);
            config.set("environmentId", environmentId);
            config.set("environmentBinding", proof);
            node.setNodeJson(config.toString());
            resolved.add(node);
            proofs.put(node.getNo(), proof);
        }
        if (frozen != null && !frozen.keySet().equals(proofs.keySet())) {
            throw exception(ETL_PIPELINE_GRAPH_INVALID, "环境绑定快照与执行节点不一致");
        }
        return new Resolved(List.copyOf(resolved), Collections.unmodifiableMap(proofs));
    }

    /** 在派发工作线程中复核，使用快照中的物理配置，禁止重新绑定或降级到节点旧 databaseId。 */
    public void validateFrozen(JSONObject snapshot, Long actor) {
        var nodes = EtlGraphConverter.toNodeDefs(EtlSubflowService.maps(snapshot.get(
                snapshot.containsKey("executionNodes") ? "executionNodes" : "nodes")));
        var verified = reuse(snapshot.getLong("environmentId"), nodes, actor, snapshot.get("environmentBindings"));
        for (int i = 0; i < nodes.size(); i++) {
            var before = JSONUtil.parseObj(nodes.get(i).getNodeJson());
            if (before.getStr("logicalDatabaseKey") == null || before.getStr("logicalDatabaseKey").isBlank()) {
                continue;
            }
            var after = JSONUtil.parseObj(verified.nodes().get(i).getNodeJson());
            if (!Objects.equals(before.getLong("databaseId"), after.getLong("databaseId"))
                    || !Objects.equals(before.getStr("schemaName"), after.getStr("schemaName"))
                    || !Objects.equals(before.getStr("tableName"), after.getStr("tableName"))) {
                throw exception(ETL_PIPELINE_GRAPH_INVALID, "执行节点物理连接与冻结绑定不一致");
            }
        }
    }

    /** 受管提交及消费前仍验证原绑定，异步提交不能绕过运行后的环境变更。 */
    public void validateTargetBinding(JSONObject config, Long actor) {
        String key = config.getStr("logicalDatabaseKey");
        if (key == null || key.isBlank()) {
            return;
        }
        var node = new EtlNodeDef();
        node.setNo("binding");
        node.setToolCode("read-db");
        node.setNodeJson(config.toString());
        var frozen = JSONUtil.createObj().set("binding", config.get("environmentBinding"));
        var verified = reuse(config.getLong("environmentId"), List.of(node), actor, frozen).nodes().getFirst();
        var current = JSONUtil.parseObj(verified.getNodeJson());
        if (!Objects.equals(config.getLong("databaseId"), current.getLong("databaseId"))
                || !Objects.equals(config.getStr("schemaName"), current.getStr("schemaName"))) {
            throw exception(ETL_PIPELINE_GRAPH_INVALID, "受管目标物理连接与环境绑定不一致");
        }
    }

    /**
     * 校验并获取ETL环境。
     *
     * @param id 记录编号
     * @param lock 锁
     * @return ETL环境
     */
    private EtlEnvironmentDO require(Long id, boolean lock) {
        var query = new LambdaQueryWrapperX<EtlEnvironmentDO>().eq(EtlEnvironmentDO::getId, id);
        if (lock) {
            query.last("FOR UPDATE");
        }
        var environment = environments.selectOne(query);
        if (environment == null) {
            throw exception(ETL_PIPELINE_GRAPH_INVALID, "环境不存在或不在当前平台");
        }
        return environment;
    }

    /**
     * 获取ETL环境的启用状态。
     *
     * @param id 记录编号
     * @return ETL环境
     */
    private EtlEnvironmentDO enabled(Long id) {
        var value = require(id, false);
        if (!Boolean.TRUE.equals(value.getEnabled())) {
            throw exception(ETL_PIPELINE_GRAPH_INVALID, "环境已停用");
        }
        return value;
    }

    /**
     * 发布变更ETL环境。
     */
    private static void changed() {
        throw exception(ETL_RUN_DEFINITION_CHANGED);
    }
}
