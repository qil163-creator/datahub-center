package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.etl.core.executor.EtlExecutorRegistry;
import cn.iocoder.yudao.framework.etl.core.model.EtlDag;
import cn.iocoder.yudao.framework.etl.core.model.EtlEdgeDef;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.schema.EtlSchemaResult;
import cn.iocoder.yudao.framework.etl.core.schema.EtlSchemaService;
import cn.iocoder.yudao.framework.etl.core.script.EtlScriptRunner;
import cn.iocoder.yudao.framework.etl.core.variable.EtlParameterDefinition;
import cn.iocoder.yudao.framework.etl.core.variable.EtlParameterValidator;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlNodeRecordDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlPipelineDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlRecordDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.etl.EtlNodeRecordMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.etl.EtlPipelineMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.etl.EtlRecordMapper;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.Resource;
import tools.jackson.core.type.TypeReference;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertList;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertSet;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;

/**
 * 可视化 ETL 流程 Service 实现类
 *
 * 引擎逻辑在 yudao-spring-boot-starter-biz-etl 中，本服务只负责元数据存储、
 * 校验、发布调度与执行提交
 *
 * @author Li
 */
@Service
@Validated
@lombok.extern.slf4j.Slf4j
public class EtlPipelineServiceImpl implements EtlPipelineService {

    private static final DateTimeFormatter DISPLAY_DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final java.util.regex.Pattern PARAM_REFERENCE =
            java.util.regex.Pattern.compile("\\$\\{([^}]+)}");

    @Resource
    private EtlPipelineMapper pipelineMapper;

    @Resource
    private EtlRunCoordinator runCoordinator;

    @Resource
    private cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService executionAccessService;

    @Resource
    private cn.iocoder.yudao.module.dataforge.service.quality.QualityGateService qualityGateService;

    @Resource
    private EtlRecordMapper recordMapper;

    @Resource
    private EtlNodeRecordMapper nodeRecordMapper;

    @Resource
    private EtlPipelineExecutor pipelineExecutor;

    @Resource
    private EtlExecutorRegistry executorRegistry;

    @Resource
    private EtlScriptRunner scriptRunner;

    @Resource
    private EtlScriptAdmissionService scriptAdmissionService;

    @Resource
    private EtlSchemaService schemaService;

    @Resource
    private EtlConnectionResolverAdapter connectionResolverAdapter;

    @Resource
    private EtlNodeAccessService nodeAccessService;

    @Resource
    private EtlApiPortAdapter apiPortAdapter;

    @Resource
    private AdminUserApi adminUserApi;

    @Resource
    private EtlDesignService designService;

    @Resource
    private EtlPolicyPlanService policyPlanService;

    @Resource
    private EtlSubflowService subflowService;

    @Resource
    private EtlTargetBatchService targetBatchService;

    @Resource
    private EtlEnvironmentService environmentService;

    // ==================== 流程 CRUD ====================

    /**
     * 保存流程（新建或更新，含 DAG 结构校验）
     *
     * @param saveReqVO 保存参数
     * @return 生成或保存的记录编号
     */
    @Override
    public Long savePipeline(EtlPipelineSaveReqVO saveReqVO) {
        validateParameterDefinitions(saveReqVO.getParameters(), false);
        EtlPipelineDO old = null;
        if (saveReqVO.getId() != null) {
            old = validatePipelineExists(saveReqVO.getId());
            if (!java.util.Objects.equals(old.getRevision(), saveReqVO.getRevision())) {
                throw exception(ETL_PIPELINE_GRAPH_INVALID, "配置已被他人修改，请重新加载后保存");
            }
        }
        EtlPipelineDO exists = pipelineMapper.selectByName(saveReqVO.getName());
        if (exists != null && !exists.getId().equals(saveReqVO.getId())) {
            throw exception(ETL_PIPELINE_NAME_DUPLICATE);
        }
        validateCron(saveReqVO.getTaskType(), saveReqVO.getCron());
        environmentService.validateSelection(saveReqVO.getEnvironmentId());
        // 草稿只校验编号、引用和循环；未连线、未配置及未选择版本的子流程可先保存。
        // 发布/运行的 prepareRun 仍展开子流程并执行完整配置、权限与结构校验。
        validateDraftGraph(saveReqVO.getNodes(), saveReqVO.getEdges());

        EtlPipelineDO pipeline = BeanUtils.toBean(saveReqVO, EtlPipelineDO.class);
        pipeline.setNodesJson(JsonUtils.toJsonString(
                saveReqVO.getNodes() == null ? List.of() : saveReqVO.getNodes()));
        pipeline.setEdgesJson(JsonUtils.toJsonString(
                saveReqVO.getEdges() == null ? List.of() : saveReqVO.getEdges()));
        pipeline.setParamsJson(JsonUtils.toJsonString(
                saveReqVO.getParameters() == null ? List.of() : saveReqVO.getParameters()));
        if (pipeline.getTaskType() != EtlPipelineDO.TASK_TYPE_PERIODIC) {
            pipeline.setCron(null);
        }
        if (old == null) {
            pipeline.setRevision(0);
            pipeline.setStatus(EtlPipelineDO.STATUS_UNRELEASED);
            pipeline.setScheduleStatus(EtlPipelineDO.SCHEDULE_OFF);
            pipelineMapper.insert(pipeline);
        } else {
            pipeline.setRevision(old.getRevision() + 1);
            int updated = pipelineMapper.update(pipeline, Wrappers.lambdaUpdate(EtlPipelineDO.class)
                    .eq(EtlPipelineDO::getId, pipeline.getId())
                    .set(pipeline.getEnvironmentId() == null, EtlPipelineDO::getEnvironmentId, null)
                    .eq(EtlPipelineDO::getRevision, old.getRevision()));
            if (updated == 0) {
                throw exception(ETL_PIPELINE_GRAPH_INVALID, "配置已变化，请重新加载后保存");
            }
        }
        return pipeline.getId();
    }

    /**
     * 校验草稿图。
     *
     * @param nodes 节点
     * @param edges 边
     */
    static void validateDraftGraph(List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
        try {
            if (CollUtil.isEmpty(nodes)) {
                if (CollUtil.isNotEmpty(edges)) {
                    throw new IllegalStateException("空画布不能包含连线");
                }
                return;
            }
            if (nodes.size() > 32 || (edges != null && edges.size() > 128)) {
                throw new IllegalStateException("草稿最多32个节点、128条连线");
            }
            EtlDag.buildLenient(EtlGraphConverter.toNodeDefs(nodes), EtlGraphConverter.toEdgeDefs(edges))
                    .topologicalSort();
        } catch (IllegalStateException ex) {
            throw exception(ETL_PIPELINE_GRAPH_INVALID, ex.getMessage());
        }
    }

    /**
     * 删除流程（已发布或运行中不可删除）
     *
     * @param id 记录编号
     */
    @Override
    public void deletePipeline(Long id) {
        validatePipelineExists(id);
        runCoordinator.deletePipeline(id);
    }

