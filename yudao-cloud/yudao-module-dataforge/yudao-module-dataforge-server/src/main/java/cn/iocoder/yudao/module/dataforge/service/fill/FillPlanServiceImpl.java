package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillPlanPageReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillPlanRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillPlanSaveReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillPlanDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillPlanOrgAssigneeDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillPlanOrgDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillTableVersionDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillTargetScopeRegistryDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillPlanMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillPlanOrgAssigneeMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillPlanOrgMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillTargetScopeRegistryMapper;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillMergeStrategyEnum;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillPeriodTypeEnum;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillPlanStatusEnum;
import cn.iocoder.yudao.module.system.api.dept.DeptApi;
import cn.iocoder.yudao.module.system.api.dept.dto.DeptRespDTO;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertList;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertSet;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;

/**
 * 在线填报-采集计划 Service 实现类
 *
 * @author Li
 */
@Service
@Validated
public class FillPlanServiceImpl implements FillPlanService {

    @Resource
    private FillPlanMapper planMapper;

    @Resource
    private FillPlanCategoryService categoryService;

    @Resource
    private FillPlanOrgMapper planOrgMapper;

    @Resource
    private FillPlanOrgAssigneeMapper planOrgAssigneeMapper;

    @Resource
    private FillTargetScopeRegistryMapper targetScopeRegistryMapper;

    @Resource
    private FillTableVersionService tableVersionService;

    @Resource
    private FillTaskService fillTaskService;

    @Resource
    private DeptApi deptApi;

    @Resource
    private AdminUserApi adminUserApi;

    /**
     * 创建采集计划。
     *
     * @param createReqVO 创建参数
     * @return 生成或保存的记录编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createPlan(FillPlanSaveReqVO createReqVO) {
        // 校验模板版本存在（草稿阶段允许引用已停用版本，发布时再强校验有效性）
        tableVersionService.getTableVersion(createReqVO.getTableVersionId());
        FillPlanDO plan = BeanUtils.toBean(createReqVO, FillPlanDO.class);
        plan.setStatus(FillPlanStatusEnum.DRAFT.getStatus());
        applyDefaults(plan);
        planMapper.insert(plan);
        saveOrgs(plan.getId(), createReqVO.getOrgs());
        // 组织/人员远程快照完成后才获取分类事务锁，避免持锁执行跨服务调用
        categoryService.validateCategoryReference(plan.getCategoryId());
        return plan.getId();
    }

    /**
     * 更新计划；启用中的计划不允许变更模板版本与周期类型（只影响未来任务的字段可改）
     *
     * @param updateReqVO 更新参数
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updatePlan(FillPlanSaveReqVO updateReqVO) {
        FillPlanDO old = validatePlanExists(updateReqVO.getId());
        if (FillPlanStatusEnum.ENABLED.getStatus().equals(old.getStatus())
                && (!Objects.equals(old.getTableVersionId(), updateReqVO.getTableVersionId())
                || !Objects.equals(old.getPeriodType(), updateReqVO.getPeriodType()))) {
            // 启用中的计划只允许调整组织/人员/提醒等"只影响未来任务"的配置
            throw exception(FILL_PLAN_STATUS_INVALID);
        }
        tableVersionService.getTableVersion(updateReqVO.getTableVersionId());
        FillPlanDO update = BeanUtils.toBean(updateReqVO, FillPlanDO.class);
        applyDefaults(update);
        planMapper.updateById(update);
        planOrgMapper.deleteByPlanId(updateReqVO.getId());
        planOrgAssigneeMapper.deleteByPlanId(updateReqVO.getId());
        saveOrgs(updateReqVO.getId(), updateReqVO.getOrgs());
        categoryService.validateCategoryReference(updateReqVO.getCategoryId());
        planMapper.updateCategoryId(updateReqVO.getId(), updateReqVO.getCategoryId());
    }

    /**
     * 删除计划（仅草稿/停用状态）
     *
     * @param id 记录编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deletePlan(Long id) {
        FillPlanDO plan = validatePlanExists(id);
        if (FillPlanStatusEnum.ENABLED.getStatus().equals(plan.getStatus())) {
            throw exception(FILL_PLAN_STATUS_INVALID);
        }
        planMapper.deleteById(id);
        planOrgMapper.deleteByPlanId(id);
        planOrgAssigneeMapper.deleteByPlanId(id);
    }

    /**
     * 获得采集计划（含目标组织与填报人）。
     *
     * @param id 记录编号
     * @return 填报计划响应数据
     */
    @Override
    public FillPlanRespVO getPlan(Long id) {
        FillPlanDO plan = validatePlanExists(id);
        FillPlanRespVO respVO = BeanUtils.toBean(plan, FillPlanRespVO.class);
        if (plan.getCategoryId() != null) {
            respVO.setCategoryName(categoryService.getCategoryNameMap(List.of(plan.getCategoryId()))
                    .get(plan.getCategoryId()));
        }
        FillTableVersionDO version = tableVersionService.getTableVersion(plan.getTableVersionId());
        respVO.setTableName(version.getName());
        respVO.setTableVersionNo(version.getVersionNo());

        List<FillPlanOrgDO> orgs = planOrgMapper.selectListByPlanId(id);
        Map<Long, List<FillPlanOrgAssigneeDO>> assigneesByOrg = planOrgAssigneeMapper.selectListByPlanId(id)
                .stream().collect(Collectors.groupingBy(FillPlanOrgAssigneeDO::getPlanOrgId));
        respVO.setOrgs(convertList(orgs, org -> {
            FillPlanRespVO.OrgRespItem item = new FillPlanRespVO.OrgRespItem();
            item.setReportingOrgId(org.getReportingOrgId());
            item.setOrgName(org.getOrgName());
            item.setSort(org.getSort());
            item.setAssignees(convertList(
                    assigneesByOrg.getOrDefault(org.getId(), Collections.emptyList()), assignee -> {
                        FillPlanRespVO.AssigneeItem assigneeItem = new FillPlanRespVO.AssigneeItem();
                        assigneeItem.setUserId(assignee.getUserId());
                        assigneeItem.setUserName(assignee.getUserName());
                        return assigneeItem;
                    }));
            return item;
        }));
        return respVO;
    }

