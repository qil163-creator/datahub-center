package cn.iocoder.yudao.module.dataforge.service.push;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.biz.system.logger.dto.OperateLogCreateReqDTO;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.monitor.TracerUtils;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.common.util.servlet.ServletUtils;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.service.IMetaDataByJdbcService;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByJdbcServiceImpl;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.framework.push.config.DataPushProperties;
import cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.DataPushColumnRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.DataPushPreviewReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.DataPushPreviewRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.DataPushTaskLogPageReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.DataPushTaskLogRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.DataPushTaskPageReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.DataPushTaskRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.DataPushTaskSaveReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.DataPushWatermarkRespVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.database.DatabaseDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.push.DataPushConfigDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.push.DataPushIncreaseLogDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.push.DataPushTaskDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.push.DataPushTaskLogDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.database.DatabaseMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.push.DataPushConfigMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.push.DataPushIncreaseLogMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.push.DataPushTaskLogMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.push.DataPushTaskMapper;
import cn.iocoder.yudao.module.dataforge.enums.push.PushModeEnum;
import cn.iocoder.yudao.module.dataforge.enums.push.PushRunTypeEnum;
import cn.iocoder.yudao.module.dataforge.enums.push.PushTaskTypeEnum;
import cn.iocoder.yudao.module.dataforge.enums.push.PushTypeEnum;
import cn.iocoder.yudao.module.dataforge.service.CopyNameSupport;
import cn.iocoder.yudao.module.dataforge.service.access.AccessDatabaseResolver;
import cn.iocoder.yudao.module.system.api.dept.DeptApi;
import cn.iocoder.yudao.module.system.api.dept.dto.DeptRespDTO;
import cn.iocoder.yudao.module.system.api.logger.OperateLogApi;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.Resource;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertList;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertMap;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertSet;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATA_PUSH_CONFIG_NOT_EXISTS;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATA_PUSH_CHANNEL_CONFIG_INVALID;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATA_PUSH_CRON_INVALID;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATA_PUSH_INCREMENT_COLUMN_NOT_EXISTS;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATA_PUSH_INCREMENT_COLUMN_REQUIRED;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATA_PUSH_LOG_NOT_EXISTS;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATA_PUSH_NOT_RELEASED;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATA_PUSH_NOT_RUNNING;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATA_PUSH_RELEASED_CANNOT_MODIFY;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATA_PUSH_RUNNING;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATA_PUSH_SOURCE_DATABASE_NOT_EXISTS;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATA_PUSH_MODE_INVALID;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATA_PUSH_SQL_INVALID;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATA_PUSH_TABLE_REQUIRED;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATA_PUSH_TASK_NOT_EXISTS;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATA_PUSH_TASK_TYPE_INVALID;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATA_PUSH_TYPE_INVALID;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATA_PUSH_EXECUTOR_BUSY;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATA_PUSH_INCREMENT_COLUMN_TYPE_INVALID;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATA_PUSH_INCREMENT_NOT_ENABLED;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATA_PUSH_INIT_WATERMARK_INVALID;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATA_PUSH_PREVIEW_FAILED;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATA_PUSH_SOURCE_META_FAILED;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATA_PUSH_TABLE_NOT_EXISTS;

@lombok.extern.slf4j.Slf4j
@Service
@Validated
public class DataPushTaskServiceImpl implements DataPushTaskService {

    @Resource
    private cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime dbSwitchRuntime;

    @Resource
    private DataPushTaskMapper taskMapper;

    @Resource
    private DataPushConfigMapper configMapper;

    @Resource
    private DataPushConfigService configService;

    @Resource
    private DataPushTaskLogMapper logMapper;

    @Resource
    private DataPushIncreaseLogMapper increaseLogMapper;

    @Resource
    private DatabaseMapper databaseMapper;

    @Resource
    private DataPushExecutor pushExecutor;

    @Resource
    private DataPushRunCoordinator runCoordinator;

    @Resource
    private AccessDatabaseResolver databaseResolver;

    @Resource
    private DeptApi deptApi;

    @Resource
    private AdminUserApi adminUserApi;

    @Resource
    private DataPushProperties pushProperties;

    @Resource
    private PushCategoryService pushCategoryService;