    /**
     * 获得流程（含画布 JSON）
     *
     * @param id 记录编号
     * @return ETL 流程响应数据
     */
    @Override
    public EtlPipelineRespVO getPipeline(Long id) {
        EtlPipelineDO pipeline = validatePipelineExists(id);
        return convertToResp(pipeline, true);
    }

    /**
     * 获得流程分页
     *
     * @param pageReqVO 分页查询条件
     * @return 流程分页结果
     */
    @Override
    public PageResult<EtlPipelineRespVO> getPipelinePage(EtlPipelinePageReqVO pageReqVO) {
        PageResult<EtlPipelineDO> pageResult = pipelineMapper.selectPage(pageReqVO);
        Set<Long> userIds = convertSet(pageResult.getList(), EtlPipelineDO::getReleaseUserId);
        Map<Long, AdminUserRespDTO> userMap = CollUtil.isEmpty(userIds)
                ? Collections.emptyMap() : adminUserApi.getUserMap(userIds);
        List<EtlPipelineRespVO> list = convertList(pageResult.getList(), pipeline -> {
            EtlPipelineRespVO vo = convertToResp(pipeline, false);
            AdminUserRespDTO user = pipeline.getReleaseUserId() == null
                    ? null : userMap.get(pipeline.getReleaseUserId());
            if (user != null) {
                vo.setReleaseUserName(user.getNickname());
            }
            return vo;
        });
        return new PageResult<>(list, pageResult.getTotal());
    }

    /**
     * 转换目标。
     *
     * @param pipeline 流程
     * @param withGraph 包含图
     * @return ETL 流程响应数据
     */
    private EtlPipelineRespVO convertToResp(EtlPipelineDO pipeline, boolean withGraph) {
        EtlPipelineRespVO vo = BeanUtils.toBean(pipeline, EtlPipelineRespVO.class);
        if (withGraph) {
            vo.setNodes(parseListOfMap(pipeline.getNodesJson()));
            vo.setEdges(parseListOfMap(pipeline.getEdgesJson()));
            vo.setParameters(StrUtil.isBlank(pipeline.getParamsJson()) ? List.of()
                    : JsonUtils.parseObject(pipeline.getParamsJson(),
                    new TypeReference<List<EtlPipelineSaveReqVO.ParamItem>>() {
                    }));
        }
        return vo;
    }

    /**
     * 获取ETL 流程的已选择运行参数。
     *
     * @param pipeline 流程
     * @return ETL运行参数定义
     */
    private EtlRunParameterDefinition selectedRunParameters(EtlPipelineDO pipeline) {
        boolean published = Integer.valueOf(EtlPipelineDO.STATUS_RELEASED).equals(pipeline.getStatus())
                && StrUtil.isNotBlank(pipeline.getPublishedConfigJson());
        List<EtlPipelineSaveReqVO.ParamItem> parameters;
        Integer definitionRevision;
        if (published) {
            var snapshot = cn.hutool.json.JSONUtil.parseObj(pipeline.getPublishedConfigJson());
            var array = snapshot.getJSONArray("parameters");
            parameters = parseParameterItems(array == null ? null : array.toString());
            definitionRevision = pipeline.getPublishedRevision();
        } else {
            parameters = parseParameterItems(pipeline.getParamsJson());
            definitionRevision = pipeline.getRevision();
        }
        validateParameterDefinitions(parameters, false);
        return new EtlRunParameterDefinition(List.copyOf(parameters), definitionRevision, published);
    }

    /**
     * 解析参数项。
     *
     * @param json JSON 内容
     * @return 解析参数项结果集合
     */
    private List<EtlPipelineSaveReqVO.ParamItem> parseParameterItems(String json) {
        if (StrUtil.isBlank(json)) {
            return List.of();
        }
        return JsonUtils.parseObject(json, new TypeReference<>() {
        });
    }

    /**
     * 获取ETL 流程的组件定义。
     *
     * @param items 项
     * @return ETL 流程的组件定义结果集合
     */
    private List<EtlParameterDefinition> componentDefinitions(List<EtlPipelineSaveReqVO.ParamItem> items) {
        if (items == null) {
            return List.of();
        }
        return items.stream().map(item -> new EtlParameterDefinition(item.getName(), item.getType(),
                item.getRequired(), item.getDefaultValue(), item.getMin(), item.getMax(),
                item.getMinLength(), item.getMaxLength())).toList();
    }

    /**
     * 校验参数定义。
     *
     * @param items 项
     * @param requireDefaultsForRequired 需要默认值对应必需
     */
    private void validateParameterDefinitions(List<EtlPipelineSaveReqVO.ParamItem> items,
            boolean requireDefaultsForRequired) {
        try {
            EtlParameterValidator.validateDefinitions(componentDefinitions(items), requireDefaultsForRequired);
        } catch (IllegalArgumentException ex) {
            throw exception(ETL_PIPELINE_GRAPH_INVALID, ex.getMessage());
        }
    }

    /**
     * 解析参数。
     *
     * @param items 项
     * @param supplied 已提供
     * @param defaultsPolicy 默认值策略
     * @return 处理后的键值映射
     */
    private Map<String, String> resolveParameters(List<EtlPipelineSaveReqVO.ParamItem> items,
            Map<String, String> supplied,
            EtlParameterValidator.DefaultsPolicy defaultsPolicy) {
        try {
            return EtlParameterValidator.resolve(componentDefinitions(items), supplied, defaultsPolicy);
        } catch (IllegalArgumentException ex) {
            throw exception(ETL_PIPELINE_GRAPH_INVALID, ex.getMessage());
        }
    }

    /**
     * 获取ETL 流程的手工输入指纹。
     *
     * @param expectedDefinitionRevision 预期定义修订号
     * @param parameters 参数定义或取值
     * @return 计算后的摘要或签名字符串
     */
    String manualInputFingerprint(Integer expectedDefinitionRevision, Map<String, String> parameters) {
        if ((parameters == null || parameters.isEmpty()) && expectedDefinitionRevision == null) {
            return null;
        }
        Map<String, Object> canonical = new java.util.LinkedHashMap<>();
        canonical.put("expectedDefinitionRevision", expectedDefinitionRevision);
        canonical.put("parameters", new java.util.TreeMap<>(parameters));
        return cn.hutool.crypto.digest.DigestUtil.sha256Hex(JsonUtils.toJsonString(canonical));
    }

