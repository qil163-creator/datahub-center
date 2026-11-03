package cn.iocoder.yudao.module.governance.service.metadata;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.collect.MetaCollectTaskPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.collect.MetaCollectTaskSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaCollectInstanceDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaCollectTaskDO;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaCollectTaskMapper;
import cn.iocoder.yudao.module.governance.service.master.MasterDatabaseResolver;
import cn.iocoder.yudao.module.governance.service.metadata.core.MetaCollectExecutor;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;

/**
 * 数据治理-元数据采集任务 Service 实现
 *
 * @author Li
 */
@Slf4j
@Service
@Validated
public class MetaCollectTaskServiceImpl implements MetaCollectTaskService {

    @Resource
    private MetaCollectTaskMapper taskMapper;

    @Resource
    private MetaCollectExecutor collectExecutor;

    @Resource
    private MasterDatabaseResolver databaseResolver;

    @Resource
    private cn.iocoder.yudao.module.governance.service.metadata.core.MetaCollectSources sources;

    @Resource
    private org.springframework.transaction.support.TransactionTemplate transactions;


    /**
     * 创建采集任务（默认下线、调度停止，由发布/调度开关显式开启）
     */
    @Override
    public Long createTask(MetaCollectTaskSaveReqVO createReqVO) {
        validateNameUnique(null, createReqVO.getName());
        validateCron(createReqVO.getCron());
        MetaCollectTaskDO task = BeanUtils.toBean(createReqVO, MetaCollectTaskDO.class);
        sources.validateAndEnrich(createReqVO, task);
        task.setScheduler(MetaCollectTaskDO.SCHEDULER_XXL_JOB);
        task.setEnabled(MetaCollectTaskDO.SWITCH_OFF);
        task.setScheduleEnabled(MetaCollectTaskDO.SWITCH_OFF);
        taskMapper.insert(task);
        return task.getId();
    }

