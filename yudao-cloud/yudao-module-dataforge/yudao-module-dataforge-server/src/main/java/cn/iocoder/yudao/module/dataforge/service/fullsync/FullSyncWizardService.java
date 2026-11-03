package cn.iocoder.yudao.module.dataforge.service.fullsync;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.framework.dolphinscheduler.DolphinSchedulerClient;
import cn.iocoder.yudao.framework.dolphinscheduler.request.CreateWorkflowDefinitionRequest;
import cn.iocoder.yudao.framework.dolphinscheduler.request.CreateScheduleRequest;
import cn.iocoder.yudao.framework.dolphinscheduler.request.TriggerWorkflowDefinitionRequest;
import cn.iocoder.yudao.module.dataforge.controller.admin.fullsync.vo.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fullsync.FullSyncWizardDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fullsync.FullSyncWizardMapper;
import cn.iocoder.yudao.module.dataforge.service.access.DataAccessService;
import cn.iocoder.yudao.module.dataforge.service.database.DatabaseService;
import cn.iocoder.yudao.module.dataforge.controller.admin.database.vo.DatabaseTableRespVO;
import cn.iocoder.yudao.module.dataforge.service.access.config.AccessNameMapping;
import jakarta.annotation.Resource;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;

/**
 * 整库同步四步向导服务。
 *
 * <p>向导草稿只保存配置，不触发任何外部调度器；提交时才根据执行模式进入 DB Switch
 * 或 DolphinScheduler DataX 边界。</p>
 */
@Service
@Validated
public class FullSyncWizardService {

    public static final String EXECUTION_MODE_DB_SWITCH = "DBSWITCH";
    public static final String EXECUTION_MODE_DOLPHIN_DATAX = "DOLPHIN_DATAX";

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_SUBMITTING = "SUBMITTING";
    public static final String STATUS_SUBMITTED = "SUBMITTED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_UNKNOWN = "UNKNOWN";

    @Resource
    private FullSyncWizardMapper mapper;

    @Resource
    private DatabaseService databaseService;

    @Resource
    private DataAccessService dataAccessService;

    @Resource
    private DolphinSchedulerClient dolphinSchedulerClient;

    @Resource
    private TransactionTemplate transactionTemplate;

    /** 具有查询权限的用户可查看平台范围内的配置；不触发源端访问或执行。 */
    public PageResult<FullSyncWizardRespVO> page(FullSyncWizardPageReqVO req) {
        var query = new LambdaQueryWrapperX<FullSyncWizardDO>()
                .apply(StrUtil.isNotBlank(req.getTaskName()),
                        "position(lower({0}) in lower(config_json ->> 'taskName')) > 0", req.getTaskName())
                .apply(req.getSourceDatabaseId() != null,
                        "config_json ->> 'sourceDatabaseId' = {0}", Objects.toString(req.getSourceDatabaseId(), ""))
                .apply(req.getTargetDatabaseId() != null,
                        "config_json ->> 'targetDatabaseId' = {0}", Objects.toString(req.getTargetDatabaseId(), ""))
                .orderByDesc(FullSyncWizardDO::getId);
        PageResult<FullSyncWizardDO> result = mapper.selectPage(req, query);
        return new PageResult<>(result.getList().stream().map(row -> toResp(row, List.of())).toList(),
                result.getTotal());
    }

    /** 删除仅限未提交执行的草稿，使用与步骤保存相同的行锁避免并发提交。 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id, Long expectedRevision) {
        FullSyncWizardDO wizard = requireForUpdate(id);
        if (expectedRevision == null || !Objects.equals(expectedRevision, wizard.getRevision())) {
            throw exception(FULL_SYNC_WIZARD_REVISION_CONFLICT);
        }
        if (!STATUS_DRAFT.equals(wizard.getStatus()) && !STATUS_FAILED.equals(wizard.getStatus())) {
            throw exception(FULL_SYNC_WIZARD_STATE_INVALID);
        }
        mapper.deleteById(id);
    }

    /** 创建第 1 步草稿，不触发数据源访问或调度器提交。 */
    public FullSyncWizardRespVO create(FullSyncWizardSaveReqVO req) {
        FullSyncWizardConfigVO config = normalize(req.getConfig());
        validateStep(config, 1, true);
        FullSyncWizardDO wizard = new FullSyncWizardDO();
        wizard.setCurrentStep(1);
        wizard.setRevision(1L);
        wizard.setStatus(STATUS_DRAFT);
        wizard.setExecutionMode(config.getExecutionMode());
        wizard.setDsProjectCode(config.getDsProjectCode());
        wizard.setDsWorkerGroup(config.getDsWorkerGroup());
        wizard.setDsTenantCode(config.getDsTenantCode());
        wizard.setDsSourceDatasourceCode(config.getDsSourceDatasourceCode());
        wizard.setDsTargetDatasourceCode(config.getDsTargetDatasourceCode());
        wizard.setConfigJson(JsonUtils.toJsonString(config));
        mapper.insert(wizard);
        return toResp(wizard, List.of());
    }

