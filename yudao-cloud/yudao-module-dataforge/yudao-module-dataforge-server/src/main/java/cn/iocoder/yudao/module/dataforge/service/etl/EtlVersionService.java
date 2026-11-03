package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.etl.*;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;

/** 复用发布事务与草稿 CAS；恢复仅产生新草稿，不切换已发布运行或撤销目标数据。 */
@Service
public class EtlVersionService {

    @Resource
    private EtlVersionMapper versions;

    @Resource
    private EtlPipelineMapper pipelines;

    /**
     * 捕获已发布。
     *
     * @param pipeline 流程
     * @param generation 代次
     * @param actor 操作人编号
     * @param snapshot 快照
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void capturePublished(EtlPipelineDO pipeline, long generation, Long actor, String snapshot) {
        var version = entry(pipeline.getId(), pipeline.getRevision(), actor, "PUBLISH", snapshot);
        version.setReleaseGeneration(generation);
        version.setReason("发布流程");
        insert(version);
    }

    /**
     * 分页读取ETL版本。
     *
     * @param req 请求参数
     * @return 分页读取ETL版本结果
     */
    public PageResult<EtlVersionRespVO> page(EtlVersionPageReqVO req) {
        requirePipeline(req.getPipelineId());
        var page = versions.selectPage(req, new LambdaQueryWrapperX<EtlVersionDO>()
                .eq(EtlVersionDO::getPipelineId, req.getPipelineId())
                .select(EtlVersionDO::getId, EtlVersionDO::getPipelineId, EtlVersionDO::getRevision,
                        EtlVersionDO::getReleaseGeneration, EtlVersionDO::getEventType,
                        EtlVersionDO::getSourceVersionId,
                        EtlVersionDO::getActorId, EtlVersionDO::getReason, EtlVersionDO::getContentHash,
                        EtlVersionDO::getCreateTime)
                .orderByDesc(EtlVersionDO::getId));
        return new PageResult<>(page.getList().stream().map(v -> BeanUtils.toBean(v, EtlVersionRespVO.class)).toList(),
                page.getTotal());
    }

    /**
     * 读取ETL版本响应数据。
     *
     * @param id 记录编号
     * @return ETL版本响应数据
     */
    public EtlVersionRespVO get(Long id) {
        var version = requireVersion(id);
        requirePipeline(version.getPipelineId());
        return BeanUtils.toBean(version, EtlVersionRespVO.class);
    }

    /** 只列可引用的不可变发布定义；RESTORE 与普通主流程发布不作为候选。 */
    public PageResult<EtlVersionRespVO> pagePublishedSubflows(EtlSubflowVersionPageReqVO req) {
        requirePipeline(req.getPipelineId());
        var page = versions.selectPage(req, new LambdaQueryWrapperX<EtlVersionDO>()
                .eq(EtlVersionDO::getPipelineId, req.getPipelineId())
                .eq(EtlVersionDO::getEventType, "PUBLISH")
                .apply("config_json::jsonb->>'subflowDefinition'='true'")
                .select(EtlVersionDO::getId, EtlVersionDO::getPipelineId, EtlVersionDO::getRevision,
                        EtlVersionDO::getReleaseGeneration, EtlVersionDO::getEventType,
                        EtlVersionDO::getSourceVersionId,
                        EtlVersionDO::getActorId, EtlVersionDO::getReason, EtlVersionDO::getContentHash,
                        EtlVersionDO::getCreateTime)
                .orderByDesc(EtlVersionDO::getId));
        return new PageResult<>(
                page.getList().stream().map(value -> BeanUtils.toBean(value, EtlVersionRespVO.class)).toList(),
                page.getTotal());
    }

