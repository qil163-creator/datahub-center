package cn.iocoder.yudao.module.asset.service.quality;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.asset.controller.admin.quality.vo.AssetQualityTaskRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.quality.vo.AssetQualityTaskSaveReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.quality.AssetQualityTaskDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.quality.AssetQualityTaskRuleDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.quality.AssetQualityTaskMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.quality.AssetQualityTaskRuleMapper;
import cn.iocoder.yudao.module.governance.api.metadata.MetadataApi;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataColumnRespDTO;
import cn.iocoder.yudao.module.governance.api.quality.QualityTaskApi;
import cn.iocoder.yudao.module.governance.api.quality.dto.QualityAssetSyncReqDTO;
import cn.iocoder.yudao.module.governance.api.quality.dto.QualityTaskResultRespDTO;
import cn.iocoder.yudao.module.governance.api.quality.dto.QualityAssetEvaluationDTO;
import cn.iocoder.yudao.module.governance.api.quality.dto.QualityRunReqDTO;
import cn.iocoder.yudao.module.asset.service.dataasset.AssetCatalogService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.*;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_TASK_NOT_EXISTS;

@Service
@Validated
public class AssetQualityTaskServiceImpl implements AssetQualityTaskService {

    @Resource
    private AssetQualityTaskMapper taskMapper;

    @Resource
    private AssetQualityTaskRuleMapper taskRuleMapper;

    @Resource
    private DataAssetMapper dataAssetMapper;

    @Resource
    private MetadataApi metadataApi;

    @Resource
    private QualityTaskApi qualityTaskApi;

    @Resource
    private AssetCatalogService catalogService;

    @Resource
    private PlatformTransactionManager transactionManager;

    /**
     * 创建资产质量任务。
     */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Long createTask(AssetQualityTaskSaveReqVO reqVO) {
        DataAssetDO asset = validateTaskRequest(null, reqVO);
        List<MetadataColumnRespDTO> columns = listAssetColumns(asset);
        AssetQualityTaskDO task = BeanUtils.toBean(reqVO, AssetQualityTaskDO.class);
        task.setId(null);
        task.setRunStatus(AssetQualityTaskDO.RUN_STATUS_NEVER);
        task.setTotalCount(0L);
        task.setIssueCount(0L);
        task.setEvaluationJson(evaluationJson(reqVO));
        task.setRemoteError("定义已保存，等待同步质量引擎");
        transaction().executeWithoutResult(status -> {
            taskMapper.insert(task);
            saveRules(task.getId(), reqVO.getRules(), columns);
        });
        synchronize(task, asset, reqVO);
        return task.getId();
    }

    /**
     * 更新资产质量任务。
     */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void updateTask(AssetQualityTaskSaveReqVO reqVO) {
        AssetQualityTaskDO exists = validateTaskExists(reqVO.getId());
        if (!Objects.equals(exists.getAssetId(), reqVO.getAssetId())) {
            throw exception(ASSET_QUALITY_TASK_NOT_EXISTS);
        }
        DataAssetDO asset = validateTaskRequest(reqVO.getId(), reqVO);
        if (StrUtil.isNotBlank(exists.getPendingRunKey())) {
            throw exception(ASSET_QUALITY_EXECUTION_UNKNOWN);
        }
        if (StrUtil.isNotBlank(exists.getEvaluationJson()) != CollUtil.isNotEmpty(reqVO.getEvaluations())) {
            throw exception(ASSET_QUALITY_CONFIG_INVALID, "旧规则任务与任务内评测不能互换，请分别维护原任务");
        }
        List<MetadataColumnRespDTO> columns = listAssetColumns(asset);
        AssetQualityTaskDO definition = BeanUtils.toBean(reqVO, AssetQualityTaskDO.class);
        definition.setEvaluationJson(evaluationJson(reqVO));
        definition.setRemoteError("定义已保存，等待同步质量引擎");
        transaction().executeWithoutResult(status -> {
            taskMapper.updateById(definition);
            taskRuleMapper.deleteByTaskId(reqVO.getId());
            saveRules(reqVO.getId(), reqVO.getRules(), columns);
        });
        exists.setTaskName(reqVO.getTaskName());
        exists.setCronExpression(reqVO.getCronExpression());
        exists.setEnabled(reqVO.getEnabled());
        exists.setScheduleType(reqVO.getScheduleType());
        exists.setEvaluationJson(definition.getEvaluationJson());
        synchronize(exists, asset, reqVO);
    }