    /** 保存任意步骤。步骤保存只做本地配置与元数据检查，不提交 DS。 */
    @Transactional(rollbackFor = Exception.class)
    public FullSyncWizardRespVO saveStep(Long id, FullSyncWizardSaveReqVO req) {
        FullSyncWizardDO old = requireForUpdate(id);
        checkRevision(old, req.getExpectedRevision());
        if (!STATUS_DRAFT.equals(old.getStatus()) && !STATUS_FAILED.equals(old.getStatus())) {
            throw exception(FULL_SYNC_WIZARD_STATE_INVALID);
        }
        FullSyncWizardConfigVO config = normalize(req.getConfig());
        validateStep(config, req.getStep(), true);
        FullSyncWizardDO update = new FullSyncWizardDO();
        update.setId(id);
        update.setCurrentStep(Math.max(old.getCurrentStep() == null ? 1 : old.getCurrentStep(), req.getStep()));
        update.setRevision(old.getRevision() + 1);
        update.setStatus(STATUS_DRAFT);
        update.setExecutionMode(config.getExecutionMode());
        update.setDsProjectCode(config.getDsProjectCode());
        update.setDsWorkerGroup(config.getDsWorkerGroup());
        update.setDsTenantCode(config.getDsTenantCode());
        update.setDsSourceDatasourceCode(config.getDsSourceDatasourceCode());
        update.setDsTargetDatasourceCode(config.getDsTargetDatasourceCode());
        update.setConfigJson(JsonUtils.toJsonString(config));
        update.setLastError(null);
        mapper.updateById(update);
        return toResp(require(id), List.of());
    }

    /** 查询向导状态与已保存配置。 */
    public FullSyncWizardRespVO get(Long id) {
        return toResp(require(id), List.of());
    }

    /** 返回四步向导校验结果，不修改草稿。 */
    public FullSyncWizardValidateRespVO validate(Long id) {
        FullSyncWizardDO wizard = require(id);
        FullSyncWizardConfigVO config = parseConfig(wizard);
        List<String> messages = new ArrayList<>();
        int sourceCount = 0;
        try {
            validateStep(config, 4, true);
            List<DatabaseTableRespVO> tables = databaseService.getTableList(config.getSourceDatabaseId());
            sourceCount = tables == null ? 0 : tables.size();
            Set<String> available = new HashSet<>();
            if (tables != null) {
                tables.forEach(t -> {
                    if (t != null && StrUtil.isNotBlank(t.getTableName())) {
                        available.add(t.getTableName());
                    }
                });
            }
            List<String> selected = config.getSourceSelectedTables();
            if (selected == null || selected.isEmpty()) {
                messages.add("至少选择一张来源表");
            } else if (selected.stream().anyMatch(StrUtil::isBlank)
                    || new HashSet<>(selected).size() != selected.size()) {
                messages.add("来源表清单不能包含空表名或重复表名");
            } else if (!available.containsAll(selected)) {
                messages.add("来源表清单已变化，请重新加载后选择");
            }
            validateMappings(config, messages);
        } catch (ServiceException ex) {
            messages.add(ex.getMessage());
        } catch (RuntimeException ex) {
            messages.add("来源表元数据读取失败");
        }
        FullSyncWizardValidateRespVO result = new FullSyncWizardValidateRespVO();
        result.setValid(messages.isEmpty());
        result.setCurrentStep(wizard.getCurrentStep());
        result.setMessages(messages);
        result.setSourceTableCount(sourceCount);
        result.setSelectedTableCount(config.getSourceSelectedTables() == null ? 0 : config.getSourceSelectedTables().size());
        result.setExecutionMode(config.getExecutionMode());
        return result;
    }