    /** 固定标识所使用的值必须等于发布或草稿中的默认值。 */
    private void assertStableParameterOverrides(EtlPipelineDO pipeline,
            List<EtlPipelineSaveReqVO.ParamItem> definitions,
            Map<String, String> effectiveParameters) {
        Map<String, String> defaults = new java.util.HashMap<>();
        for (EtlPipelineSaveReqVO.ParamItem item : definitions) {
            if (item.getDefaultValue() == null) {
                continue;
            }
            defaults.putAll(resolveParameters(List.of(item), Map.of(),
                    EtlParameterValidator.DefaultsPolicy.APPLY_DEFAULTS));
        }
        Set<String> protectedReferences = new java.util.HashSet<>();
        for (EtlNodeDef node : EtlGraphConverter.toNodeDefs(parseListOfMap(pipeline.getNodesJson()))) {
            String configText = StrUtil.blankToDefault(node.getNodeJson(), "{}");
            boolean protectedNode = node.getToolType() != null && (node.getToolType() == EtlNodeDef.TOOL_TYPE_INPUT
                    || node.getToolType() == EtlNodeDef.TOOL_TYPE_OUTPUT
                    || node.getToolType() == EtlNodeDef.TOOL_TYPE_SCRIPT)
                    || Set.of("quality-check", "boundary-input", "boundary-output")
                    .contains(StrUtil.nullToEmpty(node.getToolCode()));
            if ("data-clean".equals(node.getToolCode())) {
                var config = cn.hutool.json.JSONUtil.parseObj(configText);
                var defaultVariables = new cn.iocoder.yudao.framework.etl.core.variable.EtlVariableContext(defaults);
                var effectiveVariables = new cn.iocoder.yudao.framework.etl.core.variable.EtlVariableContext(
                        effectiveParameters);
                String defaultMode = resolveParameterValue(defaultVariables, config.getStr("executionMode"));
                String effectiveMode = resolveParameterValue(effectiveVariables, config.getStr("executionMode"));
                String defaultRemoval = resolveParameterValue(defaultVariables, config.getStr("enableFieldRemoval"));
                String effectiveRemoval = resolveParameterValue(effectiveVariables,
                        config.getStr("enableFieldRemoval"));
                protectedNode = "GOVERNANCE".equalsIgnoreCase(defaultMode) || "GOVERNANCE".equalsIgnoreCase(
                        effectiveMode)
                        || booleanConfigValue(defaultRemoval) || booleanConfigValue(effectiveRemoval);
            }
            if ("sub-pipeline".equals(node.getToolCode())) {
                var config = cn.hutool.json.JSONUtil.parseObj(configText);
                config.remove("parameters");
                configText = config.toString();
                protectedNode = true;
            }
            if (!protectedNode) {
                continue;
            }
            collectParameterReferences(cn.hutool.json.JSONUtil.parse(configText), protectedReferences);
        }
        for (String name : protectedReferences) {
            if (!defaults.containsKey(name) || !java.util.Objects.equals(defaults.get(name),
                    effectiveParameters.get(name))) {
                throw exception(ETL_PIPELINE_GRAPH_INVALID,
                        "参数【" + name + "】参与数据源、目标或冻结策略身份，运行时不可覆盖");
            }
        }
    }

    /**
     * 解析参数值。
     *
     * @param variables 变量
     * @param value 待处理的值
     * @return 解析参数值文本
     */
    private String resolveParameterValue(cn.iocoder.yudao.framework.etl.core.variable.EtlVariableContext variables,
            String value) {
        try {
            return variables.resolve(value);
        } catch (RuntimeException ex) {
            // 任一侧启用受保护模式时，下方引用校验会处理缺少可选默认值的情况。
            return null;
        }
    }

    /**
     * 判断布尔值配置值条件是否成立。
     *
     * @param value 待处理的值
     * @return 处理是否成功
     */
    private boolean booleanConfigValue(String value) {
        return Boolean.TRUE.equals(cn.hutool.json.JSONUtil.createObj().set("value", value).getBool("value", false));
    }

    /**
     * 收集参数引用。
     *
     * @param value 待处理的值
     * @param references 引用
     */
    static void collectParameterReferences(Object value, Set<String> references) {
        if (value instanceof cn.hutool.json.JSONObject object) {
            object.forEach((key, child) -> collectParameterReferences(child, references));
        } else if (value instanceof cn.hutool.json.JSONArray array) {
            array.forEach(child -> collectParameterReferences(child, references));
        } else if (value instanceof String text) {
            var matcher = PARAM_REFERENCE.matcher(text);
            while (matcher.find()) {
                String name = matcher.group(1).trim();
                if (name.startsWith("params.")) {
                    String parameterName = name.substring("params.".length());
                    if (parameterName.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")) {
                        references.add(parameterName);
                    }
                }
            }
        }
    }

    /**
     * 确认已展开子流程身份稳定。
     *
     * @param pipeline 流程
     * @param definitions 定义
     * @param effectiveParameters 生效参数
     * @param expanded 已展开
     */
    void assertExpandedSubflowIdentityStable(EtlPipelineDO pipeline,
            List<EtlPipelineSaveReqVO.ParamItem> definitions,
            Map<String, String> effectiveParameters,
            cn.iocoder.yudao.framework.etl.core.compile.EtlSubflowExpander.Result expanded) {
        Map<String, String> defaults = resolveParameters(definitions, Map.of(),
                EtlParameterValidator.DefaultsPolicy.APPLY_DEFAULTS);
        if (defaults.equals(effectiveParameters)) {
            return;
        }

        Map<String, EtlNodeDef> currentChildren = new java.util.HashMap<>();
        for (EtlNodeDef node : expanded.nodes()) {
            var origin = expanded.sourceMap().get(node.getNo());
            if (origin != null && StrUtil.isNotBlank(origin.callPath())) {
                currentChildren.put(node.getNo(), node);
            }
        }
        if (currentChildren.isEmpty()) {
            return;
        }

        if (StrUtil.isBlank(pipeline.getPublishedConfigJson())) {
            throw exception(ETL_PIPELINE_GRAPH_INVALID,
                    "运行参数覆盖了子流程受保护配置，但没有可核对的发布快照；请重新发布");
        }
        var published = cn.hutool.json.JSONUtil.parseObj(pipeline.getPublishedConfigJson());
        Object rawSnapshotNodes = published.get("executionNodes");
        if (rawSnapshotNodes == null) {
            throw exception(ETL_PIPELINE_GRAPH_INVALID,
                    "运行参数覆盖了子流程受保护配置，但发布快照缺少展开图；请重新发布");
        }

        Map<String, EtlNodeDef> previousChildren = new java.util.HashMap<>();
        var previousOrigins = published.getJSONObject("sourceMap");
        for (EtlNodeDef node : EtlGraphConverter.toNodeDefs(EtlSubflowService.maps(rawSnapshotNodes))) {
            var origin = previousOrigins == null ? null : previousOrigins.getJSONObject(node.getNo());
            if (origin != null && StrUtil.isNotBlank(origin.getStr("callPath"))) {
                previousChildren.put(node.getNo(), node);
            }
        }
        if (previousOrigins == null) {
            throw exception(ETL_PIPELINE_GRAPH_INVALID,
                    "运行参数覆盖了子流程，但发布快照缺少来源映射，无法核验受保护节点；请重新发布");
        }
        if (!previousChildren.keySet().equals(currentChildren.keySet())) {
            throw exception(ETL_PIPELINE_GRAPH_INVALID,
                    "参数覆盖时子流程展开图与发布快照不一致，无法核验受保护节点；请重新发布");
        }

        var previousDependencies = dependencyIdentity(published.getJSONArray("dependencies"));
        var currentDependencies = dependencyIdentity(expanded.dependencies());
        if (previousDependencies == null || currentDependencies == null || currentDependencies.isEmpty()
                || !previousDependencies.equals(currentDependencies)) {
            throw exception(ETL_PIPELINE_GRAPH_INVALID, "参数覆盖会改变子流程冻结版本，须重新发布父流程后运行");
        }

        for (String nodeId : currentChildren.keySet()) {
            EtlNodeDef previous = previousChildren.get(nodeId);
            EtlNodeDef current = currentChildren.get(nodeId);
            var previousOrigin = previousOrigins.getJSONObject(nodeId);
            var currentOrigin = expanded.sourceMap().get(nodeId);
            if (previousOrigin == null || currentOrigin == null
                    || !java.util.Objects.equals(previousOrigin.getStr("callPath"), currentOrigin.callPath())
                    || !java.util.Objects.equals(previousOrigin.getStr("originalNodeNo"),
                    currentOrigin.originalNodeNo())
                    || !java.util.Objects.equals(previousOrigin.getStr("definitionKey"), currentOrigin.definitionKey())
                    || !java.util.Objects.equals(previousOrigin.getStr("designNodeNo"), currentOrigin.designNodeNo())) {
                throw exception(ETL_PIPELINE_GRAPH_INVALID,
                        "参数覆盖会改变子流程节点来源或版本身份，须重新发布父流程后运行");
            }
            if (!isProtectedSubflowNode(previous) && !isProtectedSubflowNode(current)) {
                continue;
            }
            if (previous == null || current == null
                    || !java.util.Objects.equals(previous.getToolCode(), current.getToolCode())
                    || !sameJsonConfig(previous.getNodeJson(), current.getNodeJson())) {
                throw exception(ETL_PIPELINE_GRAPH_INVALID,
                        "参数覆盖会改变子流程边界或治理清洗配置，须重新发布子流程及父流程后运行");
            }
        }
    }

