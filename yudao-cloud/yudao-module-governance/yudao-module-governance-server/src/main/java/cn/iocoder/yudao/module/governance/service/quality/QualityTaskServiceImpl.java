package cn.iocoder.yudao.module.governance.service.quality;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.governance.api.quality.dto.QualityAssetSyncReqDTO;
import cn.iocoder.yudao.module.governance.api.quality.dto.QualityTaskResultRespDTO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.task.QualityTaskPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.task.QualityTaskRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.task.QualityTaskSaveReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.task.QualityTaskObjectSaveReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.task.QualityTaskEvaluationSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.*;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.*;
import jakarta.annotation.Resource;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertList;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;

/**
 * 数据治理-数据质量-质量任务 Service 实现
 *
 * @author Li
 */
@Service
@Validated
public class QualityTaskServiceImpl implements QualityTaskService {

    @Resource
    private QualityTaskMapper taskMapper;

    @Resource
    private QualityTaskObjectMapper taskObjectMapper;

    @Resource
    private QualityTaskRuleMapper taskRuleMapper;

    @Resource
    private QualityTaskInstanceMapper instanceMapper;

    @Resource
    private QualityRuleSetMapper ruleSetMapper;

    @Resource
    private QualityTaskExecutor taskExecutor;

    @Resource
    private QualityCheckObjectMapper checkObjectMapper;

    @Resource
    private QualityRuleMapper ruleMapper;

    @Resource
    private QualityTaskEvaluationService evaluationService;

    /**
     * 创建质量任务。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createTask(QualityTaskSaveReqVO createReqVO) {
        validateSaveReq(null, createReqVO);
        QualityTaskDO task = BeanUtils.toBean(createReqVO, QualityTaskDO.class);
        applyDefaults(task);
        if (task.getOwnerUserId() == null) {
            task.setOwnerUserId(SecurityFrameworkUtils.getLoginUserId());
        }
        taskMapper.insert(task);
        if (createReqVO.getObjects() != null) {
            evaluationService.save(task.getId(), createReqVO);
        } else {
            saveRelations(task.getId(), createReqVO);
        }
        return task.getId();
    }

    /**
     * 更新质量任务。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateTask(QualityTaskSaveReqVO updateReqVO) {
        validateTaskExists(updateReqVO.getId());
        validateSaveReq(updateReqVO.getId(), updateReqVO);
        if (taskExecutor.isRunning(updateReqVO.getId())) {
            throw exception(QUALITY_TASK_RUNNING);
        }
        taskMapper.updateById(BeanUtils.toBean(updateReqVO, QualityTaskDO.class));
        taskRuleMapper.deleteByTaskId(updateReqVO.getId());
        if (updateReqVO.getObjects() != null) {
            evaluationService.save(updateReqVO.getId(), updateReqVO);
        } else {
            taskObjectMapper.deleteByTaskId(updateReqVO.getId());
            saveRelations(updateReqVO.getId(), updateReqVO);
        }
    }

    /**
     * 更新任务状态（上线/下线）
     */
    @Override
    public void updateTaskStatus(Long id, Integer taskStatus) {
        validateTaskExists(id);
        if (!List.of(0, 1).contains(taskStatus)) {
            throw exception(AUDIT_RULE_CONFIG_INVALID, "任务状态不合法");
        }
        taskMapper.updateById(QualityTaskDO.builder().id(id).taskStatus(taskStatus).build());
    }

    /**
     * 删除质量任务。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteTask(Long id) {
        validateTaskExists(id);
        if (taskExecutor.isRunning(id)) {
            throw exception(QUALITY_TASK_RUNNING);
        }
        taskMapper.deleteById(id);
        taskObjectMapper.deleteByTaskId(id);
        taskRuleMapper.deleteByTaskId(id);
        evaluationService.delete(id);
    }

    /**
     * 获得质量任务详情。
     */
    @Override
    public QualityTaskDO getTask(Long id) {
        return taskMapper.selectById(id);
    }

    /**
     * 任务详情（含关联对象/规则编号、最近运行情况）
     */
    @Override
    public QualityTaskRespVO getTaskDetail(Long id) {
        QualityTaskDO task = validateTaskExists(id);
        QualityTaskRespVO respVO = BeanUtils.toBean(task, QualityTaskRespVO.class);
        respVO.setObjectIds(convertList(taskObjectMapper.selectListByTaskId(id), QualityTaskObjectDO::getObjectId));
        respVO.setRuleIds(convertList(taskRuleMapper.selectListByTaskId(id), QualityTaskRuleDO::getRuleId));
        respVO.setObjectCount(respVO.getObjectIds().size());
        respVO.setRuleCount(respVO.getRuleIds().size());
        fillRuleSetName(List.of(respVO));
        fillLastRun(List.of(respVO));
        evaluationService.fill(respVO);
        return respVO;
    }