    @Resource
    private OperateLogApi operateLogApi;

    /**
     * 创建推送任务。
     *
     * @param createReqVO 创建参数
     * @return 生成或保存的记录编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createTask(DataPushTaskSaveReqVO createReqVO) {
        DataPushConfigDO config = resolveTaskConfig(createReqVO, null);
        validateSaveReq(createReqVO, config);
        DataPushTaskDO task = buildTask(createReqVO);
        task.setPushConfigId(persistTaskConfig(createReqVO, config));
        if (task.getCategoryId() != null) pushCategoryService.validateReference(task.getCategoryId(), null);
        task.setStatus(DataPushTaskDO.STATUS_UNRELEASED);
        if (taskMapper.insert(task) != 1) {
            throw exception(DATA_PUSH_TASK_NOT_EXISTS);
        }
        return task.getId();
    }

    /**
     * 更新推送任务。
     *
     * @param updateReqVO 更新参数
     */
    @Override
    public void updateTask(DataPushTaskSaveReqVO updateReqVO) {
        DataPushTaskDO old = validateTaskExists(updateReqVO.getId());
        validateNotReleased(old);
        DataPushConfigDO config = resolveTaskConfig(updateReqVO, old.getPushConfigId());
        validateSaveReq(updateReqVO, config);
        runCoordinator.withIdleTask(updateReqVO.getId(), current -> {
            validateNotReleased(current);
            if (!Objects.equals(old.getPushConfigId(), current.getPushConfigId())
                    || !Objects.equals(old.getUpdateTime(), current.getUpdateTime())) {
                throw exception(DATA_PUSH_CHANNEL_CONFIG_INVALID, "任务已发生变化，请刷新后重试");
            }
            DataPushTaskDO task = buildTask(updateReqVO);
            // 配置与任务必须写入同一个 withIdleTask 事务，不能在外层先创建配置。
            task.setPushConfigId(persistTaskConfig(updateReqVO, config));
            if (task.getCategoryId() != null) pushCategoryService.validateReference(task.getCategoryId(), current.getCategoryId());
            task.setWatermarkResetUserId(current.getWatermarkResetUserId());
            task.setWatermarkResetTime(current.getWatermarkResetTime());
            task.setWatermarkResetOldEnd(current.getWatermarkResetOldEnd());
            if (taskMapper.updateById(task) != 1) {
                throw exception(DATA_PUSH_TASK_NOT_EXISTS);
            }
            if (task.getCategoryId() == null && current.getCategoryId() != null) {
                taskMapper.update(null, Wrappers.lambdaUpdate(DataPushTaskDO.class)
                        .eq(DataPushTaskDO::getId, task.getId()).set(DataPushTaskDO::getCategoryId, null));
            }
            return null;
        });
    }

    /**
     * 删除推送任务。
     *
     * @param id 记录编号
     */
    @Override
    public void deleteTask(Long id) {
        runCoordinator.withIdleTask(id, task -> {
            validateNotReleased(task);
            logMapper.deleteByTaskId(id);
            increaseLogMapper.deleteByTaskId(id);
            taskMapper.deleteById(id);
            configService.deleteTaskConfigIfUnused(task.getPushConfigId());
            return null;
        });
    }

    /**
     * 获得推送任务。
     *
     * @param id 记录编号
     * @return 数据推送任务响应数据
     */
    @Override
    public DataPushTaskRespVO getTask(Long id) {
        return CollUtil.getFirst(convertToRespList(Collections.singletonList(validateTaskExists(id))));
    }

    /**
     * 获得推送任务分页。
     *
     * @param pageReqVO 分页查询条件
     * @return 推送任务分页结果
     */
    @Override
    public PageResult<DataPushTaskRespVO> getTaskPage(DataPushTaskPageReqVO pageReqVO) {
        List<Long> categoryIds = pageReqVO.getCategoryId() == null ? null
                : pushCategoryService.descendants(pageReqVO.getCategoryId());
        PageResult<DataPushTaskDO> page = taskMapper.selectPage(pageReqVO, categoryIds);
        return new PageResult<>(convertToRespList(page.getList()), page.getTotal());
    }

