package cn.iocoder.yudao.module.governance.service.whitelist;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.governance.api.security.dto.SafetyMaskExemptRespDTO;
import cn.iocoder.yudao.module.governance.controller.admin.whitelist.vo.SafetyWhitelistPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.whitelist.vo.SafetyWhitelistSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.whitelist.SafetyWhitelistApprovalDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.whitelist.SafetyWhitelistDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.subject.SafetyWhitelistSubjectDO;
import cn.iocoder.yudao.module.governance.dal.mysql.whitelist.SafetyWhitelistApprovalMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.whitelist.SafetyWhitelistMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.subject.SafetyWhitelistSubjectMapper;
import cn.iocoder.yudao.module.system.api.permission.PermissionApi;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertList;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;

/**
 * 脱敏白名单 Service 实现类
 *
 * 审批流采用内置状态机（参照标准数据元）：DRAFT → PENDING → APPROVED / REJECTED；
 * 运行时豁免（getMaskExempt）要求：已审批 + 启用 + 有效期内 + 主体匹配（USER/ROLE/DEPT）。
 *
 * @author Li
 */
@Slf4j
@Service
@Validated
public class SafetyWhitelistServiceImpl implements SafetyWhitelistService {

    @Resource
    private SafetyWhitelistMapper whitelistMapper;

    @Resource
    private SafetyWhitelistSubjectMapper whitelistSubjectMapper;

    @Resource
    private SafetyWhitelistApprovalMapper approvalMapper;

    @Resource
    private AdminUserApi adminUserApi;

    @Resource
    private PermissionApi permissionApi;

    /**
     * 创建脱敏白名单
     *
     *  @param createReqVO 创建信息
     *  @return 编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createWhitelist(SafetyWhitelistSaveReqVO createReqVO) {
        validateTimeRange(createReqVO);
        // 插入（新建为草稿状态，需提交审批后方可生效）
        SafetyWhitelistDO whitelist = BeanUtils.toBean(createReqVO, SafetyWhitelistDO.class);
        whitelist.setId(null);
        whitelist.setApprovalStatus(SafetyWhitelistDO.APPROVAL_STATUS_DRAFT);
        whitelist.setApprover(null);
        whitelistMapper.insert(whitelist);


        // 插入子表
        createWhitelistSubjectList(whitelist.getId(), createReqVO.getWhitelistSubjects());
        // 返回
        return whitelist.getId();
    }

    /**
     * 更新脱敏白名单
     *
     *  @param updateReqVO 更新信息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateWhitelist(SafetyWhitelistSaveReqVO updateReqVO) {
        // 校验存在
        SafetyWhitelistDO whitelist = validateWhitelistExists(updateReqVO.getId());
        requireApprovalManaged(whitelist);
        // 审批中不允许修改
        if (SafetyWhitelistDO.APPROVAL_STATUS_PENDING.equals(whitelist.getApprovalStatus())) {
            throw exception(WHITELIST_PENDING_READONLY);
        }
        validateTimeRange(updateReqVO);
        // 更新；已通过/已驳回的白名单修改后回到草稿，需重新审批
        SafetyWhitelistDO updateObj = BeanUtils.toBean(updateReqVO, SafetyWhitelistDO.class);
        updateObj.setApprovalStatus(SafetyWhitelistDO.APPROVAL_STATUS_DRAFT);
        whitelistMapper.updateById(updateObj);

        // 更新子表
        updateWhitelistSubjectList(updateReqVO.getId(), updateReqVO.getWhitelistSubjects());
    }

    /**
     * 校验脱敏白名单生效时间不晚于失效时间。
     */
    private void validateTimeRange(SafetyWhitelistSaveReqVO reqVO) {
        if (reqVO.getEffectiveTime() != null && reqVO.getExpireTime() != null
                && reqVO.getEffectiveTime().isAfter(reqVO.getExpireTime())) {
            throw exception(WHITELIST_TIME_RANGE_INVALID);
        }
    }

    /**
     * 删除脱敏白名单
     *
     *  @param id 编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteWhitelist(Long id) {
        // 校验存在
        validateWhitelistExists(id);
        // 删除
        whitelistMapper.deleteById(id);

        // 删除子表
        deleteWhitelistSubjectByWhitelistId(id);
    }

    /**
     * 批量删除脱敏白名单
     *
     *  @param ids 编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteWhitelistListByIds(List<Long> ids) {
        // 删除
        whitelistMapper.deleteByIds(ids);

        // 删除子表
        deleteWhitelistSubjectByWhitelistIds(ids);
    }


    /**
     * 校验白名单记录存在，不存在时抛出业务异常。
     */
    private SafetyWhitelistDO validateWhitelistExists(Long id) {
        SafetyWhitelistDO whitelist = whitelistMapper.selectForUpdate(id);
        if (whitelist == null) {
            throw exception(WHITELIST_NOT_EXISTS);
        }
        return whitelist;
    }