    /**
     * 删除资产质量任务。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteTask(Long id) {
        AssetQualityTaskDO task = validateTaskExists(id);
        requireMaintainableAsset(task.getAssetId());
        if (StrUtil.isNotBlank(task.getPendingRunKey())) {
            throw exception(ASSET_QUALITY_EXECUTION_UNKNOWN);
        }
        if (!deleteRemoteTasks(task)) {
            throw exception(DATA_ASSET_QUALITY_DELETE_PENDING);
        }
        taskRuleMapper.deleteByTaskId(id);
        taskMapper.deleteById(id);
    }

    /**
     * 获得资产质量任务列表。
     */
    @Override
    public List<AssetQualityTaskRespVO> getTaskList(Long assetId) {
        List<AssetQualityTaskDO> tasks = taskMapper.selectListByAssetId(assetId);
        if (tasks.isEmpty()) {
            return List.of();
        }
        List<Long> taskIds = tasks.stream().map(AssetQualityTaskDO::getId).toList();
        Map<Long, List<AssetQualityTaskRespVO.Rule>> ruleMap =
                taskRuleMapper.selectListByTaskIds(taskIds).stream()
                        .map(rule -> BeanUtils.toBean(rule, AssetQualityTaskRespVO.Rule.class))
                        .collect(Collectors.groupingBy(AssetQualityTaskRespVO.Rule::getTaskId,
                                LinkedHashMap::new, Collectors.toList()));
        List<AssetQualityTaskRespVO> result = BeanUtils.toBean(tasks, AssetQualityTaskRespVO.class);
        result.forEach(task -> {
            task.setRules(ruleMap.getOrDefault(task.getId(), List.of()));
            AssetQualityTaskDO definition = tasks.stream().filter(value -> Objects.equals(value.getId(), task.getId()))
                    .findFirst().orElseThrow();
            task.setEvaluations(parseEvaluations(definition));
            task.setPendingSync(task.getQualityTaskId() == null || StrUtil.isNotBlank(task.getRemoteError()));
            task.setExecutionUnknown(StrUtil.isNotBlank(definition.getPendingRunKey()));
            fillLatestResult(task);
        });
        return result;
    }