    /**
     * 发布任务。
     *
     * @param id 记录编号
     */
    @Override
    public void releaseTask(Long id) {
        DataPushTaskDO task = validateTaskExists(id);
        validateNotReleased(task);
        validateConfigExists(task.getPushConfigId());
        DataPushTaskLogDO accepted = runCoordinator.publish(task, SecurityFrameworkUtils.getLoginUserId());
        if (accepted != null) {
            try {
                pushExecutor.dispatch(accepted);
            } catch (RuntimeException ex) {
                // 发布和意图已一起提交，派发故障由持久扫描恢复，不误报发布失败引导重复发布。
                log.warn("[releaseTask][任务({}) 已持久发布，等待派发恢复；异常类型={}]", id,
                        ex.getClass().getSimpleName());
            }
        }
    }

    /**
     * 取消发布。
     *
     * @param id 记录编号
     */
    @Override
    public void cancelTask(Long id) {
        runCoordinator.cancelPublication(id);
        pushExecutor.stop(id);
    }

    /**
     * 校验任务存在后提交一次手工数据推送运行。
     *
     * @param id 记录编号
     */
    @Override
    public void handRun(Long id) {
        validateTaskExists(id);
        submitOrThrow(id, PushRunTypeEnum.HAND);
    }

    /**
     * 停止正在运行的任务。
     *
     * @param id 记录编号
     */
    @Override
    public void stopTask(Long id) {
        validateTaskExists(id);
        if (!pushExecutor.stop(id)) {
            throw exception(DATA_PUSH_NOT_RUNNING);
        }
    }

    /**
     * 获得执行记录分页。
     *
     * @param pageReqVO 分页查询条件
     * @return 执行记录分页结果
     */
    @Override
    public PageResult<DataPushTaskLogRespVO> getLogPage(DataPushTaskLogPageReqVO pageReqVO) {
        return BeanUtils.toBean(logMapper.selectPage(pageReqVO), DataPushTaskLogRespVO.class);
    }

    /**
     * 获得执行记录详情。
     *
     * @param id 记录编号
     * @return 数据推送任务日志响应数据
     */
    @Override
    public DataPushTaskLogRespVO getLog(Long id) {
        DataPushTaskLogDO log = logMapper.selectById(id);
        if (log == null) {
            throw exception(DATA_PUSH_LOG_NOT_EXISTS);
        }
        return BeanUtils.toBean(log, DataPushTaskLogRespVO.class);
    }

    /**
     * 获得源表列清单。
     *
     * @param sourceDatabaseId 来源数据源编号
     * @param tableName 表名
     * @return 源表列清单结果集合
     */
    @Override
    public List<DataPushColumnRespVO> getTableColumns(Long sourceDatabaseId, String tableName) {
        AccessDatabaseResolver.ConnectionInfo conn = databaseResolver.resolve(
                sourceDatabaseId, DATA_PUSH_SOURCE_DATABASE_NOT_EXISTS);
        IMetaDataByJdbcService meta = dbSwitchRuntime.jdbcMetadata(conn.getProductType());
        String jdbcUrl = DataPushReadOnlySupport.applyUrl(conn.getProductType(), conn.getJdbcUrl());
        try {
            List<ColumnDescription> columns = meta.queryTableColumnMeta(jdbcUrl, conn.getUsername(),
                    conn.getPassword(), conn.getSchema(), tableName);
            if (CollUtil.isEmpty(columns)) {
                throw exception(DATA_PUSH_TABLE_NOT_EXISTS, tableName);
            }
            return toColumnVos(columns);
        } catch (ServiceException ex) {
            throw ex;
        } catch (Exception ex) {
            if (isTableMissing(ex)) {
                throw exception(DATA_PUSH_TABLE_NOT_EXISTS, tableName);
            }
            throw exception(DATA_PUSH_SOURCE_META_FAILED, StrUtil.blankToDefault(ex.getMessage(), "未知错误"));
        }
    }

