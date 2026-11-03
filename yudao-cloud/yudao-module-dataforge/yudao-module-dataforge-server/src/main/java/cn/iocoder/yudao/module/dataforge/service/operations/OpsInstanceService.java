package cn.iocoder.yudao.module.dataforge.service.operations;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.dataforge.controller.admin.operations.vo.OpsInstanceModels.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.dev.DevDirMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.integration.IntegrationCategoryMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.operations.OpsInstanceMapper;
import cn.iocoder.yudao.module.dataforge.service.dev.DevRecordService;
import cn.iocoder.yudao.module.dataforge.service.etl.EtlPipelineService;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DEV_RECORD_NOT_EXISTS;

/** 平台共享安全元数据；原生日志另受既有原生权限和冻结项目校验，源快照不触发执行状态机。 */
@Service
public class OpsInstanceService {

    static final int MAX_LOG_CHARS = 1_000_000;

    private static final java.util.regex.Pattern SENSITIVE_LOG_LINE = java.util.regex.Pattern.compile(
            "(?im)^.*(?:password|passwd|\\bpwd\\b|secret|token|credential|authorization|username|jdbc:|[\"']host[\"']|密码|口令|密钥|"
                    + "10(?:\\.\\d{1,3}){3}|192\\.168(?:\\.\\d{1,3}){2}|172\\.(?:1[6-9]|2\\d|3[01])(?:\\.\\d{1,3}){2}).*$");

    private static final Set<String> PUBLIC_CONFIG_NAMES = Set.of("节点名称", "组件类型", "数据源类型", "数据库",
            "数据表",
            "字段", "过滤条件", "批次大小", "读取方式", "写入方式", "转换参数");

    @Resource
    private OpsInstanceMapper mapper;

    @Resource
    private cn.iocoder.yudao.module.dataforge.dal.mysql.job.JobCategoryMapper jobCategories;

    @Resource
    private cn.iocoder.yudao.framework.dolphinscheduler.DolphinSchedulerClient dolphinScheduler;

    @Resource
    private cn.iocoder.yudao.module.dataforge.service.job.JobService jobService;

    @Resource
    private IntegrationCategoryMapper integrationCategories;

    @Resource
    private DevDirMapper developmentCategories;

    @Resource
    private DevRecordService developmentRecords;

    @Resource
    private EtlPipelineService etlPipelines;

    @Resource
    private AdminUserApi adminUserApi;

    /**
     * 分页读取平台共享实例及源系统迁入快照。
     *
     * @param req 请求参数
     * @return 分页读取平台共享实例及源系统迁入快照结果
     */
    public PageResult<InstanceResp> page(PageReq req) {
        if ("JOB".equals(req.getKind())) jobService.reconcileRecentForRead();
        var scope = categoryScope(req.getKind(), req.getCategoryId());
        if (!scope.known()) {
            return PageResult.empty();
        }
        var ids = scope.ids();
        var origin = scope.origin();
        long total = mapper.countInstances(req, ids, origin);
        if (total == 0) {
            return PageResult.empty();
        }
        long offset = ((long) req.getPageNo() - 1) * req.getPageSize();
        var rows = mapper.selectInstances(req, ids, origin, offset);
        enrichCreators(rows);
        if ("JOB".equals(req.getKind())) rows.forEach(row -> {enrichChildren(row);jobService.populateRuntimeIdentity(row);});
        return new PageResult<>(rows, total);
    }