    /**
     * 执行资产质量任务（复用治理质量引擎）
     *
     *  @return 治理质量任务实例编号
     */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Long executeTask(Long id) {
        AssetQualityTaskDO task = validateTaskExists(id);
        DataAssetDO asset = requireMaintainableAsset(task.getAssetId());
        validateColumns(asset, taskRuleMapper.selectListByTaskIds(List.of(id)).stream()
                        .map(rule -> BeanUtils.toBean(rule, AssetQualityTaskSaveReqVO.Rule.class)).toList(),
                parseEvaluations(task));
        if (task.getQualityTaskId() == null || StrUtil.isNotBlank(task.getRemoteError())) {
            AssetQualityTaskSaveReqVO syncReq = new AssetQualityTaskSaveReqVO();
            syncReq.setTaskName(task.getTaskName());
            syncReq.setCronExpression(task.getCronExpression());
            syncReq.setEnabled(task.getEnabled());
            syncReq.setRules(taskRuleMapper.selectListByTaskIds(List.of(id)).stream().map(rule -> {
                AssetQualityTaskSaveReqVO.Rule item = new AssetQualityTaskSaveReqVO.Rule();
                item.setQualityRuleId(rule.getQualityRuleId());
                item.setAuditRuleId(rule.getAuditRuleId());
                item.setRuleName(rule.getRuleName());
                return item;
            }).toList());
            syncReq.setEvaluations(parseEvaluations(task));
            synchronize(task, asset, syncReq);
            task = validateTaskExists(id);
        }
        Set<Long> engineIds = new LinkedHashSet<>();
        taskRuleMapper.selectListByTaskIds(List.of(id)).forEach(rule -> {
            if (rule.getQualityEngineTaskId() != null) {
                engineIds.add(rule.getQualityEngineTaskId());
            }
        });
        if (task.getQualityTaskId() != null) {
            engineIds.add(task.getQualityTaskId());
        }
        if (engineIds.isEmpty()) {
            throw exception(ASSET_QUALITY_TASK_NOT_SYNCED);
        }
        String runKey = task.getPendingRunKey();
        if (StrUtil.isBlank(runKey)) {
            for (Long engineId : engineIds) {
                if (!Boolean.TRUE.equals(
                        qualityTaskApi.validateForRun(engineId, SecurityFrameworkUtils.getLoginUserId())
                                .getCheckedData())) {
                    throw exception(ASSET_QUALITY_CONFIG_INVALID, "质量任务尚未满足真实执行条件");
                }
            }
            String candidate = UUID.randomUUID().toString();
            // 首个并发调用持久化运行键；其它调用复用同键，由治理唯一约束负责排队幂等。
            taskMapper.update(null, new LambdaUpdateWrapper<AssetQualityTaskDO>().eq(AssetQualityTaskDO::getId, id)
                    .isNull(AssetQualityTaskDO::getPendingRunKey).set(AssetQualityTaskDO::getPendingRunKey, candidate));
            runKey = validateTaskExists(id).getPendingRunKey();
        }
        if (StrUtil.isBlank(runKey)) {
            throw exception(ASSET_QUALITY_EXECUTION_UNKNOWN);
        }
        Long lastInstanceId = null;
        try {
            for (Long engineId : engineIds) {
                var request = new QualityRunReqDTO();
                request.setQualityTaskId(engineId);
                request.setRequestKey("ASSET:" + id + ":" + runKey + ":" + engineId);
                request.setExecutionUserId(SecurityFrameworkUtils.getLoginUserId());
                lastInstanceId = qualityTaskApi.executeForRun(request).getCheckedData();
                if (lastInstanceId == null || lastInstanceId <= 0) {
                    throw exception(ASSET_QUALITY_EXECUTION_UNKNOWN);
                }
            }
        } catch (RuntimeException failure) {
            taskMapper.updateById(
                    AssetQualityTaskDO.builder().id(id).runStatus(AssetQualityTaskDO.RUN_STATUS_UNKNOWN).build());
            throw exception(ASSET_QUALITY_EXECUTION_UNKNOWN);
        }
        taskMapper.update(null, new LambdaUpdateWrapper<AssetQualityTaskDO>().eq(AssetQualityTaskDO::getId, id)
                .eq(AssetQualityTaskDO::getPendingRunKey, runKey).set(AssetQualityTaskDO::getPendingRunKey, null)
                .set(AssetQualityTaskDO::getRunStatus, AssetQualityTaskDO.RUN_STATUS_RUNNING)
                .set(AssetQualityTaskDO::getLastRunTime, LocalDateTime.now()));
        return lastInstanceId;
    }

    /**
     * 读取任务数量。
     */
    @Override
    public Map<Long, Integer> getTaskCounts(Collection<Long> assetIds) {
        if (CollUtil.isEmpty(assetIds)) {
            return Collections.emptyMap();
        }
        return taskMapper.selectListByAssetIds(assetIds).stream()
                .collect(Collectors.groupingBy(AssetQualityTaskDO::getAssetId,
                        Collectors.collectingAndThen(Collectors.counting(), Long::intValue)));
    }