    /**
     * 解析SQL列集合。
     *
     * @param sourceDatabaseId 来源数据源编号
     * @param sqlText SQL文本
     * @return 解析SQL列集合结果集合
     */
    @Override
    public List<DataPushColumnRespVO> parseSqlColumns(Long sourceDatabaseId, String sqlText) {
        String sql;
        try {
            sql = DataPushSqlValidator.validateSelect(sqlText);
        } catch (IllegalArgumentException ex) {
            throw exception(DATA_PUSH_SQL_INVALID, ex.getMessage());
        }
        AccessDatabaseResolver.ConnectionInfo conn = databaseResolver.resolve(
                sourceDatabaseId, DATA_PUSH_SOURCE_DATABASE_NOT_EXISTS);
        IMetaDataByJdbcService meta = dbSwitchRuntime.jdbcMetadata(conn.getProductType());
        String jdbcUrl = DataPushReadOnlySupport.applyUrl(conn.getProductType(), conn.getJdbcUrl());
        return toColumnVos(meta.querySqlColumnMeta(jdbcUrl, conn.getUsername(),
                conn.getPassword(), sql));
    }

    /**
     * 解析任务配置。
     *
     * @param reqVO 请求参数
     * @param existingConfigId 已有配置编号
     * @return 数据推送配置
     */
    private DataPushConfigDO resolveTaskConfig(DataPushTaskSaveReqVO reqVO, Long existingConfigId) {
        if (!reqVO.isPushConfigSelectionValid()) {
            throw exception(DATA_PUSH_CHANNEL_CONFIG_INVALID, "既有推送配置编号与任务接收端配置必须且只能填写一个");
        }
        return reqVO.getPushConfig() == null ? validateConfigExists(reqVO.getPushConfigId())
                : configService.prepareTaskConfig(reqVO.getPushConfig(), existingConfigId);
    }

    /**
     * 持久化任务配置。
     *
     * @param reqVO 请求参数
     * @param config 处理配置
     * @return 记录编号
     */
    private Long persistTaskConfig(DataPushTaskSaveReqVO reqVO, DataPushConfigDO config) {
        if (reqVO.getPushConfig() != null) {
            return configService.saveTaskConfig(config);
        }
        configService.lockTaskConfig(config);
        return config.getId();
    }

    /**
     * 校验保存。
     *
     * @param reqVO 请求参数
     * @param selectedConfig 已选择配置
     */
    private void validateSaveReq(DataPushTaskSaveReqVO reqVO, DataPushConfigDO selectedConfig) {
        if (PushModeEnum.isIncrement(reqVO.getPushMode())
                && cn.iocoder.yudao.module.dataforge.enums.push.PushChannelTypeEnum.DB.getType()
                .equals(selectedConfig.getChannelType())) {
            throw exception(cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATA_PUSH_CHANNEL_CONFIG_INVALID,
                    "数据库增量请使用数据接入的强类型游标任务，当前推送通道没有提交回执和检查点绑定");
        }
        if (databaseMapper.selectById(reqVO.getSourceDatabaseId()) == null) {
            throw exception(DATA_PUSH_SOURCE_DATABASE_NOT_EXISTS);
        }
        if (!PushTypeEnum.isValid(reqVO.getPushType())) {
            throw exception(DATA_PUSH_TYPE_INVALID);
        }
        if (!PushModeEnum.isValid(reqVO.getPushMode())) {
            throw exception(DATA_PUSH_MODE_INVALID);
        }
        if (!PushTaskTypeEnum.isValid(reqVO.getTaskType())) {
            throw exception(DATA_PUSH_TASK_TYPE_INVALID);
        }
        List<DataPushColumnRespVO> columns = Collections.emptyList();
        if (PushTypeEnum.TABLE.getType().equals(reqVO.getPushType())) {
            if (StrUtil.isBlank(reqVO.getTableName())) {
                throw exception(DATA_PUSH_TABLE_REQUIRED);
            }
            columns = getTableColumns(reqVO.getSourceDatabaseId(), reqVO.getTableName());
        } else if (PushTypeEnum.isSql(reqVO.getPushType())) {
            try {
                reqVO.setSqlText(DataPushSqlValidator.validateSelect(reqVO.getSqlText()));
            } catch (IllegalArgumentException ex) {
                throw exception(DATA_PUSH_SQL_INVALID, ex.getMessage());
            }
            if (PushModeEnum.isIncrement(reqVO.getPushMode())) {
                columns = parseSqlColumns(reqVO.getSourceDatabaseId(), reqVO.getSqlText());
            }
        }
        if (PushTaskTypeEnum.isPeriodic(reqVO.getTaskType())
                && (StrUtil.isBlank(reqVO.getCron()) || !CronExpression.isValidExpression(reqVO.getCron()))) {
            throw exception(DATA_PUSH_CRON_INVALID, reqVO.getCron());
        }
        if (PushModeEnum.isIncrement(reqVO.getPushMode())) {
            if (StrUtil.isBlank(reqVO.getIncrementColumn())) {
                throw exception(DATA_PUSH_INCREMENT_COLUMN_REQUIRED);
            }
            DataPushColumnRespVO match = columns.stream()
                    .filter(column -> reqVO.getIncrementColumn().equalsIgnoreCase(column.getName()))
                    .findFirst()
                    .orElse(null);
            if (match == null) {
                throw exception(DATA_PUSH_INCREMENT_COLUMN_NOT_EXISTS, reqVO.getIncrementColumn());
            }
            if (!Boolean.TRUE.equals(match.getIncrementSupported())) {
                throw exception(DATA_PUSH_INCREMENT_COLUMN_TYPE_INVALID, match.getName(),
                        StrUtil.blankToDefault(match.getDataType(), "UNKNOWN"));
            }
            if (StrUtil.isNotBlank(reqVO.getInitStartVal())) {
                int maxLength = pushProperties == null ? 256 : Math.max(1, pushProperties.getWatermarkMaxLength());
                try {
                    DataPushIncreaseSupport.validateLength(reqVO.getInitStartVal().trim(), null, maxLength);
                } catch (IllegalArgumentException ex) {
                    throw exception(DATA_PUSH_INIT_WATERMARK_INVALID, ex.getMessage());
                }
            }
        }
    }

