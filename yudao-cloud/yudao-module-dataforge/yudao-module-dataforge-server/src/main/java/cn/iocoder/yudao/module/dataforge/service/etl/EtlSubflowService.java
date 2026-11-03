package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.framework.etl.core.compile.EtlSubflowExpander;
import cn.iocoder.yudao.framework.etl.core.model.*;
import cn.iocoder.yudao.framework.etl.core.schema.EtlFieldContract;
import cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlPipelineDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.etl.EtlPipelineMapper;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.ETL_PIPELINE_GRAPH_INVALID;

/** 按租户查询不可变版本；可复用编译器无需了解平台标识或权限。 */
@Service
public class EtlSubflowService {

    @Resource
    private EtlVersionService versions;

    @Resource
    private ExecutionAccessService access;

    @Resource
    private EtlPipelineMapper pipelines;

    /** 与现有流程列表一致：查询权限下平台内可见；子查询显式关联同一租户。 */
    public PageResult<EtlSubflowPipelineRespVO> pipelinePage(EtlSubflowPipelinePageReqVO req, Long actor) {
        access.requireUserPermission(actor, "dataforge:etl:query");
        requireBoundedPage(req);
        var page = pipelines.selectPage(req, new LambdaQueryWrapperX<EtlPipelineDO>()
                .likeIfPresent(EtlPipelineDO::getName, req.getName())
                .select(EtlPipelineDO::getId, EtlPipelineDO::getName)
                .apply("EXISTS (SELECT 1 FROM dh_dataforge_etl_version v WHERE v.pipeline_id=dh_dataforge_etl_pipeline.id"
                        + " AND v.deleted=0 AND v.event_type='PUBLISH'"
                        + " AND v.config_json::jsonb->>'subflowDefinition'='true')")
                .orderByDesc(EtlPipelineDO::getId));
        return new PageResult<>(
                page.getList().stream().map(value -> BeanUtils.toBean(value, EtlSubflowPipelineRespVO.class)).toList(),
                page.getTotal());
    }

    /**
     * 获取ETL子流程的版本分页。
     *
     * @param req 请求参数
     * @param actor 操作人编号
     * @return ETL子流程的版本分页结果
     */
    public PageResult<EtlVersionRespVO> versionPage(EtlSubflowVersionPageReqVO req, Long actor) {
        access.requireUserPermission(actor, "dataforge:etl:query");
        requireBoundedPage(req);
        return versions.pagePublishedSubflows(req);
    }

    /**
     * 校验有界分页。
     *
     * @param req 请求参数
     */
    private static void requireBoundedPage(cn.iocoder.yudao.framework.common.pojo.PageParam req) {
        if (req.getPageSize() == null || req.getPageSize() < 1 || req.getPageSize() > 50
                || req.getPageNo() == null || req.getPageNo() < 1) {
            throw exception(ETL_PIPELINE_GRAPH_INVALID, "子流程候选须分页查询，每页最多50条");
        }
    }

    /**
     * 先仅绑定顶层调用配置，再编译强类型的显式子流程绑定。
     *
     * @param nodes 节点
     * @param edges 边
     * @param actor 操作人编号
     * @return 结果
     */
    public EtlSubflowExpander.Result expand(List<EtlNodeDef> nodes, List<EtlEdgeDef> edges, Long actor) {
        return EtlSubflowExpander.expand(nodes, edges, (pipeline, version) -> definition(pipeline, version, actor));
    }

    /** 先仅绑定顶层调用配置，再编译强类型的显式子流程绑定。 */
    public EtlSubflowExpander.Result expand(List<EtlNodeDef> nodes, List<EtlEdgeDef> edges, Long actor,
            Map<String, String> effectiveParameters) {
        var variables = new cn.iocoder.yudao.framework.etl.core.variable.EtlVariableContext(effectiveParameters);
        var bound = nodes.stream().map(node -> {
            if (!EtlSubflowExpander.CALL.equals(node.getToolCode())) {
                return node;
            }
            var copy = BeanUtils.toBean(node, EtlNodeDef.class);
            copy.setNodeJson(variables.resolveJson(node.getNodeJson()));
            return copy;
        }).toList();
        return EtlSubflowExpander.expand(bound, edges, (pipeline, version) -> definition(pipeline, version, actor),
                false);
    }

    /**
     * 获取ETL子流程的契约。
     *
     * @param versionId 版本编号
     * @param actor 操作人编号
     * @return 契约
     */
    public Contract contract(Long versionId, Long actor) {
        access.requireUserPermission(actor, "dataforge:etl:query");
        var version = versions.get(versionId);
        var definition = definition(String.valueOf(version.getPipelineId()), String.valueOf(versionId), actor);
        // 递归编译同时验证每个嵌套不可变引用及绑定端口。
        expand(definition.nodes(), definition.edges(), actor);
        List<Port> inputs = new ArrayList<>(), outputs = new ArrayList<>();
        for (var node : definition.nodes()) {
            if (Set.of("boundary-input", "boundary-output").contains(node.getToolCode())) {
                var config = JSONUtil.parseObj(node.getNodeJson());
                var fields = EtlFieldContract.parseFields(config.getJSONArray("fields")).stream()
                        .map(EtlFieldContract::properties).toList();
                ("boundary-input".equals(node.getToolCode()) ? inputs : outputs).add(
                        new Port(config.getStr("portName"), fields));
            }
        }
        var snapshot = JSONUtil.parseObj(version.getConfigJson());
        var parameters = maps(snapshot.get("parameters")).stream().map(value -> {
            var item = BeanUtils.toBean(value, EtlPipelineSaveReqVO.ParamItem.class);
            // 契约仅公开调用方需要的类型约束，子定义默认值不跨调用边界传播。
            item.setDefaultValue(null);
            return item;
        }).toList();
        return new Contract(version.getPipelineId(), version.getId(), definition.contentHash(), inputs, outputs,
                definition.parameters().stream().sorted().toList(), parameters);
    }