    /**
     * 提交向导。DBSWITCH 进入本平台 DataAccess；DOLPHIN_DATAX 创建 DS 3.4 DATAX 节点，
     * 仅在外部响应包含工作流编码（及触发时的实例编号）后报告成功。
     */
    public FullSyncWizardRespVO submit(Long id, FullSyncWizardSubmitReqVO req) {
        FullSyncWizardDO prepared = transactionTemplate.execute(status -> prepareSubmit(id, req == null ? null : req.getExpectedRevision()));
        if (prepared == null) {
            throw exception(FULL_SYNC_WIZARD_NOT_EXISTS);
        }
        FullSyncWizardConfigVO config = parseConfig(prepared);
        try {
            FullSyncSubmitResult result = EXECUTION_MODE_DOLPHIN_DATAX.equals(config.getExecutionMode())
                    ? submitDolphin(prepared, config, req == null || !Boolean.FALSE.equals(req.getTrigger()))
                    : submitDbSwitch(prepared, config);
            transactionTemplate.executeWithoutResult(status -> markSubmitted(prepared.getId(), prepared.getRevision(), result));
        } catch (Exception ex) {
            transactionTemplate.executeWithoutResult(status -> markUnknown(prepared.getId(), prepared.getRevision(),
                    safeError(ex)));
            if (ex instanceof ServiceException serviceException) {
                throw serviceException;
            }
            throw exception(FULL_SYNC_WIZARD_DS_SUBMIT_FAILED, safeError(ex));
        }
        return get(id);
    }

    private FullSyncWizardDO prepareSubmit(Long id, Long expectedRevision) {
        FullSyncWizardDO wizard = requireForUpdate(id);
        checkRevision(wizard, expectedRevision);
        // UNKNOWN 表示外部调用结果不可判定。必须先由运维对账，不能自动重试并制造第二个 DS 工作流。
        if (!STATUS_DRAFT.equals(wizard.getStatus()) && !STATUS_FAILED.equals(wizard.getStatus())) {
            throw exception(FULL_SYNC_WIZARD_STATE_INVALID);
        }
        FullSyncWizardConfigVO config = parseConfig(wizard);
        validateStep(config, 4, true);
        wizard.setStatus(STATUS_SUBMITTING);
        wizard.setRevision(wizard.getRevision() + 1);
        wizard.setSubmitRequestId(UUID.randomUUID().toString());
        wizard.setLastError(null);
        mapper.updateById(wizard);
        return wizard;
    }

    private FullSyncSubmitResult submitDbSwitch(FullSyncWizardDO wizard, FullSyncWizardConfigVO config) {
        Long dataAccessId = wizard.getDataAccessId();
        if (dataAccessId == null) {
            dataAccessId = dataAccessService.createDataAccess(config);
        } else {
            config.setId(dataAccessId);
            dataAccessService.updateDataAccess(config);
        }
        dataAccessService.releaseDataAccess(dataAccessId);
        return new FullSyncSubmitResult(dataAccessId, null, null, null);
    }