    /**
     * 获得质量任务分页。
     */
    @Override
    public PageResult<QualityTaskRespVO> getTaskPage(QualityTaskPageReqVO pageReqVO) {
        PageResult<QualityTaskDO> pageResult = taskMapper.selectPage(pageReqVO);
        PageResult<QualityTaskRespVO> result = BeanUtils.toBean(pageResult, QualityTaskRespVO.class);
        // 批量统计关联对象数/规则数，避免逐行查询
        List<Long> taskIds = convertList(result.getList(), QualityTaskRespVO::getId);
        Map<Long, Long> objectCountMap = taskObjectMapper.selectListByTaskIds(taskIds).stream()
                .collect(Collectors.groupingBy(QualityTaskObjectDO::getTaskId, Collectors.counting()));
        Map<Long, Long> ruleCountMap = taskRuleMapper.selectListByTaskIds(taskIds).stream()
                .collect(Collectors.groupingBy(QualityTaskRuleDO::getTaskId, Collectors.counting()));
        for (QualityTaskRespVO vo : result.getList()) {
            vo.setObjectCount(objectCountMap.getOrDefault(vo.getId(), 0L).intValue());
            vo.setRuleCount(ruleCountMap.getOrDefault(vo.getId(), 0L).intValue());
            long evaluations = evaluationService.count(vo.getId());
            if (evaluations > 0) {
                vo.setRuleCount(Math.toIntExact(evaluations));
            }
        }
        fillRuleSetName(result.getList());
        fillLastRun(result.getList());
        return result;
    }

    /**
     * 获得质量任务精简列表。
     */
    @Override
    public List<QualityTaskDO> getTaskSimpleList() {
        return taskMapper.selectList(new LambdaQueryWrapperX<QualityTaskDO>()
                .orderByDesc(QualityTaskDO::getId));
    }

    /**
     * 手动执行一次
     *
     *  @return 任务实例编号
     */
    @Override
    public Long executeTask(Long id) {
        QualityTaskDO task = validateTaskExists(id);
        if (taskExecutor.isRunning(id)) {
            throw exception(QUALITY_TASK_RUNNING);
        }
        Long instanceId = taskExecutor.submit(task, QualityTaskInstanceDO.RUN_TYPE_MANUAL,
                SecurityFrameworkUtils.getLoginUserNickname());
        if (instanceId == null) {
            throw exception(QUALITY_TASK_RUNNING);
        }
        return instanceId;
    }

    /**
     * 调度触发执行（XXL-Job 扫描 cron 到期后调用）
     */
    @Override
    public void executeTaskBySchedule(Long id) {
        QualityTaskDO task = taskMapper.selectById(id);
        if (task == null || !Objects.equals(task.getTaskStatus(), QualityTaskDO.STATUS_ONLINE)
                || taskExecutor.isRunning(id)) {
            return;
        }
        taskExecutor.submit(task, QualityTaskInstanceDO.RUN_TYPE_SCHEDULE, "schedule");
    }