    /**
     * 读取运维实例类目树。
     *
     * @param kind 种类
     * @return 运维实例类目树结果集合
     */
    public List<CategoryResp> categories(String kind) {
        var result = new ArrayList<CategoryResp>();
        if ("JOB".equals(kind) || "FULL_SYNC".equals(kind)) {
            for (var item : jobCategories.selectList()) {
                result.add(category("platform", item.getId(), item.getParentId(), item.getName(), item.getSort()));
            }
        } else if ("DEVELOPMENT".equals(kind)) {
            for (var item : developmentCategories.selectListAll()) {
                result.add(category("platform", item.getId(), item.getParentId(), item.getName(), item.getSort()));
            }
        } else if ("INTEGRATION".equals(kind)) {
            for (var item : integrationCategories.selectList()) {
                result.add(category("source", item.getId(), item.getParentId(), item.getName(), item.getSort()));
            }
        }
        result.sort(Comparator.comparing(CategoryResp::getSort, Comparator.nullsLast(Integer::compareTo))
                .thenComparing(CategoryResp::getId));
        return result;
    }

    /**
     * 读取实例统计，迁入状态不表示本平台存在运行进程。
     *
     * @param req 请求参数
     * @return 统计
     */
    public StatisticsResp statistics(StatisticsReq req) {
        if ("JOB".equals(req.getKind())) jobService.reconcileRecentForRead();
        var now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        var start = now.toLocalDate().atStartOfDay();
        var scope = categoryScope(req.getKind(), req.getCategoryId());
        var result = scope.known() ? mapper.selectStatistics(req.getKind(), start, start.plusDays(1),
                scope.ids(), scope.origin(), req.getTaskType()) : new StatisticsResp();
        if (!scope.known()) {
            result.setRunning(0L);
            result.setTodayFailed(0L);
            result.setTodayExecuted(0L);
            result.setTodaySuccessRate(0);
            result.setImportedSnapshot(false);
        }
        result.setStatisticTime(now);
        return result;
    }

    private record CategoryScope(List<Long> ids, String origin, boolean known) {

    }

    /**
     * 获取运维实例的分类范围。
     *
     * @param kind 种类
     * @param selected 已选择
     * @return 分类范围
     */
    private CategoryScope categoryScope(String kind, String selected) {
        if (selected == null) {
            return new CategoryScope(null, null, true);
        }
        var categories = categories(kind);
        if (categories.stream().noneMatch(item -> selected.equals(item.getId()))) {
            return new CategoryScope(List.of(), null, false);
        }
        var descendants = new LinkedHashSet<String>();
        descendants.add(selected);
        boolean changed;
        do {
            changed = false;
            for (var item : categories) {
                if (descendants.contains(item.getParentId())) {
                    changed |= descendants.add(item.getId());
                }
            }
        } while (changed);
        return new CategoryScope(
                descendants.stream().map(id -> Long.valueOf(id.substring(id.indexOf(':') + 1))).toList(),
                selected.substring(0, selected.indexOf(':')), true);
    }

    /**
     * 读取实例详情和不含执行参数的只读流程图。
     *
     * @param req 请求参数
     * @return 实例
     */
    public InstanceResp get(IdentityReq req) {
        var record = require(req);
        if ("JOB".equals(req.getKind())) {
            jobService.reconcileForRead(record.getTaskId());
            record = require(req);
        }
        enrichCreators(List.of(record));
        record.setFlowJson(safeFlow(record.getFlowJson()));
        if ("JOB".equals(req.getKind())) {enrichChildren(record);jobService.populateRuntimeIdentity(record);}
        if ("FULL_SYNC".equals(req.getKind()) && "WIZARD".equals(req.getOrigin())) enrichWizard(record);
        if ("INTEGRATION".equals(req.getKind()) && "MANAGED".equals(req.getOrigin())) {
            var identity = mapper.selectManagedIntegrationIdentity(record.getId(), record.getTaskId());
            if (identity == null) throw exception(DEV_RECORD_NOT_EXISTS);
            record.setDsProjectCode(identity.getDsProjectCode());
            record.setDsInstanceId(identity.getDsInstanceId());
        }
        return record;
    }