    /**
     * 获取ETL 流程的依赖身份。
     *
     * @param dependencies 依赖
     * @return ETL 流程的依赖身份结果集合
     */
    private List<String> dependencyIdentity(List<?> dependencies) {
        if (dependencies == null) {
            return null;
        }
        List<String> identities = new java.util.ArrayList<>();
        for (Object value : dependencies) {
            String key;
            String hash;
            if (value instanceof cn.iocoder.yudao.framework.etl.core.compile.EtlSubflowExpander.Dependency dependency) {
                key = dependency.key();
                hash = dependency.contentHash();
            } else {
                cn.hutool.json.JSONObject item = value instanceof cn.hutool.json.JSONObject object
                        ? object : cn.hutool.json.JSONUtil.parseObj(value);
                key = item.getStr("key");
                hash = item.getStr("contentHash");
            }
            if (StrUtil.isBlank(key) || StrUtil.isBlank(hash)) {
                return null;
            }
            identities.add(key + "\u0000" + hash);
        }
        java.util.Collections.sort(identities);
        return identities;
    }

    /**
     * 判断是否满足受保护子流程节点条件。
     *
     * @param node 节点
     * @return 判断结果
     */
    private boolean isProtectedSubflowNode(EtlNodeDef node) {
        if (node == null) {
            return false;
        }
        if ("boundary-bridge".equals(node.getToolCode())) {
            return true;
        }
        if (!"data-clean".equals(node.getToolCode())) {
            return false;
        }
        var config = cn.hutool.json.JSONUtil.parseObj(StrUtil.blankToDefault(node.getNodeJson(), "{}"));
        return "GOVERNANCE".equalsIgnoreCase(config.getStr("executionMode"))
                || Boolean.TRUE.equals(config.getBool("enableFieldRemoval"));
    }

    /**
     * 判断是否一致JSON配置条件。
     *
     * @param left 左侧
     * @param right 右侧
     * @return 判断结果
     */
    private boolean sameJsonConfig(String left, String right) {
        if (StrUtil.isBlank(left) || StrUtil.isBlank(right)) {
            return false;
        }
        return cn.hutool.json.JSONUtil.parseObj(left).equals(cn.hutool.json.JSONUtil.parseObj(right));
    }

    /**
     * 解析列表对应映射。
     *
     * @param json JSON 内容
     * @return 解析列表对应映射结果集合
     */
    private List<Map<String, Object>> parseListOfMap(String json) {
        if (StrUtil.isBlank(json)) {
            return List.of();
        }
        return JsonUtils.parseObject(json, new TypeReference<>() {
        });
    }

    // ==================== 发布 / 执行 ====================

    /**
     * 任务上线：锁定配置；一次性任务上线即执行一次
     *
     * @param id 记录编号
     * @param revision 修订号
     */
    @Override
    public void releasePipeline(Long id, Integer revision) {
        EtlPipelineDO pipeline = validatePipelineExists(id);
        if (revision == null || !java.util.Objects.equals(revision, pipeline.getRevision())) {
            throw exception(ETL_PIPELINE_GRAPH_INVALID, "草稿已变化，请重新加载后发布");
        }
        qualityGateService.validate(pipeline.getQualityTaskId(), SecurityFrameworkUtils.getLoginUserId());
        // 发布校验：结构 + 组件全部已实现
        boolean periodic = pipeline.getTaskType() != null
                && pipeline.getTaskType() == EtlPipelineDO.TASK_TYPE_PERIODIC;
        if (periodic) {
            validateCron(pipeline.getTaskType(), pipeline.getCron());
        }
        Long actor = SecurityFrameworkUtils.getLoginUserId();
        var prepared = prepareRun(pipeline, EtlRecordDO.EXECUTE_TYPE_AUTO, actor, true);
        var receipt = runCoordinator.publish(pipeline, prepared.getConfigJson(), actor, prepared);
        if (receipt != null) {
            hintExecution(receipt.recordId());
        }
    }

    /**
     * 读取运行参数。
     *
     * @param id 记录编号
     * @param actorId 操作人编号
     * @return ETL运行参数定义
     */
    @Override
    public EtlRunParameterDefinition getRunParameters(Long id, Long actorId) {
        EtlPipelineDO pipeline = validatePipelineExists(id);
        executionAccessService.requireUserPermission(actorId,
                "dataforge:etl:query");
        executionAccessService.requireUserPermission(actorId,
                cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService.PERMISSION_ETL_RUN);
        return selectedRunParameters(pipeline);
    }

    /**
     * 任务下线（调度开启中不可下线）
     *
     * @param id 记录编号
     */
    @Override
    public void cancelPipeline(Long id) {
        validatePipelineExists(id);
        runCoordinator.unpublish(id);
    }

    /**
     * 开启/关闭调度（仅周期任务，需先上线；开启时按 Cron 计算下次执行时间）
     *
     * @param id 记录编号
     * @param enable 启用
     */
    @Override
    public void updateScheduleStatus(Long id, Boolean enable) {
        validatePipelineExists(id);
        runCoordinator.changeSchedule(id, Boolean.TRUE.equals(enable));
    }

