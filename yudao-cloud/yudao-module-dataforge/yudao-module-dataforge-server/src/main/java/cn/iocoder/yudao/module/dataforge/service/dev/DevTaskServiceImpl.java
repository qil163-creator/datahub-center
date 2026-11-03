package cn.iocoder.yudao.module.dataforge.service.dev;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.service.IMetaDataByJdbcService;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.debug.DevTableColumnRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.task.DevTaskPageReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.task.DevTaskSaveReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.task.DevTaskScheduleReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevDirDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevRecordDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevTaskDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevTaskVersionDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.dev.DevDirMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.dev.DevTaskMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.dev.DevTaskVersionMapper;
import cn.iocoder.yudao.module.dataforge.service.access.AccessDatabaseResolver;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.Resource;
import cn.iocoder.yudao.framework.dolphinscheduler.core.DolphinCronSchedule;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.HashSet;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.task.DevTaskRunReqVO;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import org.springframework.transaction.support.TransactionTemplate;


import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;

/**
 * 数据开发-开发任务 Service 实现类
 *
 * @author Li
 */
@Service
@Validated
public class DevTaskServiceImpl implements DevTaskService {

    @Resource
    private cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime dbSwitchRuntime;

    @Resource
    private DevNativeRuntimeSupport nativeRuntimeSupport;

    @Resource
    private DevTaskMapper taskMapper;

    @Resource
    private DevTaskVersionMapper versionMapper;

    @Resource
    private DevScheduleCoordinator scheduleCoordinator;

    @Resource
    private cn.iocoder.yudao.module.dataforge.dal.mysql.dev.DevScheduleMapper scheduleMapper;

    @Resource
    private DevDirMapper dirMapper;

    @Resource
    private DevDirService dirService;

    @Resource
    private DevDsSubmitter dsSubmitter;

    @Resource
    private AccessDatabaseResolver databaseResolver;

    @Resource
    private ExecutionAccessService executionAccessService;

    @Resource
    private cn.iocoder.yudao.module.dataforge.service.database.DatabaseService databaseService;

    @Resource
    private TransactionTemplate transactionTemplate;

    @Resource
    private cn.iocoder.yudao.module.dataforge.service.quality.QualityGateService qualityGateService;

    @Resource
    private cn.iocoder.yudao.module.system.api.user.AdminUserApi adminUserApi;

    @Resource
    private cn.iocoder.yudao.module.dataforge.dal.mysql.dev.DevRecordMapper recordMapper;

    // ==================== 任务 CRUD ====================

    /**
     * 创建开发任务。
     *
     * @param req 请求参数
     * @return 生成或保存的记录编号
     */
    @Override
    public Long createTask(DevTaskSaveReqVO req) {
        validateTaskInput(req);
        validateTableLineage(req, null);
        validateNameUnique(null, req.getName());
        DevTaskDO task = buildSavedTask(req, null);
        DevTaskSnapshot snapshot = hasCompleteDefinition(task) ? prepareExecutionSnapshot(task) : null;
        return transactionTemplate.execute(status -> {
            dirService.lockAndValidateDirExists(task.getDirId());
            taskMapper.insert(task);
            if (snapshot != null) {
                saveCurrentVersion(task, snapshot, req.getVersionRemark(), null);
            }
            return task.getId();
        });
    }

    /**
     * 保存开发任务并立即生成当前运行版本。
     *
     * @param req 请求参数
     */
    @Override
    public void updateTask(DevTaskSaveReqVO req) {
        DevTaskDO old = validateTaskExists(req.getId());
        if (req.getExpectedRevision() == null || !Objects.equals(old.getRevision(), req.getExpectedRevision())) {
            throw exception(DEV_TASK_REVISION_CONFLICT);
        }
        validateTaskInput(req);
        validateTableLineage(req, old);
        validateNameUnique(old.getId(), req.getName());
        DevTaskDO task = buildSavedTask(req, old);
        // 已有可运行版本不能被不完整定义替换；基本信息新建阶段不生成虚假运行版本。
        boolean complete = hasCompleteDefinition(task);
        if (!complete && old.getCurrentVersionId() != null) {
            throw exception(DEV_TASK_SAVE_REQUIRED);
        }
        if (!complete) {
            task.setStatus(DevTaskDO.STATUS_OFFLINE);
            task.setScheduleStatus(DevTaskDO.SCHEDULE_OFF);
            task.setNextRunTime(null);
        }
        DevTaskSnapshot snapshot = complete ? prepareExecutionSnapshot(task) : null;
        if (snapshot != null && DevTaskDO.SCHEDULE_ON.equals(task.getScheduleStatus())) {
            scheduleCoordinator.validateTarget(snapshot);
        }
        transactionTemplate.executeWithoutResult(status -> {
            dirService.lockAndValidateDirExists(task.getDirId());
            if (snapshot == null) {
                updateDefinition(task, req.getExpectedRevision());
            } else {
                saveCurrentVersion(task, snapshot, req.getVersionRemark(), req.getExpectedRevision());
            }
        });
    }

    /**
     * 构造已保存任务。
     *
     * @param req 请求参数
     * @param old 历史
     * @return 开发任务
     */
    private DevTaskDO buildSavedTask(DevTaskSaveReqVO req, DevTaskDO old) {
        DevTaskDO task = old == null ? new DevTaskDO() : BeanUtils.toBean(old, DevTaskDO.class);
        task.setName(req.getName());
        task.setDirId(req.getDirId() == null ? DevDirDO.PARENT_ROOT : req.getDirId());
        task.setTaskType(StrUtil.blankToDefault(req.getTaskType(), DevTaskDO.TYPE_SQL));
        task.setContent(req.getContent());
        task.setDescription(req.getDescription());
        task.setDatabaseId(req.getDatabaseId());
        task.setDatasourceName(null);
        task.setDatabaseName(null);
        task.setDbType(req.getDbType());
        task.setDsDatasourceId(null);
        task.setDsDatasourceType(null);
        task.setProgramConfigJson(
                req.getProgramConfig() == null ? null : JsonUtils.toJsonString(req.getProgramConfig()));
        task.setQualityTaskId(req.getQualityTaskId());
        task.setCronExpression(StrUtil.trimToNull(req.getCronExpression()));
        task.setDependencyTaskIdsJson(
                JsonUtils.toJsonString(req.getDependencyTaskIds() == null ? List.of() : req.getDependencyTaskIds()));
        task.setTimeoutSeconds(req.getTimeoutSeconds() == null
                ? (old == null ? 300 : old.getTimeoutSeconds()) : req.getTimeoutSeconds());
        task.setMaxRows(req.getMaxRows() == null
                ? (old == null ? 1000 : old.getMaxRows()) : req.getMaxRows());
        Long actor = SecurityFrameworkUtils.getLoginUserId();
        if (req.getExecutionUserId() != null && !Objects.equals(req.getExecutionUserId(), actor)) {
            throw exception(OPERATION_PERMISSION_DENIED);
        }
        // 保存新内容必须以当前操作人重新授权，不能继承历史版本中的特权执行身份。
        task.setExecutionUserId(actor);
        String previousExecution = old == null ? null : old.getExecutionConfigJson();
        fillTaskManagementInfo(task, req);
        if (req.getExecutionConfig() == null && previousExecution != null) {
            task.setExecutionConfigJson(previousExecution);
        }
        fillDatasourceInfo(task);
        task.setRevision(old == null ? 1 : old.getRevision() + 1);
        if (old == null) {
            task.setStatus(DevTaskDO.STATUS_OFFLINE);
            task.setScheduleStatus(DevTaskDO.SCHEDULE_OFF);
            task.setLatestVersion(0);
            task.setCurrentVersionId(null);
        }
        return task;
    }