    /**
     * 资产质量任务同步为治理质量任务（编码 ASSET-{assetTaskId}）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long syncFromAsset(QualityAssetSyncReqDTO reqDTO) {
        if (!reqDTO.isRuleSelectionValid()) {
            throw exception(QUALITY_TASK_RULE_EMPTY);
        }
        boolean inline = CollUtil.isNotEmpty(reqDTO.getEvaluations());
        for (Long ruleId : inline ? List.<Long>of() : reqDTO.getRuleIds()) {
            if (ruleMapper.selectById(ruleId) == null) {
                throw exception(QUALITY_RULE_NOT_EXISTS);
            }
        }
        Long objectId = inline ? null : findOrCreateAssetCheckObject(reqDTO);
        String taskCode = "ASSET-" + reqDTO.getAssetTaskId()
                + (inline ? "-inline" : StrUtil.isBlank(reqDTO.getBindingKey()) ? "" : "-" + reqDTO.getBindingKey());
        QualityTaskDO existing = taskMapper.selectByTaskCode(taskCode);
        QualityTaskSaveReqVO saveReqVO = new QualityTaskSaveReqVO();
        saveReqVO.setId(existing == null ? null : existing.getId());
        saveReqVO.setTaskName(uniqueAssetTaskName(reqDTO.getTaskName(), reqDTO.getAssetTaskId(),
                existing == null ? null : existing.getId()));
        saveReqVO.setTaskCode(taskCode);
        saveReqVO.setTaskCategory("ASSET");
        if (inline) {
            QualityTaskObjectSaveReqVO object = new QualityTaskObjectSaveReqVO();
            object.setClientKey("asset");
            object.setObjectName(StrUtil.maxLength("ASSET-" + reqDTO.getAssetTaskId(), 128));
            object.setDatasourceConnId(reqDTO.getDatasourceId());
            object.setTableName(reqDTO.getTableName());
            object.setTableId(reqDTO.getTableId());
            saveReqVO.setObjects(List.of(object));
            saveReqVO.setEvaluations(reqDTO.getEvaluations().stream().map(input -> {
                var evaluation = BeanUtils.toBean(input, QualityTaskEvaluationSaveReqVO.class);
                evaluation.setObjectKey("asset");
                return evaluation;
            }).toList());
        } else {
            saveReqVO.setObjectIds(List.of(objectId));
            saveReqVO.setRuleIds(reqDTO.getRuleIds());
        }
        saveReqVO.setOwnerUserId(reqDTO.getOwnerUserId());
        saveReqVO.setDescription("由资产质量任务同步");
        boolean enabled = Objects.equals(reqDTO.getEnabled(), 1);
        if (enabled && StrUtil.isNotBlank(reqDTO.getCronExpression())) {
            saveReqVO.setScheduleType(QualityTaskDO.SCHEDULE_TIMER);
            saveReqVO.setCronExpression(reqDTO.getCronExpression());
            saveReqVO.setTaskStatus(QualityTaskDO.STATUS_ONLINE);
        } else {
            saveReqVO.setScheduleType(QualityTaskDO.SCHEDULE_MANUAL);
            saveReqVO.setCronExpression(null);
            saveReqVO.setTaskStatus(enabled ? QualityTaskDO.STATUS_ONLINE : QualityTaskDO.STATUS_OFFLINE);
        }
        if (existing == null) {
            return createTask(saveReqVO);
        }
        updateTask(saveReqVO);
        return existing.getId();
    }

    /**
     * 最近一次执行结果；无实例时返回 null
     */
    @Override
    public QualityTaskResultRespDTO getLatestResult(Long taskId) {
        QualityTaskInstanceDO last = instanceMapper.selectLastByTaskId(taskId);
        if (last == null) {
            return null;
        }
        QualityTaskResultRespDTO result = new QualityTaskResultRespDTO();
        result.setTaskId(taskId);
        result.setInstanceStatus(last.getInstanceStatus());
        result.setStartTime(last.getStartTime());
        result.setEndTime(last.getEndTime());
        result.setTotalCount(last.getTotalCount());
        result.setFailCount(last.getFailCount());
        result.setScore(last.getScore());
        return result;
    }

    /**
     * 查找或创建资产检查对象。
     */
    private Long findOrCreateAssetCheckObject(QualityAssetSyncReqDTO reqDTO) {
        QualityCheckObjectDO existing = checkObjectMapper.selectByDatasourceAndTable(
                reqDTO.getDatasourceId(), reqDTO.getTableName());
        if (existing != null) {
            return existing.getId();
        }
        QualityCheckObjectDO checkObject = QualityCheckObjectDO.builder()
                .objectName("ASSET-" + reqDTO.getDatasourceId() + "-" + reqDTO.getTableName())
                .datasourceConnId(reqDTO.getDatasourceId())
                .tableName(reqDTO.getTableName())
                .tableId(reqDTO.getTableId())
                .remark("由资产质量任务自动创建")
                .build();
        checkObjectMapper.insert(checkObject);
        return checkObject.getId();
    }

    /**
     * 为资产关联质量任务生成不冲突的名称。
     */
    private String uniqueAssetTaskName(String taskName, Long assetTaskId, Long currentId) {
        String name = StrUtil.maxLength(StrUtil.blankToDefault(taskName, "资产质量任务"), 120);
        QualityTaskDO sameName = taskMapper.selectByName(name);
        if (sameName != null && !Objects.equals(sameName.getId(), currentId)) {
            name = StrUtil.maxLength(name, 100) + "-" + assetTaskId;
        }
        return name;
    }

    /**
     * 应用默认值。
     */
    private void applyDefaults(QualityTaskDO task) {
        if (StrUtil.isBlank(task.getExecuteStrategy())) {
            task.setExecuteStrategy(QualityTaskDO.STRATEGY_SERIAL);
        }
        if (StrUtil.isBlank(task.getScheduleType())) {
            task.setScheduleType(QualityTaskDO.SCHEDULE_MANUAL);
        }
        if (task.getTaskStatus() == null) {
            task.setTaskStatus(QualityTaskDO.STATUS_OFFLINE);
        }
        if (StrUtil.isBlank(task.getPriority())) {
            task.setPriority("MID");
        }
        if (task.getRetryCount() == null) {
            task.setRetryCount(0);
        }
    }

