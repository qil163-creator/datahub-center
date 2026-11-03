package cn.iocoder.yudao.module.governance.service.master;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterDistributeDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterDistributeLogDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterModelDO;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterDistributeLogMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterDistributeMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterModelMapper;
import cn.iocoder.yudao.module.governance.enums.master.MasterDistributeTypeEnum;
import cn.iocoder.yudao.module.governance.enums.master.MasterRunTypeEnum;
import cn.iocoder.yudao.module.governance.enums.master.MasterTaskTypeEnum;
import cn.iocoder.yudao.module.system.api.dept.DeptApi;
import cn.iocoder.yudao.module.system.api.dept.dto.DeptRespDTO;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import jakarta.annotation.Resource;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertList;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertMap;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertSet;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;

/**
 * 主数据派发 Service 实现类
 *
 * 参考 srt-cloud 的 DataGovernanceMasterDistribute，调度改用 XXL-Job + next_run_time 扫描，
 * 并修正其缺陷：已发布任务禁止修改/删除、配置按类型做服务端校验
 *
 * @author Li
 */
@Service
@Validated
public class MasterDistributeServiceImpl implements MasterDistributeService {

    @Resource
    private MasterDistributeMapper distributeMapper;

    @Resource
    private MasterPushCategoryService categoryService;

    @Resource
    private MasterDistributeLogMapper logMapper;

    @Resource
    private MasterModelMapper modelMapper;

    @Resource
    private MasterDistributeExecutor distributeExecutor;

    @Resource
    private MasterDistributeRunCoordinator coordinator;

    @Resource
    private DeptApi deptApi;

    @Resource
    private AdminUserApi adminUserApi;

    @Resource
    private MasterDistributeApiPolicy apiPolicy;

    @Resource
    private cn.iocoder.yudao.module.governance.dal.mysql.master.MasterDistributeCheckpointMapper checkpoints;

    @Resource
    private cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService securityFrameworkService;

    // ==================== CRUD ====================