    /**
     * 获得脱敏白名单
     *
     *  @param id 编号
     *  @return 脱敏白名单
     */
    @Override
    public SafetyWhitelistDO getWhitelist(Long id) {
        return whitelistMapper.selectById(id);
    }

    /**
     * 获得脱敏白名单分页
     *
     *  @param pageReqVO 分页查询
     *  @return 脱敏白名单分页
     */
    @Override
    public PageResult<SafetyWhitelistDO> getWhitelistPage(SafetyWhitelistPageReqVO pageReqVO) {
        return whitelistMapper.selectPage(pageReqVO);
    }

    /**
     * 校验白名单属于人工审批流程，拒绝修改直接授权记录的审批状态。
     */
    private static void requireApprovalManaged(SafetyWhitelistDO row) {
        // 直接授权的记录不进入人工审批状态机，保留本地授权依据与审批历史的独立语义。
        if (StrUtil.isNotBlank(row.getAuthorizationBasis()) || SafetyWhitelistDO.APPROVAL_STATUS_AUTHORIZED.equals(
                row.getApprovalStatus())) {
            throw exception(WHITELIST_APPROVAL_STATUS_INVALID);
        }
    }

    // ==================== 审批流 ====================

    /**
     * 提交审批：DRAFT / REJECTED → PENDING
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submitWhitelist(Long id) {
        SafetyWhitelistDO whitelist = validateWhitelistExists(id);
        requireApprovalManaged(whitelist);
        if (!SafetyWhitelistDO.APPROVAL_STATUS_DRAFT.equals(whitelist.getApprovalStatus())
                && !SafetyWhitelistDO.APPROVAL_STATUS_REJECTED.equals(whitelist.getApprovalStatus())) {
            throw exception(WHITELIST_APPROVAL_STATUS_INVALID);
        }
        whitelistMapper.updateById(SafetyWhitelistDO.builder()
                .id(id).approvalStatus(SafetyWhitelistDO.APPROVAL_STATUS_PENDING)
                .build());
        approvalMapper.insert(SafetyWhitelistApprovalDO.builder()
                .whitelistId(id).action(SafetyWhitelistApprovalDO.ACTION_SUBMIT)
                .opinion(whitelist.getApplyReason())
                .build());
    }

    /**
     * 审批通过：PENDING → APPROVED，并自动启用
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void approveWhitelist(Long id, String opinion) {
        SafetyWhitelistDO whitelist = validateWhitelistExists(id);
        requireApprovalManaged(whitelist);
        if (!SafetyWhitelistDO.APPROVAL_STATUS_PENDING.equals(whitelist.getApprovalStatus())) {
            throw exception(WHITELIST_APPROVAL_STATUS_INVALID);
        }
        Long approverId = SecurityFrameworkUtils.getLoginUserId();
        whitelistMapper.updateById(SafetyWhitelistDO.builder()
                .id(id).approvalStatus(SafetyWhitelistDO.APPROVAL_STATUS_APPROVED)
                .status(SafetyWhitelistDO.STATUS_ENABLE)
                .approver(approverId == null ? null : String.valueOf(approverId))
                .build());
        approvalMapper.insert(SafetyWhitelistApprovalDO.builder()
                .whitelistId(id).action(SafetyWhitelistApprovalDO.ACTION_APPROVE)
                .opinion(opinion)
                .build());
    }

    /**
     * 审批驳回：PENDING → REJECTED，并停用
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rejectWhitelist(Long id, String opinion) {
        SafetyWhitelistDO whitelist = validateWhitelistExists(id);
        requireApprovalManaged(whitelist);
        if (!SafetyWhitelistDO.APPROVAL_STATUS_PENDING.equals(whitelist.getApprovalStatus())) {
            throw exception(WHITELIST_APPROVAL_STATUS_INVALID);
        }
        Long approverId = SecurityFrameworkUtils.getLoginUserId();
        whitelistMapper.updateById(SafetyWhitelistDO.builder()
                .id(id).approvalStatus(SafetyWhitelistDO.APPROVAL_STATUS_REJECTED)
                .status(SafetyWhitelistDO.STATUS_DISABLE)
                .approver(approverId == null ? null : String.valueOf(approverId))
                .build());
        approvalMapper.insert(SafetyWhitelistApprovalDO.builder()
                .whitelistId(id).action(SafetyWhitelistApprovalDO.ACTION_REJECT)
                .opinion(opinion)
                .build());
    }

    /**
     * 获得白名单审批记录列表
     */
    @Override
    public List<SafetyWhitelistApprovalDO> getWhitelistApprovalList(Long whitelistId) {
        return approvalMapper.selectListByWhitelistId(whitelistId);
    }