    /**
     * 获取ETL子流程的定义。
     *
     * @param pipeline 流程
     * @param version 版本
     * @param actor 操作人编号
     * @return 定义
     */
    private EtlSubflowExpander.Definition definition(String pipeline, String version, Long actor) {
        access.requireUserPermission(actor, "dataforge:etl:query");
        var value = versions.get(Long.valueOf(version));
        if (!Objects.equals(String.valueOf(value.getPipelineId()), pipeline) || !"PUBLISH".equals(
                value.getEventType())) {
            throw exception(ETL_PIPELINE_GRAPH_INVALID, "子流程仅能引用对应流程的已发布版本");
        }
        var snapshot = JSONUtil.parseObj(value.getConfigJson());
        var nodes = EtlGraphConverter.toNodeDefs(maps(snapshot.get("nodes")));
        var edges = EtlGraphConverter.toEdgeDefs(maps(snapshot.get("edges")));
        EtlSubflowExpander.validateDefinition(nodes, edges);
        Set<String> parameters = new LinkedHashSet<>();
        for (var param : maps(snapshot.get("parameters"))) {
            String name = JSONUtil.parseObj(param).getStr("name", "");
            if (name.isBlank() || !parameters.add(name)) {
                throw exception(ETL_PIPELINE_GRAPH_INVALID, "子流程参数定义无效");
            }
        }
        var parameterDefinitions = cn.iocoder.yudao.framework.etl.core.variable.EtlParameterValidator.parseDefinitions(
                maps(snapshot.get("parameters")));
        return new EtlSubflowExpander.Definition(pipeline + ":" + version, value.getContentHash(), nodes, edges,
                parameters, parameterDefinitions);
    }

    public record Port(String name, List<Map<String, Object>> fields) {

    }

    public record Contract(Long pipelineId, Long versionId, String contentHash, List<Port> inputs, List<Port> outputs,
                           List<String> parameters, List<EtlPipelineSaveReqVO.ParamItem> parameterDefinitions) {

        /**
         * 创建契约实例。
         *
         * @param pipelineId 流程编号
         * @param versionId 版本编号
         * @param contentHash 内容摘要
         * @param inputs 输入
         * @param outputs 输出
         * @param parameters 参数定义或取值
         */
        public Contract(Long pipelineId, Long versionId, String contentHash, List<Port> inputs, List<Port> outputs,
                List<String> parameters) {
            this(pipelineId, versionId, contentHash, inputs, outputs, parameters, List.of());
        }
    }

    /**
     * 将 JSON 数组转换为对象映射列表。
     *
     * @param value 待处理的值
     * @return 将 JSON 数组转换为对象映射列表结果集合
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> maps(Object value) {
        return value == null ? List.of() : JsonUtils.parseObject(JSONUtil.toJsonStr(value), List.class);
    }

    /**
     * 获取ETL子流程的节点。
     *
     * @param graph 图
     * @return ETL子流程的节点结果集合
     */
    public static List<Map<String, Object>> nodes(EtlSubflowExpander.Result graph) {
        return nodes(graph.nodes());
    }

    /**
     * 获取ETL子流程的节点。
     *
     * @param nodes 节点
     * @return ETL子流程的节点结果集合
     */
    public static List<Map<String, Object>> nodes(List<EtlNodeDef> nodes) {
        return nodes.stream().map(node -> {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("name", node.getName());
            properties.put("toolCode", node.getToolCode());
            properties.put("toolType", node.getToolType());
            properties.put("nodeJson", JSONUtil.parseObj(node.getNodeJson()));
            properties.put("failGoOn", node.getFailGoOn());
            properties.put("overTimes", node.getOverTimeMinutes());
            return Map.<String, Object>of("id", node.getNo(), "properties", properties);
        }).toList();
    }

    /**
     * 获取ETL子流程的边。
     *
     * @param graph 图
     * @return ETL子流程的边结果集合
     */
    public static List<Map<String, Object>> edges(EtlSubflowExpander.Result graph) {
        return graph.edges().stream().map(edge -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("sourceNodeId", edge.getSourceNo());
            value.put("targetNodeId", edge.getTargetNo());
            value.put("sourcePort", edge.resolveOutputPort());
            value.put("targetPort", edge.getInputPort());
            value.put("properties",
                    Map.of("edgeType", Objects.requireNonNullElse(edge.getEdgeType(), "DATA"), "outputPort",
                            edge.resolveOutputPort()));
            return value;
        }).toList();
    }
}