    /**
     * 获得采集计划分页。
     *
     * @param pageReqVO 分页查询条件
     * @return 采集计划分页结果
     */
    @Override
    public PageResult<FillPlanRespVO> getPlanPage(FillPlanPageReqVO pageReqVO) {
        List<Long> categoryIds = pageReqVO.getCategoryId() == null || pageReqVO.getCategoryId() == 0
                ? null : categoryService.getCategoryAndDescendantIds(pageReqVO.getCategoryId());
        PageResult<FillPlanDO> pageResult = planMapper.selectPage(pageReqVO, categoryIds);
        Map<Long, String> categoryNames = categoryService.getCategoryNameMap(pageResult.getList().stream()
                .map(FillPlanDO::getCategoryId).filter(Objects::nonNull).collect(Collectors.toSet()));
        List<FillPlanRespVO> list = convertList(pageResult.getList(), plan -> {
            FillPlanRespVO resp = BeanUtils.toBean(plan, FillPlanRespVO.class);
            if (plan.getCategoryId() != null) {
                resp.setCategoryName(categoryNames.get(plan.getCategoryId()));
            }
            return resp;
        });
        return new PageResult<>(list,
                pageResult.getTotal());
    }

    /**
     * 发布（启用）计划：完整性校验（模板版本有效、组织与填报人齐备、目标表写入冲突检测）、
     *  登记目标表写入范围、计算首次生成时点；一次性计划发布即生成任务
     *
     * @param id 记录编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void publishPlan(Long id) {
        FillPlanDO plan = validatePlanExists(id);
        if (FillPlanStatusEnum.ENABLED.getStatus().equals(plan.getStatus())) {
            throw exception(FILL_PLAN_STATUS_INVALID);
        }
        // 1. 模板版本必须有效（未停用）
        FillTableVersionDO version = tableVersionService.getActiveTableVersion(plan.getTableVersionId());
        // 2. 组织与填报人齐备
        List<FillPlanOrgDO> orgs = planOrgMapper.selectListByPlanId(id);
        if (CollUtil.isEmpty(orgs)) {
            throw exception(FILL_PLAN_NO_ORG);
        }
        Map<Long, List<FillPlanOrgAssigneeDO>> assigneesByOrg = planOrgAssigneeMapper.selectListByPlanId(id)
                .stream().collect(Collectors.groupingBy(FillPlanOrgAssigneeDO::getPlanOrgId));
        for (FillPlanOrgDO org : orgs) {
            if (CollUtil.isEmpty(assigneesByOrg.get(org.getId()))) {
                throw exception(FILL_PLAN_ORG_NO_ASSIGNEE, org.getOrgName());
            }
        }
        // 3. 目标表写入冲突检测 + 登记
        registerTargetScope(plan, version);
        // 4. 计算生成位点并启用
        FillPeriodConfig config = FillPeriodCalculator.parseConfig(plan.getPeriodConfig());
        LocalDateTime now = LocalDateTime.now();
        FillPlanDO update = new FillPlanDO();
        update.setId(id);
        update.setStatus(FillPlanStatusEnum.ENABLED.getStatus());
        boolean once = FillPeriodTypeEnum.ONCE.getType().equals(plan.getPeriodType());
        update.setNextGenerateTime(once ? null
                : FillPeriodCalculator.computeFirstGenerateTime(plan.getPeriodType(), config, now));
        planMapper.updateById(update);
        // 5. 一次性计划：发布即生成任务
        if (once) {
            fillTaskService.generateForPlan(plan, now);
        }
    }

    /**
     * 停用计划：停止未来任务生成并释放目标表写入登记；已生成任务不受影响
     *
     * @param id 记录编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void disablePlan(Long id) {
        FillPlanDO plan = validatePlanExists(id);
        if (!FillPlanStatusEnum.ENABLED.getStatus().equals(plan.getStatus())) {
            throw exception(FILL_PLAN_STATUS_INVALID);
        }
        FillPlanDO update = new FillPlanDO();
        update.setId(id);
        update.setStatus(FillPlanStatusEnum.DISABLED.getStatus());
        update.setNextGenerateTime(null);
        planMapper.updateById(update);
        // 释放目标表写入登记
        targetScopeRegistryMapper.selectListByPlanId(id).forEach(registry -> {
            FillTargetScopeRegistryDO release = new FillTargetScopeRegistryDO();
            release.setId(registry.getId());
            release.setStatus(0);
            targetScopeRegistryMapper.updateById(release);
        });
    }

    // ---------------------------------------------------------------- 内部方法

    /**
     * 校验计划存在性。
     *
     * @param id 记录编号
     * @return 填报计划
     */
    private FillPlanDO validatePlanExists(Long id) {
        FillPlanDO plan = planMapper.selectById(id);
        if (plan == null) {
            throw exception(FILL_PLAN_NOT_EXISTS);
        }
        return plan;
    }

