package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.iocoder.yudao.framework.etl.core.engine.EtlPreviewEngine;
import cn.iocoder.yudao.framework.etl.core.executor.EtlExecutorRegistry;
import cn.iocoder.yudao.framework.etl.core.model.EtlDag;
import cn.iocoder.yudao.framework.etl.core.validation.*;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.*;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.ETL_PIPELINE_GRAPH_INVALID;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.ETL_PREVIEW_RESULT_RESTRICTED;

@Service
public class EtlDesignService {

    @Resource
    private EtlPreviewMaskSupport previewMasks;

    @Resource
    private EtlExecutorRegistry registry;

    @Resource
    private EtlPolicyPlanService plans;

    @Resource
    private EtlTargetBatchService targets;

    @Resource
    private EtlSubflowService subflows;

    @Resource
    private EtlEnvironmentService environments;

    @Resource
    private EtlConnectionResolverAdapter connections;

    @Resource
    private EtlNodeAccessService nodeAccess;

    @Resource
    private cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime databaseRuntime;

    /**
     * 校验ETL 设计输入及状态。
     *
     * @param request 请求参数
     * @return ETL校验结果
     */
    public EtlValidationResult validate(EtlSchemaReqVO request) {
        try {
            Map<String, String> effectiveParameters = EtlRunParameterResolver.resolve(
                    request.getParameterDefinitions(), request.getParameters());
            var nodes = EtlGraphConverter.toNodeDefs(request.getNodes());
            var edges = EtlGraphConverter.toEdgeDefs(request.getEdges());
            var expanded = subflows.expand(nodes, edges, SecurityFrameworkUtils.getLoginUserId(), effectiveParameters);
            nodes = environments.freeze(request.getEnvironmentId(), expanded.nodes(),
                    SecurityFrameworkUtils.getLoginUserId()).nodes();
            edges = expanded.edges();
            var result = EtlGraphValidator.validate(nodes, edges, registry);
            if (!result.valid()) {
                return result;
            }
            plans.freeze(nodes, SecurityFrameworkUtils.getLoginUserId(), effectiveParameters);
            targets.validate(EtlDag.build(nodes, edges), SecurityFrameworkUtils.getLoginUserId(), effectiveParameters);
            return result;
        } catch (RuntimeException ex) {
            return EtlValidationResult.of(
                    List.of(new EtlValidationResult.Issue(null, "governance", "ERROR", "PLAN_INVALID",
                            ex.getMessage())));
        }
    }

    /**
     * 校验有效。
     *
     * @param dag DAG
     */
    public void requireValid(EtlDag dag) {
        var result = EtlGraphValidator.validate(dag.getNodes(), dag.getEdges(), registry);
        if (!result.valid()) {
            throw exception(ETL_PIPELINE_GRAPH_INVALID, result.issues().getFirst().message());
        }
    }

    /**
     * 预览ETL 设计处理结果。
     *
     * @param request 请求参数
     * @return 结果
     */
    public EtlPreviewEngine.Result preview(EtlPreviewReqVO request) {
        var guard = new EtlPreviewResultGuard();
        try {
            var result = preview(request, SecurityFrameworkUtils.getLoginUserId(), null, guard);
            if (result.success()) {
                try {
                    guard.verify();
                } catch (RuntimeException denied) {
                    throw exception(ETL_PREVIEW_RESULT_RESTRICTED);
                }
            }
            return result;
        } finally {
            // 同步预览与会话轮询使用相同的最终授权校验；请求返回或失败后
            // 不保留源端凭据。
            guard.clear();
        }
    }