    /**
     * 克隆流程（复制画布与配置，新流程为未上线状态）
     *
     * @param id 记录编号
     * @return 记录编号
     */
    @Override
    public Long copyPipeline(Long id) {
        EtlPipelineDO source = validatePipelineExists(id);
        EtlPipelineDO copy = EtlPipelineDO.builder()
                .name(buildCopyName(source.getName()))
                .qualityTaskId(source.getQualityTaskId()).revision(0)
                .environmentId(source.getEnvironmentId())
                .taskType(source.getTaskType())
                .cron(source.getCron())
                .status(EtlPipelineDO.STATUS_UNRELEASED)
                .scheduleStatus(EtlPipelineDO.SCHEDULE_OFF)
                .description(source.getDescription())
                .nodesJson(source.getNodesJson())
                .edgesJson(source.getEdgesJson())
                .paramsJson(source.getParamsJson())
                .orgId(source.getOrgId())
                .build();
        pipelineMapper.insert(copy);
        return copy.getId();
    }

    /**
     * 生成不重名的克隆名称：xxx_副本、xxx_副本2 ...
     */
    private String buildCopyName(String sourceName) {
        String base = StrUtil.maxLength(StrUtil.nullToEmpty(sourceName), 40) + "_副本";
        String name = base;
        for (int i = 2; pipelineMapper.selectByName(name) != null && i < 100; i++) {
            name = base + i;
        }
        return name;
    }

    /**
     * 手动执行一次
     *
     * @param id 记录编号
     * @return 运行流程数值
     */
    @Override
    public Long runPipeline(Long id) {
        return runPipelineCommand(id, java.util.UUID.randomUUID().toString()).recordId();
    }

    /**
     * 运行流程命令。
     *
     * @param id 记录编号
     * @param requestId 请求编号
     * @return 运行回执
     */
    @Override
    public cn.iocoder.yudao.module.dataforge.service.execution.RunReceipt runPipelineCommand(Long id,
            String requestId) {
        return runPipelineCommand(id, requestId, null, Map.of());
    }

    /**
     * 运行流程命令。
     *
     * @param id 记录编号
     * @param requestId 请求编号
     * @param expectedDefinitionRevision 预期定义修订号
     * @param parameters 参数定义或取值
     * @return 运行回执
     */
    @Override
    public cn.iocoder.yudao.module.dataforge.service.execution.RunReceipt runPipelineCommand(
            Long id, String requestId, Integer expectedDefinitionRevision, Map<String, String> parameters) {
        EtlPipelineDO pipeline = validatePipelineExists(id);
        Long actor = SecurityFrameworkUtils.getLoginUserId();
        executionAccessService.requireUserPermission(actor,
                cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService.PERMISSION_ETL_RUN);
        Map<String, String> supplied = parameters == null ? Map.of() : parameters;
        if (!supplied.isEmpty() && expectedDefinitionRevision == null) {
            throw exception(ETL_PIPELINE_GRAPH_INVALID, "覆盖运行参数时必须携带读取到的参数定义版本");
        }
        String inputFingerprint = manualInputFingerprint(expectedDefinitionRevision, supplied);
        var replay = runCoordinator.findManualReceipt(id, actor, requestId, inputFingerprint);
        if (replay.isPresent()) {
            return replay.get();
        }
        EtlPipelineDO execution = executionSnapshot(pipeline);
        EtlRunParameterDefinition selected = selectedRunParameters(execution);
        if (expectedDefinitionRevision != null
                && !java.util.Objects.equals(expectedDefinitionRevision, selected.definitionRevision())) {
            throw exception(ETL_RUN_DEFINITION_CHANGED);
        }
        List<EtlPipelineSaveReqVO.ParamItem> definitions = selected.parameters();
        Map<String, String> effectiveParameters = resolveParameters(definitions, supplied,
                EtlParameterValidator.DefaultsPolicy.APPLY_DEFAULTS);
        assertStableParameterOverrides(execution, definitions, effectiveParameters);
        var prepared = prepareRun(execution, EtlRecordDO.EXECUTE_TYPE_HAND, actor, false, effectiveParameters);
        var receipt = runCoordinator.enqueueManual(pipeline, prepared, requestId, inputFingerprint);
        hintExecution(receipt.recordId());
        return receipt;
    }

    /**
     * 调度触发执行（执行方式记为自动调度）
     *
     * @param id 记录编号
     * @return 运行流程自动数值
     */
    @Override
    public Long runPipelineAuto(Long id) {
        EtlPipelineDO pipeline = validatePipelineExists(id);
        EtlPipelineDO execution = authorizedAutomaticSnapshot(pipeline);
        var receipt = runCoordinator.enqueueAutomatic(pipeline,
                prepareRun(execution, EtlRecordDO.EXECUTE_TYPE_AUTO, execution.getReleaseUserId()),
                java.util.UUID.randomUUID().toString());
        hintExecution(receipt.recordId());
        return receipt.recordId();
    }

    /**
     * 运行流程定时。
     *
     * @param id 记录编号
     * @param generation 代次
     * @param scheduledTime 定时时间
     * @return 运行回执
     */
    @Override
    public cn.iocoder.yudao.module.dataforge.service.execution.RunReceipt runPipelineScheduled(
            Long id, Long generation, LocalDateTime scheduledTime) {
        EtlPipelineDO pipeline = validatePipelineExists(id);
        if (!java.util.Objects.equals(generation, pipeline.getScheduleGeneration())
                || !java.util.Objects.equals(scheduledTime, pipeline.getNextRunTime())) {
            return null;
        }
        EtlPipelineDO execution = authorizedAutomaticSnapshot(pipeline);
        // 只做持久受理；XXL-Job 的有界派发扫描负责执行，进程退出不丢已受理记录。
        return runCoordinator.enqueueScheduled(pipeline,
                prepareRun(execution, EtlRecordDO.EXECUTE_TYPE_AUTO, execution.getReleaseUserId())).orElse(null);
    }

    /**
     * 获取ETL 流程的已授权自动快照。
     *
     * @param pipeline 流程
     * @return ETL 流程
     */
    private EtlPipelineDO authorizedAutomaticSnapshot(EtlPipelineDO pipeline) {
        if (!Integer.valueOf(EtlPipelineDO.STATUS_RELEASED).equals(pipeline.getStatus())
                || StrUtil.isBlank(pipeline.getPublishedConfigJson())) {
            throw exception(ETL_PIPELINE_NOT_RELEASED);
        }
        EtlPipelineDO execution = executionSnapshot(pipeline);
        Long operator = execution.getReleaseUserId();
        if (operator == null) {
            throw exception(ETL_PIPELINE_GRAPH_INVALID, "发布执行主体缺失，请重新发布");
        }
        executionAccessService.requireUserPermission(operator,
                cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService.PERMISSION_ETL_RUN,
                cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService.PERMISSION_ETL_RELEASE);
        return execution;
    }

