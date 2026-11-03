package cn.iocoder.yudao.module.governance.service.whitelist;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.governance.controller.admin.whitelist.vo.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.whitelist.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.subject.SafetyWhitelistSubjectDO;
import cn.iocoder.yudao.module.governance.dal.mysql.whitelist.*;
import cn.iocoder.yudao.module.governance.dal.mysql.subject.SafetyWhitelistSubjectMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.category.SafetyCategoryMapper;
import cn.iocoder.yudao.module.governance.service.category.SafetyClassificationReferenceService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;

/** 主体有效性由事务外正式用户 API 核验；这里只持有本地定义锁及行锁。 */
@Service
public class SafetyWhitelistWorkbenchWriter {

    @Resource
    private SafetyWhitelistMapper mapper;

    @Resource
    private SafetyWhitelistSubjectMapper subjects;

    @Resource
    private SafetyWhitelistApprovalMapper approvals;

    @Resource
    private SafetyCategoryMapper categories;

    @Resource
    private SafetyClassificationReferenceService definitions;

    /**
     * 保存脱敏白名单工作台配置或业务记录。
     */
    @Transactional(rollbackFor = Exception.class)
    public Long save(SafetyWhitelistWorkbenchSaveReqVO req, boolean creating) {
        requireActor();
        categories.lockDefinitions();
        if (req.getStartTime().isAfter(req.getEndTime())) {
            throw exception(WHITELIST_TIME_RANGE_INVALID);
        }
        if (req.getEndTime().getYear() > 9998) {
            throw exception(SAFETY_MASK_INVALID, "结束日期超出范围");
        }
        if (new HashSet<>(req.getUserIds()).size() != req.getUserIds().size()) {
            throw exception(SAFETY_MASK_INVALID, "生效用户不能重复");
        }
        var definition = definitions.get(req.getCategoryId());
        if (definition == null || !Objects.equals(definition.getStatus(), (short) 0)) {
            throw exception(CATEGORY_NOT_EXISTS);
        }
        SafetyWhitelistDO row;
        if (creating) {
            if (req.getId() != null || req.getRevision() != null) {
                throw exception(SAFETY_MASK_INVALID, "新增不能指定已有白名单版本");
            }
            row = new SafetyWhitelistDO();
            row.setRevision(1L);
        } else {
            row = locked(req.getId(), req.getRevision());
            if (!Objects.equals(row.getObjectId(), req.getCategoryId())) {
                throw exception(SAFETY_MASK_INVALID, "修改不能改变数据分类");
            }
            if (SafetyWhitelistDO.APPROVAL_STATUS_PENDING.equals(row.getApprovalStatus())) {
                throw exception(WHITELIST_PENDING_READONLY);
            }
        }
        var clash = mapper.selectList(
                new cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX<SafetyWhitelistDO>()
                        .eq(SafetyWhitelistDO::getObjectType, "CATEGORY").eq(SafetyWhitelistDO::getSubjectType, "USER")
                        .eq(SafetyWhitelistDO::getScene, "QUERY")
                        .eq(SafetyWhitelistDO::getObjectId, req.getCategoryId()));
        if (clash.stream().anyMatch(v -> !Objects.equals(v.getId(), row.getId()))) {
            throw exception(SAFETY_MASK_INVALID, "该分类已建立白名单");
        }
        if (creating) {
            row.setName(req.getName());
            row.setSubjectType("USER");
            row.setObjectType("CATEGORY");
            row.setObjectId(req.getCategoryId());
            row.setScene("QUERY");
            row.setEffectiveTime(req.getStartTime().atStartOfDay());
            row.setExpireTime(req.getEndTime().plusDays(1).atStartOfDay().minusNanos(1000));
            row.setStatus(req.getStatus());
            row.setDescription(req.getDescription());
            row.setApprovalStatus(SafetyWhitelistDO.APPROVAL_STATUS_AUTHORIZED);
            row.setAuthorizationBasis("LOCAL_POLICY_AUTHORIZATION");
            row.setAuthorizationReason("由本地白名单管理权限配置的分类范围授权");
            mapper.insert(row);
        } else {
            var change = changed(row).set(SafetyWhitelistDO::getName, req.getName())
                    .set(SafetyWhitelistDO::getStatus, req.getStatus())
                    .set(SafetyWhitelistDO::getDescription, req.getDescription())
                    .set(SafetyWhitelistDO::getEffectiveTime, req.getStartTime().atStartOfDay())
                    .set(SafetyWhitelistDO::getExpireTime, req.getEndTime().plusDays(1).atStartOfDay().minusNanos(1000))
                    .set(SafetyWhitelistDO::getApprovalStatus, SafetyWhitelistDO.APPROVAL_STATUS_AUTHORIZED)
                    .set(SafetyWhitelistDO::getApprover, null)
                    .set(SafetyWhitelistDO::getAuthorizationBasis, "LOCAL_POLICY_AUTHORIZATION")
                    .set(SafetyWhitelistDO::getAuthorizationReason, "由本地白名单管理权限重新配置的分类范围授权");
            requireChanged(mapper.update(null, change));
        }
        subjects.deleteByWhitelistId(row.getId());
        for (Long user : req.getUserIds()) {
            subjects.insert(SafetyWhitelistSubjectDO.builder().whitelistId(row.getId()).subjectId(user).build());
        }
        audit(row.getId(),
                "保存分类策略；配置摘要=" + cn.hutool.crypto.digest.DigestUtil.sha256Hex(JsonUtils.toJsonString(req)));
        return row.getId();
    }