    /**
     * 预览ETL 设计处理结果。
     *
     * @param request 请求参数
     * @param actor 操作人编号
     * @param control 控制
     * @param guard 门禁
     * @return 结果
     */
    EtlPreviewEngine.Result preview(EtlPreviewReqVO request, Long actor,
            cn.iocoder.yudao.framework.etl.core.runtime.EtlExecutionControl control,
            EtlPreviewResultGuard guard) {
        var nodes = EtlGraphConverter.toNodeDefs(request.getNodes());
        var edges = EtlGraphConverter.toEdgeDefs(request.getEdges());
        Map<String, String> effectiveParameters = EtlRunParameterResolver.resolve(
                request.getParameterDefinitions(), request.getParameters());
        if (nodes.stream().anyMatch(n -> "sub-pipeline".equals(n.getToolCode()))) {
            var expanded = subflows.expand(nodes, edges, actor, effectiveParameters);
            nodes = expanded.nodes();
            edges = expanded.edges();
        }
        if (nodes.stream().noneMatch(n -> n.getNo().equals(request.getTargetNodeId()))) {
            return new EtlPreviewEngine.Result(false, List.of(), List.of(), 0, 0,
                    List.of(new EtlValidationResult.Issue(request.getTargetNodeId(), "preview", "ERROR",
                            "PREVIEW_TARGET", "请预览子流程之后的下游节点；调用可包含多个输出端口")), false);
        }
        Set<String> ancestors = new HashSet<>();
        ancestors.add(request.getTargetNodeId());
        boolean changed;
        do {
            changed = false;
            for (var edge : edges) {
                if (ancestors.contains(edge.getTargetNo())) {
                    changed |= ancestors.add(edge.getSourceNo());
                }
            }
        } while (changed);
        var selected = nodes.stream().filter(n -> ancestors.contains(n.getNo())).toList();
        selected = environments.freeze(request.getEnvironmentId(), selected, actor).nodes();
        // 保留连接标识或解析凭据之前，先拒绝缺少执行者、源端不可访问或已禁用的情况。
        // 在返回任一侧比较结果之前，Guard 再次校验同一组祖先节点的连接与操作权限。
        nodeAccess.authorize(selected, actor, effectiveParameters);
        var frozen = plans.freeze(selected, actor, effectiveParameters);
        if (guard != null) {
            var authorizedNodes = List.copyOf(selected);
            var sourceRevisions = new LinkedHashMap<Long, String>();
            var variables = new cn.iocoder.yudao.framework.etl.core.variable.EtlVariableContext(effectiveParameters);
            for (var node : selected) {
                if ("read-db".equals(node.getToolCode())) {
                    Long databaseId = cn.hutool.json.JSONUtil.parseObj(variables.resolveJson(node.getNodeJson()))
                            .getLong("databaseId");
                    var connection = connections.resolve(databaseId);
                    if (connection == null || connection.getConfigurationRevision() == null || connection.getConfigurationRevision()
                            .isBlank()) {
                        throw new IllegalStateException("Source identity cannot be retained safely");
                    }
                    sourceRevisions.put(databaseId, connection.getConfigurationRevision());
                }
            }
            guard.currentAccessCheck(() -> {
                // 即使解析的是已保留的源端标识，也必须先校验当前对象及执行者的访问权限。
                nodeAccess.authorize(authorizedNodes, actor, effectiveParameters);
                sourceRevisions.forEach((id, revision) -> {
                    var current = connections.resolve(id);
                    if (current == null || !revision.equals(current.getConfigurationRevision())) {
                        throw new IllegalStateException("Source identity changed");
                    }
                });
                plans.authorize(frozen, actor);
                plans.freeze(authorizedNodes, actor, effectiveParameters);
            });
        }
        EtlPreviewEngine.DatabaseSource source = null;
        if (selected.stream().anyMatch(n -> "read-db".equals(n.getToolCode()))) {

            var readPolicy = nodeAccess.createReadPort(actor);
            source = new EtlPreviewEngine.DatabaseSource(databaseRuntime, connections,
                    guard == null ? readPolicy : guard.wrap(readPolicy),
                    "etl-preview:" + actor,
                    task -> () -> task.run());
        }
        var selectedEdges = edges.stream()
                .filter(e -> ancestors.contains(e.getSourceNo()) && ancestors.contains(e.getTargetNo())).toList();
        var result = new EtlPreviewEngine().preview(selected, selectedEdges, request.getTargetNodeId(),
                request.getLimit(), effectiveParameters, plans.createPort(frozen, null), source, control);
        // 预览连接已在引擎内关闭；按查看者身份动态脱敏，governance 不可用时不返回数据。
        return previewMasks.mask(result, selected, selectedEdges, request.getTargetNodeId(), actor);
    }
}