    /** 当前发布版本和可编辑草稿完全分离，运行实例继续保存自己的不可变快照。 */
    private Map<String, Object> buildSnapshot(EtlPipelineDO pipeline) {
        Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
        snapshot.put("nodes", parseListOfMap(pipeline.getNodesJson()));
        snapshot.put("edges", parseListOfMap(pipeline.getEdgesJson()));
        snapshot.put("parameters", StrUtil.isBlank(pipeline.getParamsJson()) ? List.of()
                : JsonUtils.parseObject(pipeline.getParamsJson(), List.class));
        snapshot.put("name", pipeline.getName());
        snapshot.put("revision", pipeline.getRevision());
        snapshot.put("qualityTaskId", pipeline.getQualityTaskId());
        snapshot.put("environmentId", pipeline.getEnvironmentId());
        snapshot.put("taskType", pipeline.getTaskType());
        snapshot.put("cron", pipeline.getCron());
        return snapshot;
    }

    /**
     * 获取ETL 流程的执行快照。
     *
     * @param pipeline 流程
     * @return ETL 流程
     */
    private EtlPipelineDO executionSnapshot(EtlPipelineDO pipeline) {
        if (Integer.valueOf(EtlPipelineDO.STATUS_RELEASED).equals(pipeline.getStatus())
                && StrUtil.isNotBlank(pipeline.getPublishedConfigJson())) {
            var published = cn.hutool.json.JSONUtil.parseObj(pipeline.getPublishedConfigJson());
            EtlPipelineDO execution = BeanUtils.toBean(pipeline, EtlPipelineDO.class);
            execution.setName(published.getStr("name", pipeline.getName()));
            execution.setNodesJson(published.getJSONArray("nodes").toString());
            execution.setEdgesJson(published.getJSONArray("edges").toString());
            execution.setParamsJson(published.getJSONArray("parameters").toString());
            execution.setQualityTaskId(published.getLong("qualityTaskId"));
            execution.setEnvironmentId(published.getLong("environmentId"));
            execution.setRevision(pipeline.getPublishedRevision());
            execution.setTaskType(published.getInt("taskType", pipeline.getTaskType()));
            execution.setCron(published.getStr("cron"));
            execution.setReleaseUserId(pipeline.getPublishedExecutionUserId());
            return execution;
        }
        // 尚未发布的流程直接运行当前草稿，便于在发布前验证配置
        return pipeline;
    }

    /** 外部校验与配置准备在持久事务之前完成，受理时再核对定义版本。 */
    private EtlRecordDO prepareRun(EtlPipelineDO pipeline, int executeType, Long actor) {
        return prepareRun(pipeline, executeType, actor, false, Map.of());
    }

    /**
     * 外部校验与配置准备在持久事务之前完成，受理时再核对定义版本。
     *
     * @param pipeline 流程
     * @param executeType 执行类型
     * @param actor 操作人编号
     * @param publication 发布
     * @return ETL 运行记录
     */
    private EtlRecordDO prepareRun(EtlPipelineDO pipeline, int executeType, Long actor, boolean publication) {
        return prepareRun(pipeline, executeType, actor, publication, Map.of());
    }

    /**
     * 外部校验与配置准备在持久事务之前完成，受理时再核对定义版本。
     *
     * @param pipeline 流程
     * @param executeType 执行类型
     * @param actor 操作人编号
     * @param publication 发布
     * @param runtimeParameters 运行时参数
     * @return ETL 运行记录
     */
    private EtlRecordDO prepareRun(EtlPipelineDO pipeline, int executeType, Long actor, boolean publication,
            Map<String, String> runtimeParameters) {
        List<EtlPipelineSaveReqVO.ParamItem> parameterItems = parseParameterItems(pipeline.getParamsJson());
        validateParameterDefinitions(parameterItems, publication);
        Map<String, String> effectiveParameters = resolveParameters(parameterItems, runtimeParameters,
                EtlParameterValidator.DefaultsPolicy.APPLY_DEFAULTS);
        assertStableParameterOverrides(pipeline, parameterItems, effectiveParameters);
        var designNodes = EtlGraphConverter.toNodeDefs(parseListOfMap(pipeline.getNodesJson()));
        var designEdges = EtlGraphConverter.toEdgeDefs(parseListOfMap(pipeline.getEdgesJson()));
        boolean definition = cn.iocoder.yudao.framework.etl.core.compile.EtlSubflowExpander.isDefinition(designNodes);
        if (definition && (!publication || pipeline.getQualityTaskId() != null
                || !Integer.valueOf(EtlPipelineDO.TASK_TYPE_ONCE).equals(pipeline.getTaskType()))) {
            throw exception(ETL_PIPELINE_GRAPH_INVALID,
                    "子流程定义只能发布供父流程调用，不能直接运行、调度或绑定流程级检测任务");
        }
        var expanded = subflowService.expand(designNodes, designEdges, actor, effectiveParameters);
        boolean reusePublishedSnapshot = !publication && Integer.valueOf(EtlPipelineDO.STATUS_RELEASED)
                .equals(pipeline.getStatus())
                && java.util.Objects.equals(pipeline.getRevision(), pipeline.getPublishedRevision())
                && StrUtil.isNotBlank(pipeline.getPublishedConfigJson());
        if (reusePublishedSnapshot) {
            assertExpandedSubflowIdentityStable(pipeline, parameterItems, effectiveParameters, expanded);
        }
        requireScriptWorker(expanded.nodes());
        boolean reuseEnvironment = reusePublishedSnapshot;
        var resolved = reuseEnvironment ? environmentService.reuse(pipeline.getEnvironmentId(), expanded.nodes(), actor,
                cn.hutool.json.JSONUtil.parseObj(pipeline.getPublishedConfigJson()).get("environmentBindings"))
                : environmentService.freeze(pipeline.getEnvironmentId(), expanded.nodes(), actor);
        EtlDag dag = EtlDag.build(resolved.nodes(), expanded.edges());
        designService.requireValid(dag);
        for (EtlNodeDef node : dag.getNodes()) {
            if (!executorRegistry.supports(node.getToolCode())) {
                throw exception(ETL_PIPELINE_GRAPH_INVALID,
                        String.format("节点【%s】的组件【%s】暂不支持执行", node.getName(), node.getToolCode()));
            }
        }
        qualityGateService.validate(pipeline.getQualityTaskId(), actor);
        Map<String, Object> snapshot = buildSnapshot(pipeline);
        snapshot.put("parameterValues", effectiveParameters);
        snapshot.put("subflowDefinition", definition);
        snapshot.put("executionNodes", EtlSubflowService.nodes(resolved.nodes()));
        snapshot.put("environmentBindings", resolved.bindings());
        snapshot.put("executionEdges", EtlSubflowService.edges(expanded));
        snapshot.put("dependencies", expanded.dependencies());
        snapshot.put("sourceMap", expanded.sourceMap());
        nodeAccessService.authorize(dag.getNodes(), actor, snapshotParams(snapshot));
        Map<String, EtlPolicyPlanService.Plan> plans;
        if (reusePublishedSnapshot) {
            var published = JsonUtils.parseObject(pipeline.getPublishedConfigJson(), Map.class);
            if (published.containsKey("governancePlans")) {
                plans = policyPlanService.decode(published.get("governancePlans"));
            } else {
                plans = policyPlanService.freeze(dag.getNodes(), actor, snapshotParams(snapshot));
            }
        } else {
            plans = policyPlanService.freeze(dag.getNodes(), actor, snapshotParams(snapshot));
        }
        policyPlanService.authorize(plans, actor);
        snapshot.put("governancePlans", plans);
        Map<String, cn.hutool.json.JSONObject> targetPlans = new java.util.LinkedHashMap<>();
        if (reusePublishedSnapshot
                && cn.hutool.json.JSONUtil.parseObj(pipeline.getPublishedConfigJson()).containsKey("targetPlans")) {
            var stored = cn.hutool.json.JSONUtil.parseObj(pipeline.getPublishedConfigJson())
                    .getJSONObject("targetPlans");
            stored.forEach((key, value) -> targetPlans.put(key, cn.hutool.json.JSONUtil.parseObj(value)));
        } else {
            targetPlans.putAll(targetBatchService.validate(dag, actor, snapshotParams(snapshot)));
        }
        snapshot.put("targetPlans", targetPlans);
        return EtlRecordDO.builder().pipelineId(pipeline.getId()).pipelineRevision(pipeline.getRevision())
                .qualityTaskId(pipeline.getQualityTaskId()).qualityStatus("NOT_CONFIGURED")
                .executionUserId(actor).name(pipeline.getName()).executeType(executeType)
                .configJson(JsonUtils.toJsonString(snapshot)).orgId(pipeline.getOrgId()).build();
    }