    /**
     * 校验配置存在性。
     *
     * @param configId 配置编号
     * @return 数据推送配置
     */
    private DataPushConfigDO validateConfigExists(Long configId) {
        DataPushConfigDO config = configMapper.selectById(configId);
        if (config == null) {
            throw exception(DATA_PUSH_CONFIG_NOT_EXISTS);
        }
        return config;
    }

    /**
     * 校验任务存在性。
     *
     * @param id 记录编号
     * @return 数据推送任务
     */
    private DataPushTaskDO validateTaskExists(Long id) {
        DataPushTaskDO task = taskMapper.selectById(id);
        if (task == null) {
            throw exception(DATA_PUSH_TASK_NOT_EXISTS);
        }
        return task;
    }

    /**
     * 校验未已发布。
     *
     * @param task 任务记录
     */
    private void validateNotReleased(DataPushTaskDO task) {
        if (task.getStatus() != null && task.getStatus() == DataPushTaskDO.STATUS_RELEASED) {
            throw exception(DATA_PUSH_RELEASED_CANNOT_MODIFY);
        }
    }

    /**
     * 构造数据推送任务的任务。
     *
     * @param reqVO 请求参数
     * @return 数据推送任务
     */
    private DataPushTaskDO buildTask(DataPushTaskSaveReqVO reqVO) {
        DataPushTaskDO task = BeanUtils.toBean(reqVO, DataPushTaskDO.class);
        if (task.getBatchSize() == null || task.getBatchSize() <= 0) {
            task.setBatchSize(100);
        }
        if (!PushTaskTypeEnum.isPeriodic(reqVO.getTaskType())) {
            task.setCron(null);
        }
        if (!PushModeEnum.isIncrement(reqVO.getPushMode())) {
            task.setIncrementColumn(null);
            task.setInitStartVal(null);
        } else {
            task.setInitStartVal(StrUtil.trimToNull(reqVO.getInitStartVal()));
        }
        if (PushTypeEnum.TABLE.getType().equals(reqVO.getPushType())) {
            task.setSqlText(null);
        } else {
            task.setTableName(null);
        }
        return task;
    }

