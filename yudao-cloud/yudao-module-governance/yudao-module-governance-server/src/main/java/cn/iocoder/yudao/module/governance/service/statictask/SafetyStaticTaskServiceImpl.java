package cn.iocoder.yudao.module.governance.service.statictask;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.governance.controller.admin.statictask.vo.SafetyStaticTaskFieldRuleVO;
import cn.iocoder.yudao.module.governance.controller.admin.statictask.vo.SafetyStaticTaskLogPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.statictask.vo.SafetyStaticTaskPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.statictask.vo.SafetyStaticTaskSaveReqVO;

import cn.iocoder.yudao.module.governance.dal.dataobject.statictask.SafetyStaticTaskDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.statictask.SafetyStaticTaskLogDO;
import cn.iocoder.yudao.module.governance.dal.mysql.statictask.SafetyStaticTaskLogMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.statictask.SafetyStaticTaskMapper;
import cn.iocoder.yudao.module.governance.service.rule.SafetyRuleService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;

/**
 * 静态脱敏任务 Service 实现类
 *
 * @author Li
 */
@Service
@Validated
@Slf4j
public class SafetyStaticTaskServiceImpl implements SafetyStaticTaskService {

    @Resource
    private SafetyStaticTaskMapper taskMapper;

    @Resource
    private SafetyStaticTaskLogMapper logMapper;

    @Resource
    private SafetyRuleService ruleService;

    @Resource
    private SafetyStaticTaskExecutor taskExecutor;

    /**
     * 创建静态脱敏任务
     *
     *  @param createReqVO 创建信息
     *  @return 编号
     */
    @Override
    public Long createTask(SafetyStaticTaskSaveReqVO createReqVO) {
        validateTask(createReqVO);
        validateTaskNameUnique(null, createReqVO.getTaskName());
        SafetyStaticTaskDO task = BeanUtils.toBean(createReqVO, SafetyStaticTaskDO.class);
        task.setFieldRulesJson(JsonUtils.toJsonString(createReqVO.getFieldRules()));
        taskMapper.insert(task);
        return task.getId();
    }

    /**
     * 更新静态脱敏任务
     *
     *  @param updateReqVO 更新信息
     */
    @Override
    public void updateTask(SafetyStaticTaskSaveReqVO updateReqVO) {
        validateTaskExists(updateReqVO.getId());
        validateTask(updateReqVO);
        validateTaskNameUnique(updateReqVO.getId(), updateReqVO.getTaskName());
        SafetyStaticTaskDO task = BeanUtils.toBean(updateReqVO, SafetyStaticTaskDO.class);
        task.setFieldRulesJson(JsonUtils.toJsonString(updateReqVO.getFieldRules()));
        taskMapper.updateById(task);
    }

    /**
     * 删除静态脱敏任务
     *
     *  @param id 编号
     */
    @Override
    public void deleteTask(Long id) {
        validateTaskExists(id);
        validateTaskNotRunning(id);
        taskMapper.deleteById(id);
    }

    /**
     * 批量删除静态脱敏任务
     *
     *  @param ids 编号列表
     */
    @Override
    public void deleteTaskListByIds(List<Long> ids) {
        for (Long id : ids) {
            validateTaskExists(id);
            validateTaskNotRunning(id);
        }
        taskMapper.deleteByIds(ids);
    }

    /**
     * 获得静态脱敏任务
     *
     *  @param id 编号
     *  @return 任务
     */
    @Override
    public SafetyStaticTaskDO getTask(Long id) {
        return taskMapper.selectById(id);
    }

    /**
     * 获得静态脱敏任务分页
     *
     *  @param pageReqVO 分页查询
     *  @return 任务分页
     */
    @Override
    public PageResult<SafetyStaticTaskDO> getTaskPage(SafetyStaticTaskPageReqVO pageReqVO) {
        return taskMapper.selectPage(pageReqVO);
    }

    /**
     * 更新任务上下线状态
     *
     *  @param id     编号
     *  @param status 状态：0-上线、1-下线
     */
    @Override
    public void updateTaskStatus(Long id, Integer status) {
        validateTaskExists(id);
        if (!SafetyStaticTaskDO.STATUS_ENABLE.equals(status)
                && !SafetyStaticTaskDO.STATUS_DISABLE.equals(status)) {
            throw exception(STATIC_TASK_CONFIG_INVALID, "状态只能是 0（上线）或 1（下线）");
        }
        SafetyStaticTaskDO updateObj = new SafetyStaticTaskDO();
        updateObj.setId(id);
        updateObj.setStatus(status);
        taskMapper.updateById(updateObj);
    }