    /**
     * 固定参数。
     *
     * @param snapshot 快照
     * @return 处理后的键值映射
     */
    private Map<String, String> snapshotParams(Map<String, Object> snapshot) {
        List<EtlPipelineSaveReqVO.ParamItem> definitions = parseParameterItems(
                snapshot.get("parameters") == null ? null : JsonUtils.toJsonString(snapshot.get("parameters")));
        Map<String, String> supplied = new java.util.LinkedHashMap<>();
        Object rawValues = snapshot.get("parameterValues");
        if (rawValues instanceof Map<?, ?> values) {
            for (Map.Entry<?, ?> entry : values.entrySet()) {
                if (!(entry.getKey() instanceof String key) || !(entry.getValue() instanceof String value)) {
                    throw exception(ETL_PIPELINE_GRAPH_INVALID, "运行参数快照格式无效");
                }
                supplied.put(key, value);
            }
        }
        return resolveParameters(definitions, supplied, EtlParameterValidator.DefaultsPolicy.APPLY_DEFAULTS);
    }

    /**
     * 提示执行。
     *
     * @param recordId 记录编号
     */
    private void hintExecution(Long recordId) {
        try {
            pipelineExecutor.submit(recordId);
        } catch (RuntimeException unavailable) {
            // 提示派发失败不撤销已提交回执，后续持久扫描会重新领取。
            log.warn("ETL 已持久受理，等待后续派发 recordId={}", recordId);
        }
    }

    /**
     * 停止执行
     *
     * @param recordId 记录编号
     */
    @Override
    public void stopRecord(Long recordId) {
        stopRecordCommand(recordId, java.util.UUID.randomUUID().toString());
    }

    /**
     * 停止记录命令。
     *
     * @param recordId 记录编号
     * @param requestId 请求编号
     * @return 运行回执
     */
    @Override
    public cn.iocoder.yudao.module.dataforge.service.execution.RunReceipt stopRecordCommand(Long recordId,
            String requestId) {
        EtlRecordDO record = validateRecordExists(recordId);
        validatePipelineExists(record.getPipelineId()); // 当前对象可见性；本领域为平台内授权可见。
        Long actor = SecurityFrameworkUtils.getLoginUserId();
        executionAccessService.requireUserPermission(actor, "dataforge:etl:run");
        return runCoordinator.stopCommand(recordId, requestId, actor);
    }

    /**
     * 凭状态版本与退出、输出证据人工核查运行结果。
     *
     * @param request 请求参数
     * @return 运行回执
     */
    @Override
    public cn.iocoder.yudao.module.dataforge.service.execution.RunReceipt resolveCommand(
            EtlResolveCommandReqVO request) {
        EtlRecordDO record = validateRecordExists(request.getRecordId());
        validatePipelineExists(record.getPipelineId());
        Long actor = SecurityFrameworkUtils.getLoginUserId();
        executionAccessService.requireUserPermission(actor, "dataforge:etl:resolve");
        return runCoordinator.resolve(request, actor);
    }

    /**
     * 兼容人工核查入口（同样要求状态版本与完整证据）。
     *
     * @param reqVO 请求参数
     */
    @Override
    public void resolveUnknown(EtlResolveUnknownReqVO reqVO) {
        // 保留旧路由与 Boolean 响应，但不能将旧的单一停止确认当成完整核查证据。
        resolveCommand(reqVO);
    }

    // ==================== 日志 / 记录 ====================

    /**
     * 查询执行记录的流程级日志与状态（设计器轮询用）
     *
     * @param recordId 记录编号
     * @return ETL 运行记录响应数据
     */
    @Override
    public EtlRecordRespVO getRecordLog(Long recordId) {
        EtlRecordDO record = validateRecordExists(recordId);
        EtlRecordRespVO vo = BeanUtils.toBean(record, EtlRecordRespVO.class);
        vo.setEnd(record.getRunStatus() != null
                && record.getRunStatus() != EtlRecordDO.RUN_STATUS_WAITING
                && record.getRunStatus() != EtlRecordDO.RUN_STATUS_RUNNING
                && record.getRunStatus() != EtlRecordDO.RUN_STATUS_UNKNOWN
                && !Boolean.TRUE.equals(record.getOccupiesSlot())
                && !Boolean.TRUE.equals(record.getReviewRequired())
                && !qualityUnresolved(record));
        vo.setRuntimeMetrics(readRuntimeMetrics(recordId));
        return vo;
    }

    /** 观测快照只用于展示；内容无法解析时按未记录处理，不影响日志与状态查询。 */
    private cn.iocoder.yudao.framework.etl.core.runtime.EtlRuntimeSample readRuntimeMetrics(Long recordId) {
        String json = recordMapper.selectRuntimeMetricsJson(recordId);
        if (StrUtil.isBlank(json)) {
            return null;
        }
        try {
            return JsonUtils.parseObject(json, cn.iocoder.yudao.framework.etl.core.runtime.EtlRuntimeSample.class);
        } catch (RuntimeException ex) {
            log.warn("[readRuntimeMetrics][ETL 运行观测快照无法解析 recordId={}]", recordId);
            return null;
        }
    }