    /**
     * 恢复ETL版本。
     *
     * @param req 请求参数
     * @return 恢复数值
     */
    @Transactional(rollbackFor = Exception.class)
    public Integer restore(EtlVersionRestoreReqVO req) {
        Long actor = SecurityFrameworkUtils.getLoginUserId();
        if (actor == null) {
            throw exception(OPERATION_PERMISSION_DENIED);
        }
        var source = requireVersion(req.getVersionId());
        if (!"PUBLISH".equals(source.getEventType())) {
            throw exception(ETL_VERSION_RESTORE_INVALID);
        }
        var current = pipelines.selectOne(new LambdaQueryWrapperX<EtlPipelineDO>()
                .eq(EtlPipelineDO::getId, source.getPipelineId()).last("FOR UPDATE"));
        if (current == null) {
            throw exception(ETL_PIPELINE_NOT_EXISTS);
        }
        if (!Objects.equals(current.getRevision(), req.getExpectedRevision())) {
            throw exception(ETL_RUN_DEFINITION_CHANGED);
        }
        var snapshot = JSONUtil.parseObj(source.getConfigJson());
        if (snapshot.getJSONArray("nodes") == null || snapshot.getJSONArray("edges") == null
                || snapshot.getJSONArray("parameters") == null) {
            throw exception(ETL_VERSION_RESTORE_INVALID);
        }
        int revision = Math.addExact(current.getRevision(), 1);
        var update = Wrappers.lambdaUpdate(EtlPipelineDO.class).eq(EtlPipelineDO::getId, current.getId())
                .eq(EtlPipelineDO::getRevision, req.getExpectedRevision())
                .set(EtlPipelineDO::getRevision, revision)
                .set(EtlPipelineDO::getNodesJson, snapshot.getJSONArray("nodes").toString(), jsonHandler())
                .set(EtlPipelineDO::getEdgesJson, snapshot.getJSONArray("edges").toString(), jsonHandler())
                .set(EtlPipelineDO::getParamsJson, snapshot.getJSONArray("parameters").toString(), jsonHandler())
                .set(EtlPipelineDO::getQualityTaskId, snapshot.getLong("qualityTaskId"))
                .set(EtlPipelineDO::getEnvironmentId, snapshot.getLong("environmentId"))
                .set(EtlPipelineDO::getTaskType, snapshot.getInt("taskType", EtlPipelineDO.TASK_TYPE_ONCE))
                .set(EtlPipelineDO::getCron, snapshot.getStr("cron"));
        if (pipelines.update(null, update) != 1) {
            throw exception(ETL_RUN_DEFINITION_CHANGED);
        }
        var restored = entry(current.getId(), revision, actor, "RESTORE", source.getConfigJson());
        restored.setSourceVersionId(source.getId());
        restored.setReason(req.getReason().trim());
        insert(restored);
        return revision;
    }

    /** 与当前草稿比较业务定义，忽略画布位置及运行高亮，不返回原始配置值。 */
    public List<Change> diff(Long id) {
        var version = requireVersion(id);
        var current = requirePipeline(version.getPipelineId());
        var old = JSONUtil.parseObj(version.getConfigJson());
        List<Change> result = new ArrayList<>();
        var previous = nodeDefinitions(old.getJSONArray("nodes"));
        var draft = nodeDefinitions(JSONUtil.parseArray(current.getNodesJson()));
        Set<String> ids = new TreeSet<>(previous.keySet());
        ids.addAll(draft.keySet());
        for (String node : ids) {
            if (!previous.containsKey(node)) {
                result.add(new Change("NODE_ADDED", node));
            } else if (!draft.containsKey(node)) {
                result.add(new Change("NODE_REMOVED", node));
            } else if (!previous.get(node).equals(draft.get(node))) {
                result.add(new Change("NODE_CHANGED", node));
            }
        }
        if (!edgeDefinitions(old.getJSONArray("edges"), old.getJSONArray("nodes"))
                .equals(edgeDefinitions(JSONUtil.parseArray(current.getEdgesJson()),
                        JSONUtil.parseArray(current.getNodesJson())))) {
            result.add(new Change("EDGES_CHANGED", "edges"));
        }
        if (!Objects.equals(old.getJSONArray("parameters"), JSONUtil.parseArray(current.getParamsJson()))) {
            result.add(new Change("PARAMETERS_CHANGED", "parameters"));
        }
        if (!Objects.equals(old.getLong("qualityTaskId"), current.getQualityTaskId())) {
            result.add(new Change("QUALITY_TASK_CHANGED", "qualityTaskId"));
        }
        if (!Objects.equals(old.getLong("environmentId"), current.getEnvironmentId())) {
            result.add(new Change("ENVIRONMENT_CHANGED", "environmentId"));
        }
        if (!Objects.equals(old.getInt("taskType"), current.getTaskType()) || !Objects.equals(old.getStr("cron"),
                current.getCron())) {
            result.add(new Change("SCHEDULE_CHANGED", "schedule"));
        }
        return result;
    }