    private FullSyncSubmitResult submitDolphin(FullSyncWizardDO wizard, FullSyncWizardConfigVO config,
            boolean trigger) {
        requireDolphinConfig(config);
        verifyDolphinDataSources(config);
        JSONObject datax = parseDatax(config.getDataxJobJson());
        Long project = config.getDsProjectCode();
        Long taskCode = firstTaskCode(project);
        JSONObject taskParams = new JSONObject()
                .set("localParams", new JSONArray())
                .set("rawScript", datax.toString())
                .set("resourceList", new JSONArray());
        JSONObject node = new JSONObject()
                .set("code", taskCode).set("version", 1).set("name", config.getTaskName())
                .set("taskType", "DATAX").set("taskParams", taskParams).set("flag", "YES")
                .set("taskPriority", StrUtil.blankToDefault(config.getDsTaskPriority(), "MEDIUM"))
                .set("workerGroup", StrUtil.blankToDefault(config.getDsWorkerGroup(), "default"))
                .set("failRetryTimes", 0).set("failRetryInterval", 1)
                .set("timeoutFlag", "CLOSE").set("timeout", 0).set("delayTime", 0)
                .set("environmentCode", -1).set("taskExecuteType", "BATCH");
        CreateWorkflowDefinitionRequest request = new CreateWorkflowDefinitionRequest()
                .setName(config.getTaskName()).setDescription(config.getDescription())
                .setGlobalParams(new JSONObject()
                        .set("source_datasource_code", config.getDsSourceDatasourceCode())
                        .set("target_datasource_code", config.getDsTargetDatasourceCode()).toString())
                .setLocations("[]").setTimeout(0).setExecutionType(
                        StrUtil.blankToDefault(config.getDsExecutionType(), "SERIAL_WAIT"))
                .setTaskDefinitionJson(new JSONArray().put(node).toString())
                .setTaskRelationJson(new JSONArray().put(new JSONObject()
                        .set("preTaskCode", 0).set("preTaskVersion", 0).set("postTaskCode", taskCode)
                        .set("postTaskVersion", 1).set("conditionType", "NONE")
                        .set("conditionParams", new JSONObject())).toString());
        JSONObject created = dolphinSchedulerClient.workflowDefinition().createWorkflowDefinition(project, request);
        JSONObject createdData = created == null ? null : created.getJSONObject("data");
        Long workflowCode = createdData == null ? null : createdData.getLong("code");
        if (workflowCode == null || workflowCode <= 0) {
            throw new IllegalStateException("DS 创建流程未返回 workflow code");
        }
        // 先保存工作流编码，再调用后续 DS 接口；发布或触发发生未知结果时，运维仍可按编码对账。
        persistExternalReference(wizard.getId(), wizard.getRevision(), workflowCode, null);
        dolphinSchedulerClient.workflowDefinition().releaseWorkflowDefinition(project, workflowCode, "ONLINE",
                config.getTaskName());
        Integer scheduleId = null;
        if (Objects.equals(config.getTaskType(), 3)) {
            String schedule = new JSONObject().set("startTime", System.currentTimeMillis())
                    .set("endTime", 4102444800000L).set("crontab", config.getCron())
                    .set("timezoneId", java.time.ZoneId.systemDefault().getId()).toString();
            JSONObject scheduled = dolphinSchedulerClient.schedule().createSchedule(project,
                    new CreateScheduleRequest().setWorkflowDefinitionCode(workflowCode).setSchedule(schedule)
                            .setWarningType("NONE").setFailureStrategy("END")
                            .setWorkerGroup(config.getDsWorkerGroup()).setTenantCode(config.getDsTenantCode())
                            .setWorkflowInstancePriority(StrUtil.blankToDefault(config.getDsTaskPriority(), "MEDIUM")));
            JSONObject scheduleData = scheduled == null ? null : scheduled.getJSONObject("data");
            scheduleId = scheduleData == null ? null : scheduleData.getInt("id");
            if (scheduleId == null || scheduleId <= 0) {
                throw new IllegalStateException("DS 创建调度未返回 schedule id");
            }
            // 调度创建成功但发布失败时保留 schedule id，避免丢失外部状态。
            persistExternalReference(wizard.getId(), wizard.getRevision(), workflowCode, scheduleId);
            dolphinSchedulerClient.schedule().publishScheduleOnline(project, scheduleId);
        }
        Integer instanceId = null;
        if (trigger) {
            TriggerWorkflowDefinitionRequest triggerRequest = new TriggerWorkflowDefinitionRequest()
                    .setWorkflowDefinitionCode(workflowCode).setScheduleTime("").setFailureStrategy("END")
                    .setWarningType("NONE").setExecType("START_PROCESS")
                    .setWorkflowInstancePriority(StrUtil.blankToDefault(config.getDsTaskPriority(), "MEDIUM"))
                    .setWorkerGroup(StrUtil.blankToDefault(config.getDsWorkerGroup(), "default"))
                    .setTenantCode(config.getDsTenantCode()).setDryRun(0);
            JSONObject triggered = dolphinSchedulerClient.executor().triggerWorkflowDefinition(project, triggerRequest);
            JSONArray ids = triggered == null ? null : triggered.getJSONArray("data");
            if (ids == null || ids.size() != 1) {
                throw new IllegalStateException("DS 触发未返回唯一工作流实例编号");
            }
            long raw = ids.getLong(0);
            if (raw <= 0 || raw > Integer.MAX_VALUE) {
                throw new IllegalStateException("DS 返回的工作流实例编号无效");
            }
            instanceId = (int) raw;
        }
        return new FullSyncSubmitResult(null, workflowCode, instanceId, scheduleId);
    }