    /**
     * 转换目标列表。
     *
     * @param list 待处理列表
     * @return 转换目标列表结果集合
     */
    private List<DataPushTaskRespVO> convertToRespList(List<DataPushTaskDO> list) {
        if (CollUtil.isEmpty(list)) {
            return Collections.emptyList();
        }
        Map<Long, DataPushConfigDO> configMap = convertMap(
                configMapper.selectByIds(convertSet(list, DataPushTaskDO::getPushConfigId)),
                DataPushConfigDO::getId);
        Map<Long, DatabaseDO> databaseMap = convertMap(
                databaseMapper.selectByIds(convertSet(list, DataPushTaskDO::getSourceDatabaseId)),
                DatabaseDO::getId);
        Set<Long> orgIds = convertSet(list, DataPushTaskDO::getOrgId);
        Map<Long, DeptRespDTO> deptMap = CollUtil.isEmpty(orgIds)
                ? Collections.emptyMap() : deptApi.getDeptMap(orgIds);
        Set<Long> userIds = convertSet(list, DataPushTaskDO::getReleaseUserId);
        Map<Long, AdminUserRespDTO> userMap = CollUtil.isEmpty(userIds)
                ? Collections.emptyMap() : adminUserApi.getUserMap(userIds);
        Set<Long> taskIds = convertSet(list, DataPushTaskDO::getId);
        Map<Long, DataPushTaskLogDO> lastLogMap = convertMap(
                logMapper.selectLatestByTaskIds(taskIds), DataPushTaskLogDO::getPushTaskId);
        Map<Long, DataPushIncreaseLogDO> watermarkMap = convertMap(
                increaseLogMapper.selectNewestByTaskIds(taskIds), DataPushIncreaseLogDO::getPushTaskId);
        return convertList(list, task -> {
            DataPushTaskRespVO vo = BeanUtils.toBean(task, DataPushTaskRespVO.class);
            DataPushConfigDO config = configMap.get(task.getPushConfigId());
            if (config != null) {
                vo.setPushConfigName(config.getName());
                vo.setChannelType(config.getChannelType());
            }
            DatabaseDO database = databaseMap.get(task.getSourceDatabaseId());
            if (database != null) {
                vo.setSourceDatabaseName(database.getName());
            }
            if (task.getOrgId() != null && deptMap.get(task.getOrgId()) != null) {
                vo.setOrgName(deptMap.get(task.getOrgId()).getName());
            }
            if (task.getReleaseUserId() != null && userMap.get(task.getReleaseUserId()) != null) {
                vo.setReleaseUserName(userMap.get(task.getReleaseUserId()).getNickname());
            }
            int blockingStatus = runCoordinator.blockingState(task.getId());
            vo.setBlockingRunStatus(blockingStatus);
            vo.setRunning(blockingStatus == 1 || blockingStatus == 2);
            DataPushTaskLogDO lastLog = lastLogMap.get(task.getId());
            if (lastLog != null) {
                vo.setLastRunStatus(lastLog.getRunStatus());
                vo.setLastStartTime(lastLog.getStartTime());
                vo.setLastEndTime(lastLog.getEndTime());
                vo.setLastDataCount(lastLog.getDataCount());
                vo.setLastErrorInfo(StrUtil.maxLength(lastLog.getErrorInfo(), 200));
            }
            DataPushIncreaseLogDO watermark = watermarkMap.get(task.getId());
            if (watermark != null) {
                vo.setWatermarkEndVal(watermark.getEndVal());
            }
            return vo;
        });
    }

    /**
     * 获得增量水位。
     *
     * @param id 记录编号
     * @return 数据推送水位响应数据
     */
    @Override
    public DataPushWatermarkRespVO getWatermark(Long id) {
        DataPushTaskDO task = validateTaskExists(id);
        DataPushWatermarkRespVO vo = new DataPushWatermarkRespVO();
        vo.setPushTaskId(id);
        vo.setIncrement(PushModeEnum.isIncrement(task.getPushMode()));
        vo.setIncrementColumn(task.getIncrementColumn());
        vo.setInitStartVal(task.getInitStartVal());
        vo.setResetUserId(task.getWatermarkResetUserId());
        vo.setResetTime(task.getWatermarkResetTime());
        vo.setResetOldEnd(task.getWatermarkResetOldEnd());
        if (task.getWatermarkResetUserId() != null) {
            Map<Long, AdminUserRespDTO> userMap = adminUserApi.getUserMap(
                    Collections.singleton(task.getWatermarkResetUserId()));
            AdminUserRespDTO user = userMap.get(task.getWatermarkResetUserId());
            if (user != null) {
                vo.setResetUserName(user.getNickname());
            }
        }
        if (!Boolean.TRUE.equals(vo.getIncrement())) {
            vo.setExists(false);
            return vo;
        }
        DataPushIncreaseLogDO last = increaseLogMapper.selectNewest(id);
        vo.setExists(last != null);
        if (last != null) {
            vo.setIncrementColumn(last.getIncreaseColumn());
            vo.setStartVal(last.getStartVal());
            vo.setEndVal(last.getEndVal());
            vo.setUpdateTime(last.getUpdateTime());
        }
        return vo;
    }