    /**
     * 读取实例的有界脱敏日志。
     *
     * @param req 请求参数
     * @return 日志
     */
    public LogResp log(IdentityReq req) {
        var record = require(req);
        if ("JOB".equals(req.getKind())) {
            jobService.reconcileForRead(record.getTaskId());
            record = require(req);
        }
        String raw;
        boolean source = "SOURCE".equals(req.getOrigin());
        if ("JOB".equals(req.getKind())) {
            raw = "PLATFORM".equals(req.getOrigin()) ? jobService.managedLog(req.getId()) : mapper.selectJobLog(req.getId(), req.getOrigin());
        } else if ("INTEGRATION".equals(req.getKind()) && "MANAGED".equals(req.getOrigin())) {
            raw = jobService.managedIntegrationLog(req.getId());
        } else if ("FULL_SYNC".equals(req.getKind()) && "SOURCE".equals(req.getOrigin())) {
            raw = mapper.selectFullSyncSnapshotLog(req.getId());
        } else if ("FULL_SYNC".equals(req.getKind()) && "WIZARD".equals(req.getOrigin())) {
            enrichWizard(record);
            raw = mapper.selectWizardLog(req.getId()) + "\n已核对运行状态：" + record.getStatus()
                    + "\n此视图提供向导提交与运行状态摘要；不包含引擎原始日志或连接参数。";
        } else if ("DATA_ACCESS".equals(req.getKind()) || "FULL_SYNC".equals(req.getKind())) {
            if (!"PLATFORM".equals(req.getOrigin())) {
                throw exception(DEV_RECORD_NOT_EXISTS);
            }
            // 数据同步与整库同步均为平台执行记录，日志统一走受控表并在返回前脱敏。
            raw = mapper.selectAccessLog(req.getId());
        } else if (source) {
            raw = "INTEGRATION".equals(req.getKind()) ? mapper.selectIntegrationLog(req.getId())
                    : mapper.selectDevelopmentLog(req.getId());
        } else if ("DEVELOPMENT".equals(req.getKind())) {
            // 复用既有二十页、一兆字节的有界聚合及原生授权；上限或不可读原因由原服务显式写入结果。
            raw = developmentRecords.downloadTaskLog(req.getId());
        } else {
            raw = etlPipelines.getRecordLog(req.getId()).getLog();
        }
        var response = new LogResp();
        response.setMessage(safeLog(raw));
        response.setAvailable(StrUtil.isNotEmpty(response.getMessage()));
        response.setImportedSnapshot(source);
        return response;
    }

    /**
     * 下载相同平台范围内的脱敏实例日志。
     *
     * @param req 请求参数
     * @return 下载相同平台范围内的脱敏实例日志文本
     */
    public String download(IdentityReq req) {
        require(req);
        if ("PLATFORM".equals(req.getOrigin()) && "DEVELOPMENT".equals(req.getKind())) {
            return safeLog(developmentRecords.downloadTaskLog(req.getId()));
        }
        return log(req).getMessage();
    }

    /**
     * 校验并获取实例。
     *
     * @param req 请求参数
     * @return 实例
     */
    private InstanceResp require(IdentityReq req) {
        var record = mapper.selectInstance(req);
        if (record == null) {
            throw exception(DEV_RECORD_NOT_EXISTS);
        }
        return record;
    }

    /** 只信任当前受管实例的实际状态；网络失败保持 UNKNOWN，不重试提交。 */
    private void enrichWizard(InstanceResp record) {
        record.setStatus("UNKNOWN");
        var target = mapper.selectWizardTarget(record.getId());
        if (target == null || target.getProjectCode() == null || target.getInstanceId() == null) return;
        try {
            var response = dolphinScheduler.workflowInstance().queryWorkflowInstanceById(target.getProjectCode(), target.getInstanceId());
            if (response == null || response.getInt("code", -1) != 0) return;
            var data = response.getJSONObject("data");
            if (data == null || !Objects.equals(data.getInt("id"), target.getInstanceId())) return;
            record.setStatus(switch (String.valueOf(data.getStr("state"))) {
                case "SUCCESS", "FORCED_SUCCESS" -> "SUCCESS";
                case "FAILURE" -> "FAILURE";
                case "STOP", "KILL" -> "STOPPED";
                case "RUNNING_EXECUTION" -> "RUNNING";
                case "SUBMITTED_SUCCESS", "DISPATCH", "DELAY_EXECUTION", "SERIAL_WAIT" -> "WAITING";
                default -> "UNKNOWN";
            });
            record.setStartTime(runtimeTime(data.get("startTime")));
            record.setEndTime(runtimeTime(data.get("endTime")));
            if (record.getStartTime() != null && record.getEndTime() != null) {
                record.setDuration(Math.max(0, java.time.Duration.between(record.getStartTime(), record.getEndTime()).toSeconds()));
            }
        } catch (RuntimeException ignored) {
            // 远端不可读不表示运行成功、失败或已停止。
        }
    }