    /**
     * 判断是否存在完整定义条件。
     *
     * @param task 任务记录
     * @return 判断结果
     */
    private boolean hasCompleteDefinition(DevTaskDO task) {
        var type = DevTaskDefinition.requireType(task.getTaskType());
        if ((!DevNativeRuntimeSupport.isNative(task) || "SQL".equals(type.getProgramType())) && StrUtil.isBlank(
                task.getContent())) {
            return false;
        }
        if (DevNativeRuntimeSupport.isNative(task)) {
            var config = DevTaskDefinition.readConfig(task.getProgramConfigJson());
            return config != null && StrUtil.isNotBlank(config.getRuntimeProfileCode())
                    && ("SQL".equals(type.getProgramType()) || config.getArtifactFileId() != null);
        }
        return DevTaskDO.TYPE_SHELL.equals(task.getTaskType()) || task.getDatabaseId() != null;
    }

    /** CAS 同时更新全部当前定义，显式清空可空属性；调用方保证已进入本地事务。 */
    private void updateDefinition(DevTaskDO task, Integer expectedRevision) {
        var update = Wrappers.lambdaUpdate(DevTaskDO.class)
                .set(DevTaskDO::getName, task.getName()).set(DevTaskDO::getDirId, task.getDirId())
                .set(DevTaskDO::getOwnerId, task.getOwnerId()).set(DevTaskDO::getOwnerName, task.getOwnerName())
                .set(DevTaskDO::getContactNumber, task.getContactNumber())
                .set(DevTaskDO::getScheduler, task.getScheduler())
                .set(DevTaskDO::getContent, task.getContent()).set(DevTaskDO::getTaskType, task.getTaskType())
                .set(DevTaskDO::getDatabaseId, task.getDatabaseId())
                .set(DevTaskDO::getDatasourceName, task.getDatasourceName())
                .set(DevTaskDO::getDatabaseName, task.getDatabaseName()).set(DevTaskDO::getDbType, task.getDbType())
                .set(DevTaskDO::getDsDatasourceId, null).set(DevTaskDO::getDsDatasourceType, null)
                .set(DevTaskDO::getExecutionConfigJson, task.getExecutionConfigJson(),
                        "typeHandler=cn.iocoder.yudao.module.dataforge.framework.mybatis.core.type.JsonbStringTypeHandler")
                .set(DevTaskDO::getProgramConfigJson, task.getProgramConfigJson(),
                        "typeHandler=cn.iocoder.yudao.module.dataforge.framework.mybatis.core.type.JsonbStringTypeHandler")
                .set(DevTaskDO::getDependencyTaskIdsJson, task.getDependencyTaskIdsJson(),
                        "typeHandler=cn.iocoder.yudao.module.dataforge.framework.mybatis.core.type.JsonbStringTypeHandler")
                .set(DevTaskDO::getQualityTaskId, task.getQualityTaskId())
                .set(DevTaskDO::getCronExpression, task.getCronExpression())
                .set(DevTaskDO::getDescription, task.getDescription())
                .set(DevTaskDO::getTimeoutSeconds, task.getTimeoutSeconds())
                .set(DevTaskDO::getMaxRows, task.getMaxRows())
                .set(DevTaskDO::getExecutionUserId, task.getExecutionUserId())
                .set(DevTaskDO::getCurrentVersionId, task.getCurrentVersionId())
                .set(DevTaskDO::getLatestVersion, task.getLatestVersion())
                .set(DevTaskDO::getStatus, task.getStatus()).set(DevTaskDO::getScheduleStatus, task.getScheduleStatus())
                .set(DevTaskDO::getNextRunTime, task.getNextRunTime())
                .set(DevTaskDO::getLegacyCronExpression, task.getLegacyCronExpression())
                .set(DevTaskDO::getRevision, task.getRevision()).eq(DevTaskDO::getId, task.getId());
        if (expectedRevision != null) {
            update.eq(DevTaskDO::getRevision, expectedRevision);
        }
        if (taskMapper.update(null, update) != 1) {
            throw exception(DEV_TASK_REVISION_CONFLICT);
        }
    }

    /**
     * 校验任务输入。
     *
     * @param req 请求参数
     */
    private void validateTaskInput(DevTaskSaveReqVO req) {
        dirService.validateDirExists(req.getDirId());
        String type = StrUtil.blankToDefault(req.getTaskType(), DevTaskDO.TYPE_SQL);
        DevTaskDefinition.validateConfig(DevTaskDefinition.requireType(type), req.getProgramConfig());
        DevTaskDefinition.validateExecutionConfig(req.getExecutionConfig());
        if (req.getScheduler() != null && !"DOLPHIN_SCHEDULER".equals(req.getScheduler())) {
            throw exception(DEV_TASK_DEPENDENCY_INVALID, "当前项目调度控制面仅支持 DolphinScheduler");
        }
        if (StrUtil.isNotBlank(req.getDbType())) {
            try {
                cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.valueOf(req.getDbType());
            } catch (IllegalArgumentException invalid) {
                throw exception(DEV_TASK_DEPENDENCY_INVALID, "数据连接类型无效");
            }
        }
        if (StrUtil.isNotBlank(req.getCronExpression()) && !DolphinCronSchedule.isValidExpression(
                req.getCronExpression())) {
            throw exception(DEV_TASK_CRON_INVALID);
        }
        boolean streaming = req.getProgramConfig() != null && "STREAMING".equals(
                req.getProgramConfig().getWorkloadMode());
        if (req.getTimeoutSeconds() != null && (req.getTimeoutSeconds() < (streaming ? 0 : 1) || req.getTimeoutSeconds() > 86400)) {
            throw exception(DEV_TASK_DEPENDENCY_INVALID, "批任务超时为 1 至 86400 秒；流任务可为 0 表示持续运行");
        }
        if (req.getProgramConfig() != null && (req.getDatabaseId() != null || req.getDsDatasourceType() != null)) {
            throw exception(DEV_PROGRAM_CONFIG_INVALID, "原生程序不能绑定 JDBC 数据源");
        }
        if (req.getDatabaseId() != null) {
            executionAccessService.requireDatabase(req.getDatabaseId());
        }
        // 外部调度器数据源编号可绕过平台数据源及其权限校验，必须通过平台数据源绑定。
        if (req.getDsDatasourceId() != null) {
            throw exception(DEV_TASK_DEPENDENCY_INVALID, "请绑定平台数据源，禁止直接指定外部调度器数据源编号");
        }
    }