    /**
     * 创建派发任务
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createDistribute(MasterDistributeSaveReqVO createReqVO) {
        if (Integer.valueOf(2).equals(createReqVO.getDistributeType()) && Integer.valueOf(1)
                .equals(createReqVO.getStatus())
                && !securityFrameworkService.hasPermission("governance:master-distribute:release")) {
            throw new org.springframework.security.access.AccessDeniedException("启用推送需要发布权限");
        }
        validateSaveReq(createReqVO);
        validateModelReference(createReqVO.getMasterModelId());
        if (createReqVO.getCategoryId() != null) {
            categoryService.validateReference(createReqVO.getCategoryId(), null);
        }
        MasterDistributeDO distribute = buildDistribute(createReqVO);
        distribute.setStatus(
                Integer.valueOf(2).equals(createReqVO.getDistributeType()) && createReqVO.getStatus() != null
                        ? createReqVO.getStatus() : MasterDistributeDO.STATUS_UNRELEASED);
        if (Integer.valueOf(2).equals(distribute.getDistributeType()) && Integer.valueOf(1)
                .equals(distribute.getStatus())
                && Integer.valueOf(2).equals(distribute.getTaskType())) {
            distribute.setNextRunTime(CronExpression.parse(distribute.getCron()).next(logMapper.databaseNow()));
        }
        distributeMapper.insert(distribute);
        return distribute.getId();
    }

    /**
     * 更新派发任务（已发布不可修改）
     */
    @Override
    public void updateDistribute(MasterDistributeSaveReqVO updateReqVO) {
        // 权限解析可能访问 RPC，必须在本地任务行锁之前完成。
        boolean releaseAllowed = securityFrameworkService.hasPermission("governance:master-distribute:release");
        coordinator.withIdleTask(updateReqVO.getId(), old -> {
            if ((Integer.valueOf(2).equals(old.getDistributeType()) && Integer.valueOf(1).equals(old.getStatus())
                    || Integer.valueOf(2).equals(updateReqVO.getDistributeType()) && Integer.valueOf(1)
                    .equals(updateReqVO.getStatus()))
                    && !releaseAllowed) {
                throw new org.springframework.security.access.AccessDeniedException("修改启用中的推送需要发布权限");
            }
            if (!Integer.valueOf(2).equals(old.getDistributeType())) {
                validateNotReleased(old);
            }
            if (Integer.valueOf(2).equals(updateReqVO.getDistributeType()) && updateReqVO.getConfig() != null
                    && updateReqVO.getConfig().getApi() != null) {
                MasterDistributeApiPolicy.restoreSecrets(old, updateReqVO.getConfig().getApi());
            }
            validateSaveReq(updateReqVO);
            validateModelReference(updateReqVO.getMasterModelId());
            if (updateReqVO.getCategoryId() != null) {
                categoryService.validateReference(updateReqVO.getCategoryId(), old.getCategoryId());
            }
            var updated = buildDistribute(updateReqVO);
            if (Integer.valueOf(2).equals(updated.getDistributeType())) {
                updated.setStatus(updateReqVO.getStatus() == null ? old.getStatus() : updateReqVO.getStatus());
                updated.setScheduleGeneration(
                        old.getScheduleGeneration() == null ? 1 : old.getScheduleGeneration() + 1);
            } else {
                updated.setStatus(old.getStatus());
            }
            distributeMapper.updateById(updated);
            if (updated.getCategoryId() == null && old.getCategoryId() != null) {
                distributeMapper.update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaUpdate(MasterDistributeDO.class)
                        .eq(MasterDistributeDO::getId, old.getId()).set(MasterDistributeDO::getCategoryId, null));
            }
            if (Integer.valueOf(2).equals(updated.getDistributeType())) {
                distributeMapper.update(null,
                        com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaUpdate(MasterDistributeDO.class)
                                .eq(MasterDistributeDO::getId, old.getId())
                                .set(MasterDistributeDO::getNextRunTime, Integer.valueOf(1).equals(updated.getStatus())
                                        && Integer.valueOf(2).equals(updated.getTaskType()) ? CronExpression.parse(
                                        updated.getCron()).next(logMapper.databaseNow()) : null)
                                .set(MasterDistributeDO::getCron, updated.getCron()));
            }
            if (!MasterDistributeApiPolicy.checkpointIdentity(old)
                    .equals(MasterDistributeApiPolicy.checkpointIdentity(updated))) {
                checkpoints.clear(old.getId());
            }
            return null;
        });
    }

    /**
     * 删除派发任务（已发布不可删除）
     */
    @Override
    public void deleteDistribute(Long id) {
        coordinator.withIdleTask(id, distribute -> {
            validateNotReleased(distribute);
            checkpoints.clear(id);
            distributeMapper.deleteById(id);
            return null;
        });
    }

    /**
     * 获得派发任务
     */
    @Override
    public MasterDistributeRespVO getDistribute(Long id) {
        MasterDistributeDO distribute = validateDistributeExists(id);
        return CollUtil.getFirst(convertToRespList(Collections.singletonList(distribute)));
    }

    /**
     * 获得派发任务分页
     */
    @Override
    public PageResult<MasterDistributeRespVO> getDistributePage(MasterDistributePageReqVO pageReqVO) {
        PageResult<MasterDistributeDO> pageResult = pageReqVO.getCategoryId() == null
                ? distributeMapper.selectPage(pageReqVO)
                : distributeMapper.selectPage(pageReqVO, categoryService.descendants(pageReqVO.getCategoryId()));
        return new PageResult<>(convertToRespList(pageResult.getList()), pageResult.getTotal());
    }

    // ==================== 发布 / 取消 / 手动执行 ====================

    /**
     * 发布：一次性任务立即执行一次，周期任务按 Cron 排期
     */
    @Override
    public void releaseDistribute(Long id) {
        distributeExecutor.dispatch(coordinator.release(id, SecurityFrameworkUtils.getLoginUserId()));
    }

    /**
     * 取消发布：停止后续排期，发送前取消，发送后保留未知结果。
     */
    @Override
    public void cancelDistribute(Long id) {
        coordinator.cancel(id);
    }

    /**
     * 手动执行一次（无论是否发布）
     */
    @Override
    public void handRun(Long id) {
        MasterDistributeDO distribute = validateDistributeExists(id);
        validateModelReleased(distribute.getMasterModelId());
        if (Integer.valueOf(2).equals(distribute.getDistributeType()) && !Integer.valueOf(1)
                .equals(distribute.getStatus())) {
            throw exception(MASTER_DISTRIBUTE_NOT_RELEASED);
        }
        if (Integer.valueOf(2).equals(distribute.getDistributeType())) {
            var accepted = coordinator.acceptApi(id);
            if (accepted == null) {
                throw exception(MASTER_DISTRIBUTE_RUNNING);
            }
            distributeExecutor.dispatch(accepted);
        } else if (!distributeExecutor.submit(id, MasterRunTypeEnum.HAND)) {
            throw exception(MASTER_DISTRIBUTE_RUNNING);
        }
    }

    /**
     * 启用或停用 REST 推送（不发送数据）。
     */
    @Override
    public void updateStatus(Long id, Integer status) {
        if (status == null) {
            throw exception(MASTER_DISTRIBUTE_CONFIG_INVALID, "启用状态不能为空");
        }
        coordinator.updateApiStatus(id, status, SecurityFrameworkUtils.getLoginUserId());
    }

    /**
     * 重试最近一次发送前失败的推送。
     */
    @Override
    public void retry(Long id) {
        distributeExecutor.dispatch(coordinator.retry(id));
    }

    // ==================== 执行记录 ====================

    /**
     * 停止指定运行，发布及周期排期保持不变。平台内拥有运行权限的管理员可操作。
     */
    @Override
    public MasterDistributeLogRespVO stopRun(MasterDistributeStopReqVO request) {
        return BeanUtils.toBean(coordinator.stop(request, SecurityFrameworkUtils.getLoginUserId()),
                MasterDistributeLogRespVO.class);
    }

    /**
     * 获得执行记录分页
     */
    @Override
    public PageResult<MasterDistributeLogRespVO> getDistributeLogPage(MasterDistributeLogPageReqVO pageReqVO) {
        PageResult<MasterDistributeLogDO> pageResult = logMapper.selectPage(pageReqVO);
        return BeanUtils.toBean(pageResult, MasterDistributeLogRespVO.class);
    }

    /**
     * 获得执行记录详情
     */
    @Override
    public MasterDistributeLogRespVO getDistributeLog(Long id) {
        MasterDistributeLogDO log = logMapper.selectById(id);
        if (log == null) {
            throw exception(MASTER_DISTRIBUTE_LOG_NOT_EXISTS);
        }
        return BeanUtils.toBean(log, MasterDistributeLogRespVO.class);
    }

    // ==================== 内部方法 ====================

    /**
     * 校验分发记录存在，不存在时抛出业务异常。
     */
    private MasterDistributeDO validateDistributeExists(Long id) {
        MasterDistributeDO distribute = distributeMapper.selectById(id);
        if (distribute == null) {
            throw exception(MASTER_DISTRIBUTE_NOT_EXISTS);
        }
        return distribute;
    }

    /**
     * 校验主数据分发的非已发布，不满足约束时拒绝当前操作。
     */
    private void validateNotReleased(MasterDistributeDO distribute) {
        if (distribute.getStatus() != null
                && distribute.getStatus() == MasterDistributeDO.STATUS_RELEASED) {
            throw exception(MASTER_DISTRIBUTE_RELEASED_CANNOT_MODIFY);
        }
    }

    /**
     * 校验主数据分发的模型已发布，不满足约束时拒绝当前操作。
     */
    private void validateModelReleased(Long modelId) {
        MasterModelDO model = modelMapper.selectById(modelId);
        if (model == null) {
            throw exception(MASTER_MODEL_NOT_EXISTS);
        }
        if (model.getStatus() == null || model.getStatus() != MasterModelDO.STATUS_RELEASED) {
            throw exception(MASTER_DISTRIBUTE_MODEL_NOT_RELEASED);
        }
        if (model.getPublishState() != null && !MasterModelDO.PUBLISH_READY.equals(model.getPublishState())) {
            throw exception(MASTER_MODEL_PUBLISH_UNCERTAIN);
        }
    }

    /** 引用写入与模型结构草稿保存共用父行锁，避免校验后模型已转为草稿。 */
    private void validateModelReference(Long modelId) {
        MasterModelDO model = modelMapper.selectForUpdate(modelId);
        if (model == null) {
            throw exception(MASTER_MODEL_NOT_EXISTS);
        }
        if (!Integer.valueOf(MasterModelDO.STATUS_RELEASED).equals(model.getStatus())) {
            throw exception(MASTER_DISTRIBUTE_MODEL_NOT_RELEASED);
        }
        if (model.getPublishState() != null && !MasterModelDO.PUBLISH_READY.equals(model.getPublishState())) {
            throw exception(MASTER_MODEL_PUBLISH_UNCERTAIN);
        }
    }

    /**
     * 保存前校验：模型已发布、Cron、按派发类型校验配置完整性
     */
    private void validateSaveReq(MasterDistributeSaveReqVO reqVO) {
        validateModelReleased(reqVO.getMasterModelId());
        if (MasterTaskTypeEnum.PERIODIC.getType().equals(reqVO.getTaskType())
                && (StrUtil.isBlank(reqVO.getCron()) || !CronExpression.isValidExpression(reqVO.getCron()))) {
            throw exception(MASTER_DISTRIBUTE_CRON_INVALID);
        }
        MasterDistributeConfigVO config = reqVO.getConfig();
        MasterDistributeTypeEnum type = MasterDistributeTypeEnum.of(reqVO.getDistributeType());
        if (type == null) {
            throw exception(MASTER_DISTRIBUTE_CONFIG_INVALID, "派发类型不合法");
        }
        switch (type) {
            case DB -> {
                if (config.getDb() == null || config.getDb().getDatabaseId() == null) {
                    throw exception(MASTER_DISTRIBUTE_CONFIG_INVALID, "请选择目标数据源");
                }
                if (config.getDb().getFetchSize() != null && config.getDb().getFetchSize() <= 0) {
                    throw exception(MASTER_DISTRIBUTE_CONFIG_INVALID, "批次大小必须大于 0");
                }
            }
            case API -> {
                try {
                    MasterDistributeExecutor.prepareApi(config.getApi());
                    apiPolicy.plan(reqVO.getMasterModelId(), config.getApi());
                } catch (IllegalArgumentException ex) {
                    // URI/客户端原异常可能含凭据或请求头，只返回领域校验错误。
                    throw exception(MASTER_DISTRIBUTE_CONFIG_INVALID,
                            "请检查 HTTP 配置、状态字段、增量主键及标准映射；GET 不支持推送");
                }
            }
            case MQ -> {
                try {
                    MasterDistributeExecutor.validateMq(config.getMq());
                } catch (IllegalArgumentException ex) {
                    throw exception(MASTER_DISTRIBUTE_CONFIG_INVALID,
                            "请检查队列地址、Topic/交换机/路由键及批次大小（1-10000）");
                }
            }
        }
    }

    /**
     * 组装 DO：配置按派发类型只保留对应节点后序列化
     */
    private MasterDistributeDO buildDistribute(MasterDistributeSaveReqVO reqVO) {
        MasterDistributeDO distribute = BeanUtils.toBean(reqVO, MasterDistributeDO.class);
        MasterDistributeConfigVO config = new MasterDistributeConfigVO();
        MasterDistributeTypeEnum type = MasterDistributeTypeEnum.of(reqVO.getDistributeType());
        switch (type) {
            case DB -> config.setDb(reqVO.getConfig().getDb());
            case API -> {
                var api = JsonUtils.parseObject(JsonUtils.toJsonString(reqVO.getConfig().getApi()),
                        MasterDistributeConfigVO.ApiConfig.class);
                MasterDistributeApiPolicy.separateSecrets(distribute, api);
                config.setApi(api);
            }
            case MQ -> config.setMq(reqVO.getConfig().getMq());
        }
        distribute.setDistributeJson(JsonUtils.toJsonString(config));
        // 一次性任务不保留 Cron
        if (!MasterTaskTypeEnum.PERIODIC.getType().equals(reqVO.getTaskType())) {
            distribute.setCron(null);
        }
        return distribute;
    }

    /**
     * 转换目标响应列表。
     */
    private List<MasterDistributeRespVO> convertToRespList(List<MasterDistributeDO> list) {
        if (CollUtil.isEmpty(list)) {
            return Collections.emptyList();
        }
        Map<Long, MasterModelDO> modelMap = convertMap(
                modelMapper.selectByIds(convertSet(list, MasterDistributeDO::getMasterModelId)),
                MasterModelDO::getId);
        Set<Long> orgIds = convertSet(list, MasterDistributeDO::getOrgId);
        Map<Long, DeptRespDTO> deptMap = CollUtil.isEmpty(orgIds)
                ? Collections.emptyMap() : deptApi.getDeptMap(orgIds);
        Set<Long> userIds = new java.util.HashSet<>(convertSet(list, MasterDistributeDO::getReleaseUserId));
        userIds.remove(null);
        list.forEach(task -> {
            addActor(userIds, task.getCreator());
            addActor(userIds, task.getUpdater());
        });
        Map<Long, AdminUserRespDTO> userMap = CollUtil.isEmpty(userIds)
                ? Collections.emptyMap() : adminUserApi.getUserMap(userIds);
        Map<Long, MasterDistributeLogDO> summaries = convertMap(
                logMapper.summaries(convertSet(list, MasterDistributeDO::getId)),
                MasterDistributeLogDO::getDistributeId);
        return convertList(list, distribute -> {
            MasterDistributeRespVO vo = BeanUtils.toBean(distribute, MasterDistributeRespVO.class);
            vo.setBlockingRunStatus(coordinator.blockingState(distribute.getId()));
            vo.setConfig(JsonUtils.parseObject(distribute.getDistributeJson(), MasterDistributeConfigVO.class));
            if (vo.getConfig() != null) {
                MasterDistributeApiPolicy.redact(distribute, vo.getConfig().getApi());
            }
            vo.setCreatorName(actorName(userMap, distribute.getCreator()));
            vo.setUpdaterName(actorName(userMap, distribute.getUpdater()));
            var summary = summaries.get(distribute.getId());
            vo.setTotalCount(summary == null ? 0L : summary.getTotalCount());
            vo.setSuccessCount(summary == null ? 0L : summary.getSuccessCount());
            vo.setFailureCount(summary == null ? 0L : summary.getFailureCount());
            vo.setUnknownCount(summary == null ? 0L : summary.getUnknownCount());
            if (summary != null) {
                vo.setLatestRunStatus(summary.getRunStatus());
                vo.setLastPushTime(summary.getStartTime());
            }
            MasterModelDO model = modelMap.get(distribute.getMasterModelId());
            if (model != null) {
                vo.setMasterModelName(model.getTableCn());
                vo.setMasterModelTableName(model.getTableName());
            }
            DeptRespDTO dept = distribute.getOrgId() == null ? null : deptMap.get(distribute.getOrgId());
            if (dept != null) {
                vo.setOrgName(dept.getName());
            }
            AdminUserRespDTO user = distribute.getReleaseUserId() == null
                    ? null : userMap.get(distribute.getReleaseUserId());
            if (user != null) {
                vo.setReleaseUserName(user.getNickname());
            }
            return vo;
        });
    }

    /**
     * 添加操作人。
     */
    private static void addActor(Set<Long> actors, String value) {
        try {
            if (value != null && Long.parseLong(value) > 0) {
                actors.add(Long.valueOf(value));
            }
        } catch (NumberFormatException ignored) {
        }
    }

    /**
     * 将操作人编号解析为展示名称。
     */
    private static String actorName(Map<Long, AdminUserRespDTO> users, String value) {
        try {
            var user = value == null ? null : users.get(Long.valueOf(value));
            return user == null ? value : user.getNickname();
        } catch (NumberFormatException ignored) {
            return value;
        }
    }

}