    // ==================== 运行时消费 ====================

    /**
     * 查询用户是否命中脱敏白名单（已审批 + 启用 + 有效期内 + 主体匹配）
     *
     *  @param userId 管理端用户编号
     *  @param scene  豁免场景（如 QUERY），白名单配置为 ALL 时通配
     */
    @Override
    public SafetyMaskExemptRespDTO getMaskExempt(Long userId, String scene) {
        return getMaskExempt(userId, scene, false);
    }

    /**
     * 生成凭据的调用方不能将不可用的主体、角色或部门校验视为无豁免。
     */
    @Override
    public SafetyMaskExemptRespDTO getMaskExemptStrict(Long userId, String scene) {
        if (userId == null || userId <= 0 || StrUtil.isBlank(scene)) {
            throw proofUnavailable();
        }
        return getMaskExempt(userId, scene, true);
    }

    /**
     * 读取脱敏豁免。
     */
    private SafetyMaskExemptRespDTO getMaskExempt(Long userId, String scene, boolean strict) {
        SafetyMaskExemptRespDTO resp = new SafetyMaskExemptRespDTO();
        resp.setExempt(false);
        if (userId == null || StrUtil.isBlank(scene)) {
            return resp;
        }
        // 严格入口必须区分“没有有效白名单”和“查询结果缺失”，后者不能作为完整的豁免证明。
        List<SafetyWhitelistDO> actives = whitelistMapper.selectActiveList(scene, LocalDateTime.now());
        if (strict && actives == null) {
            throw proofUnavailable();
        }
        if (CollUtil.isEmpty(actives)) {
            return resp;
        }
        for (SafetyWhitelistDO whitelist : actives) {
            if (strict && (whitelist == null || whitelist.getId() == null)) {
                throw proofUnavailable();
            }
            if (!globalScope(whitelist, strict)) {
                continue;
            }
            var subjects = whitelistSubjectMapper.selectListByWhitelistId(whitelist.getId());
            if (strict && (subjects == null || subjects.stream()
                    .anyMatch(s -> s == null || s.getSubjectId() == null || s.getSubjectId() <= 0))) {
                throw proofUnavailable();
            }
            List<Long> subjectIds = convertList(
                    subjects,
                    SafetyWhitelistSubjectDO::getSubjectId);
            if (CollUtil.isEmpty(subjectIds)) {
                continue;
            }
            if (matchSubject(whitelist.getSubjectType(), subjectIds, userId, strict)) {
                resp.setExempt(true);
                resp.setWhitelistId(whitelist.getId());
                resp.setWhitelistName(whitelist.getName());
                return resp;
            }
        }
        return resp;
    }

    /**
     * 仅匹配调用方已核实的分类，不能把局部范围提升为全局。
     */
    @Override
    public Set<Long> getCategoryExemptionsStrict(Long userId, String scene, Set<Long> categoryIds) {
        if (userId == null || userId <= 0 || StrUtil.isBlank(scene) || categoryIds == null || categoryIds.size() > 1000
                || categoryIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw proofUnavailable();
        }
        if (categoryIds.isEmpty()) {
            return Set.of();
        }
        var active = whitelistMapper.selectActiveList(scene, LocalDateTime.now());
        if (active == null) {
            throw proofUnavailable();
        }
        var result = new java.util.HashSet<Long>();
        for (var row : active) {
            if (row == null || row.getId() == null) {
                throw proofUnavailable();
            }
            // 分类豁免只返回本次请求且主体命中的分类，不能扩展成整次查询的全局豁免。
            if (!"CATEGORY".equals(row.getObjectType()) || !categoryIds.contains(row.getObjectId())) {
                continue;
            }
            var subjects = whitelistSubjectMapper.selectListByWhitelistId(row.getId());
            if (subjects == null || subjects.stream()
                    .anyMatch(v -> v == null || v.getSubjectId() == null || v.getSubjectId() <= 0)) {
                throw proofUnavailable();
            }
            var ids = subjects.stream().map(SafetyWhitelistSubjectDO::getSubjectId).toList();
            if (!ids.isEmpty() && matchSubject(row.getSubjectType(), ids, userId, true)) {
                result.add(row.getObjectId());
            }
        }
        return Set.copyOf(result);
    }