    /** 血缘只记录受管真实表关系；元数据读取复用 DatabaseService 的 DB Switch 通道，不执行任务脚本。 */
    private void validateTableLineage(DevTaskSaveReqVO req, DevTaskDO current) {
        if (req.getExecutionConfig() == null) {
            return;
        }
        var lineage = req.getExecutionConfig().getTableLineage();
        var previous = current == null ? null
                : DevTaskDefinition.readExecutionConfig(current.getExecutionConfigJson()).getTableLineage();
        boolean changed = !Objects.equals(lineage, previous)
                && !(emptyTableLineage(lineage) && emptyTableLineage(previous));
        if (changed && current != null && DevTaskDO.STATUS_ONLINE.equals(current.getStatus())) {
            throw exception(DEV_TASK_ONLINE_CANNOT_MODIFY);
        }
        if (emptyTableLineage(lineage)) {
            return;
        }
        var checkedDatabases = new java.util.HashSet<Long>();
        var checkedTables = new java.util.HashMap<String,
                cn.iocoder.yudao.module.dataforge.controller.admin.database.vo.DatabaseTableMetaRespVO>();
        java.util.stream.Stream.concat(lineage.getUpstream().stream(), lineage.getDownstream().stream())
                .forEach(reference -> {
                    if (checkedDatabases.add(reference.getDatabaseId())) {
                        executionAccessService.requireDatabase(reference.getDatabaseId());
                    }
                    String key = reference.getDatabaseId() + "\u0000" + reference.getTableName();
                    var metadata = checkedTables.computeIfAbsent(key,
                            ignored -> databaseService.getTableMeta(reference.getDatabaseId(), reference.getTableName()));
                    if (metadata == null || StrUtil.isBlank(metadata.getTableName())
                            || (StrUtil.isNotBlank(reference.getSchemaName())
                            && !Objects.equals(reference.getSchemaName(), metadata.getSchemaName()))) {
                        throw exception(DEV_TASK_DEPENDENCY_INVALID, "血缘数据表不存在或不属于数据连接配置的 Schema");
                    }
                    reference.setTableName(metadata.getTableName());
                    reference.setSchemaName(metadata.getSchemaName());
                });
        DevTaskDefinition.validateExecutionConfig(req.getExecutionConfig());
        if (current != null && DevTaskDO.STATUS_ONLINE.equals(current.getStatus())
                && !Objects.equals(lineage, previous)) {
            throw exception(DEV_TASK_ONLINE_CANNOT_MODIFY);
        }
    }

    private static boolean emptyTableLineage(
            cn.iocoder.yudao.module.dataforge.model.dev.DevExecutionConfig.TableLineage lineage) {
        return lineage == null || (lineage.getUpstream().isEmpty() && lineage.getDownstream().isEmpty());
    }

    /**
     * 删除开发任务。
     *
     * @param id 记录编号
     */
    @Override
    public void deleteTask(Long id) {
        DevTaskDO task = validateTaskExists(id);
        if (DevTaskDO.STATUS_ONLINE.equals(task.getStatus()) || dsSubmitter.isRunning(id)) {
            throw exception(DEV_TASK_ONLINE_CANNOT_MODIFY);
        }
        transactionTemplate.executeWithoutResult(status -> {
            scheduleCoordinator.retire(id);
            if (taskMapper.delete(Wrappers.<DevTaskDO>lambdaQuery().eq(DevTaskDO::getId, id)
                    .eq(DevTaskDO::getRevision, task.getRevision())
                    .eq(DevTaskDO::getStatus, DevTaskDO.STATUS_OFFLINE)) != 1) {
                throw exception(DEV_TASK_REVISION_CONFLICT);
            }
        });
    }

    /**
     * 获得开发任务。
     *
     * @param id 记录编号
     * @return 开发任务
     */
    @Override
    public DevTaskDO getTask(Long id) {
        return validateTaskExists(id);
    }

    /**
     * 获得开发任务分页。
     *
     * @param pageReqVO 分页查询条件
     * @return 开发任务分页结果
     */
    @Override
    public PageResult<DevTaskDO> getTaskPage(DevTaskPageReqVO pageReqVO) {
        return taskMapper.selectPage(pageReqVO, resolveDirIds(pageReqVO.getDirId()));
    }

    /**
     * 补充任务管理信息。
     *
     * @param task 任务记录
     * @param req 请求参数
     */
    private void fillTaskManagementInfo(DevTaskDO task, DevTaskSaveReqVO req) {
        task.setScheduler("DOLPHIN_SCHEDULER");
        task.setExecutionConfigJson(JsonUtils.toJsonString(req.getExecutionConfig() == null
                ? new cn.iocoder.yudao.module.dataforge.model.dev.DevExecutionConfig() : req.getExecutionConfig()));
        if (req.getOwnerId() != null) {
            var user = adminUserApi.validateUser(req.getOwnerId());
            if (user == null) {
                throw exception(DEV_TASK_DEPENDENCY_INVALID, "任务负责人不存在或已禁用");
            }
            task.setOwnerId(user.getId());
            task.setOwnerName(user.getNickname());
            task.setContactNumber(user.getMobile());
        }
    }