    /**
     * 按资产编号删除资产质量任务记录。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByAssetId(Long assetId) {
        List<AssetQualityTaskDO> tasks = taskMapper.selectListByAssetId(assetId);
        if (tasks.isEmpty()) {
            return;
        }
        List<Long> taskIds = tasks.stream().map(AssetQualityTaskDO::getId).toList();
        boolean pending = false;
        for (AssetQualityTaskDO task : tasks) {
            if (!deleteRemoteTasks(task)) {
                pending = true;
            }
        }
        if (pending) {
            throw exception(DATA_ASSET_QUALITY_DELETE_PENDING);
        }
        taskRuleMapper.deleteByTaskIds(taskIds);
        taskMapper.deleteBatchIds(taskIds);
    }

    /**
     * 校验资产质量任务的任务请求，不满足约束时拒绝当前操作。
     */
    private DataAssetDO validateTaskRequest(Long id, AssetQualityTaskSaveReqVO reqVO) {
        DataAssetDO asset = requireMaintainableAsset(reqVO.getAssetId());
        if (!reqVO.isRuleSelectionValid()) {
            throw exception(ASSET_QUALITY_CONFIG_INVALID, "旧规则与任务内评测必须选择且仅选择一种");
        }
        if (reqVO.getScheduleType() == null || reqVO.getEnabled() == null
                || !List.of(0, 1).contains(reqVO.getScheduleType()) || !List.of(0, 1).contains(reqVO.getEnabled())) {
            throw exception(ASSET_QUALITY_CONFIG_INVALID, "调度类型或启用状态不合法");
        }
        if (reqVO.getScheduleType() == 1 && (StrUtil.isBlank(
                reqVO.getCronExpression()) || !CronExpression.isValidExpression(reqVO.getCronExpression()))) {
            throw exception(ASSET_QUALITY_TASK_CRON_REQUIRED);
        }
        AssetQualityTaskDO sameName =
                taskMapper.selectByAssetIdAndName(reqVO.getAssetId(), reqVO.getTaskName());
        if (sameName != null && !Objects.equals(id, sameName.getId())) {
            throw exception(ASSET_QUALITY_TASK_NAME_DUPLICATE);
        }
        validateColumns(asset, reqVO.getRules(), reqVO.getEvaluations());
        return asset;
    }

    /**
     * 校验资产质量任务的字段，不满足约束时拒绝当前操作。
     */
    private void validateColumns(DataAssetDO asset, List<AssetQualityTaskSaveReqVO.Rule> rules,
            List<QualityAssetEvaluationDTO> evaluations) {
        List<MetadataColumnRespDTO> columns = listAssetColumns(asset);
        Set<Long> columnIds = columns.stream()
                .map(MetadataColumnRespDTO::getId)
                .collect(Collectors.toSet());
        Set<String> physicalNames = columns.stream().map(MetadataColumnRespDTO::getColumnName)
                .collect(Collectors.toSet());
        Set<String> uniqueRules = new HashSet<>();
        for (AssetQualityTaskSaveReqVO.Rule rule : rules == null ? List.<AssetQualityTaskSaveReqVO.Rule>of() : rules) {
            if (rule.getMetaColumnId() != null && !columnIds.contains(rule.getMetaColumnId())) {
                throw exception(ASSET_QUALITY_COLUMN_INVALID);
            }
            if (StrUtil.isNotBlank(rule.getColumnName()) && !physicalNames.contains(rule.getColumnName())) {
                throw exception(ASSET_QUALITY_COLUMN_INVALID);
            }
            Long ruleId = rule.getQualityRuleId() != null ? rule.getQualityRuleId() : rule.getAuditRuleId();
            String uniqueKey = ruleId + ":" + Objects.toString(rule.getMetaColumnId(), "0");
            if (!uniqueRules.add(uniqueKey)) {
                throw exception(ASSET_QUALITY_TASK_NAME_DUPLICATE);
            }
        }
        for (var evaluation : evaluations == null ? List.<QualityAssetEvaluationDTO>of() : evaluations) {
            if (evaluation.getColumnNames() == null || !physicalNames.containsAll(evaluation.getColumnNames())) {
                throw exception(ASSET_QUALITY_COLUMN_INVALID);
            }
        }
    }