    /** 当前协议没有实际对象上下文，局部范围不能被解释为整次查询的全局豁免。 */
    private static boolean globalScope(SafetyWhitelistDO row, boolean strict) {
        if (row == null) {
            return false;
        }
        if (SafetyWhitelistDO.OBJECT_TYPE_GLOBAL.equals(row.getObjectType()) && row.getObjectId() == null) {
            return true;
        }
        if (("CATEGORY".equals(row.getObjectType()) || "CATALOG".equals(row.getObjectType()))
                && row.getObjectId() != null && row.getObjectId() > 0) {
            return false;
        }
        if (strict) {
            throw proofUnavailable();
        }
        return false;
    }

    /**
     * 主体匹配：USER 直接比对；DEPT 取用户部门比对；ROLE 查询拥有这些角色的用户集合比对
     */
    private boolean matchSubject(String subjectType, List<Long> subjectIds, Long userId, boolean strict) {
        try {
            switch (StrUtil.nullToEmpty(subjectType)) {
                case SafetyWhitelistDO.SUBJECT_TYPE_USER -> {
                    return subjectIds.contains(userId);
                }
                case SafetyWhitelistDO.SUBJECT_TYPE_DEPT -> {
                    AdminUserRespDTO user = adminUserApi.getUser(userId).getCheckedData();
                    if (strict && (user == null || user.getDeptId() == null)) {
                        throw proofUnavailable();
                    }
                    return user != null && user.getDeptId() != null
                            && subjectIds.contains(user.getDeptId());
                }
                case SafetyWhitelistDO.SUBJECT_TYPE_ROLE -> {
                    Set<Long> userIds = permissionApi.getUserRoleIdListByRoleIds(subjectIds).getCheckedData();
                    if (strict && userIds == null) {
                        throw proofUnavailable();
                    }
                    return userIds != null && userIds.stream().anyMatch(id -> Objects.equals(id, userId));
                }
                default -> {
                    if (strict) {
                        throw proofUnavailable();
                    }
                    return false;
                }
            }
        } catch (Exception ex) {
            // 远程主体核验失败时严格入口中止调用；普通入口仅按未命中处理。
            if (strict) {
                throw proofUnavailable();
            }
            log.warn("[matchSubject][主体匹配失败 subjectType({}) userId({})：{}]",
                    subjectType, userId, ex.getMessage());
            return false;
        }
    }

    /**
     * 构造无法完整核验脱敏豁免时的业务异常。
     */
    private static cn.iocoder.yudao.framework.common.exception.ServiceException proofUnavailable() {
        return exception(cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.PERMISSION_SQL_PARSE_FAIL,
                "当前脱敏豁免无法完整核验");
    }

    /**
     * 统计生效中的白名单数量（已审批 + 启用 + 有效期内）
     */
    @Override
    public Long countActiveWhitelist() {
        return whitelistMapper.selectActiveCount(LocalDateTime.now());
    }

    // ==================== 子表（脱敏白名单生效主体） ====================

    /**
     * 获得脱敏白名单生效主体列表
     *
     *  @param whitelistId 所属白名单编号
     *  @return 脱敏白名单生效主体列表
     */
    @Override
    public List<SafetyWhitelistSubjectDO> getWhitelistSubjectListByWhitelistId(Long whitelistId) {
        return whitelistSubjectMapper.selectListByWhitelistId(whitelistId);
    }

    /**
     * 创建白名单主体列表。
     */
    private void createWhitelistSubjectList(Long whitelistId, List<SafetyWhitelistSubjectDO> list) {
        if (CollUtil.isEmpty(list)) {
            return;
        }
        list.forEach(o -> {
            o.setId(null);
            o.setWhitelistId(whitelistId);
            o.clean();
        });
        whitelistSubjectMapper.insertBatch(list);
    }

    /**
     * 更新白名单主体列表。
     */
    private void updateWhitelistSubjectList(Long whitelistId, List<SafetyWhitelistSubjectDO> list) {
        if (list == null) {
            // 未提交子表数据时不更新主体明细（如列表页仅切换状态）
            return;
        }
        // 先删后插做全量替换：前端提交的明细不带 id，且 (whitelist_id, subject_id) 有唯一约束
        whitelistSubjectMapper.deleteByWhitelistId(whitelistId);
        if (CollUtil.isEmpty(list)) {
            return;
        }
        list.forEach(o -> {
            o.setId(null);
            o.setWhitelistId(whitelistId);
            o.clean();
        });
        whitelistSubjectMapper.insertBatch(list);
    }

    /**
     * 按白名单编号删除白名单主体。
     */
    private void deleteWhitelistSubjectByWhitelistId(Long whitelistId) {
        whitelistSubjectMapper.deleteByWhitelistId(whitelistId);
    }

    /**
     * 按白名单编号删除白名单主体。
     */
    private void deleteWhitelistSubjectByWhitelistIds(List<Long> whitelistIds) {
        whitelistSubjectMapper.deleteByWhitelistIds(whitelistIds);
    }

}