    /**
     * 更新采集任务
     */
    @Override
    public void updateTask(MetaCollectTaskSaveReqVO updateReqVO) {
        validateTaskExists(updateReqVO.getId());
        validateNameUnique(updateReqVO.getId(), updateReqVO.getName());
        validateCron(updateReqVO.getCron());
        MetaCollectTaskDO update = BeanUtils.toBean(updateReqVO, MetaCollectTaskDO.class);
        sources.validateAndEnrich(updateReqVO, update);
        transactions.executeWithoutResult(tx -> {
            MetaCollectTaskDO task = taskMapper.lockTask(update.getId());
            if (task == null) {
                throw exception(META_COLLECT_TASK_NOT_EXISTS);
            }
            if (collectExecutor.isRunning(update.getId())) {
                throw exception(META_COLLECT_ACTIVE);
            }
            // 以锁后的调度状态校验，避免并发开启调度后保存空周期。
            if (isOn(task.getScheduleEnabled()) && StrUtil.isBlank(update.getCron())) {
                throw exception(META_COLLECT_TASK_CRON_INVALID);
            }
            if (isOn(task.getScheduleEnabled()) && !java.util.Objects.equals(update.getCron(), task.getCron())) {
                update.setNextRunTime(CronExpression.parse(update.getCron()).next(LocalDateTime.now()));
            }
            taskMapper.updateById(update);
            if (update.getScopeConfig() == null) {
                taskMapper.update(null,
                        com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaUpdate(MetaCollectTaskDO.class)
                                .eq(MetaCollectTaskDO::getId, update.getId())
                                .set(MetaCollectTaskDO::getScopeConfig, null));
            }
            if (update.getBlacklist() == null) {
                taskMapper.update(null,
                        com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaUpdate(MetaCollectTaskDO.class)
                                .eq(MetaCollectTaskDO::getId, update.getId())
                                .set(MetaCollectTaskDO::getBlacklist, null));
            }
            taskMapper.update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaUpdate(MetaCollectTaskDO.class)
                    .eq(MetaCollectTaskDO::getId, update.getId())
                    .set(MetaCollectTaskDO::getFilePath, update.getFilePath())
                    .set(MetaCollectTaskDO::getCron, update.getCron())
                    .set(MetaCollectTaskDO::getDescription, update.getDescription())
                    .set(MetaCollectTaskDO::getRemark, update.getRemark())
                    .set(MetaCollectTaskDO::getSourceSystemId, update.getSourceSystemId())
                    .set(MetaCollectTaskDO::getSourceSystemName, update.getSourceSystemName())
                    .set(MetaCollectTaskDO::getOwnerUserId, update.getOwnerUserId())
                    .set(MetaCollectTaskDO::getOwnerName, update.getOwnerName())
                    .set(MetaCollectTaskDO::getOwnerPhone, update.getOwnerPhone())
                    .set(MetaCollectTaskDO::getOwnerDeptId, update.getOwnerDeptId()));
        });
    }

    /**
     * 删除采集任务（仅下线状态可删）
     */
    @Override
    public void deleteTask(Long id) {
        deleteTasks(java.util.List.of(id));
    }

    /**
     * 删除元数据采集任务的任务。
     */
    @Override
    public void deleteTasks(java.util.List<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 100) {
            throw exception(META_COLLECT_SCOPE_INVALID);
        }
        transactions.executeWithoutResult(tx -> {
            for (Long id : ids.stream().distinct().sorted().toList()) {
                MetaCollectTaskDO task = taskMapper.lockTask(id);
                if (task == null) {
                    throw exception(META_COLLECT_TASK_NOT_EXISTS);
                }
                if (isOn(task.getEnabled())) {
                    throw exception(META_COLLECT_TASK_ENABLED_CANNOT_DELETE);
                }
                if (collectExecutor.isRunning(id)) {
                    throw exception(META_COLLECT_ACTIVE);
                }
                taskMapper.deleteById(id);
            }
        });
    }

    /**
     * 获取采集任务
     */
    @Override
    public MetaCollectTaskDO getTask(Long id) {
        return validateTaskExists(id);
    }

    /**
     * 采集任务分页
     */
    @Override
    public PageResult<MetaCollectTaskDO> getTaskPage(MetaCollectTaskPageReqVO pageReqVO) {
        return taskMapper.selectPage(pageReqVO);
    }

    /**
     * 任务上下线（发布/取消发布任务）
     */
    @Override
    public void updateEnabled(Long id, Integer enabled) {
        validateSwitch(enabled);
        transactions.executeWithoutResult(tx -> {
            MetaCollectTaskDO task = taskMapper.lockTask(id);
            if (task == null) {
                throw exception(META_COLLECT_TASK_NOT_EXISTS);
            }
            MetaCollectTaskDO update = new MetaCollectTaskDO();
            update.setId(id);
            update.setEnabled(enabled);
            if (isOn(enabled) && isOn(task.getScheduleEnabled())) {
                if (StrUtil.isBlank(task.getCron()) || !CronExpression.isValidExpression(task.getCron())) {
                    throw exception(META_COLLECT_TASK_CRON_INVALID);
                }
                update.setNextRunTime(CronExpression.parse(task.getCron()).next(LocalDateTime.now()));
            }
            taskMapper.updateById(update);
        });
    }

    /**
     * 调度启停（开启时校验 cron 并推进下次执行时间）
     */
    @Override
    public void updateScheduleEnabled(Long id, Integer scheduleEnabled) {
        validateSwitch(scheduleEnabled);
        transactions.executeWithoutResult(tx -> {
            MetaCollectTaskDO task = taskMapper.lockTask(id);
            if (task == null) {
                throw exception(META_COLLECT_TASK_NOT_EXISTS);
            }
            MetaCollectTaskDO update = new MetaCollectTaskDO();
            update.setId(id);
            update.setScheduleEnabled(scheduleEnabled);
            if (isOn(scheduleEnabled)) {
                if (StrUtil.isBlank(task.getCron()) || !CronExpression.isValidExpression(task.getCron())) {
                    throw exception(META_COLLECT_TASK_CRON_INVALID);
                }
                update.setNextRunTime(CronExpression.parse(task.getCron()).next(LocalDateTime.now()));
            }
            taskMapper.updateById(update);
        });
    }

    /**
     * 手动执行一次
     */
    @Override
    public void runOnce(Long id) {
        MetaCollectTaskDO task = validateTaskExists(id);
        if (!isOn(task.getEnabled())) {
            throw exception(META_COLLECT_TASK_DISABLED_CANNOT_RUN);
        }
        if (!collectExecutor.submit(id, MetaCollectInstanceDO.TRIGGER_MANUAL)) {
            throw exception(META_COLLECT_TASK_RUNNING);
        }
    }

    /**
     * 校验任务记录存在，不存在时抛出业务异常。
     */
    private MetaCollectTaskDO validateTaskExists(Long id) {
        MetaCollectTaskDO task = taskMapper.selectById(id);
        if (task == null) {
            throw exception(META_COLLECT_TASK_NOT_EXISTS);
        }
        return task;
    }

    /**
     * 校验元数据采集任务名称在指定范围内唯一。
     */
    private void validateNameUnique(Long id, String name) {
        MetaCollectTaskDO task = taskMapper.selectByName(name);
        if (task != null && !task.getId().equals(id)) {
            throw exception(META_COLLECT_TASK_NAME_DUPLICATE);
        }
    }

    /**
     * 校验元数据采集任务的Cron，不满足约束时拒绝当前操作。
     */
    private void validateCron(String cron) {
        if (StrUtil.isNotBlank(cron) && !CronExpression.isValidExpression(cron)) {
            throw exception(META_COLLECT_TASK_CRON_INVALID);
        }
    }

    /**
     * 校验元数据采集任务的开关，不满足约束时拒绝当前操作。
     */
    private void validateSwitch(Integer value) {
        if (!java.util.Objects.equals(value, 0) && !java.util.Objects.equals(value, 1)) {
            throw exception(META_COLLECT_SWITCH_INVALID);
        }
    }

    /**
     * 判断元数据采集任务是否启用。
     */
    private boolean isOn(Integer value) {
        return value != null && value == MetaCollectTaskDO.SWITCH_ON;
    }

}