    /** 比较两个不可变快照，仅返回安全的标识、类别及数量。 */
    public EtlVersionCompareRespVO compare(Long sourceVersionId, Long targetVersionId) {
        var source = requireVersion(sourceVersionId);
        var target = requireVersion(targetVersionId);
        if (!Objects.equals(source.getPipelineId(), target.getPipelineId())) {
            throw exception(ETL_VERSION_NOT_EXISTS);
        }
        requirePipeline(source.getPipelineId());
        if (!immutableSnapshot(source) || !immutableSnapshot(target)) {
            throw exception(ETL_VERSION_RESTORE_INVALID);
        }

        var sourceConfig = parseSnapshot(source.getConfigJson());
        var targetConfig = parseSnapshot(target.getConfigJson());
        var sourceNodes = boundedNodes(sourceConfig);
        var targetNodes = boundedNodes(targetConfig);
        var sourceEdges = boundedEdges(sourceConfig);
        var targetEdges = boundedEdges(targetConfig);
        var changes = new ArrayList<EtlVersionCompareRespVO.Change>();
        var counts = new LinkedHashMap<String, Integer>();

        Set<String> nodeIds = new TreeSet<>(sourceNodes.keySet());
        nodeIds.addAll(targetNodes.keySet());
        for (String nodeId : nodeIds) {
            Map<String, Object> before = sourceNodes.get(nodeId);
            Map<String, Object> after = targetNodes.get(nodeId);
            if (before == null) {
                addCompareChange(changes, counts, "NODE_ADDED", nodeId, 1);
                continue;
            }
            if (after == null) {
                addCompareChange(changes, counts, "NODE_REMOVED", nodeId, 1);
                continue;
            }
            if (!Objects.equals(before.get("toolCode"), after.get("toolCode"))
                    || !Objects.equals(before.get("toolType"), after.get("toolType"))) {
                addCompareChange(changes, counts, "NODE_TOOL_CHANGED", nodeId, 1);
            }
            if (!Objects.equals(before.get("nodeJson"), after.get("nodeJson"))) {
                String type = isSubflowVersionChange(before, after) ? "SUBFLOW_VERSION_CHANGED" : "NODE_CONFIG_CHANGED";
                addCompareChange(changes, counts, type, nodeId, 1);
            }
            if (!Objects.equals(before.get("name"), after.get("name"))
                    || !Objects.equals(before.get("failGoOn"), after.get("failGoOn"))
                    || !Objects.equals(before.get("overTimes"), after.get("overTimes"))) {
                addCompareChange(changes, counts, "NODE_METADATA_CHANGED", nodeId, 1);
            }
        }

        int addedEdges = multisetDifferenceCount(targetEdges, sourceEdges);
        int removedEdges = multisetDifferenceCount(sourceEdges, targetEdges);
        if (addedEdges > 0) {
            addCompareChange(changes, counts, "EDGE_ADDED", "edges", addedEdges);
        }
        if (removedEdges > 0) {
            addCompareChange(changes, counts, "EDGE_REMOVED", "edges", removedEdges);
        }

        Object sourceParameters = boundedParameters(sourceConfig);
        Object targetParameters = boundedParameters(targetConfig);
        if (!Objects.equals(sourceParameters, targetParameters)) {
            addCompareChange(changes, counts, "PARAMETERS_CHANGED", "parameters", 1);
        }
        if (!Objects.equals(sourceConfig.getLong("qualityTaskId"), targetConfig.getLong("qualityTaskId"))) {
            addCompareChange(changes, counts, "QUALITY_TASK_CHANGED", "qualityTaskId", 1);
        }
        if (!Objects.equals(sourceConfig.getLong("environmentId"), targetConfig.getLong("environmentId"))) {
            addCompareChange(changes, counts, "ENVIRONMENT_CHANGED", "environmentId", 1);
        }
        if (!Objects.equals(sourceConfig.getInt("taskType"), targetConfig.getInt("taskType"))
                || !Objects.equals(sourceConfig.getStr("cron"), targetConfig.getStr("cron"))) {
            addCompareChange(changes, counts, "SCHEDULE_CHANGED", "schedule", 1);
        }
        var sourceDependencies = dependencyDefinitions(sourceConfig);
        var targetDependencies = dependencyDefinitions(targetConfig);
        int dependencyChanges = multisetDifferenceCount(targetDependencies, sourceDependencies)
                + multisetDifferenceCount(sourceDependencies, targetDependencies);
        if (dependencyChanges > 0) {
            addCompareChange(changes, counts, "SUBFLOW_VERSION_CHANGED", "subflows", dependencyChanges);
        }

        var response = new EtlVersionCompareRespVO();
        response.setSourceVersionId(source.getId());
        response.setSourceRevision(source.getRevision());
        response.setTargetVersionId(target.getId());
        response.setTargetRevision(target.getRevision());
        response.setChanges(List.copyOf(changes));
        response.setChangeCounts(Collections.unmodifiableMap(counts));
        return response;
    }