    private void verifyDolphinDataSources(FullSyncWizardConfigVO config) {
        if (config.getDsSourceDatasourceCode() > Integer.MAX_VALUE
                || config.getDsTargetDatasourceCode() > Integer.MAX_VALUE) {
            throw exception(FULL_SYNC_WIZARD_DS_NOT_CONFIGURED, "数据源编号超出 DS 3.4 接口范围");
        }
        dolphinSchedulerClient.dataSource().queryDataSource(config.getDsSourceDatasourceCode().intValue());
        dolphinSchedulerClient.dataSource().queryDataSource(config.getDsTargetDatasourceCode().intValue());
    }

    private Long firstTaskCode(Long projectCode) {
        JSONArray codes = dolphinSchedulerClient.taskDefinition().genTaskCodeList(projectCode, 1)
                .getJSONArray("data");
        if (codes == null || codes.size() != 1 || codes.getLong(0) == null || codes.getLong(0) <= 0) {
            throw new IllegalStateException("DS 未返回有效任务编码");
        }
        return codes.getLong(0);
    }

    private void requireDolphinConfig(FullSyncWizardConfigVO config) {
        if (config.getDsProjectCode() == null || config.getDsProjectCode() <= 0) {
            throw exception(FULL_SYNC_WIZARD_DS_NOT_CONFIGURED, "projectCode");
        }
        if (StrUtil.isBlank(config.getDsWorkerGroup())) {
            throw exception(FULL_SYNC_WIZARD_DS_NOT_CONFIGURED, "workerGroup");
        }
        if (StrUtil.isBlank(config.getDsTenantCode())) {
            throw exception(FULL_SYNC_WIZARD_DS_NOT_CONFIGURED, "tenantCode");
        }
        if (config.getDsSourceDatasourceCode() == null || config.getDsSourceDatasourceCode() <= 0
                || config.getDsTargetDatasourceCode() == null || config.getDsTargetDatasourceCode() <= 0) {
            throw exception(FULL_SYNC_WIZARD_DS_NOT_CONFIGURED, "DS 数据源编号");
        }
        parseDatax(config.getDataxJobJson());
    }

    private JSONObject parseDatax(String raw) {
        if (StrUtil.isBlank(raw)) {
            throw exception(FULL_SYNC_WIZARD_DS_NOT_CONFIGURED, "DataX rawScript");
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.contains("\"password\"") || lower.contains("\"passwd\"") || lower.contains("\"secret\"")
                || lower.contains("\"token\"")) {
            throw exception(FULL_SYNC_WIZARD_DS_NOT_CONFIGURED, "rawScript 不得包含连接凭据，请使用 DS 数据源中心引用");
        }
        JSONObject root;
        try {
            root = JSONUtil.parseObj(raw);
        } catch (RuntimeException ex) {
            throw exception(FULL_SYNC_WIZARD_DS_NOT_CONFIGURED, "rawScript 必须是 JSON 对象");
        }
        JSONObject job = root.getJSONObject("job");
        JSONArray content = job == null ? null : job.getJSONArray("content");
        if (job == null || content == null || content.isEmpty()) {
            throw exception(FULL_SYNC_WIZARD_DS_NOT_CONFIGURED, "rawScript 必须包含 job.content");
        }
        return root;
    }

    private FullSyncWizardConfigVO normalize(FullSyncWizardConfigVO config) {
        config.setExecutionMode(StrUtil.blankToDefault(config.getExecutionMode(), EXECUTION_MODE_DB_SWITCH)
                .trim().toUpperCase(Locale.ROOT));
        config.setEngineType(1);
        config.setSourceType(config.getSourceType() == null ? 1 : config.getSourceType());
        config.setTaskType(config.getTaskType() == null ? 2 : config.getTaskType());
        config.setIncludeOrExclude(config.getIncludeOrExclude() == null ? 1 : config.getIncludeOrExclude());
        config.setTargetDistributedKeys(config.getTargetDistributedKeys() == null ? List.of()
                : config.getTargetDistributedKeys());
        return config;
    }