    /**
     * 手动执行任务（异步）
     *
     *  @param id 任务编号
     */
    @Override
    public void executeTask(Long id) {
        validateTaskExists(id);
        if (!taskExecutor.submit(id, SafetyStaticTaskLogDO.RUN_TYPE_MANUAL)) {
            throw exception(STATIC_TASK_RUNNING);
        }
    }

    /**
     * 调度触发执行任务（异步，运行中则跳过）
     *
     *  @param id 任务编号
     */
    @Override
    public void executeTaskBySchedule(Long id) {
        if (!taskExecutor.submit(id, SafetyStaticTaskLogDO.RUN_TYPE_SCHEDULE)) {
            log.info("[executeTaskBySchedule][静态脱敏任务({}) 正在执行中，本次调度跳过]", id);
        }
    }

    /**
     * 获得任务执行记录分页
     *
     *  @param pageReqVO 分页查询
     *  @return 执行记录分页
     */
    @Override
    public PageResult<SafetyStaticTaskLogDO> getTaskLogPage(SafetyStaticTaskLogPageReqVO pageReqVO) {
        return logMapper.selectPage(pageReqVO);
    }

    // ==================== 校验 ====================

    /**
     * 校验任务记录存在，不存在时抛出业务异常。
     */
    private SafetyStaticTaskDO validateTaskExists(Long id) {
        SafetyStaticTaskDO task = taskMapper.selectById(id);
        if (task == null) {
            throw exception(STATIC_TASK_NOT_EXISTS);
        }
        return task;
    }

    /**
     * 校验静态脱敏任务的任务非运行中，不满足约束时拒绝当前操作。
     */
    private void validateTaskNotRunning(Long id) {
        if (taskExecutor.isRunning(id)) {
            throw exception(STATIC_TASK_RUNNING);
        }
    }

    /**
     * 校验静态脱敏任务的任务名称在指定范围内唯一。
     */
    private void validateTaskNameUnique(Long id, String taskName) {
        SafetyStaticTaskDO exists = taskMapper.selectByTaskName(taskName);
        if (exists != null && !Objects.equals(exists.getId(), id)) {
            throw exception(STATIC_TASK_NAME_DUPLICATE);
        }
    }

    /**
     * 校验静态脱敏任务的任务，不满足约束时拒绝当前操作。
     */
    private void validateTask(SafetyStaticTaskSaveReqVO reqVO) {
        // 写入模式
        if (!SafetyStaticTaskDO.WRITE_MODE_OVERWRITE.equals(reqVO.getWriteMode())
                && !SafetyStaticTaskDO.WRITE_MODE_APPEND.equals(reqVO.getWriteMode())) {
            throw exception(STATIC_TASK_CONFIG_INVALID, "写入模式只能是 OVERWRITE（全量覆盖）或 APPEND（追加）");
        }
        // 源目标不能是同一张表
        if (Objects.equals(reqVO.getSourceDatabaseId(), reqVO.getTargetDatabaseId())
                && StrUtil.equalsIgnoreCase(reqVO.getSourceTableName(), reqVO.getTargetTableName())) {
            throw exception(STATIC_TASK_CONFIG_INVALID, "目标表不能与源表相同，静态脱敏不允许覆盖生产原表");
        }
        // Cron 表达式
        if (StrUtil.isNotBlank(reqVO.getCronExpression())
                && !CronExpression.isValidExpression(reqVO.getCronExpression())) {
            throw exception(STATIC_TASK_CRON_INVALID);
        }
        // 批次大小
        if (reqVO.getBatchSize() != null && reqVO.getBatchSize() < 1) {
            throw exception(STATIC_TASK_CONFIG_INVALID, "批次大小必须是正整数");
        }
        // 字段配置：字段不重复、规则存在且有生效版本
        Set<String> columnNames = new HashSet<>();
        for (SafetyStaticTaskFieldRuleVO fieldRule : reqVO.getFieldRules()) {
            if (!columnNames.add(fieldRule.getColumnName())) {
                throw exception(STATIC_TASK_CONFIG_INVALID,
                        String.format("脱敏字段【%s】重复配置", fieldRule.getColumnName()));
            }
            if (ruleService.resolveUsableMaskingSpec(fieldRule.getRuleId()) == null) {
                throw exception(STATIC_TASK_RULE_NOT_AVAILABLE, fieldRule.getRuleId());
            }
        }
    }

}