    /**
     * 保存质量任务的关联。
     */
    private void saveRelations(Long taskId, QualityTaskSaveReqVO reqVO) {
        if (CollUtil.isNotEmpty(reqVO.getObjectIds())) {
            taskObjectMapper.insertBatch(reqVO.getObjectIds().stream().distinct()
                    .map(objectId -> QualityTaskObjectDO.builder().taskId(taskId).objectId(objectId).build())
                    .toList());
        }
        if (CollUtil.isNotEmpty(reqVO.getRuleIds())) {
            taskRuleMapper.insertBatch(reqVO.getRuleIds().stream().distinct()
                    .map(ruleId -> QualityTaskRuleDO.builder().taskId(taskId).ruleId(ruleId).build())
                    .toList());
        }
    }

    /**
     * 校验质量任务的保存请求，不满足约束时拒绝当前操作。
     */
    private void validateSaveReq(Long id, QualityTaskSaveReqVO reqVO) {
        evaluationService.validate(reqVO);
        if (reqVO.getExecuteStrategy() != null && !List.of("SERIAL", "PARALLEL").contains(reqVO.getExecuteStrategy())) {
            throw exception(AUDIT_RULE_CONFIG_INVALID, "不支持的执行策略");
        }
        if (reqVO.getTaskStatus() != null && !List.of(0, 1).contains(reqVO.getTaskStatus())) {
            throw exception(AUDIT_RULE_CONFIG_INVALID, "任务状态不合法");
        }
        if (reqVO.getRetryCount() != null && (reqVO.getRetryCount() < 0 || reqVO.getRetryCount() > 10)
                || reqVO.getRetryInterval() != null && (reqVO.getRetryInterval() < 0 || reqVO.getRetryInterval() > 1440)
                || reqVO.getDelayExecTime() != null && (reqVO.getDelayExecTime() < 0 || reqVO.getDelayExecTime() > 1440)) {
            throw exception(AUDIT_RULE_CONFIG_INVALID, "重试与延迟参数超出范围");
        }
        QualityTaskDO sameName = taskMapper.selectByName(reqVO.getTaskName());
        if (sameName != null && !Objects.equals(sameName.getId(), id)) {
            throw exception(QUALITY_TASK_NAME_DUPLICATE);
        }
        if (QualityTaskDO.SCHEDULE_TIMER.equals(reqVO.getScheduleType())) {
            if (StrUtil.isBlank(reqVO.getCronExpression())
                    || !CronExpression.isValidExpression(reqVO.getCronExpression())) {
                throw exception(QUALITY_TASK_CRON_INVALID);
            }
        }
        if (reqVO.getRuleSetId() != null && ruleSetMapper.selectById(reqVO.getRuleSetId()) == null) {
            throw exception(QUALITY_RULE_SET_NOT_EXISTS);
        }
    }

    /**
     * 校验任务记录存在，不存在时抛出业务异常。
     */
    private QualityTaskDO validateTaskExists(Long id) {
        QualityTaskDO task = id == null ? null : taskMapper.selectById(id);
        if (task == null) {
            throw exception(QUALITY_TASK_NOT_EXISTS);
        }
        return task;
    }

    /**
     * 补充质量任务的规则集合名称。
     */
    private void fillRuleSetName(List<QualityTaskRespVO> list) {
        List<Long> ruleSetIds = list.stream().map(QualityTaskRespVO::getRuleSetId)
                .filter(Objects::nonNull).distinct().toList();
        if (ruleSetIds.isEmpty()) {
            return;
        }
        List<QualityRuleSetDO> ruleSets = ruleSetMapper.selectBatchIds(ruleSetIds);
        list.forEach(vo -> ruleSets.stream()
                .filter(rs -> Objects.equals(rs.getId(), vo.getRuleSetId()))
                .findFirst()
                .ifPresent(rs -> vo.setRuleSetName(rs.getSetName())));
    }

    /**
     * 补充质量任务的末个运行。
     */
    private void fillLastRun(List<QualityTaskRespVO> list) {
        if (list.isEmpty()) {
            return;
        }
        // 一次查出这些任务的实例（按 id 倒序），内存中取每个任务的最近一条
        List<Long> taskIds = convertList(list, QualityTaskRespVO::getId);
        Map<Long, QualityTaskInstanceDO> lastMap = new java.util.HashMap<>();
        instanceMapper.selectList(new LambdaQueryWrapperX<QualityTaskInstanceDO>()
                        .in(QualityTaskInstanceDO::getTaskId, taskIds)
                        .orderByDesc(QualityTaskInstanceDO::getId))
                .forEach(instance -> lastMap.putIfAbsent(instance.getTaskId(), instance));
        for (QualityTaskRespVO vo : list) {
            QualityTaskInstanceDO last = lastMap.get(vo.getId());
            if (last != null) {
                vo.setLastRunStatus(last.getInstanceStatus());
                vo.setLastRunTime(last.getStartTime());
            }
        }
    }

}