    /**
     * 判断不可变快照条件是否成立。
     *
     * @param version 版本
     * @return 处理是否成功
     */
    private boolean immutableSnapshot(EtlVersionDO version) {
        return "PUBLISH".equals(version.getEventType()) || "RESTORE".equals(version.getEventType());
    }

    /**
     * 解析快照。
     *
     * @param json JSON 内容
     * @return JSON对象
     */
    private cn.hutool.json.JSONObject parseSnapshot(String json) {
        try {
            var snapshot = JSONUtil.parseObj(json);
            if (snapshot.getJSONArray("nodes") == null || snapshot.getJSONArray("edges") == null) {
                throw exception(ETL_VERSION_RESTORE_INVALID);
            }
            // 较旧的不可变快照早于强类型流水线参数，其有效参数定义为空。
            if (!snapshot.containsKey("parameters")) {
                snapshot.set("parameters", new cn.hutool.json.JSONArray());
            } else if (snapshot.getJSONArray("parameters") == null) {
                throw exception(ETL_VERSION_RESTORE_INVALID);
            }
            return snapshot;
        } catch (RuntimeException ex) {
            if (ex instanceof cn.iocoder.yudao.framework.common.exception.ServiceException) {
                throw ex;
            }
            throw exception(ETL_VERSION_RESTORE_INVALID);
        }
    }

    /**
     * 获取ETL版本的有界节点。
     *
     * @param snapshot 快照
     * @return 处理后的键值映射
     */
    private Map<String, Map<String, Object>> boundedNodes(cn.hutool.json.JSONObject snapshot) {
        var array = snapshot.getJSONArray("nodes");
        if (array.size() > 32) {
            throw exception(ETL_VERSION_RESTORE_INVALID);
        }
        Map<String, Map<String, Object>> nodes;
        try {
            nodes = nodeDefinitions(array);
        } catch (RuntimeException ex) {
            throw exception(ETL_VERSION_RESTORE_INVALID);
        }
        if (nodes.size() != array.size() || nodes.keySet().stream()
                .anyMatch(id -> id == null || id.isBlank() || id.length() > 128)) {
            throw exception(ETL_VERSION_RESTORE_INVALID);
        }
        return nodes;
    }

    /**
     * 获取ETL版本的有界边。
     *
     * @param snapshot 快照
     * @return 处理后的键值映射
     */
    private Map<String, Object> boundedEdges(cn.hutool.json.JSONObject snapshot) {
        var array = snapshot.getJSONArray("edges");
        if (array.size() > 128) {
            throw exception(ETL_VERSION_RESTORE_INVALID);
        }
        try {
            return edgeDefinitions(array, snapshot.getJSONArray("nodes"));
        } catch (RuntimeException ex) {
            throw exception(ETL_VERSION_RESTORE_INVALID);
        }
    }

    /**
     * 获取ETL版本的有界参数。
     *
     * @param snapshot 快照
     * @return ETL版本
     */
    private Object boundedParameters(cn.hutool.json.JSONObject snapshot) {
        var parameters = snapshot.getJSONArray("parameters");
        if (parameters.size() > 100) {
            throw exception(ETL_VERSION_RESTORE_INVALID);
        }
        return canonical(parameters);
    }

    /**
     * 获取ETL版本的依赖定义。
     *
     * @param snapshot 快照
     * @return ETL版本的依赖定义结果集合
     */
    private List<String> dependencyDefinitions(cn.hutool.json.JSONObject snapshot) {
        var dependencies = snapshot.getJSONArray("dependencies");
        if (dependencies == null) {
            return List.of();
        }
        if (dependencies.size() > 128) {
            throw exception(ETL_VERSION_RESTORE_INVALID);
        }
        List<String> result = new ArrayList<>();
        for (Object item : dependencies) {
            var dependency = JSONUtil.parseObj(item);
            String key = dependency.getStr("key");
            String hash = dependency.getStr("contentHash");
            if (key == null || key.isBlank() || hash == null || hash.isBlank()) {
                throw exception(ETL_VERSION_RESTORE_INVALID);
            }
            result.add(JSONUtil.toJsonStr(List.of(key, hash)));
        }
        Collections.sort(result);
        return result;
    }