    /**
     * 保存资产质量任务的规则。
     */
    private void saveRules(Long taskId, List<AssetQualityTaskSaveReqVO.Rule> rules,
            List<MetadataColumnRespDTO> physicalColumns) {
        if (CollUtil.isEmpty(rules)) {
            return;
        }
        Map<Long, MetadataColumnRespDTO> columns = physicalColumns.stream()
                .collect(Collectors.toMap(MetadataColumnRespDTO::getId, Function.identity(), (a, b) -> a));
        taskRuleMapper.insertBatch(rules.stream().map(item -> {
            MetadataColumnRespDTO column = item.getMetaColumnId() == null
                    ? null : columns.get(item.getMetaColumnId());
            return AssetQualityTaskRuleDO.builder()
                    .taskId(taskId)
                    .auditRuleId(item.getAuditRuleId())
                    .qualityRuleId(item.getQualityRuleId())
                    .ruleName(item.getRuleName())
                    .dimensionName(item.getDimensionName())
                    .metaColumnId(item.getMetaColumnId())
                    .columnName(column == null ? item.getColumnName() : column.getColumnName())
                    .remark(item.getRemark())
                    .build();
        }).toList());
    }

    /**
     * 校验任务记录存在，不存在时抛出业务异常。
     */
    private AssetQualityTaskDO validateTaskExists(Long id) {
        AssetQualityTaskDO task = id == null ? null : taskMapper.selectById(id);
        if (task == null) {
            throw exception(ASSET_QUALITY_TASK_NOT_EXISTS);
        }
        return task;
    }

    /**
     * 同步质量引擎。
     */
    private void syncQualityEngine(AssetQualityTaskDO task, DataAssetDO asset,
            AssetQualityTaskSaveReqVO reqVO) {
        if (CollUtil.isNotEmpty(reqVO.getEvaluations())) {
            QualityAssetSyncReqDTO request = syncRequest(task, asset, reqVO);
            request.setEvaluations(reqVO.getEvaluations());
            Long engineId = qualityTaskApi.syncFromAsset(request).getCheckedData();
            if (engineId == null || engineId <= 0) {
                throw exception(ASSET_QUALITY_TASK_NOT_SYNCED);
            }
            markSynchronized(task, engineId);
            return;
        }
        List<AssetQualityTaskRuleDO> saved = taskRuleMapper.selectListByTaskIds(List.of(task.getId()));
        Long firstEngineId = null;
        for (AssetQualityTaskRuleDO rule : saved) {
            Long ruleId = rule.getQualityRuleId() != null ? rule.getQualityRuleId() : rule.getAuditRuleId();
            if (ruleId == null) {
                continue;
            }
            QualityAssetSyncReqDTO syncReq = syncRequest(task, asset, reqVO);
            syncReq.setRuleIds(List.of(ruleId));
            String bindingKey = rule.getMetaColumnId() == null
                    ? "rule-" + ruleId + "-table"
                    : "col-" + rule.getMetaColumnId();
            syncReq.setBindingKey(bindingKey);
            syncReq.setColumnName(rule.getColumnName());
            Long engineId = qualityTaskApi.syncFromAsset(syncReq).getCheckedData();
            if (engineId == null || engineId <= 0) {
                throw exception(ASSET_QUALITY_TASK_NOT_SYNCED);
            }
            taskRuleMapper.updateById(AssetQualityTaskRuleDO.builder()
                    .id(rule.getId())
                    .qualityEngineTaskId(engineId)
                    .build());
            if (firstEngineId == null) {
                firstEngineId = engineId;
            }
        }
        if (firstEngineId == null) {
            throw exception(ASSET_QUALITY_TASK_NOT_SYNCED);
        }
        markSynchronized(task, firstEngineId);
    }