    /**
     * 应用默认值。
     *
     * @param plan 计划
     */
    private void applyDefaults(FillPlanDO plan) {
        if (plan.getOverduePolicy() == null) {
            plan.setOverduePolicy(1);
        }
        if (plan.getPublishApprovalEnabled() == null) {
            plan.setPublishApprovalEnabled(0);
        }
        if (plan.getPrefillLastPeriod() == null) {
            plan.setPrefillLastPeriod(0);
        }
        if (StrUtil.isBlank(plan.getTimeZone())) {
            plan.setTimeZone("Asia/Shanghai");
        }
        if (StrUtil.isBlank(plan.getPeriodConfig())) {
            plan.setPeriodConfig("{}");
        }
    }

    /**
     * 保存组织。
     *
     * @param planId 计划编号
     * @param orgItems 组织项
     */
    private void saveOrgs(Long planId, List<FillPlanSaveReqVO.OrgItem> orgItems) {
        if (CollUtil.isEmpty(orgItems)) {
            return;
        }
        // 组织与人员快照：组织架构后续变化不影响已配置计划
        Set<Long> orgIds = convertSet(orgItems, FillPlanSaveReqVO.OrgItem::getReportingOrgId);
        Map<Long, DeptRespDTO> deptMap = deptApi.getDeptMap(orgIds);
        Set<Long> userIds = new HashSet<>();
        orgItems.forEach(item -> {
            if (CollUtil.isNotEmpty(item.getAssigneeUserIds())) {
                userIds.addAll(item.getAssigneeUserIds());
            }
        });
        Map<Long, AdminUserRespDTO> userMap = CollUtil.isEmpty(userIds)
                ? Collections.emptyMap() : adminUserApi.getUserMap(userIds);

        int sort = 0;
        for (FillPlanSaveReqVO.OrgItem item : orgItems) {
            DeptRespDTO dept = deptMap.get(item.getReportingOrgId());
            FillPlanOrgDO org = FillPlanOrgDO.builder()
                    .planId(planId)
                    .reportingOrgId(item.getReportingOrgId())
                    .orgName(dept != null ? dept.getName() : String.valueOf(item.getReportingOrgId()))
                    .sort(item.getSort() != null ? item.getSort() : sort)
                    .build();
            planOrgMapper.insert(org);
            sort++;
            if (CollUtil.isEmpty(item.getAssigneeUserIds())) {
                continue;
            }
            for (Long userId : item.getAssigneeUserIds().stream().distinct().toList()) {
                AdminUserRespDTO user = userMap.get(userId);
                planOrgAssigneeMapper.insert(FillPlanOrgAssigneeDO.builder()
                        .planId(planId)
                        .planOrgId(org.getId())
                        .userId(userId)
                        .userName(user != null ? user.getNickname() : String.valueOf(userId))
                        .deptId(user != null ? user.getDeptId() : null)
                        .build());
            }
        }
    }