    /** 与任务查询相同的当前平台工作区范围；所有聚合在数据库完成。 */
    @Override
    public cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.task.DevTaskStatisticsRespVO getStatistics(
            String workloadMode) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime start = now.toLocalDate().atStartOfDay();
        LocalDateTime end = start.plusDays(1);
        var result = new cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.task.DevTaskStatisticsRespVO();
        result.setRunningTasks(recordMapper.countActiveTasks(workloadMode));
        long total = recordMapper.countStartedBetween(start, end, null, workloadMode);
        long failed = recordMapper.countStartedBetween(start, end, DevRecordDO.STATUS_FAILED, workloadMode);
        long succeeded = recordMapper.countStartedBetween(start, end, DevRecordDO.STATUS_SUCCESS, workloadMode);
        result.setTodayExecutions(total);
        result.setTodayFailed(failed);
        result.setTodaySuccessRate(total == 0 ? 0d : Math.round(succeeded * 10000d / total) / 100d);
        result.setRefreshedAt(now);
        return result;
    }

    /**
     * 预览 Cron 接下来五次运行时间。
     *
     * @param cronExpression Cron表达式
     * @return  Cron 接下来五次运行时间预览结果集合
     */
    @Override
    public List<LocalDateTime> previewCron(String cronExpression) {
        if (StrUtil.isBlank(cronExpression) || cronExpression.length() > 100
                || !DolphinCronSchedule.isValidExpression(cronExpression)) {
            throw exception(DEV_TASK_CRON_INVALID);
        }
        return DolphinCronSchedule.parse(cronExpression)
                .nextTimes(LocalDateTime.now(scheduleCoordinator.zone()), scheduleCoordinator.zone(), 5);
    }

    /**
     * 按目录获得开发任务列表（资源树用，含子目录）。
     *
     * @param dirId 目录编号
     * @return 按目录获得开发任务列表（资源树用，含子目录）结果集合
     */
    @Override
    public List<DevTaskDO> getTaskListByDir(Long dirId, String workloadMode) {
        var query = new cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX<DevTaskDO>()
                .inIfPresent(DevTaskDO::getDirId, dirId == null ? null : resolveDirIds(dirId))
                .orderByAsc(DevTaskDO::getId);
        if (workloadMode != null) {
            query.apply("(" + DevTaskMapper.WORKLOAD_MODE_SQL + ") = {0}", workloadMode);
        }
        return taskMapper.selectList(query);
    }

    // ==================== 状态与调度 ====================

    /**
     * 更新已保存任务的启用状态。
     *
     * @param id 记录编号
     * @param status 目标状态
     */
    @Override
    public void updateTaskStatus(Long id, Integer status) {
        DevTaskDO task = validateTaskExists(id);
        if (!DevTaskDO.STATUS_OFFLINE.equals(status) && !DevTaskDO.STATUS_ONLINE.equals(status)) {
            throw exception(DEV_TASK_DEPENDENCY_INVALID, "任务状态必须为启用或停用");
        }
        if (DevTaskDO.STATUS_ONLINE.equals(status)) {
            DevTaskSnapshot snapshot = DevTaskSnapshot.read(requireCurrentVersion(task).getSnapshotJson());
            requireInteractiveExecutionAccess(snapshot.getTask());
            executionAccessService.requireUserPermission(snapshot.getTask().getExecutionUserId(),
                    ExecutionAccessService.PERMISSION_DEV_TASK_RUN);
            if (DevNativeRuntimeSupport.isNative(snapshot.getTask())) {
                nativeRuntimeSupport.requireActor(snapshot.getTask(), SecurityFrameworkUtils.getLoginUserId());
                nativeRuntimeSupport.requireActor(snapshot.getTask(), snapshot.getTask().getExecutionUserId());
                nativeRuntimeSupport.requireFrozen(snapshot);
            }
        }
        if (status != null && DevTaskDO.STATUS_OFFLINE.equals(status)
                && task.getScheduleStatus() != null
                && DevTaskDO.SCHEDULE_ON.equals(task.getScheduleStatus())) {
            throw exception(DEV_TASK_SCHEDULE_ON);
        }
        if (taskMapper.update(null, Wrappers.lambdaUpdate(DevTaskDO.class)
                .set(DevTaskDO::getStatus, status).set(DevTaskDO::getRevision, task.getRevision() + 1)
                .eq(DevTaskDO::getId, id).eq(DevTaskDO::getRevision, task.getRevision())) != 1) {
            throw exception(DEV_TASK_REVISION_CONFLICT);
        }
    }

    /**
     * 更新任务调度设置。
     *
     * @param reqVO 请求参数
     */
    @Override
    public void updateTaskSchedule(DevTaskScheduleReqVO reqVO) {
        DevTaskDO task = validateTaskExists(reqVO.getId());
        if (task.getCurrentVersionId() == null) {
            throw exception(DEV_TASK_SAVE_REQUIRED);
        }
        boolean enable = reqVO.getScheduleStatus() != null
                && DevTaskDO.SCHEDULE_ON.equals(reqVO.getScheduleStatus());
        DevTaskSnapshot current = DevTaskSnapshot.read(requireCurrentVersion(task).getSnapshotJson());
        if (enable && DevNativeRuntimeSupport.isNative(current.getTask())) {
            nativeRuntimeSupport.requireActor(current.getTask(), SecurityFrameworkUtils.getLoginUserId());
            nativeRuntimeSupport.requireActor(current.getTask(), current.getTask().getExecutionUserId());
            if (enable) {
                executionAccessService.requireUserPermission(current.getTask().getExecutionUserId(),
                        ExecutionAccessService.PERMISSION_DEV_TASK_RUN);
                nativeRuntimeSupport.requireFrozen(current);
            }
        }
        String cron = StrUtil.trimToNull(current.getTask().getCronExpression());
        if (StrUtil.isNotBlank(reqVO.getCronExpression()) && !Objects.equals(cron,
                StrUtil.trim(reqVO.getCronExpression()))) {
            throw exception(DEV_TASK_DEPENDENCY_INVALID, "调度只能启停当前保存的 Cron；修改表达式请先保存任务");
        }
        if (enable) {
            requireInteractiveExecutionAccess(current.getTask());
            executionAccessService.requireUserPermission(current.getTask().getExecutionUserId(),
                    ExecutionAccessService.PERMISSION_DEV_TASK_RUN);
            if (!"DOLPHIN_SCHEDULER".equals(current.getTask().getScheduler()) || !Objects.equals(cron,
                    task.getCronExpression())) {
                throw exception(DEV_TASK_DEPENDENCY_INVALID, "旧周期需要先按 DS Quartz 规则重新配置并保存任务");
            }
            if (task.getStatus() == null || !DevTaskDO.STATUS_ONLINE.equals(task.getStatus())) {
                throw exception(DEV_TASK_NOT_ONLINE);
            }
            if (cron == null) {
                throw exception(DEV_TASK_CRON_EMPTY);
            }
        }
        if (enable && cron != null && !DolphinCronSchedule.isValidExpression(cron)) {
            throw exception(DEV_TASK_CRON_INVALID);
        }
        if (enable) {
            scheduleCoordinator.validateTarget(current);
        }
        transactionTemplate.executeWithoutResult(status -> {
            int changed = taskMapper.update(null, Wrappers.lambdaUpdate(DevTaskDO.class)
                    .set(DevTaskDO::getScheduleStatus, enable ? DevTaskDO.SCHEDULE_ON : DevTaskDO.SCHEDULE_OFF)
                    .set(DevTaskDO::getNextRunTime, null).set(DevTaskDO::getRevision, task.getRevision() + 1)
                    .eq(DevTaskDO::getId, reqVO.getId()).eq(DevTaskDO::getRevision, task.getRevision())
                    .eq(DevTaskDO::getCurrentVersionId, task.getCurrentVersionId()));
            if (changed != 1) {
                throw exception(DEV_TASK_REVISION_CONFLICT);
            }
            task.setScheduleStatus(enable ? DevTaskDO.SCHEDULE_ON : DevTaskDO.SCHEDULE_OFF);
            task.setRevision(task.getRevision() + 1);
            scheduleCoordinator.saveIntent(task, current);
        });
    }

    // ==================== 运行与保存版本 ====================

    /** 权限、外部数据源及受管运行环境预检必须在本地写事务外完成。 */
    private DevTaskSnapshot prepareExecutionSnapshot(DevTaskDO task) {
        if (DevNativeRuntimeSupport.isNative(task)) {
            nativeRuntimeSupport.resolve(task);
        } else {
            DevTaskDefinition.requireExecutable(task);
        }
        Long actor = task.getExecutionUserId() != null ? task.getExecutionUserId() : SecurityFrameworkUtils.getLoginUserId();
        if (actor == null) {
            throw exception(DEV_EXECUTION_IDENTITY_REQUIRED);
        }
        // 执行身份会被持久化到执行快照并用于后续无登录调度，必须持有任务运行权限
        executionAccessService.requireUserPermission(actor, ExecutionAccessService.PERMISSION_DEV_TASK_RUN);
        if (task.getDsDatasourceId() != null) {
            throw exception(DEV_TASK_DEPENDENCY_INVALID,
                    "旧任务含外部调度器数据源编号，请重新选择平台数据源并重新保存任务");
        }
        boolean nativeTask = DevNativeRuntimeSupport.isNative(task);
        if (nativeTask) {
            nativeRuntimeSupport.requireActor(task, SecurityFrameworkUtils.getLoginUserId());
            nativeRuntimeSupport.requireActor(task, actor);
        }
        if ((!nativeTask || "SQL".equals(DevTaskDefinition.requireType(task.getTaskType()).getProgramType()))
                && StrUtil.isBlank(task.getContent())) {
            throw exception(DEV_TASK_CONTENT_EMPTY);
        }
        qualityGateService.validate(task.getQualityTaskId(), actor);
        if (!nativeTask && !DevTaskDO.TYPE_SHELL.equals(task.getTaskType())) {
            if (task.getDatabaseId() == null) {
                throw exception(DEV_TASK_DATABASE_EMPTY);
            }
            executionAccessService.requireDatabase(task.getDatabaseId());
        }
        if (StrUtil.isNotBlank(task.getCronExpression()) && !DolphinCronSchedule.isValidExpression(
                task.getCronExpression())) {
            throw exception(DEV_TASK_CRON_INVALID);
        }
        if (DevTaskDO.SCHEDULE_ON.equals(task.getScheduleStatus()) && StrUtil.isBlank(task.getCronExpression())) {
            throw exception(DEV_TASK_CRON_EMPTY);
        }
        DevDsSubmitter.ExecutionTarget target = dsSubmitter.executionTarget(task);
        DevTaskSnapshot snapshot = new DevTaskSnapshot();
        snapshot.setWorkerGroup(target.workerGroup());
        snapshot.setTenantCode(target.tenantCode());
        snapshot.setManagedEnvironmentCode(nativeTask ? null : dsSubmitter.configuredEnvironmentCode());
        task.setExecutionUserId(actor);
        dsSubmitter.validateForSave(task);
        snapshot.setTask(task);
        if (nativeTask) {
            nativeRuntimeSupport.freeze(snapshot);
        }
        List<Long> dependencies = dependencyIds(task);
        for (Long dependencyId : dependencies) {
            DevTaskDO dependency = validateTaskExists(dependencyId);
            if (Objects.equals(task.getId(), dependencyId) || dependency.getCurrentVersionId() == null) {
                throw exception(DEV_TASK_DEPENDENCY_INVALID, "上游必须是已保存可运行的其他任务");
            }
            assertAcyclic(task.getId(), dependency.getCurrentVersionId(), new HashSet<>());
            snapshot.getDependencyVersions().put(dependencyId, dependency.getCurrentVersionId());
        }
        return snapshot;
    }

    /** 保存定义、不可变版本和当前指针是同一事务，不存在独立发布阶段。 */
    private void saveCurrentVersion(DevTaskDO task, DevTaskSnapshot snapshot, String remark, Integer expectedRevision) {
        int versionNo = (task.getLatestVersion() == null ? 0 : task.getLatestVersion()) + 1;
        DevTaskVersionDO version = DevTaskVersionDO.builder().taskId(task.getId()).version(versionNo)
                .content(task.getContent()).snapshotJson(snapshot.write())
                .remark(StrUtil.blankToDefault(remark, "保存任务")).build();
        versionMapper.insert(version);
        task.setCurrentVersionId(version.getId());
        task.setLatestVersion(versionNo);
        task.setStatus(DevTaskDO.STATUS_ONLINE);
        task.setNextRunTime(null);
        updateDefinition(task, expectedRevision);
        scheduleCoordinator.saveIntent(task, snapshot);
    }

    /**
     * 确认无环。
     *
     * @param taskId 任务编号
     * @param versionId 版本编号
     * @param visited 已访问
     */
    private void assertAcyclic(Long taskId, Long versionId, Set<Long> visited) {
        if (!visited.add(versionId)) {
            return;
        }
        if (visited.size() > 500) {
            throw exception(DEV_TASK_DEPENDENCY_INVALID, "依赖图超过 500 个版本");
        }
        DevTaskVersionDO version = versionMapper.selectById(versionId);
        if (version == null || StrUtil.isBlank(version.getSnapshotJson())) {
            throw exception(DEV_TASK_DEPENDENCY_INVALID, "上游版本缺少完整快照，请重新保存任务");
        }
        DevTaskSnapshot snapshot = DevTaskSnapshot.read(version.getSnapshotJson());
        if (Objects.equals(taskId, snapshot.getTask().getId())) {
            throw exception(DEV_TASK_DEPENDENCY_INVALID, "存在循环依赖");
        }
        for (Long upstream : snapshot.getDependencyVersions().values()) {
            assertAcyclic(taskId, upstream, visited);
        }
    }

    /**
     * 获取开发任务的依赖编号集合。
     *
     * @param task 任务记录
     * @return 开发任务的依赖编号集合结果集合
     */
    static List<Long> dependencyIds(DevTaskDO task) {
        return StrUtil.isBlank(task.getDependencyTaskIdsJson()) ? List.of()
                : JsonUtils.parseArray(task.getDependencyTaskIdsJson(), Long.class);
    }

    /**
     * 手动运行任务（生成执行实例）
     *
     * @param req 请求参数
     * @return 运行任务数值
     */
    @Override
    public Long runTask(DevTaskRunReqVO req) {
        DevTaskDO task = validateTaskExists(req.getId());
        return enqueueCurrent(task, DevRecordDO.TRIGGER_MANUAL, "MANUAL:" + req.getRequestId(), req.getBusinessDate());
    }

    /** 作业内的人工执行复用直接运行的本人身份、保存版本与原生护栏。 */
    @Override
    public cn.iocoder.yudao.module.dataforge.model.dev.DevJobNode previewJobNode(Long taskId) {
        return previewJobNodeForActor(taskId, SecurityFrameworkUtils.getLoginUserId());
    }

    @Override
    public cn.iocoder.yudao.module.dataforge.model.dev.DevJobNode previewJobNodeForActor(Long taskId, Long actor) {
        var task=validateTaskExists(taskId);validateRunnable(task);var version=requireCurrentVersion(task);
        var snapshot=DevTaskSnapshot.read(version.getSnapshotJson());
        if(actor==null||!Objects.equals(actor,snapshot.getTask().getExecutionUserId()))throw exception(DEV_EXECUTION_IDENTITY_REQUIRED);
        executionAccessService.requireUserPermission(actor,ExecutionAccessService.PERMISSION_DEV_TASK_RUN);
        if(DevNativeRuntimeSupport.isNative(snapshot.getTask())) {
            nativeRuntimeSupport.requireActor(snapshot.getTask(),actor);nativeRuntimeSupport.requireFrozen(snapshot);
        }
        return dsSubmitter.previewJobNode(snapshot,version.getId());
    }

    @Override
    public cn.iocoder.yudao.module.dataforge.model.dev.DevJobNode prepareJobNode(Long taskId, Long expectedVersionId,
            String runKey, java.time.LocalDate businessDate) {
        return prepareJobNodeForActor(taskId,expectedVersionId,runKey,businessDate,SecurityFrameworkUtils.getLoginUserId());
    }

    @Override
    public cn.iocoder.yudao.module.dataforge.model.dev.DevJobNode prepareJobNodeForActor(Long taskId,Long expectedVersionId,
            String runKey,java.time.LocalDate businessDate,Long actor) {
        var target=previewJobNodeForActor(taskId,actor);
        if(!Objects.equals(expectedVersionId,target.versionId())||runKey==null||!runKey.matches("JOB:[1-9][0-9]*:[A-Za-z0-9_-]{1,80}"))throw exception(DEV_RUN_REQUEST_CONFLICT);
        var version=versionMapper.selectById(target.versionId());
        Long recordId=transactionTemplate.execute(status->{
            var current=taskMapper.selectForUpdate(taskId);
            if(current==null||!Objects.equals(current.getCurrentVersionId(),target.versionId()))throw exception(DEV_TASK_REVISION_CONFLICT);
            validateRunnable(current);return dsSubmitter.enqueue(current,version,"JOB",runKey,businessDate);
        });
        try{return dsSubmitter.prepareJobNode(recordId);}
        catch(RuntimeException failure){dsSubmitter.cancelPreparedJobNode(recordId);throw failure;}
    }

    @Override
    public DevRecordDO authorizeJobNode(Long recordId,Long actor) {return dsSubmitter.authorizeJobNode(recordId,actor);}

    @Override
    public void authorizeJobTaskStop(Long taskId,Long versionId,Long caller) {
        var version=versionMapper.selectById(versionId);
        if(version==null||!Objects.equals(taskId,version.getTaskId()))throw exception(DEV_RECORD_NOT_EXISTS);
        var snapshot=DevTaskSnapshot.read(version.getSnapshotJson());
        if(!Objects.equals(taskId,snapshot.getTask().getId()))throw exception(DEV_RECORD_NOT_EXISTS);
        executionAccessService.requireUserPermission(caller,ExecutionAccessService.PERMISSION_DEV_TASK_RUN);
        if(DevNativeRuntimeSupport.isNative(snapshot.getTask()))nativeRuntimeSupport.requireActor(snapshot.getTask(),caller);
    }

    @Override
    public DevRecordDO authorizeJobNodeForCaller(Long recordId,Long caller) {return dsSubmitter.authorizeJobNodeForCaller(recordId,caller);}

    @Override
    public void stopJobNode(Long recordId,Long actor) {
        var record=dsSubmitter.authorizeJobNode(recordId,actor);
        if(DevRecordDO.ACTIVE_STATUSES.contains(record.getStatus()))dsSubmitter.stop(recordId);
    }

    @Override
    public DevRecordDO bindJobNode(Long recordId, Long projectCode, Long workflowCode, Integer instanceId) {
        return dsSubmitter.bindJobNode(recordId, projectCode, workflowCode, instanceId);
    }

    @Override
    public DevRecordDO observeJobNode(Long recordId) { return dsSubmitter.observeJobNode(recordId); }

    @Override
    public void cancelPreparedJobNode(Long recordId) { dsSubmitter.cancelPreparedJobNode(recordId); }

    /**
     * 导入真实 DS 周期触发实例并持久化运行意图
     *
     * @param binding 绑定
     * @param instanceId 实例编号
     * @param scheduledTime 定时时间
     * @return 运行任务定时数值
     */
    @Override
    public Long runTaskScheduled(cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevScheduleDO binding,
            Long instanceId, LocalDateTime scheduledTime) {
        if (instanceId == null || instanceId <= 0 || scheduledTime == null) {
            throw exception(DEV_TASK_SNAPSHOT_INVALID, "周期触发所有权或时间无效");
        }
        DevTaskDO candidate = validateTaskExists(binding.getTaskId());
        DevTaskVersionDO version = requireCurrentVersion(candidate);
        if (!Objects.equals(binding.getVersionId(), version.getId())) {
            return null;
        }
        DevTaskSnapshot snapshot = DevTaskSnapshot.read(version.getSnapshotJson());
        executionAccessService.requireUserPermission(snapshot.getTask().getExecutionUserId(),
                ExecutionAccessService.PERMISSION_DEV_TASK_RUN);
        if (DevNativeRuntimeSupport.isNative(snapshot.getTask())) {
            nativeRuntimeSupport.requireActor(snapshot.getTask(), snapshot.getTask().getExecutionUserId());
            nativeRuntimeSupport.requireFrozen(snapshot);
        }
        return transactionTemplate.execute(status -> {
            DevTaskDO task = taskMapper.selectForUpdate(binding.getTaskId());
            var actual = scheduleMapper.selectById(binding.getId());
            if (!isActiveScheduleBinding(task, actual, binding)) {
                return null;
            }
            return dsSubmitter.enqueue(task, version, DevRecordDO.TRIGGER_SCHEDULE,
                    "DS_SCHEDULE:" + binding.getDsProjectCode() + ":" + instanceId, scheduledTime.toLocalDate());
        });
    }

    /**
     * 登记已核验的失败周期标记，仅持久化失败记录，不投递业务程序。
     *
     * @param binding 绑定
     * @param instanceId 实例编号
     * @param scheduledTime 定时时间
     * @param markerStatus 标记状态
     * @return 记录编号
     */
    @Override
    public Long recordTaskScheduledFailure(cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevScheduleDO binding,
            Long instanceId, LocalDateTime scheduledTime, String markerStatus) {
        if (instanceId == null || instanceId <= 0 || scheduledTime == null
                || (!DevRecordDO.STATUS_FAILED.equals(markerStatus) && !DevRecordDO.STATUS_STOPPED.equals(
                markerStatus))) {
            throw exception(DEV_TASK_SNAPSHOT_INVALID, "失败周期标记所有权、时间或终态无效");
        }
        // 没有投递业务程序的失败不依赖外部授权服务，避免已撤回身份再次阻断周期导入。
        return transactionTemplate.execute(status -> {
            DevTaskDO task = taskMapper.selectForUpdate(binding.getTaskId());
            var actual = scheduleMapper.selectById(binding.getId());
            if (!isActiveScheduleBinding(task, actual, binding)) {
                return null;
            }
            DevTaskVersionDO version = requireCurrentVersion(task);
            DevTaskSnapshot snapshot = DevTaskSnapshot.read(version.getSnapshotJson());
            String runKey = "DS_SCHEDULE:" + binding.getDsProjectCode() + ":" + instanceId;
            DevRecordDO existing = recordMapper.selectOne(DevRecordDO::getRunKey, runKey);
            if (existing != null) {
                if (!Objects.equals(existing.getTaskId(), task.getId())
                        || !Objects.equals(existing.getVersionId(), version.getId())
                        || !Objects.equals(existing.getBusinessDate(), scheduledTime.toLocalDate())) {
                    throw exception(DEV_RUN_REQUEST_CONFLICT);
                }
                return existing.getId();
            }
            String reason = "DS 周期触发标记实例 #" + instanceId + " " + markerStatus + "，业务任务未投递";
            DevRecordDO record = DevRecordDO.builder().taskId(task.getId()).taskName(snapshot.getTask().getName())
                    .databaseId(snapshot.getTask().getDatabaseId())
                    .datasourceName(snapshot.getTask().getDatasourceName())
                    .businessDate(scheduledTime.toLocalDate()).runKey(runKey).versionId(version.getId())
                    .snapshotJson(version.getSnapshotJson()).executionUserId(snapshot.getTask().getExecutionUserId())
                    .dsProjectCode(binding.getDsProjectCode()).triggerType(DevRecordDO.TRIGGER_SCHEDULE)
                    .status(DevRecordDO.STATUS_FAILED).submissionStage("TRIGGER_FAILED").cancelRequested(false)
                    .qualityStatus("NOT_CONFIGURED").startTime(scheduledTime).endTime(LocalDateTime.now())
                    .durationMs(0L).statementTotal(0).statementSuccess(0).affectedRows(0L).lastError(reason).log(reason)
                    .build();

            recordMapper.insert(record);
            return record.getId();
        });
    }

    /**
     * 判断是否满足有效调度绑定条件。
     *
     * @param task 任务记录
     * @param actual 实际值
     * @param binding 绑定
     * @return 判断结果
     */
    private boolean isActiveScheduleBinding(DevTaskDO task,
            cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevScheduleDO actual,
            cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevScheduleDO binding) {
        return task != null && actual != null && Objects.equals(actual.getTaskId(), binding.getTaskId())
                && Boolean.TRUE.equals(actual.getDesiredEnabled())
                && Boolean.TRUE.equals(actual.getSyncedEnabled()) && "SYNCED".equals(actual.getSyncStatus())
                && Objects.equals(actual.getDsWorkflowCode(), binding.getDsWorkflowCode())
                && Objects.equals(actual.getLeaseOwner(), binding.getLeaseOwner()) && actual.getLeaseUntil() != null
                && actual.getLeaseUntil().isAfter(LocalDateTime.now())
                && Objects.equals(actual.getGeneration(), binding.getGeneration()) && Objects.equals(
                actual.getVersionId(), binding.getVersionId())
                && Objects.equals(actual.getDsProjectCode(), binding.getDsProjectCode())
                && Objects.equals(task.getCurrentVersionId(), binding.getVersionId())
                && DevTaskDO.STATUS_ONLINE.equals(task.getStatus()) && DevTaskDO.SCHEDULE_ON.equals(
                task.getScheduleStatus());
    }

    /**
     * 入队当前。
     *
     * @param task 任务记录
     * @param trigger 触发
     * @param runKey 运行键
     * @param businessDate 业务日期
     * @return 入队当前数值
     */
    private Long enqueueCurrent(DevTaskDO task, String trigger, String runKey, java.time.LocalDate businessDate) {
        validateRunnable(task);
        DevTaskVersionDO version = requireCurrentVersion(task);
        DevTaskSnapshot snapshot = DevTaskSnapshot.read(version.getSnapshotJson());
        requireInteractiveExecutionAccess(snapshot.getTask());
        executionAccessService.requireUserPermission(snapshot.getTask().getExecutionUserId(),
                ExecutionAccessService.PERMISSION_DEV_TASK_RUN);
        if (DevNativeRuntimeSupport.isNative(snapshot.getTask())) {
            nativeRuntimeSupport.requireActor(snapshot.getTask(), SecurityFrameworkUtils.getLoginUserId());
            nativeRuntimeSupport.requireActor(snapshot.getTask(), snapshot.getTask().getExecutionUserId());
            nativeRuntimeSupport.requireFrozen(snapshot);
        }
        // 与启停、保存和删除共用任务行锁；权限与受管环境预检已在事务外完成。
        return transactionTemplate.execute(status -> {
            DevTaskDO current = taskMapper.selectForUpdate(task.getId());
            if (current == null) {
                throw exception(DEV_TASK_NOT_EXISTS);
            }
            validateRunnable(current);
            if (!Objects.equals(current.getCurrentVersionId(), task.getCurrentVersionId())) {
                throw exception(DEV_TASK_REVISION_CONFLICT);
            }
            return dsSubmitter.enqueue(current, version, trigger, runKey, businessDate);
        });
    }

    /**
     * 校验当前版本。
     *
     * @param task 任务记录
     * @return 开发任务版本
     */
    private DevTaskVersionDO requireCurrentVersion(DevTaskDO task) {
        if (task.getCurrentVersionId() == null) {
            throw exception(DEV_TASK_SAVE_REQUIRED);
        }
        DevTaskVersionDO version = versionMapper.selectById(task.getCurrentVersionId());
        if (version == null || !Objects.equals(version.getTaskId(), task.getId()) || StrUtil.isBlank(
                version.getSnapshotJson())) {
            throw exception(DEV_TASK_SAVE_REQUIRED);
        }
        return version;
    }

    /** 人工执行只能使用本人保存的身份；后台周期仍使用已经冻结并持续复核的身份。 */
    private void requireInteractiveExecutionAccess(DevTaskDO task) {
        Long actor = SecurityFrameworkUtils.getLoginUserId();
        if (actor == null || !Objects.equals(actor, task.getExecutionUserId())) {
            throw exception(OPERATION_PERMISSION_DENIED);
        }
        dsSubmitter.validateForSave(task);
    }

    /**
     * 查询表字段元数据（编辑器补全用）
     *
     * @param databaseId 数据源编号
     * @param tableName 表名
     * @return 表字段元数据（编辑器补全用）结果集合
     */
    @Override
    public List<DevTableColumnRespVO> getTableColumns(Long databaseId, String tableName) {
        executionAccessService.requireDatabase(databaseId);
        AccessDatabaseResolver.ConnectionInfo conn = databaseResolver.resolve(databaseId, DATABASE_NOT_EXISTS);
        IMetaDataByJdbcService metaDataService = dbSwitchRuntime.jdbcMetadata(conn.getProductType());
        List<ColumnDescription> descriptions = metaDataService.queryTableColumnMeta(
                conn.getJdbcUrl(), conn.getUsername(), conn.getPassword(), conn.getSchema(), tableName);
        List<DevTableColumnRespVO> result = new ArrayList<>(descriptions.size());
        for (ColumnDescription description : descriptions) {
            DevTableColumnRespVO vo = new DevTableColumnRespVO();
            vo.setName(description.getFieldName());
            vo.setComment(description.getRemarks());
            try {
                vo.setDataType(java.sql.JDBCType.valueOf(description.getMetaData().getType()).getName());
            } catch (Exception ignored) {
                vo.setDataType(String.valueOf(description.getMetaData().getType()));
            }
            result.add(vo);
        }
        return result;
    }

    // ==================== 版本 ====================

    /**
     * 获得任务版本列表。
     *
     * @param taskId 任务编号
     * @return 任务版本列表结果集合
     */
    @Override
    public List<DevTaskVersionDO> getTaskVersionList(Long taskId) {
        validateTaskExists(taskId);
        return versionMapper.selectListByTaskId(taskId);
    }

    /**
     * 回滚到指定版本（版本内容写回任务并生成新版本）
     *
     * @param versionId 版本编号
     * @param expectedRevision 预期修订号
     */
    @Override
    public void rollbackVersion(Long versionId, Integer expectedRevision) {
        DevTaskVersionDO version = versionMapper.selectById(versionId);
        if (version == null || StrUtil.isBlank(version.getSnapshotJson())) {
            throw exception(DEV_VERSION_NOT_EXISTS);
        }
        DevTaskDO current = validateTaskExists(version.getTaskId());
        DevTaskDO restored = DevTaskSnapshot.read(version.getSnapshotJson()).getTask();
        if (!Objects.equals(restored.getId(), current.getId())) {
            throw exception(DEV_TASK_SNAPSHOT_INVALID, "快照任务与历史版本不匹配");
        }
        if (!Objects.equals(current.getRevision(), expectedRevision)) {
            throw exception(DEV_TASK_REVISION_CONFLICT);
        }
        var req = BeanUtils.toBean(restored, DevTaskSaveReqVO.class);
        req.setExecutionUserId(SecurityFrameworkUtils.getLoginUserId());
        req.setExpectedRevision(expectedRevision);
        req.setScheduler("DOLPHIN_SCHEDULER");
        req.setProgramConfig(DevTaskDefinition.readConfig(restored.getProgramConfigJson()));
        req.setExecutionConfig(DevTaskDefinition.readExecutionConfig(restored.getExecutionConfigJson()));
        req.setDependencyTaskIds(dependencyIds(restored));
        req.setDsDatasourceId(null);
        req.setDsDatasourceType(null);
        boolean legacyCron = !"DOLPHIN_SCHEDULER".equals(restored.getScheduler());
        if (legacyCron) {
            req.setCronExpression(null);
        }
        req.setVersionRemark("恢复历史版本 " + version.getVersion());
        validateTaskInput(req);
        validateTableLineage(req, current);
        validateNameUnique(current.getId(), req.getName());
        DevTaskDO task = buildSavedTask(req, current);
        if (restored.getOwnerId() == null) {
            task.setOwnerId(null);
            task.setOwnerName(null);
            task.setContactNumber(null);
        }
        if (legacyCron) {
            task.setLegacyCronExpression(restored.getCronExpression());
        }
        if (StrUtil.isBlank(task.getCronExpression())) {
            task.setScheduleStatus(DevTaskDO.SCHEDULE_OFF);
        }
        DevTaskSnapshot snapshot = prepareExecutionSnapshot(task);
        if (DevTaskDO.SCHEDULE_ON.equals(task.getScheduleStatus())) {
            scheduleCoordinator.validateTarget(snapshot);
        }
        transactionTemplate.executeWithoutResult(
                status -> saveCurrentVersion(task, snapshot, req.getVersionRemark(), expectedRevision));
    }

    /**
     * 校验执行逻辑。
     *
     * @param task 任务记录
     */
    private void validateRunnable(DevTaskDO task) {
        if (task.getStatus() == null || !DevTaskDO.STATUS_ONLINE.equals(task.getStatus())) {
            throw exception(DEV_TASK_NOT_ONLINE);
        }
        if (task.getCurrentVersionId() == null) {
            throw exception(DEV_TASK_SAVE_REQUIRED);
        }
    }

    /**
     * 冗余数据源信息（展示与方言判断用）
     */
    private void fillDatasourceInfo(DevTaskDO task) {
        if (task.getDatabaseId() == null) {
            return;
        }
        AccessDatabaseResolver.ConnectionInfo conn =
                databaseResolver.resolve(task.getDatabaseId(), DATABASE_NOT_EXISTS);
        task.setDatasourceName(conn.getName());
        task.setDatabaseName(StrUtil.blankToDefault(conn.getSchema(),
                StrUtil.blankToDefault(conn.getDatabaseName(), "")));
        task.setDbType(conn.getProductType() == null ? null : conn.getProductType().name());
    }

    /**
     * 目录过滤：包含选中目录及其全部子孙
     */
    private List<Long> resolveDirIds(Long dirId) {
        if (dirId == null) {
            return null;
        }
        List<DevDirDO> all = dirMapper.selectListAll();
        List<Long> ids = new ArrayList<>();
        ids.add(dirId);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (DevDirDO dir : all) {
                if (dir.getParentId() != null && ids.contains(dir.getParentId())
                        && !ids.contains(dir.getId())) {
                    ids.add(dir.getId());
                    changed = true;
                }
            }
        }
        return ids;
    }

    /**
     * 校验任务存在性。
     *
     * @param id 记录编号
     * @return 开发任务
     */
    private DevTaskDO validateTaskExists(Long id) {
        DevTaskDO task = taskMapper.selectById(id);
        if (task == null) {
            throw exception(DEV_TASK_NOT_EXISTS);
        }
        return task;
    }

    /**
     * 校验名称唯一性。
     *
     * @param id 记录编号
     * @param name 名称
     */
    private void validateNameUnique(Long id, String name) {
        DevTaskDO exists = taskMapper.selectByName(name);
        if (exists != null && !exists.getId().equals(id)) {
            throw exception(DEV_TASK_NAME_DUPLICATE);
        }
    }

}