    /**
     * 查询资产字段。
     */
    private List<MetadataColumnRespDTO> listAssetColumns(DataAssetDO asset) {
        if (asset.getDatasourceId() == null || asset.getMetaTableId() == null || StrUtil.isBlank(
                asset.getTableName())) {
            throw exception(DATA_ASSET_TECH_UNAVAILABLE);
        }
        catalogService.assertSingleTableCompatible(asset);
        var table = metadataApi.getTable(asset.getMetaTableId()).getCheckedData();
        if (table == null || !Objects.equals(table.getDatasourceId(), asset.getDatasourceId())
                || !Objects.equals(table.getTableName(), asset.getTableName())) {
            throw exception(DATA_ASSET_TECH_UNAVAILABLE);
        }
        List<MetadataColumnRespDTO> columns = metadataApi.getColumnList(asset.getMetaTableId()).getCheckedData();
        if (CollUtil.isEmpty(columns)) {
            throw exception(DATA_ASSET_TECH_UNAVAILABLE);
        }
        return columns;
    }

    /**
     * 校验资产质量任务的可维护资产，不满足约束时拒绝当前操作。
     */
    private DataAssetDO requireMaintainableAsset(Long assetId) {
        DataAssetDO asset = dataAssetMapper.selectById(assetId);
        if (asset == null) {
            throw exception(DATA_ASSET_NOT_EXISTS);
        }
        catalogService.assertCanMaintain(asset);
        return asset;
    }

    /**
     * 在平台事务边界内完成资产质量任务的本地读写。
     */
    private TransactionTemplate transaction() {
        var transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(10);
        return transaction;
    }

    /**
     * 将资产质量任务的评估规则序列化为持久化配置。
     */
    private static String evaluationJson(AssetQualityTaskSaveReqVO request) {
        return CollUtil.isEmpty(request.getEvaluations()) ? null : JsonUtils.toJsonString(request.getEvaluations());
    }

    /**
     * 解析资产质量任务的评估。
     */
    private static List<QualityAssetEvaluationDTO> parseEvaluations(AssetQualityTaskDO task) {
        return StrUtil.isBlank(task.getEvaluationJson()) ? List.of() : JsonUtils.parseArray(task.getEvaluationJson(),
                QualityAssetEvaluationDTO.class);
    }

    /**
     * 同步资产质量任务的请求。
     */
    private QualityAssetSyncReqDTO syncRequest(AssetQualityTaskDO task, DataAssetDO asset,
            AssetQualityTaskSaveReqVO request) {
        QualityAssetSyncReqDTO result = new QualityAssetSyncReqDTO();
        result.setAssetTaskId(task.getId());
        result.setTaskName(request.getTaskName());
        result.setDatasourceId(asset.getDatasourceId());
        result.setTableName(asset.getTableName());
        result.setTableId(asset.getMetaTableId());
        result.setCronExpression(Objects.equals(task.getScheduleType(), 1) ? request.getCronExpression() : null);
        result.setEnabled(request.getEnabled());
        result.setOwnerUserId(SecurityFrameworkUtils.getLoginUserId());
        return result;
    }

    /** 定义已先提交；同步失败保留配置与待核对状态，按原任务编号安全重试。 */
    private void synchronize(AssetQualityTaskDO task, DataAssetDO asset, AssetQualityTaskSaveReqVO request) {
        try {
            syncQualityEngine(task, asset, request);
        } catch (RuntimeException failure) {
            String message = failure instanceof ServiceException ? failure.getMessage() : "质量引擎同步结果未知，请按原任务编号重试核对";
            taskMapper.updateById(
                    AssetQualityTaskDO.builder().id(task.getId()).remoteError(StrUtil.maxLength(message, 1000))
                            .build());
            if (failure instanceof ServiceException) {
                throw failure;
            }
            throw exception(ASSET_QUALITY_TASK_NOT_SYNCED);
        }
    }