    /**
     * 变更脱敏白名单工作台的状态。
     */
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(SafetyWhitelistWorkbenchStatusReqVO req) {
        categories.lockDefinitions();
        var row = locked(req.getId(), req.getRevision());
        if (SafetyWhitelistDO.APPROVAL_STATUS_PENDING.equals(row.getApprovalStatus())) {
            throw exception(WHITELIST_PENDING_READONLY);
        }
        if (Objects.equals(req.getStatus(), (short) 0) && !SafetyWhitelistDO.APPROVAL_STATUS_AUTHORIZED.equals(
                row.getApprovalStatus())
                && !SafetyWhitelistDO.APPROVAL_STATUS_APPROVED.equals(row.getApprovalStatus())) {
            throw exception(WHITELIST_APPROVAL_STATUS_INVALID);
        }
        requireChanged(mapper.update(null, changed(row).set(SafetyWhitelistDO::getStatus, req.getStatus())));
        audit(row.getId(), "修改分类白名单状态为" + req.getStatus());
    }

    /**
     * 校验目标后删除脱敏白名单工作台记录。
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(SafetyWhitelistWorkbenchVersionReqVO req) {
        categories.lockDefinitions();
        deleteLocked(req);
    }

    /**
     * 批量校验并删除选定的脱敏白名单工作台记录。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteList(SafetyWhitelistWorkbenchBatchReqVO req) {
        categories.lockDefinitions();
        var ids = new HashSet<Long>();
        for (var item : req.getItems()) {
            if (!ids.add(item.getId())) {
                throw exception(SAFETY_MASK_INVALID, "批量记录不能重复");
            }
        }
        for (var item : req.getItems().stream()
                .sorted(Comparator.comparing(SafetyWhitelistWorkbenchVersionReqVO::getId)).toList()) {
            deleteLocked(item);
        }
    }

    /**
     * 删除已锁定。
     */
    private void deleteLocked(SafetyWhitelistWorkbenchVersionReqVO req) {
        var row = locked(req.getId(), req.getRevision());
        if (!Objects.equals(row.getStatus(), (short) 1)) {
            throw exception(SAFETY_MASK_INVALID, "启用的白名单不能删除，请先停用");
        }
        if (SafetyWhitelistDO.APPROVAL_STATUS_PENDING.equals(row.getApprovalStatus())) {
            throw exception(WHITELIST_PENDING_READONLY);
        }
        requireChanged(mapper.update(null, changed(row).setSql("deleted=1")));
        subjects.deleteByWhitelistId(row.getId());
        audit(row.getId(), "逻辑删除分类白名单");
    }

    /**
     * 读取并锁定脱敏白名单工作台记录，供事务内更新使用。
     */
    private SafetyWhitelistDO locked(Long id, Long revision) {
        var row = id == null ? null : mapper.selectForUpdate(id);
        if (row == null || !"USER".equals(row.getSubjectType()) || !"CATEGORY".equals(
                row.getObjectType()) || !"QUERY".equals(row.getScene())) {
            throw exception(WHITELIST_NOT_EXISTS);
        }
        if (revision == null || !Objects.equals(row.getRevision(), revision)) {
            throw exception(SAFETY_CATEGORY_CONFLICT);
        }
        return row;
    }

    /**
     * 读取并校验当前操作人身份。
     */
    private static Long requireActor() {
        Long actor = SecurityFrameworkUtils.getLoginUserId();
        if (actor == null || actor <= 0) {
            throw exception(SAFETY_MASK_INVALID, "白名单管理需要明确操作人");
        }
        return actor;
    }

    /**
     * 检查脱敏白名单工作台写入结果是否反映预期变更。
     */
    static LambdaUpdateWrapper<SafetyWhitelistDO> changed(SafetyWhitelistDO row) {
        Long actor = SecurityFrameworkUtils.getLoginUserId();
        if (actor == null || actor <= 0) {
            throw exception(SAFETY_MASK_INVALID, "白名单管理需要明确操作人");
        }
        return new LambdaUpdateWrapper<SafetyWhitelistDO>().eq(SafetyWhitelistDO::getId, row.getId())
                .eq(SafetyWhitelistDO::getRevision, row.getRevision())
                .set(SafetyWhitelistDO::getRevision, Math.addExact(row.getRevision(), 1))
                .set(SafetyWhitelistDO::getUpdater, String.valueOf(actor))
                .set(SafetyWhitelistDO::getUpdateTime, LocalDateTime.now())
                .set(SafetyWhitelistDO::getSourceUpdaterName, null).set(SafetyWhitelistDO::getSourceUpdateTime, null);
    }

    /**
     * 记录脱敏白名单工作台变更的操作人、对象与结果。
     */
    private void audit(Long id, String opinion) {
        approvals.insert(SafetyWhitelistApprovalDO.builder().whitelistId(id).action("LOCAL_POLICY_AUTHORIZATION")
                .opinion(opinion).build());
    }

    /**
     * 检查脱敏白名单工作台条件更新是否成功，拒绝状态或修订号冲突。
     */
    private static void requireChanged(int count) {
        if (count != 1) {
            throw exception(SAFETY_CATEGORY_CONFLICT);
        }
    }
}