    private static LocalDateTime runtimeTime(Object value) {
        if (value instanceof Number number) return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(number.longValue()), ZoneId.of("Asia/Shanghai"));
        if (value instanceof String text) {
            try { return LocalDateTime.parse(text.replace('T', ' ').substring(0, 19), java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")); }
            catch (RuntimeException ignored) { return null; }
        }
        return null;
    }

    /** 子实例按已校验父实例身份加载；不继承日志访问身份，不生成推断的运行状态。 */
    private void enrichChildren(InstanceResp record) {
        String raw = mapper.selectJobChildren(record.getId(), record.getOrigin());
        if ("SOURCE".equals(record.getOrigin())) record.setRunCount(1);
        if (StrUtil.isBlank(raw)) {
            record.setChildren(List.of());
            return;
        }
        try {
            var children = cn.iocoder.yudao.framework.common.util.json.JsonUtils.parseArray(raw, InstanceResp.class);
            record.setChildren(children.stream().limit(200).map(child -> {
                child.setFlowJson(safeFlow(child.getFlowJson()));
                child.setDescription(safeLog(child.getDescription()));
                child.setChildren(null);
                if ("SOURCE".equals(record.getOrigin())) child.setRunCount(1);
                return child;
            }).toList());
        } catch (RuntimeException ignored) {
            record.setChildren(List.of());
        }
    }

    /**
     * 补充创建人。
     *
     * @param rows 待处理的行数据
     */
    private void enrichCreators(List<InstanceResp> rows) {
        var ids = new IdentityHashMap<InstanceResp, Long>();
        for (var row : rows) {
            if (!Set.of("PLATFORM", "MANAGED").contains(row.getOrigin()) || row.getCreatorName() == null
                    || !row.getCreatorName().matches("[1-9][0-9]{0,18}")) {
                continue;
            }
            try {
                ids.put(row, Long.valueOf(row.getCreatorName()));
            } catch (NumberFormatException ignored) {
                row.setCreatorName("-");
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        var users = adminUserApi.getUserMap(new HashSet<>(ids.values()));
        for (var entry : ids.entrySet()) {
            var user = users.get(entry.getValue());
            entry.getKey().setCreatorName(user == null ? "-" : user.getNickname());
        }
    }

    /**
     * 获取运维实例的分类。
     *
     * @param origin 原始
     * @param id 记录编号
     * @param parent 父级
     * @param name 名称
     * @param sort 排序
     * @return 分类
     */
    private static CategoryResp category(String origin, Long id, Long parent, String name, Integer sort) {
        var response = new CategoryResp();
        response.setId(origin + ":" + id);
        response.setParentId(parent == null || parent == 0 ? "0" : origin + ":" + parent);
        response.setName(name);
        response.setSort(sort);
        return response;
    }

    /** 只返回图节点和连线，运行参数、连接配置和脚本文本不进入业务流程画布。 */
    static String safeFlow(String raw) {
        if (StrUtil.isBlank(raw)) {
            return "{\"nodes\":[],\"edges\":[]}";
        }
        try {
            var graph = JSONUtil.parseObj(raw);
            var nodes = new ArrayList<Map<String, Object>>();
            var edges = new ArrayList<Map<String, Object>>();
            var sourceNodes = graph.getJSONArray("nodes");
            if (sourceNodes != null) {
                for (int i = 0; i < Math.min(sourceNodes.size(), 1000); i++) {
                    var source = sourceNodes.getJSONObject(i);
                    var node = new LinkedHashMap<String, Object>();
                    for (String key : List.of("id", "label", "type", "kind", "taskId", "name")) {
                        Object value = source.get(key);
                        if (value instanceof String || value instanceof Number) {
                            node.put(key, String.valueOf(value));
                        }
                    }
                    for (String key : List.of("x", "y")) {
                        Object value = source.get(key);
                        if (value instanceof Number number && Double.isFinite(number.doubleValue())
                                && Math.abs(number.doubleValue()) <= 100000) {
                            node.put(key, number);
                        }
                    }
                    var publicConfig = source.getJSONArray("publicConfig");
                    if (publicConfig != null) {
                        var fields = new ArrayList<Map<String, String>>();
                        for (int fieldIndex = 0; fieldIndex < Math.min(publicConfig.size(), 50); fieldIndex++) {
                            var field = publicConfig.getJSONObject(fieldIndex);
                            if (field.get("name") instanceof String name && PUBLIC_CONFIG_NAMES.contains(name)
                                    && field.get("value") instanceof String value) {
                                fields.add(Map.of("name", StrUtil.sub(name, 0, 100), "value",
                                        safeLog(StrUtil.sub(value, 0, 4000))));
                            }
                        }
                        node.put("publicConfig", fields);
                    }
                    for (String key : List.of("publicFields", "publicMappings", "publicRules")) {
                        var values = source.getJSONArray(key);
                        if (values == null) {
                            continue;
                        }
                        var allowed = switch (key) {
                            case "publicFields" -> List.of("name", "description", "type", "primary", "length", "scale");
                            case "publicMappings" -> List.of("source", "target");
                            default -> List.of("name", "field", "rule", "description", "dimension");
                        };
                        var projected = new ArrayList<Map<String, Object>>();
                        for (int valueIndex = 0; valueIndex < Math.min(values.size(), 1000); valueIndex++) {
                            var value = values.getJSONObject(valueIndex);
                            var item = new LinkedHashMap<String, Object>();
                            for (String field : allowed) {
                                if (value.get(field) instanceof String text) {
                                    item.put(field, safeLog(StrUtil.sub(text, 0, 4000)));
                                } else if ("primary".equals(field) && value.get(field) instanceof Boolean primary) {
                                    item.put(field, primary);
                                }
                            }
                            projected.add(item);
                        }
                        node.put(key, projected);
                    }
                    nodes.add(node);
                }
            }
            var sourceEdges = graph.getJSONArray("edges");
            if (sourceEdges != null) {
                for (int i = 0; i < Math.min(sourceEdges.size(), 2000); i++) {
                    var source = sourceEdges.getJSONObject(i);
                    var edge = new LinkedHashMap<String, Object>();
                    for (String key : List.of("source", "target")) {
                        Object value = source.get(key);
                        if (value instanceof String || value instanceof Number) {
                            edge.put(key, String.valueOf(value));
                        }
                    }
                    edges.add(edge);
                }
            }
            return JSONUtil.toJsonStr(Map.of("nodes", nodes, "edges", edges));
        } catch (RuntimeException ignored) {
            return "{\"nodes\":[],\"edges\":[]}";
        }
    }

    /**
     * 获取运维实例的安全日志。
     *
     * @param raw 原始
     * @return 运维实例的安全日志文本
     */
    static String safeLog(String raw) {
        if (raw == null) {
            return "";
        }
        // 先按完整日志行脱敏，再截断，避免截断敏感键后留下其值。
        String safe = SENSITIVE_LOG_LINE.matcher(raw).replaceAll("[连接身份与敏感参数已脱敏]");
        return safe.length() <= MAX_LOG_CHARS ? safe : safe.substring(0, MAX_LOG_CHARS) + "\n[日志超出展示上限]";
    }

}
