package cn.iocoder.yudao.module.dataforge.service.integration;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.dolphinscheduler.core.DolphinCronSchedule;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.integration.vo.IntegrationModels.*;
import cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.EtlRecordPageReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.EtlRecordRespVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.integration.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.integration.*;
import cn.iocoder.yudao.module.dataforge.service.etl.EtlPipelineService;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;

/**
 * 集成工作台的领域控制面。任务、类目与迁入快照在当前平台内共享，接口权限决定可读写范围。
 * 引擎执行复用已有 ETL 服务，不从迁入的源图推断本平台执行能力。
 */
@Service
public class IntegrationTaskService {

    @Resource
    private IntegrationTaskMapper taskMapper;

    @Resource
    private IntegrationCategoryMapper categoryMapper;

    @Resource
    private IntegrationRecordMapper recordMapper;

    @Resource
    private AdminUserApi adminUserApi;

    @Resource
    private EtlPipelineService pipelineService;

    @Resource private IntegrationFlowCompiler flowCompiler;
    @Resource private IntegrationDsRuntime dsRuntime;
    @Resource private IntegrationSchemaService schemaService;

    /**
     * 分页查询当前平台共享的集成任务。
     *
     * @param req 请求参数
     * @return 分页查询当前平台共享的集成任务结果
     */
    public PageResult<TaskResp> page(PageReq req) {
        var today = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).toLocalDate().atStartOfDay();
        var dateFrom = Boolean.TRUE.equals(req.getExecutedToday()) ? today
                : req.getRunDateStart() == null ? null : req.getRunDateStart().atStartOfDay();
        var dateTo = Boolean.TRUE.equals(req.getExecutedToday()) ? today.plusDays(1)
                : req.getRunDateEnd() == null ? null : req.getRunDateEnd().plusDays(1).atStartOfDay();
        var result = taskMapper.selectPage(req, new LambdaQueryWrapperX<IntegrationTaskDO>()
                .eq(IntegrationTaskDO::getMode, req.getMode())
                .likeIfPresent(IntegrationTaskDO::getName, req.getName())
                .inIfPresent(IntegrationTaskDO::getCategoryId, categoryIds(req.getCategoryId()))
                .eqIfPresent(IntegrationTaskDO::getStatus, req.getStatus())
                .eqIfPresent(IntegrationTaskDO::getLastRunStatus, req.getRunStatus())
                .eqIfPresent(IntegrationTaskDO::getOwnerId, req.getOwnerId())
                .ge(dateFrom != null, IntegrationTaskDO::getLastRunTime, dateFrom)
                .lt(dateTo != null, IntegrationTaskDO::getLastRunTime, dateTo)
                .orderBy(true, "asc".equals(req.getSortOrder()), IntegrationTaskDO::getId));
        var names = categoryNames();
        return new PageResult<>(result.getList().stream().map(task -> responseWithRuntime(task, names)).toList(),
                result.getTotal());
    }

    /**
     * 读取集成任务与只读源流程。
     *
     * @param id 记录编号
     * @return 任务
     */
    public TaskResp get(Long id) {
        var task = requireTask(id);
        return responseWithRuntime(task, categoryNames());
    }

    /**
     * 读取共享集成类目树。
     *
     * @return 共享集成类目树结果集合
     */
    public List<CategoryResp> categories() {
        return categoryMapper.selectList(new LambdaQueryWrapperX<IntegrationCategoryDO>()
                        .orderByAsc(IntegrationCategoryDO::getSort, IntegrationCategoryDO::getId)).stream()
                .map(category -> {
                    var result = BeanUtils.toBean(category, CategoryResp.class);
                    result.setSortOrder(category.getSort());
                    return result;
                }).toList();
    }

    /**
     * 读取当前平台有效负责人。
     *
     * @return 当前平台有效负责人结果集合
     */
    public List<OwnerResp> owners() {
        return adminUserApi.getUserListByStatus(0).getCheckedData().stream().map(user -> {
            var result = new OwnerResp();
            result.setId(user.getId());
            result.setName(user.getNickname());
            result.setContactNumber(user.getMobile());
            return result;
        }).toList();
    }

    /**
     * 新建集成任务基础信息。
     *
     * @param req 请求参数
     * @return 生成或保存的记录编号
     */
    @Transactional(rollbackFor = Exception.class)
    public Long create(SaveReq req) {
        if (req.getId() != null) {
            throw exception(INTEGRATION_CONFIG_INVALID, "新增任务不能指定编号");
        }
        var actor = adminUserApi.getUser(SecurityFrameworkUtils.getLoginUserId()).getCheckedData();
        var task = prepare(req);
        if (Integer.valueOf(1).equals(req.getStatus())) {
            throw exception(INTEGRATION_FLOW_NOT_READY);
        }
        task.setRevision(0);
        task.setStatus(0);
        task.setScheduleStatus(0);
        task.setConfigurationStatus("UNCONFIGURED");
        task.setImportedSnapshot(false);
        task.setLastRunStatus("IDLE");
        task.setFlowJson("{\"nodes\":[],\"edges\":[]}");
        task.setCreatorName(actor == null ? "" : actor.getNickname());
        insert(task);
        return task.getId();
    }

    /**
     * 修改集成任务基础信息。
     *
     * @param req 请求参数
     */
    @Transactional(rollbackFor = Exception.class)
    @com.baomidou.lock.annotation.Lock4j(name = "dataforge:integration-task", keys = "#req.id", expire = 600000, acquireTimeout = 0)
    public void update(SaveReq req) {
        if (req.getId() == null || req.getRevision() == null) {
            throw exception(INTEGRATION_CONFLICT);
        }
        var old = requireTask(req.getId());
        requireEditable(old);
        if (!old.getMode().equals(req.getMode())) {
            throw exception(INTEGRATION_CONFIG_INVALID, "不能跨离线和实时移动任务");
        }
        if (Integer.valueOf(1).equals(req.getStatus()) && !"COMPLETE".equals(old.getConfigurationStatus())) {
            throw exception(INTEGRATION_FLOW_NOT_READY);
        }
        var task = prepare(req);
        if (old.getFlowJson() != null && !old.getFlowJson().isBlank())
            flowCompiler.validateEngineComponents(task, old.getFlowJson());
        task.setRevision(req.getRevision() + 1);
        task.setConfigurationStatus("UNCONFIGURED");
        try {
            changed(taskMapper.update(task, Wrappers.lambdaUpdate(IntegrationTaskDO.class)
                    .eq(IntegrationTaskDO::getId, old.getId()).eq(IntegrationTaskDO::getRevision, req.getRevision())
                    .eq(IntegrationTaskDO::getStatus, 0).eq(IntegrationTaskDO::getScheduleStatus, 0)
                    .set(IntegrationTaskDO::getDsDefinitionCode, null).set(IntegrationTaskDO::getDsScheduleId, null)));
        } catch (DuplicateKeyException duplicate) {
            throw exception(INTEGRATION_NAME_DUPLICATE);
        }
    }

    /**
     * 克隆集成定义为未上线的新任务。
     *
     * @param id 记录编号
     * @return 记录编号
     */
    @Transactional(rollbackFor = Exception.class)
    @com.baomidou.lock.annotation.Lock4j(name = "dataforge:integration-task", keys = "#id", expire = 600000, acquireTimeout = 0)
    public Long cloneTask(Long id) {
        var actorId = SecurityFrameworkUtils.getLoginUserId();
        var actor = actorId == null ? null : adminUserApi.getUser(actorId).getCheckedData();
        var source = taskMapper.selectOne(new LambdaQueryWrapperX<IntegrationTaskDO>()
                .eq(IntegrationTaskDO::getId, id).last("FOR UPDATE"));
        if (source == null) {
            throw exception(INTEGRATION_TASK_NOT_EXISTS);
        }
        requireEditable(source);
        var copy = BeanUtils.toBean(source, IntegrationTaskDO.class);
        copy.setId(null);
        copy.setRevision(0);
        copy.setStatus(0);
        copy.setScheduleStatus(0);
        copy.setName(
                StrUtil.maxLength(source.getName(), 155) + "_copy_" + UUID.randomUUID().toString().substring(0, 8));
        copy.setPipelineId(null);
        copy.setDsProjectCode(null); copy.setDsDefinitionCode(null); copy.setDsScheduleId(null);
        copy.setConfigurationStatus("UNCONFIGURED"); copy.setDsBindingHash(null);
        copy.setImportedSnapshot(false);
        copy.setLastRunStatus("IDLE");
        copy.setLastRunTime(null);
        copy.setLastRunDuration(null);
        copy.setCreatorName(actor == null ? "" : actor.getNickname());
        copy.setCreator(String.valueOf(actorId));
        copy.setUpdater(copy.getCreator());
        copy.setCreateTime(null);
        copy.setUpdateTime(null);
        insert(copy);
        return copy.getId();
    }

    /**
     * 删除已下线且关闭调度的集成任务。
     *
     * @param id 记录编号
     */
    @Transactional(rollbackFor = Exception.class)
    @com.baomidou.lock.annotation.Lock4j(name = "dataforge:integration-task", keys = "#id", expire = 600000, acquireTimeout = 0)
    public void delete(Long id) {
        var task = requireTask(id);
        requireEditable(task);
        changed(taskMapper.delete(Wrappers.lambdaQuery(IntegrationTaskDO.class)
                .eq(IntegrationTaskDO::getId, id).eq(IntegrationTaskDO::getRevision, task.getRevision())
                .eq(IntegrationTaskDO::getStatus, 0).eq(IntegrationTaskDO::getScheduleStatus, 0)));
        recordMapper.delete(Wrappers.lambdaQuery(IntegrationRecordDO.class).eq(IntegrationRecordDO::getTaskId, id));
    }

    /**
     * 切换集成任务上下线状态。
     *
     * @param req 请求参数
     */
    @com.baomidou.lock.annotation.Lock4j(name = "dataforge:integration-task", keys = "#req.id", expire = 600000, acquireTimeout = 0)
    public void status(StatusReq req) {
        var task = requireTask(req.getId());
        if (!Objects.equals(task.getRevision(), req.getRevision())) throw exception(INTEGRATION_CONFLICT);
        if (Integer.valueOf(1).equals(task.getScheduleStatus())) {
            throw exception(INTEGRATION_STATE_INVALID);
        }
        if (req.getStatus() == 1 && (!"COMPLETE".equals(task.getConfigurationStatus()) || task.getDsDefinitionCode() == null)) {
            throw exception(INTEGRATION_FLOW_NOT_READY);
        }
        if (task.getDsDefinitionCode() != null) {
            if (req.getStatus() == 1) requireCompiled(task);
            dsRuntime.release(task, req.getStatus() == 1);
        }
        changed(taskMapper.update(null, Wrappers.lambdaUpdate(IntegrationTaskDO.class)
                .eq(IntegrationTaskDO::getId, req.getId()).eq(IntegrationTaskDO::getRevision, req.getRevision())
                .eq(IntegrationTaskDO::getScheduleStatus, 0)
                .set(IntegrationTaskDO::getUpdater, String.valueOf(SecurityFrameworkUtils.getLoginUserId()))
                .set(IntegrationTaskDO::getUpdateTime, LocalDateTime.now())
                .set(IntegrationTaskDO::getStatus, req.getStatus())
                .set(IntegrationTaskDO::getRevision, req.getRevision() + 1)));
    }

    /**
     * 保存调度周期或切换调度；开启要求绑定本平台可执行流程。
     *
     * @param req 请求参数
     */
    @com.baomidou.lock.annotation.Lock4j(name = "dataforge:integration-task", keys = "#req.id", expire = 600000, acquireTimeout = 0)
    public void schedule(ScheduleReq req) {
        var task = requireTask(req.getId());
        if (!"OFFLINE".equals(task.getMode())) {
            throw exception(INTEGRATION_CONFIG_INVALID, "实时任务没有周期调度");
        }
        var cron = StrUtil.blankToDefault(req.getCronExpression(), task.getCronExpression());
        validateCron(cron);
        if (Integer.valueOf(1).equals(task.getScheduleStatus()) && !Objects.equals(cron, task.getCronExpression())) {
            throw exception(INTEGRATION_CONFIG_INVALID, "请先关闭调度，再修改调度周期");
        }
        if (task.getDsDefinitionCode() != null) {
            if (!Objects.equals(task.getRevision(), req.getRevision())) throw exception(INTEGRATION_CONFLICT);
            if (req.getScheduleStatus() == 1 && !Integer.valueOf(1).equals(task.getStatus())) throw exception(INTEGRATION_STATE_INVALID);
            if (req.getScheduleStatus() == 1) requireCompiled(task);
            var scheduleId = dsRuntime.schedule(task, cron, req.getScheduleStatus() == 1);
            changed(taskMapper.update(null, Wrappers.lambdaUpdate(IntegrationTaskDO.class)
                    .eq(IntegrationTaskDO::getId, req.getId()).eq(IntegrationTaskDO::getRevision, req.getRevision())
                    .set(IntegrationTaskDO::getCronExpression, cron).set(IntegrationTaskDO::getDsScheduleId, scheduleId)
                    .set(IntegrationTaskDO::getScheduleStatus, req.getScheduleStatus()).set(IntegrationTaskDO::getRevision, req.getRevision() + 1)));
            return;
        }
        if (req.getScheduleStatus() == 1) throw exception(INTEGRATION_FLOW_NOT_READY);
        changed(taskMapper.update(null, Wrappers.lambdaUpdate(IntegrationTaskDO.class)
                .eq(IntegrationTaskDO::getId, req.getId()).eq(IntegrationTaskDO::getRevision, req.getRevision())
                .set(IntegrationTaskDO::getCronExpression, cron).set(IntegrationTaskDO::getScheduleStatus, 0)
                .set(IntegrationTaskDO::getDsScheduleId, null).set(IntegrationTaskDO::getRevision, req.getRevision() + 1)));
    }

    /**
     * 委托已绑定的受管 ETL 流程执行，不把源流程快照当作可执行配置。
     *
     * @param id 记录编号
     * @return 运行数值
     */
    @com.baomidou.lock.annotation.Lock4j(name = "dataforge:integration-task", keys = "#id", expire = 600000, acquireTimeout = 0)
    public Long run(Long id) {
        var task = requireTask(id);
        requireExecutable(task);
        if (!Integer.valueOf(1).equals(task.getStatus())) {
            throw exception(INTEGRATION_STATE_INVALID);
        }
        if (task.getDsDefinitionCode() != null) { requireCompiled(task); return dsRuntime.run(task); }
        throw exception(INTEGRATION_CONFIG_INVALID, "请重新保存流程，交由 DolphinScheduler 执行");
    }

    /** 作业引用仅复核现有正式绑定，不单独触发集成流程。 */
    public IntegrationTaskDO validateJobReference(Long id) {
        var task = requireTask(id);
        requireExecutable(task);
        if (!Integer.valueOf(1).equals(task.getStatus()) || !"OFFLINE".equals(task.getMode()) || task.getDsDefinitionCode() == null) throw exception(INTEGRATION_STATE_INVALID);
        requireCompiled(task);
        dsRuntime.requireEnvironment(task);
        return task;
    }

    /**
     * 请求停止本平台正在执行的实例。
     *
     * @param id 记录编号
     */
    @com.baomidou.lock.annotation.Lock4j(name = "dataforge:integration-task", keys = "#id", expire = 600000, acquireTimeout = 0)
    public void stop(Long id) {
        var task = requireTask(id);
        requireExecutable(task);
        if (task.getDsDefinitionCode() != null) {
            try { dsRuntime.stop(task); } catch (IllegalArgumentException invalid) { throw exception(INTEGRATION_CONFIG_INVALID, invalid.getMessage()); }
            return;
        }
        var req = new EtlRecordPageReqVO();
        req.setPipelineId(task.getPipelineId());
        req.setPageSize(1);
        req.setRunStatus(2);
        var records = pipelineService.getRecordPage(req).getList();
        if (records.isEmpty()) {
            throw exception(INTEGRATION_CONFIG_INVALID, "当前平台没有可停止的运行实例；迁入摘要不能作为停止依据");
        }
        pipelineService.stopRecord(records.getFirst().getId());
    }

    /**
     * 分页读取执行实例；迁入实例与本平台真实执行记录区分展示。
     *
     * @param req 请求参数
     * @return 分页读取执行实例；迁入实例与本平台真实执行记录区分展示结果
     */
    public PageResult<RecordResp> records(RecordPageReq req) {
        var task = requireTask(req.getTaskId());
        refreshRuntime(task);
        if (task.getDsDefinitionCode() == null && task.getPipelineId() != null) {
            var request = new EtlRecordPageReqVO();
            request.setPipelineId(task.getPipelineId());
            request.setRunStatus(switch (StrUtil.nullToEmpty(req.getStatus())) {
                case "RUNNING" -> 2;
                case "SUCCESS" -> 3;
                case "FAILURE" -> 4;
                case "IDLE" -> 5;
                default -> null;
            });
            request.setPageNo(req.getPageNo());
            request.setPageSize(req.getPageSize());
            var result = pipelineService.getRecordPage(request);
            return new PageResult<>(
                    result.getList().stream().map(record -> runtimeRecord(task.getId(), record)).toList(),
                    result.getTotal());
        }
        var result = recordMapper.selectPage(req, new LambdaQueryWrapperX<IntegrationRecordDO>()
                .eq(IntegrationRecordDO::getTaskId, task.getId())
                .eqIfPresent(IntegrationRecordDO::getStatus, req.getStatus())
                .orderByDesc(IntegrationRecordDO::getStartTime, IntegrationRecordDO::getId));
        return new PageResult<>(result.getList().stream().map(record -> {
            var response = BeanUtils.toBean(record, RecordResp.class);
            response.setTaskName(task.getName());
            response.setExecutionType(task.getExecutionStrategy());
            return response;
        }).toList(), result.getTotal());
    }

    /**
     * 读取集成运行摘要，迁入数据显式标记为源系统快照。
     *
     * @param mode 处理模式
     * @return 统计
     */
    public StatisticsResp statistics(String mode) {
        int unavailable = 0;
        for (var task : taskMapper.selectList(new LambdaQueryWrapperX<IntegrationTaskDO>().eq(IntegrationTaskDO::getMode, mode)
                .isNotNull(IntegrationTaskDO::getDsDefinitionCode))) {
            try {
                refreshRuntime(task);
            } catch (IntegrationDsRuntime.MissingWorkflowDefinitionException missing) {
                unavailable++;
            }
        }
        var now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        var start = now.toLocalDate().atStartOfDay();
        var end = start.plusDays(1);
        var result = new StatisticsResp();
        if (unavailable > 0) {
            result.setRuntimeAvailable(false);
            result.setRuntimeMessage("有 " + unavailable + " 个任务的 DolphinScheduler 项目或工作流不存在，当前运行统计不完整，已保留历史记录");
        }
        result.setStatisticTime(now);
        result.setRunning(taskMapper.selectCount(new LambdaQueryWrapperX<IntegrationTaskDO>()
                .eq(IntegrationTaskDO::getMode, mode).eq(IntegrationTaskDO::getImportedSnapshot, false).eq(IntegrationTaskDO::getLastRunStatus, "RUNNING")));
        result.setImportedSnapshot(taskMapper.selectCount(new LambdaQueryWrapperX<IntegrationTaskDO>()
                .eq(IntegrationTaskDO::getMode, mode).eq(IntegrationTaskDO::getImportedSnapshot, true)) > 0);
        var base = "task_id IN (SELECT id FROM dh_dataforge_integration_task WHERE deleted=0 AND mode={0})";
        long total = recordMapper.selectCount(new LambdaQueryWrapperX<IntegrationRecordDO>()
                .apply(base, mode).ne(IntegrationRecordDO::getTriggerType, "IMPORTED").ge(IntegrationRecordDO::getStartTime, start)
                .lt(IntegrationRecordDO::getStartTime, end));
        long failed = recordMapper.selectCount(new LambdaQueryWrapperX<IntegrationRecordDO>()
                .apply(base, mode).ne(IntegrationRecordDO::getTriggerType, "IMPORTED").ge(IntegrationRecordDO::getStartTime, start)
                .lt(IntegrationRecordDO::getStartTime, end)
                .eq(IntegrationRecordDO::getStatus, "FAILURE"));
        long succeeded = recordMapper.selectCount(new LambdaQueryWrapperX<IntegrationRecordDO>()
                .apply(base, mode).ne(IntegrationRecordDO::getTriggerType, "IMPORTED").ge(IntegrationRecordDO::getStartTime, start)
                .lt(IntegrationRecordDO::getStartTime, end)
                .eq(IntegrationRecordDO::getStatus, "SUCCESS"));
        result.setTodayExecuted(total);
        // 运行卡片只统计本平台执行；源系统历史快照仅在任务和实例列表回显。
        result.setTodayFailed(taskMapper.selectCount(new LambdaQueryWrapperX<IntegrationTaskDO>()
                .eq(IntegrationTaskDO::getMode, mode).eq(IntegrationTaskDO::getImportedSnapshot, false).eq(IntegrationTaskDO::getLastRunStatus, "FAILURE")));
        long completed = succeeded + failed;
        result.setTodaySuccessRate(completed == 0 ? 0 : (int) (succeeded * 100 / completed));
        return result;
    }

    /**
     * 读取指定集成任务的全量实例运行状态计数。
     *
     * @param taskId 任务编号
     * @return 记录统计
     */
    public RecordStatisticsResp recordStatistics(Long taskId) {
        var task = requireTask(taskId);
        refreshRuntime(task);
        var result = new RecordStatisticsResp();
        result.setStatisticTime(LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
        result.setRunning(recordCount(task, "RUNNING", 2));
        result.setSuccess(recordCount(task, "SUCCESS", 3));
        result.setFailed(recordCount(task, "FAILURE", 4));
        return result;
    }

    /**
     * 统计集成任务的记录数量。
     *
     * @param task 任务记录
     * @param status 目标状态
     * @param runtimeStatus 运行时状态
     * @return 记录数量数值
     */
    private Long recordCount(IntegrationTaskDO task, String status, Integer runtimeStatus) {
        if (task.getDsDefinitionCode() == null && task.getPipelineId() != null) {
            var req = new EtlRecordPageReqVO();
            req.setPipelineId(task.getPipelineId());
            req.setPageSize(1);
            req.setRunStatus(runtimeStatus);
            return pipelineService.getRecordPage(req).getTotal();
        }
        return recordMapper.selectCount(new LambdaQueryWrapperX<IntegrationRecordDO>()
                .eq(IntegrationRecordDO::getTaskId, task.getId()).eq(IntegrationRecordDO::getStatus, status));
    }

    /**
     * 按上海时区预览 Quartz 调度周期。
     *
     * @param cron Cron
     * @return 按上海时区预览 Quartz 调度周期结果集合
     */
    public List<LocalDateTime> cronPreview(String cron) {
        validateCron(cron);
        return DolphinCronSchedule.parse(cron)
                .nextTimes(LocalDateTime.now(ZoneId.of("Asia/Shanghai")), ZoneId.of("Asia/Shanghai"), 5);
    }

    /**
     * 准备集成任务所需的配置和状态。
     *
     * @param req 请求参数
     * @return 集成任务
     */
    private IntegrationTaskDO prepare(SaveReq req) {
        if ("REALTIME".equals(req.getMode()) && !"FLINK".equals(req.getEngine())) {
            throw exception(INTEGRATION_CONFIG_INVALID, "实时任务必须使用 Flink 引擎");
        }
        if ("OFFLINE".equals(req.getMode())) {
            validateCron(req.getCronExpression());
        }
        var config = req.getEngineConfig();
        if (config != null && config.getJvmInitialMemory() != null && config.getJvmMaximumMemory() != null
                && config.getJvmInitialMemory() > config.getJvmMaximumMemory()) {
            throw exception(INTEGRATION_CONFIG_INVALID, "JVM 初始内存不能大于最大内存");
        }
        var owner = adminUserApi.getUser(req.getOwnerId()).getCheckedData();
        if (owner == null || !Integer.valueOf(0).equals(owner.getStatus())) {
            throw exception(INTEGRATION_CONFIG_INVALID, "负责人不存在或已停用");
        }
        categoryMapper.lockTree();
        var category = categoryMapper.selectById(req.getCategoryId());
        if (category == null || !Boolean.TRUE.equals(category.getValidFlag())) {
            throw exception(INTEGRATION_CONFIG_INVALID, "任务类目不存在或已停用");
        }
        var task = BeanUtils.toBean(req, IntegrationTaskDO.class);
        task.setName(req.getName().strip());
        task.setOwnerName(owner.getNickname());
        task.setContactNumber(owner.getMobile());
        task.setEngineConfigJson(JsonUtils.toJsonString(req.getEngineConfig()));
        return task;
    }


    /** 保存现有画布；草稿只持久化，正式保存通过原生编译后创建离线 DS 定义。 */
    @com.baomidou.lock.annotation.Lock4j(name = "dataforge:integration-task", keys = "#req.id", expire = 600000, acquireTimeout = 0)
    public TaskResp saveFlow(FlowReq req) {
        var task = requireTask(req.getId());
        requireEditable(task);
        if (!Objects.equals(task.getRevision(), req.getRevision())) throw exception(INTEGRATION_CONFLICT);
        Long definition = null; String bindingHash = null;
        IntegrationFlowCompiler.Publication publication = null;
        try {
            flowCompiler.validateEngineComponents(task, req.getFlowJson());
            if (!Boolean.TRUE.equals(req.getDraft())) {
                publication = flowCompiler.compileForPublication(task, req.getFlowJson());
                var steps = publication.steps();
                definition = dsRuntime.publish(task, steps); bindingHash = dsRuntime.fingerprint(task, steps);
            }
            changed(taskMapper.update(null, Wrappers.lambdaUpdate(IntegrationTaskDO.class)
                .eq(IntegrationTaskDO::getId, task.getId()).eq(IntegrationTaskDO::getRevision, req.getRevision())
                .eq(IntegrationTaskDO::getStatus, 0).eq(IntegrationTaskDO::getScheduleStatus, 0)
                .set(IntegrationTaskDO::getFlowJson, req.getFlowJson(), "typeHandler=cn.iocoder.yudao.module.dataforge.framework.mybatis.core.type.JsonbStringTypeHandler")
                .set(IntegrationTaskDO::getDsProjectCode, definition == null ? null : dsRuntime.projectCode())
                .set(IntegrationTaskDO::getDsDefinitionCode, definition).set(IntegrationTaskDO::getDsScheduleId, null)
                .set(IntegrationTaskDO::getDsBindingHash, bindingHash)
                .set(IntegrationTaskDO::getPipelineId, null).set(IntegrationTaskDO::getImportedSnapshot, false)
                .set(IntegrationTaskDO::getConfigurationStatus, definition == null ? "UNCONFIGURED" : "COMPLETE")
                .set(IntegrationTaskDO::getRevision, req.getRevision() + 1)
                .set(IntegrationTaskDO::getUpdater, String.valueOf(SecurityFrameworkUtils.getLoginUserId()))
                .set(IntegrationTaskDO::getUpdateTime, LocalDateTime.now())));
            flowCompiler.completePublication(publication);
        } catch (IllegalArgumentException invalid) {
            flowCompiler.abortPublication(publication); throw exception(INTEGRATION_CONFIG_INVALID, invalid.getMessage());
        } catch (RuntimeException failure) {
            flowCompiler.abortPublication(publication); throw failure;
        }
        return get(task.getId());
    }

    /** 检查和保存使用同一编译器，不以仅有连线或快照的流程声明可执行。 */
    public FlowCheckResp checkFlow(FlowReq req) {
        var task = requireTask(req.getId()); var result = new FlowCheckResp();
        try {
            if (!Objects.equals(task.getRevision(), req.getRevision())) throw new IllegalArgumentException("任务已更新，请刷新后重试");
            flowCompiler.compile(task, req.getFlowJson()); dsRuntime.requireEnvironment(task);
            result.setValid(true);
        } catch (IllegalArgumentException invalid) { result.setValid(false); result.setErrors(List.of(invalid.getMessage())); }
        return result;
    }

    /** 仅分析当前快照字段；不刷新、发布或运行 DS，也不写入任务记录。 */
    public cn.iocoder.yudao.framework.etl.core.schema.EtlSchemaResult schema(FlowReq req) {
        var task = requireTask(req.getId());
        if (!Objects.equals(task.getRevision(), req.getRevision())) throw exception(INTEGRATION_CONFLICT);
        try { return schemaService.resolve(task, req.getFlowJson()); }
        catch (IllegalArgumentException | IllegalStateException invalid) {
            var result = new cn.iocoder.yudao.framework.etl.core.schema.EtlSchemaResult(); result.getErrors().add(invalid.getMessage()); return result;
        }
    }

    private void requireCompiled(IntegrationTaskDO task) {
        try {
            var steps = flowCompiler.compileForExecution(task, task.getFlowJson()); dsRuntime.requireEnvironment(task);
            if (!Objects.equals(task.getDsBindingHash(), dsRuntime.fingerprint(task, steps)))
                throw new IllegalArgumentException("受管环境或数据源绑定已变化，请重新保存流程");
        }
        catch (IllegalArgumentException invalid) { throw exception(INTEGRATION_CONFIG_INVALID, invalid.getMessage()); }
    }

    private void refreshRuntime(IntegrationTaskDO task) {
        if (task.getDsDefinitionCode() != null) dsRuntime.refresh(task);
    }

    private TaskResp responseWithRuntime(IntegrationTaskDO task, Map<Long, String> names) {
        try {
            refreshRuntime(task);
            return response(task, names);
        } catch (IntegrationDsRuntime.MissingWorkflowDefinitionException missing) {
            var result = response(task, names);
            result.setRuntimeAvailable(false);
            result.setRuntimeMessage(missing.getMessage());
            result.setExecutable(false);
            return result;
        }
    }

    /**
     * 保存分类。
     *
     * @param req 请求参数
     * @param update 更新
     * @return 生成或保存的记录编号
     */
    @Transactional(rollbackFor = Exception.class)
    public Long saveCategory(CategorySaveReq req, boolean update) {
        categoryMapper.lockTree();
        if (update && (req.getId() == null || categoryMapper.selectById(req.getId()) == null)) {
            throw exception(INTEGRATION_CONFIG_INVALID, "类目不存在");
        }
        if (!update && req.getId() != null) {
            throw exception(INTEGRATION_CONFIG_INVALID, "新增类目不能指定编号");
        }
        if (req.getParentId() != 0 && categoryMapper.selectById(req.getParentId()) == null) {
            throw exception(INTEGRATION_CONFIG_INVALID, "父类目不存在");
        }
        if (update && categoryIds(req.getId()).contains(req.getParentId())) {
            throw exception(INTEGRATION_CONFIG_INVALID, "不能将类目移动到自身或下级类目");
        }
        var category = BeanUtils.toBean(req, IntegrationCategoryDO.class);
        category.setName(req.getName().strip());
        try {
            if (update) {
                changed(categoryMapper.updateById(category));
            } else {
                categoryMapper.insert(category);
            }
        } catch (DuplicateKeyException duplicate) {
            throw exception(INTEGRATION_CONFIG_INVALID, "同级类目名称已存在");
        }
        return category.getId();
    }

    /**
     * 删除无子类目且无任务引用的共享类目。
     *
     * @param id 记录编号
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteCategory(Long id) {
        categoryMapper.lockTree();
        if (categoryMapper.selectById(id) == null) {
            throw exception(INTEGRATION_CONFIG_INVALID, "类目不存在");
        }
        if (categoryMapper.selectCount(
                new LambdaQueryWrapperX<IntegrationCategoryDO>().eq(IntegrationCategoryDO::getParentId, id)) > 0
                || taskMapper.selectCount(
                new LambdaQueryWrapperX<IntegrationTaskDO>().eq(IntegrationTaskDO::getCategoryId, id)) > 0) {
            throw exception(INTEGRATION_CONFIG_INVALID, "类目包含子级或任务，不能删除");
        }
        changed(categoryMapper.deleteById(id));
    }

    /**
     * 新增集成任务记录。
     *
     * @param task 任务记录
     */
    private void insert(IntegrationTaskDO task) {
        try {
            taskMapper.insert(task);
        } catch (DuplicateKeyException duplicate) {
            throw exception(INTEGRATION_NAME_DUPLICATE);
        }
    }

    /**
     * 校验任务。
     *
     * @param id 记录编号
     * @return 集成任务
     */
    private IntegrationTaskDO requireTask(Long id) {
        var task = taskMapper.selectById(id);
        if (task == null) {
            throw exception(INTEGRATION_TASK_NOT_EXISTS);
        }
        return task;
    }

    /**
     * 发布变更集成任务。
     *
     * @param count 数量
     */
    private static void changed(int count) {
        if (count != 1) {
            throw exception(INTEGRATION_CONFLICT);
        }
    }

    /**
     * 校验可编辑。
     *
     * @param task 任务记录
     */
    private void requireEditable(IntegrationTaskDO task) {
        if (task.getDsDefinitionCode() != null) {
            if (recordMapper.selectCount(Wrappers.lambdaQuery(IntegrationRecordDO.class).eq(IntegrationRecordDO::getTaskId, task.getId())
                    .eq(IntegrationRecordDO::getStatus, "RUNNING").isNotNull(IntegrationRecordDO::getDsProjectCode)) > 0)
                throw exception(INTEGRATION_CONFIG_INVALID, "请先停止并确认 DS 实例退出，再修改任务");
        }
        if (!Integer.valueOf(0).equals(task.getStatus()) || !Integer.valueOf(0).equals(task.getScheduleStatus())) {
            throw exception(INTEGRATION_STATE_INVALID);
        }
    }

    /**
     * 校验可执行。
     *
     * @param task 任务记录
     */
    private static void requireExecutable(IntegrationTaskDO task) {
        if ((task.getDsDefinitionCode() == null && task.getPipelineId() == null) || !"COMPLETE".equals(task.getConfigurationStatus())) {
            throw exception(INTEGRATION_FLOW_NOT_READY);
        }
    }

    /**
     * 校验Cron。
     *
     * @param cron Cron
     */
    private static void validateCron(String cron) {
        if (!DolphinCronSchedule.isValidExpression(cron)) {
            throw exception(INTEGRATION_CONFIG_INVALID, "调度周期不是有效的 Quartz Cron 表达式");
        }
    }

    /**
     * 获取集成任务的分类名称。
     *
     * @return 处理后的键值映射
     */
    private Map<Long, String> categoryNames() {
        var result = new HashMap<Long, String>();
        categoryMapper.selectList().forEach(category -> result.put(category.getId(), category.getName()));
        return result;
    }

    /**
     * 获取集成任务的分类编号集合。
     *
     * @param id 记录编号
     * @return 集成任务的分类编号集合结果集合
     */
    private Collection<Long> categoryIds(Long id) {
        if (id == null) {
            return null;
        }
        var categories = categoryMapper.selectList();
        var ids = new HashSet<Long>();
        ids.add(id);
        boolean changed;
        do {
            changed = false;
            for (var category : categories) {
                if (ids.contains(category.getParentId())) {
                    changed |= ids.add(category.getId());
                }
            }
        } while (changed);
        return ids;
    }

    /**
     * 组装集成任务响应数据。
     *
     * @param task 任务记录
     * @param names 名称
     * @return 任务
     */
    private TaskResp response(IntegrationTaskDO task, Map<Long, String> names) {
        var result = BeanUtils.toBean(task, TaskResp.class);
        result.setCategoryName(names.get(task.getCategoryId()));
        result.setEngineConfig(StrUtil.isBlank(task.getEngineConfigJson()) ? new EngineConfig()
                : JsonUtils.parseObject(task.getEngineConfigJson(), EngineConfig.class));
        result.setExecutable(task.getDsDefinitionCode() != null && "COMPLETE".equals(task.getConfigurationStatus()));
        result.setActiveRunning(task.getDsDefinitionCode() != null && recordMapper.selectCount(
                Wrappers.lambdaQuery(IntegrationRecordDO.class).eq(IntegrationRecordDO::getTaskId, task.getId())
                        .eq(IntegrationRecordDO::getStatus, "RUNNING").isNotNull(IntegrationRecordDO::getDsProjectCode)) > 0);
        return result;
    }

    /**
     * 获取集成任务的运行时记录。
     *
     * @param taskId 任务编号
     * @param source 来源数据
     * @return 记录
     */
    private static RecordResp runtimeRecord(Long taskId, EtlRecordRespVO source) {
        var record = new RecordResp();
        record.setId(source.getId());
        record.setTaskId(taskId);
        record.setStatus(switch (source.getRunStatus()) {
            case 1, 2 -> "RUNNING";
            case 3 -> "SUCCESS";
            case 4 -> "FAILURE";
            default -> "IDLE";
        });
        record.setTriggerType(Integer.valueOf(2).equals(source.getExecuteType()) ? "SCHEDULE" : "MANUAL");
        record.setStartTime(source.getStartTime());
        record.setEndTime(source.getEndTime());
        record.setMessage(source.getLog());
        if (source.getStartTime() != null && source.getEndTime() != null) {
            record.setDuration(Duration.between(source.getStartTime(), source.getEndTime()).getSeconds());
        }
        return record;
    }
}