    /**
     * 目标表写入冲突检测与登记：同一目标表上，任一方为 REPLACE_SCOPE 的多计划并存会互相覆盖，拒绝发布
     */
    private void registerTargetScope(FillPlanDO plan, FillTableVersionDO version) {
        Map<?, ?> targetMapping = JsonUtils.parseObject(version.getTargetMapping(), Map.class);
        Long databaseId = Long.valueOf(String.valueOf(targetMapping.get("databaseId")));
        String tableName = String.valueOf(targetMapping.get("tableName"));

        List<FillTargetScopeRegistryDO> actives = targetScopeRegistryMapper
                .selectActiveListByTarget(databaseId, tableName).stream()
                .filter(registry -> !registry.getPlanId().equals(plan.getId()))
                .toList();
        boolean selfReplace = FillMergeStrategyEnum.REPLACE_SCOPE.getStrategy().equals(version.getMergeStrategy());
        List<Long> conflictPlanIds = actives.stream()
                .filter(registry -> selfReplace
                        || FillMergeStrategyEnum.REPLACE_SCOPE.getStrategy().equals(registry.getMergeStrategy()))
                .map(FillTargetScopeRegistryDO::getPlanId)
                .toList();
        if (CollUtil.isNotEmpty(conflictPlanIds)) {
            throw exception(FILL_PLAN_TARGET_SCOPE_CONFLICT,
                    "计划" + conflictPlanIds + "已登记写入目标表[" + tableName + "]且存在范围替换策略");
        }

        FillTargetScopeRegistryDO exists = targetScopeRegistryMapper
                .selectByTargetAndPlan(databaseId, tableName, plan.getId());
        if (exists != null) {
            FillTargetScopeRegistryDO update = new FillTargetScopeRegistryDO();
            update.setId(exists.getId());
            update.setTableVersionId(version.getId());
            update.setMergeStrategy(version.getMergeStrategy());
            update.setStatus(1);
            targetScopeRegistryMapper.updateById(update);
        } else {
            targetScopeRegistryMapper.insert(FillTargetScopeRegistryDO.builder()
                    .targetDatabaseId(databaseId)
                    .targetTableName(tableName)
                    .planId(plan.getId())
                    .tableVersionId(version.getId())
                    .mergeStrategy(version.getMergeStrategy())
                    .scopeDesc("reportingOrgId + periodKey")
                    .status(1)
                    .build());
        }
    }

}