    /**
     * 试跑预览前 N 行（只读源端，不推送）。
     *
     * @param reqVO 请求参数
     * @return 数据推送预览响应数据
     */
    @Override
    public DataPushPreviewRespVO preview(DataPushPreviewReqVO reqVO) {
        DataPushTaskDO task;
        DataPushConfigDO config;
        if (reqVO.getId() != null && reqVO.getPushConfigId() == null && reqVO.getPushConfig() == null) {
            task = validateTaskExists(reqVO.getId());
            config = validateConfigExists(task.getPushConfigId());
        } else {
            DataPushTaskDO existing = reqVO.getId() == null ? null : validateTaskExists(reqVO.getId());
            DataPushTaskSaveReqVO saveReqVO = BeanUtils.toBean(reqVO, DataPushTaskSaveReqVO.class);
            config = resolveTaskConfig(saveReqVO, existing == null ? null : existing.getPushConfigId());
            validateSaveReq(saveReqVO, config);
            task = buildTask(saveReqVO);
            // 编辑预览继续读取本任务水位；新建草稿没有任务水位。
            task.setId(existing == null ? null : existing.getId());
        }
        int limit = reqVO.getLimit() == null ? 10 : reqVO.getLimit();
        try {
            return pushExecutor.preview(task, config, limit);
        } catch (ServiceException ex) {
            throw ex;
        } catch (Exception ex) {
            throw exception(DATA_PUSH_PREVIEW_FAILED, StrUtil.blankToDefault(ex.getMessage(), "未知错误"));
        }
    }

    /**
     * 复制推送任务。
     *
     * @param id 记录编号
     * @return 记录编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long copyTask(Long id) {
        DataPushTaskDO source = validateTaskExists(id);
        DataPushTaskDO copy = BeanUtils.toBean(source, DataPushTaskDO.class);
        copy.setId(null);
        copy.clean();
        copy.setDeleted(null);
        copy.setPushConfigId(configService.copyConfig(source.getPushConfigId()));
        if (copy.getCategoryId() != null) pushCategoryService.validateReference(copy.getCategoryId(), null);
        copy.setName(CopyNameSupport.next(source.getName(), 80, this::taskNameExists));
        copy.setStatus(DataPushTaskDO.STATUS_UNRELEASED);
        copy.setNextRunTime(null);
        copy.setReleaseTime(null);
        copy.setReleaseUserId(null);
        copy.setWatermarkResetUserId(null);
        copy.setWatermarkResetTime(null);
        copy.setWatermarkResetOldEnd(null);
        if (taskMapper.insert(copy) != 1) {
            throw exception(DATA_PUSH_TASK_NOT_EXISTS);
        }
        return copy.getId();
    }

    /**
     * 判断任务名称存在性条件是否成立。
     *
     * @param name 名称
     * @return 处理是否成功
     */
    private boolean taskNameExists(String name) {
        return taskMapper.selectCount(Wrappers.lambdaQuery(DataPushTaskDO.class)
                .eq(DataPushTaskDO::getName, name)) > 0;
    }

    /**
     * 重置增量水位。
     *
     * @param id 记录编号
     */
    @Override
    public void resetWatermark(Long id) {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        record Reset(DataPushTaskDO task, String oldEnd) {

        }
        Reset reset = runCoordinator.withIdleTask(id, task -> {
            if (!PushModeEnum.isIncrement(task.getPushMode())) {
                throw exception(DATA_PUSH_INCREMENT_NOT_ENABLED);
            }
            DataPushIncreaseLogDO last = increaseLogMapper.selectNewest(id);
            String oldEnd = last == null ? null : last.getEndVal();
            increaseLogMapper.deleteByTaskId(id);
            taskMapper.update(null, Wrappers.lambdaUpdate(DataPushTaskDO.class)
                    .set(DataPushTaskDO::getWatermarkResetUserId, userId)
                    .set(DataPushTaskDO::getWatermarkResetTime, LocalDateTime.now())
                    .set(DataPushTaskDO::getWatermarkResetOldEnd, oldEnd)
                    .eq(DataPushTaskDO::getId, id));
            return new Reset(task, oldEnd);
        });
        writeWatermarkResetLog(reset.task(), reset.oldEnd(), userId);
    }