    /**
     * 判断是否满足子流程版本变更条件。
     *
     * @param before 变更前状态
     * @param after 变更后状态
     * @return 判断结果
     */
    private boolean isSubflowVersionChange(Map<String, Object> before, Map<String, Object> after) {
        if (!"sub-pipeline".equals(before.get("toolCode")) || !"sub-pipeline".equals(after.get("toolCode"))) {
            return false;
        }
        var oldConfig = JSONUtil.parseObj(JSONUtil.toJsonStr(before.get("nodeJson")));
        var newConfig = JSONUtil.parseObj(JSONUtil.toJsonStr(after.get("nodeJson")));
        for (String key : List.of("pipelineId", "versionId", "definitionKey")) {
            if (!Objects.equals(String.valueOf(oldConfig.get(key)), String.valueOf(newConfig.get(key)))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 添加比较变更。
     *
     * @param changes 变更
     * @param counts 数量
     * @param type 类型标识
     * @param objectId 对象编号
     * @param count 数量
     */
    private void addCompareChange(List<EtlVersionCompareRespVO.Change> changes, Map<String, Integer> counts,
            String type, String objectId, int count) {
        changes.add(new EtlVersionCompareRespVO.Change(type, objectId, count));
        counts.merge(type, count, Integer::sum);
    }

    /**
     * 获取ETL版本的多重集合差异数量。
     *
     * @param left 左侧
     * @param right 右侧
     * @return 多重集合差异数量数值
     */
    private int multisetDifferenceCount(Map<String, ?> left, Map<String, ?> right) {
        int difference = 0;
        for (var entry : left.entrySet()) {
            int leftCount = ((Number) entry.getValue()).intValue();
            int rightCount = right.containsKey(entry.getKey()) ? ((Number) right.get(entry.getKey())).intValue() : 0;
            int count = leftCount - rightCount;
            if (count > 0) {
                difference += count;
            }
        }
        return difference;
    }

    /**
     * 获取ETL版本的多重集合差异数量。
     *
     * @param left 左侧
     * @param right 右侧
     * @return 多重集合差异数量数值
     */
    private int multisetDifferenceCount(List<String> left, List<String> right) {
        Map<String, Integer> leftCounts = new HashMap<>();
        Map<String, Integer> rightCounts = new HashMap<>();
        left.forEach(value -> leftCounts.merge(value, 1, Integer::sum));
        right.forEach(value -> rightCounts.merge(value, 1, Integer::sum));
        return multisetDifferenceCount(leftCounts, rightCounts);
    }

    public record Change(String type, String objectId) {

    }

    /**
     * 获取ETL版本的节点定义。
     *
     * @param array 数组
     * @return 处理后的键值映射
     */
    private Map<String, Map<String, Object>> nodeDefinitions(cn.hutool.json.JSONArray array) {
        Map<String, Map<String, Object>> result = new TreeMap<>();
        if (array != null) {
            for (Object value : array) {
                var node = JSONUtil.parseObj(value);
                var props = node.getJSONObject("properties");
                Map<String, Object> business = new TreeMap<>();
                if (props != null) {
                    for (String key : List.of("name", "toolCode", "toolType", "nodeJson", "failGoOn", "overTimes")) {
                        business.put(key, canonical(props.get(key)));
                    }
                }
                // 旧节点 nodeJson 可以是字符串，新节点为对象；按内容比较。
                if (business.get("nodeJson") instanceof String text) {
                    business.put("nodeJson", canonical(JSONUtil.parseObj(text)));
                }
                if ("sub-pipeline".equals(business.get("toolCode")) && business.get(
                        "nodeJson") instanceof Map<?, ?> config) {
                    Map<String, Object> normalized = new TreeMap<>();
                    config.forEach((key, child) -> normalized.put(String.valueOf(key), child));
                    for (String key : List.of("pipelineId", "versionId")) {
                        if (normalized.containsKey(key)) {
                            normalized.put(key, subflowId(normalized.get(key)));
                        }
                    }
                    business.put("nodeJson", normalized);
                }
                result.put(node.getStr("id"), business);
            }
        }
        return result;
    }

    /** 仅接受编译器 getStr 与正十进制语法所允许的精确数值标识。 */
    private Object subflowId(Object value) {
        boolean exact = value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof java.math.BigInteger || value instanceof java.math.BigDecimal;
        if (exact) {
            String text = value.toString();
            if (text.matches("[1-9][0-9]*")) {
                return text;
            }
        }
        return value;
    }

    /**
     * 将输入转为稳定的规范表示。
     *
     * @param value 待处理的值
     * @return ETL版本
     */
    private Object canonical(Object value) {
        if (value instanceof cn.hutool.json.JSONObject object) {
            Map<String, Object> result = new TreeMap<>();
            object.forEach((key, child) -> result.put(key, canonical(child)));
            return result;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new TreeMap<>();
            map.forEach((key, child) -> result.put(String.valueOf(key), canonical(child)));
            return result;
        }
        if (value instanceof cn.hutool.json.JSONArray array) {
            return array.stream().map(this::canonical).toList();
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::canonical).toList();
        }
        return value;
    }

    /**
     * 获取ETL版本的边定义。
     *
     * @param array 数组
     * @param nodeArray 节点数组
     * @return 处理后的键值映射
     */
    private Map<String, Object> edgeDefinitions(cn.hutool.json.JSONArray array, cn.hutool.json.JSONArray nodeArray) {
        Map<String, Object> result = new TreeMap<>();
        if (array == null || array.isEmpty()) {
            return result;
        }
        var nodes = EtlGraphConverter.toNodeDefs(EtlSubflowService.maps(nodeArray));
        Map<String, cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef> byId = new HashMap<>();
        nodes.forEach(node -> byId.put(node.getNo(), node));
        for (var edge : EtlGraphConverter.toEdgeDefs(EtlSubflowService.maps(array))) {
            String source = edge.getSourceNo(), target = edge.getTargetNo();
            boolean control = edge.isControl();
            if (byId.containsKey(source) && byId.containsKey(target)) {
                // 复用公开的图语义，包括输出及控制脚本边、Java/JS 数据行脚本。
                var dag = cn.iocoder.yudao.framework.etl.core.model.EtlDag.buildLenient(nodes, List.of(edge));
                control = dag.getControlUpstreams(target).contains(source);
            }
            String input = edge.getInputPort();
            var targetNode = byId.get(target);
            boolean namedBoundary = targetNode != null && ("sub-pipeline".equals(targetNode.getToolCode())
                    || targetNode.getToolCode() != null && targetNode.getToolCode().startsWith("boundary-"));
            if (input == null) {
                input = namedBoundary ? "" : "in";
            }
            if (targetNode != null && !namedBoundary && (input.isBlank() || "main".equals(input))) {
                input = "in";
            }
            String key = JSONUtil.toJsonStr(List.of(source, target, edge.resolveOutputPort(), input,
                    control ? "CONTROL" : "DATA"));
            result.compute(key, (ignored, count) -> count == null ? 1 : (Integer) count + 1);
        }
        return result;
    }

    /**
     * 获取ETL版本的条目。
     *
     * @param pipeline 流程
     * @param revision 修订号
     * @param actor 操作人编号
     * @param type 类型标识
     * @param snapshot 快照
     * @return ETL版本
     */
    private EtlVersionDO entry(Long pipeline, Integer revision, Long actor, String type, String snapshot) {
        var version = new EtlVersionDO();
        version.setPipelineId(pipeline);
        version.setRevision(revision);
        version.setActorId(actor);
        version.setEventType(type);
        version.setConfigJson(snapshot);
        version.setContentHash(DigestUtil.sha256Hex(snapshot));
        return version;
    }

    /**
     * 新增ETL版本记录。
     *
     * @param version 版本
     */
    private void insert(EtlVersionDO version) {
        if (versions.insert(version) != 1) {
            throw new IllegalStateException("流程版本未成功持久化");
        }
    }

    /**
     * 校验版本。
     *
     * @param id 记录编号
     * @return ETL版本
     */
    private EtlVersionDO requireVersion(Long id) {
        var version = versions.selectById(id);
        if (version == null) {
            throw exception(ETL_VERSION_NOT_EXISTS);
        }
        if (!Objects.equals(version.getContentHash(), DigestUtil.sha256Hex(version.getConfigJson()))) {
            throw exception(ETL_VERSION_RESTORE_INVALID);
        }
        return version;
    }

    /**
     * 校验流程。
     *
     * @param id 记录编号
     * @return ETL 流程
     */
    private EtlPipelineDO requirePipeline(Long id) {
        var pipeline = pipelines.selectById(id);
        if (pipeline == null) {
            throw exception(ETL_PIPELINE_NOT_EXISTS);
        }
        return pipeline;
    }

    /**
     * 获取ETL版本的JSON处理器。
     *
     * @return ETL版本的JSON处理器文本
     */
    private String jsonHandler() {
        return "typeHandler=cn.iocoder.yudao.module.dataforge.framework.mybatis.core.type.JsonbStringTypeHandler";
    }
}