    private void validateStep(FullSyncWizardConfigVO config, int step, boolean checkTables) {
        if (!EXECUTION_MODE_DB_SWITCH.equals(config.getExecutionMode())
                && !EXECUTION_MODE_DOLPHIN_DATAX.equals(config.getExecutionMode())) {
            throw exception(FULL_SYNC_WIZARD_EXECUTION_MODE_INVALID, config.getExecutionMode());
        }
        if (StrUtil.isBlank(config.getTaskName())) {
            throw exception(FULL_SYNC_WIZARD_STEP_INVALID, "第 1 步必须填写任务名称");
        }
        if (config.getSourceDatabaseId() == null || config.getTargetDatabaseId() == null
                || config.getSourceDatabaseId() <= 0 || config.getTargetDatabaseId() <= 0) {
            throw exception(FULL_SYNC_WIZARD_STEP_INVALID, "第 1 步必须选择来源库和目标库");
        }
        if (Objects.equals(config.getSourceDatabaseId(), config.getTargetDatabaseId())) {
            throw exception(FULL_SYNC_WIZARD_STEP_INVALID, "来源库和目标库不能相同");
        }
        if (step >= 2) {
            if (config.getSourceSelectedTables() == null || config.getSourceSelectedTables().isEmpty()) {
                throw exception(FULL_SYNC_WIZARD_STEP_INVALID, "第 2 步至少选择一张来源表");
            }
            if (config.getSourceSelectedTables().stream().anyMatch(StrUtil::isBlank)
                    || new HashSet<>(config.getSourceSelectedTables()).size()
                    != config.getSourceSelectedTables().size()) {
                throw exception(FULL_SYNC_WIZARD_STEP_INVALID, "第 2 步来源表不能包含空表名或重复表名");
            }
            if (checkTables) {
                List<DatabaseTableRespVO> tables = databaseService.getTableList(config.getSourceDatabaseId());
                Set<String> available = new HashSet<>();
                if (tables != null) {
                    tables.forEach(t -> {
                        if (t != null && StrUtil.isNotBlank(t.getTableName())) {
                            available.add(t.getTableName());
                        }
                    });
                }
                if (!available.containsAll(config.getSourceSelectedTables())) {
                    throw exception(FULL_SYNC_WIZARD_STEP_INVALID, "第 2 步包含不存在或已变化的来源表");
                }
            }
        }
        if (step >= 3) {
            List<String> messages = new ArrayList<>();
            validateMappings(config, messages);
            if (!messages.isEmpty()) {
                throw exception(FULL_SYNC_WIZARD_STEP_INVALID, String.join("；", messages));
            }
        }
        if (step >= 4) {
            if (!List.of(2, 3).contains(config.getTaskType())) {
                throw exception(FULL_SYNC_WIZARD_STEP_INVALID, "第 4 步任务类型仅支持一次性全量或周期增量");
            }
            if (config.getTaskType() == 3
                    && (StrUtil.isBlank(config.getCron()) || !CronExpression.isValidExpression(config.getCron()))) {
                throw exception(FULL_SYNC_WIZARD_STEP_INVALID, "第 4 步周期任务必须填写有效 Cron");
            }
            if (EXECUTION_MODE_DOLPHIN_DATAX.equals(config.getExecutionMode())) {
                requireDolphinConfig(config);
            }
        }
    }

    private void validateMappings(FullSyncWizardConfigVO config, List<String> messages) {
        if (config.getMapperType() != null && !List.of("ALL", "SINGLE").contains(config.getMapperType())) {
            messages.add("映射类型只能为 ALL 或 SINGLE");
        }
        if (config.getTableNameMapper() != null) {
            Set<String> from = new HashSet<>();
            for (AccessNameMapping item : config.getTableNameMapper()) {
                if (item == null || StrUtil.isBlank(item.getFromPattern()) || StrUtil.isBlank(item.getToValue())) {
                    messages.add("统一表名映射的来源和目标不能为空");
                }
                if (item != null && !from.add(item.getFromPattern())) {
                    messages.add("统一表名映射来源不能重复");
                }
            }
        }
        if (config.getConfigMap() != null) {
            for (String table : config.getConfigMap().keySet()) {
                if (StrUtil.isBlank(table) || config.getSourceSelectedTables() == null
                        || !config.getSourceSelectedTables().contains(table)) {
                    messages.add("单表映射必须引用已选择的来源表");
                }
            }
        }
    }