    /**
     * 写入水位重置日志。
     *
     * @param task 任务记录
     * @param oldEnd 历史结束
     * @param userId 用户编号
     */
    private void writeWatermarkResetLog(DataPushTaskDO task, String oldEnd, Long userId) {
        if (userId == null) {
            return;
        }
        try {
            OperateLogCreateReqDTO reqDTO = new OperateLogCreateReqDTO();
            reqDTO.setTraceId(TracerUtils.getTraceId());
            reqDTO.setUserId(userId);
            var loginUser = SecurityFrameworkUtils.getLoginUser();
            reqDTO.setUserType(loginUser == null ? 2 : loginUser.getUserType());
            reqDTO.setType("数据推送");
            reqDTO.setSubType("重置增量水位");
            reqDTO.setBizId(task.getId());
            reqDTO.setAction(String.format("重置任务「%s」水位，旧上界=%s，首次起始=%s",
                    StrUtil.blankToDefault(task.getName(), String.valueOf(task.getId())),
                    StrUtil.blankToDefault(oldEnd, "（无）"),
                    StrUtil.blankToDefault(task.getInitStartVal(), "（空，下次按首次全量区间）")));
            jakarta.servlet.http.HttpServletRequest request = ServletUtils.getRequest();
            if (request != null) {
                reqDTO.setRequestMethod(request.getMethod());
                reqDTO.setRequestUrl(request.getRequestURI());
                reqDTO.setUserIp(ServletUtils.getClientIP(request));
                reqDTO.setUserAgent(ServletUtils.getUserAgent(request));
            } else {
                reqDTO.setRequestMethod("PUT");
                reqDTO.setRequestUrl("/dataforge/data-push-task/reset-watermark");
                reqDTO.setUserIp("127.0.0.1");
                reqDTO.setUserAgent("unknown");
            }
            operateLogApi.createOperateLog(reqDTO);
        } catch (Exception ex) {
            // 审计失败不影响重置本身
        }
    }

    /**
     * 提交或抛出。
     *
     * @param id 记录编号
     * @param runType 运行类型
     */
    private void submitOrThrow(Long id, PushRunTypeEnum runType) {
        try {
            if (!pushExecutor.submit(id, runType)) {
                throw exception(DATA_PUSH_RUNNING);
            }
        } catch (RejectedExecutionException ex) {
            throw exception(DATA_PUSH_EXECUTOR_BUSY);
        }
    }

    /**
     * 判断是否满足表缺失条件。
     *
     * @param ex 异常信息
     * @return 判断结果
     */
    private static boolean isTableMissing(Throwable ex) {
        for (Throwable current = ex; current != null; current = current.getCause()) {
            String message = StrUtil.nullToEmpty(current.getMessage());
            if (StrUtil.containsIgnoreCase(message, "not exist")
                    || StrUtil.containsIgnoreCase(message, "doesn't exist")
                    || StrUtil.containsIgnoreCase(message, "does not exist")
                    || StrUtil.containsIgnoreCase(message, "unknown table")
                    || StrUtil.containsIgnoreCase(message, "invalid object name")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 转换为列响应对象集合。
     *
     * @param descriptions 描述
     * @return 转换为列响应对象集合结果集合
     */
    private List<DataPushColumnRespVO> toColumnVos(List<ColumnDescription> descriptions) {
        List<DataPushColumnRespVO> result = new ArrayList<>(descriptions.size());
        for (ColumnDescription description : descriptions) {
            DataPushColumnRespVO vo = new DataPushColumnRespVO();
            vo.setName(description.getFieldName());
            vo.setComment(description.getRemarks());
            try {
                vo.setDataType(java.sql.JDBCType.valueOf(description.getFieldType()).getName());
            } catch (Exception ignored) {
                vo.setDataType(description.getFieldTypeName());
            }
            vo.setIncrementSupported(DataPushIncrementTypes.supported(
                    description.getFieldType(), description.getFieldTypeName()));
            result.add(vo);
        }
        return result;
    }

}