    /**
     * 标记已同步。
     */
    private void markSynchronized(AssetQualityTaskDO task, Long engineId) {
        taskMapper.update(null,
                new LambdaUpdateWrapper<AssetQualityTaskDO>().eq(AssetQualityTaskDO::getId, task.getId())
                        .set(AssetQualityTaskDO::getQualityTaskId, engineId)
                        .set(AssetQualityTaskDO::getRemoteError, null));
        task.setQualityTaskId(engineId);
        task.setRemoteError(null);
    }

    /**
     * 删除远程任务。
     */
    private boolean deleteRemoteTasks(AssetQualityTaskDO task) {
        Set<Long> engineIds = new LinkedHashSet<>();
        if (task.getQualityTaskId() != null) {
            engineIds.add(task.getQualityTaskId());
        }
        taskRuleMapper.selectListByTaskIds(List.of(task.getId())).forEach(rule -> {
            if (rule.getQualityEngineTaskId() != null) {
                engineIds.add(rule.getQualityEngineTaskId());
            }
        });
        String lastError = null;
        boolean pending = false;
        for (Long engineId : engineIds) {
            try {
                qualityTaskApi.delete(engineId).checkError();
            } catch (ServiceException ex) {
                if (Objects.equals(ex.getCode(), QUALITY_TASK_NOT_EXISTS.getCode())) {
                    continue;
                }
                pending = true;
                lastError = ex.getMessage();
            } catch (Exception ex) {
                pending = true;
                lastError = ex.getMessage();
            }
        }
        if (pending) {
            taskMapper.updateById(AssetQualityTaskDO.builder()
                    .id(task.getId())
                    .deletePending(1)
                    .remoteError(lastError == null || lastError.length() <= 1000
                            ? lastError : lastError.substring(0, 1000))
                    .build());
            return false;
        }
        return true;
    }

    /**
     * 补充资产质量任务的最新结果。
     */
    private void fillLatestResult(AssetQualityTaskRespVO task) {
        if (Boolean.TRUE.equals(task.getExecutionUnknown())) {
            task.setResultAvailable(Boolean.FALSE);
            task.setResultStatus("UNKNOWN");
            return;
        }
        if (StrUtil.isNotBlank(task.getRemoteError())) {
            task.setResultAvailable(Boolean.FALSE);
            task.setResultStatus("UNAVAILABLE");
            return;
        }
        if (task.getQualityTaskId() == null) {
            task.setResultAvailable(Boolean.FALSE);
            task.setResultStatus("NONE");
            return;
        }
        try {
            QualityTaskResultRespDTO result =
                    qualityTaskApi.getLatestResult(task.getQualityTaskId()).getCheckedData();
            if (result == null) {
                task.setResultAvailable(Boolean.FALSE);
                task.setResultStatus("NONE");
                return;
            }
            task.setResultAvailable(Boolean.TRUE);
            task.setLastRunTime(result.getStartTime());
            task.setLastFinishTime(result.getEndTime());
            task.setTotalCount(result.getTotalCount());
            task.setIssueCount(result.getFailCount());
            task.setQualityScore(result.getScore());
            if ("RUNNING".equals(result.getInstanceStatus())) {
                task.setRunStatus(AssetQualityTaskDO.RUN_STATUS_RUNNING);
                task.setResultStatus("RUNNING");
            } else if ("SUCCESS".equals(result.getInstanceStatus())) {
                task.setRunStatus(AssetQualityTaskDO.RUN_STATUS_SUCCESS);
                task.setResultStatus("SUCCESS");
            } else if ("FAILED".equals(result.getInstanceStatus())) {
                task.setRunStatus(AssetQualityTaskDO.RUN_STATUS_FAILED);
                task.setResultStatus("FAILED");
            } else {
                task.setResultStatus(result.getInstanceStatus());
            }
        } catch (Exception ignored) {
            task.setResultAvailable(Boolean.FALSE);
            task.setResultStatus("UNAVAILABLE");
        }
    }

}