    /**
     * 查询执行记录的节点级状态与日志（设计器着色用）
     *
     * @param recordId 记录编号
     * @return 执行记录的节点级状态与日志（设计器着色用）结果集合
     */
    @Override
    public List<EtlNodeRecordRespVO> getNodeRecords(Long recordId) {
        var record = validateRecordExists(recordId);
        var sourceMap = StrUtil.isBlank(record.getConfigJson()) ? null : cn.hutool.json.JSONUtil.parseObj(
                record.getConfigJson()).getJSONObject("sourceMap");
        return convertList(nodeRecordMapper.selectListByRecordId(recordId),
                nodeRecord -> {
                    var response = BeanUtils.toBean(nodeRecord, EtlNodeRecordRespVO.class);
                    if (StrUtil.isNotBlank(nodeRecord.getPortCountsJson())) {
                        response.setPortCounts(cn.hutool.json.JSONUtil.toBean(nodeRecord.getPortCountsJson(),
                                new cn.hutool.core.lang.TypeReference<java.util.Map<String, Long>>() {
                                }, false));
                    }
                    response.setDesignNodeNo(nodeRecord.getNodeNo());
                    var origin = sourceMap == null ? null : sourceMap.getJSONObject(nodeRecord.getNodeNo());
                    if (origin != null) {
                        String path = origin.getStr("callPath", "");
                        response.setCallPath(path);
                        response.setOriginalNodeNo(origin.getStr("originalNodeNo"));
                        if (!path.isBlank()) {
                            response.setDesignNodeNo(origin.getStr("designNodeNo", path.split("/", 2)[0]));
                        }
                    }
                    return response;
                });
    }

    /**
     * 字段契约推导（未保存的画布也可提交）
     *
     * @param reqVO 请求参数
     * @return ETL结构结果
     */
    @Override
    public EtlSchemaResult resolveSchema(EtlSchemaReqVO reqVO) {
        List<EtlNodeDef> nodes = EtlGraphConverter.toNodeDefs(reqVO.getNodes());
        List<EtlEdgeDef> edges = EtlGraphConverter.toEdgeDefs(reqVO.getEdges());
        try {
            Map<String, String> effectiveParameters = EtlRunParameterResolver.resolve(
                    reqVO.getParameterDefinitions(), reqVO.getParameters());
            if (nodes.stream().anyMatch(n -> "sub-pipeline".equals(n.getToolCode()))) {
                var expanded = subflowService.expand(nodes, edges, SecurityFrameworkUtils.getLoginUserId(),
                        effectiveParameters);
                nodes = expanded.nodes();
                edges = expanded.edges();
            }
            var variables = new cn.iocoder.yudao.framework.etl.core.variable.EtlVariableContext(effectiveParameters);
            nodes = nodes.stream().map(node -> {
                var bound = BeanUtils.toBean(node, EtlNodeDef.class);
                bound.setNodeJson(variables.resolveJson(StrUtil.blankToDefault(node.getNodeJson(), "{}")));
                return bound;
            }).toList();
            nodes = environmentService.freeze(reqVO.getEnvironmentId(), nodes, SecurityFrameworkUtils.getLoginUserId())
                    .nodes();
            var schemaGraph = nodeAccessService.prepareSchema(nodes, SecurityFrameworkUtils.getLoginUserId());
            EtlDag dag = EtlDag.buildLenient(schemaGraph.nodes(), edges);
            return schemaService.resolve(dag, databaseId -> {
                executionAccessService.requireDatabase(databaseId);
                return schemaGraph.connections().resolve(databaseId);
            }, apiPortAdapter);
        } catch (IllegalStateException | IllegalArgumentException ex) {
            EtlSchemaResult result = new EtlSchemaResult();
            result.getErrors().add(ex.getMessage());
            return result;
        }
    }

    /**
     * Java 脚本编译检查
     *
     * @param code 编码
     * @return Java 脚本编译检查结果集合
     */
    @Override
    public List<String> compileJavaScript(String code) {
        return scriptRunner.checkJava(code);
    }

    /**
     * 校验脚本工作线程。
     *
     * @param nodes 节点
     */
    private void requireScriptWorker(List<EtlNodeDef> nodes) {
        if (nodes.stream().noneMatch(node -> "java-script".equals(node.getToolCode())
                || "js-script".equals(node.getToolCode()))) {
            return;
        }
        scriptAdmissionService.requireAvailable(nodes);
    }

    /**
     * 执行记录分页
     *
     * @param pageReqVO 分页查询条件
     * @return 执行记录分页结果
     */
    @Override
    public PageResult<EtlRecordRespVO> getRecordPage(EtlRecordPageReqVO pageReqVO) {
        PageResult<EtlRecordDO> pageResult = recordMapper.selectPage(pageReqVO);
        // 列表不返回大字段日志
        List<EtlRecordRespVO> list = convertList(pageResult.getList(), record -> {
            EtlRecordRespVO vo = BeanUtils.toBean(record, EtlRecordRespVO.class);
            vo.setLog(null);
            return vo;
        });
        return new PageResult<>(list, pageResult.getTotal());
    }

    /**
     * 删除执行记录（含节点记录）
     *
     * @param id 记录编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteRecord(Long id) {
        validateRecordExists(id);
        runCoordinator.deleteRecord(id, () -> nodeRecordMapper.deleteByRecordId(id));
    }

    // ==================== 内部方法 ====================

    /**
     * 判断质量未解决条件是否成立。
     *
     * @param record 运行记录
     * @return 处理是否成功
     */
    private boolean qualityUnresolved(EtlRecordDO record) {
        return java.util.Objects.equals(record.getRunStatus(), EtlRecordDO.RUN_STATUS_SUCCESS)
                && ("PENDING".equals(record.getQualityStatus()) || "UNKNOWN".equals(record.getQualityStatus()));
    }

    /**
     * 校验流程存在性。
     *
     * @param id 记录编号
     * @return ETL 流程
     */
    private EtlPipelineDO validatePipelineExists(Long id) {
        EtlPipelineDO pipeline = pipelineMapper.selectById(id);
        if (pipeline == null) {
            throw exception(ETL_PIPELINE_NOT_EXISTS);
        }
        return pipeline;
    }

    /**
     * 校验未已发布。
     *
     * @param pipeline 流程
     */
    private void validateNotReleased(EtlPipelineDO pipeline) {
        if (pipeline.getStatus() != null && pipeline.getStatus() == EtlPipelineDO.STATUS_RELEASED) {
            throw exception(ETL_PIPELINE_RELEASED_CANNOT_MODIFY);
        }
    }

    /**
     * 校验记录存在性。
     *
     * @param id 记录编号
     * @return ETL 运行记录
     */
    private EtlRecordDO validateRecordExists(Long id) {
        EtlRecordDO record = recordMapper.selectById(id);
        if (record == null) {
            throw exception(ETL_RECORD_NOT_EXISTS);
        }
        return record;
    }

    /**
     * 校验Cron。
     *
     * @param taskType 任务类型
     * @param cron Cron
     */
    private void validateCron(Integer taskType, String cron) {
        if (taskType != null && taskType == EtlPipelineDO.TASK_TYPE_PERIODIC
                && (StrUtil.isBlank(cron) || !CronExpression.isValidExpression(cron))) {
            throw exception(ETL_PIPELINE_CRON_INVALID);
        }
    }

}