    private FullSyncWizardDO require(Long id) {
        FullSyncWizardDO wizard = mapper.selectById(id);
        if (wizard == null) {
            throw exception(FULL_SYNC_WIZARD_NOT_EXISTS);
        }
        return wizard;
    }

    private FullSyncWizardDO requireForUpdate(Long id) {
        FullSyncWizardDO wizard = mapper.lock(id);
        if (wizard == null) {
            throw exception(FULL_SYNC_WIZARD_NOT_EXISTS);
        }
        return wizard;
    }

    private void checkRevision(FullSyncWizardDO wizard, Long expectedRevision) {
        if (expectedRevision != null && !Objects.equals(expectedRevision, wizard.getRevision())) {
            throw exception(FULL_SYNC_WIZARD_REVISION_CONFLICT);
        }
    }

    private FullSyncWizardConfigVO parseConfig(FullSyncWizardDO wizard) {
        try {
            return normalize(JsonUtils.parseObject(wizard.getConfigJson(), FullSyncWizardConfigVO.class));
        } catch (RuntimeException ex) {
            throw exception(FULL_SYNC_WIZARD_STEP_INVALID, "草稿配置损坏");
        }
    }

    private FullSyncWizardRespVO toResp(FullSyncWizardDO wizard, List<String> messages) {
        FullSyncWizardRespVO result = BeanUtils.toBean(wizard, FullSyncWizardRespVO.class);
        result.setConfig(parseConfig(wizard));
        result.setValidationMessages(messages);
        return result;
    }

    private void markSubmitted(Long id, Long revision, FullSyncSubmitResult result) {
        FullSyncWizardDO update = require(id);
        if (!Objects.equals(update.getRevision(), revision) || !STATUS_SUBMITTING.equals(update.getStatus())) {
            return;
        }
        update.setStatus(STATUS_SUBMITTED);
        update.setRevision(revision + 1);
        update.setDataAccessId(result.dataAccessId());
        update.setDsWorkflowCode(result.workflowCode());
        update.setDsInstanceId(result.instanceId());
        update.setDsScheduleId(result.scheduleId());
        update.setLastError(null);
        mapper.updateById(update);
    }

    /**
     * 持久化已经由 DS 返回的外部引用，不推进向导 revision，供未知结果对账使用。
     *
     * @param id 向导编号
     * @param revision 提交事务生成的 revision
     * @param workflowCode DS 工作流编码
     * @param scheduleId DS 调度编号，可为空
     */
    private void persistExternalReference(Long id, Long revision, Long workflowCode, Integer scheduleId) {
        transactionTemplate.executeWithoutResult(status -> {
            FullSyncWizardDO current = require(id);
            if (!Objects.equals(current.getRevision(), revision) || !STATUS_SUBMITTING.equals(current.getStatus())) {
                return;
            }
            FullSyncWizardDO update = new FullSyncWizardDO();
            update.setId(id);
            update.setDsWorkflowCode(workflowCode);
            if (scheduleId != null) {
                update.setDsScheduleId(scheduleId);
            }
            mapper.updateById(update);
        });
    }

    private void markUnknown(Long id, Long revision, String message) {
        FullSyncWizardDO update = require(id);
        if (!Objects.equals(update.getRevision(), revision)) {
            return;
        }
        update.setStatus(STATUS_UNKNOWN);
        update.setRevision(revision + 1);
        update.setLastError(message);
        mapper.updateById(update);
    }

    private static String safeError(Exception ex) {
        // 外部调度器异常可能回显任务参数；只持久化异常类型，避免 rawScript 或凭据进入错误字段。
        return "外部提交结果待核对：" + ex.getClass().getSimpleName();
    }

    private record FullSyncSubmitResult(Long dataAccessId, Long workflowCode, Integer instanceId, Integer scheduleId) {
    }
}

