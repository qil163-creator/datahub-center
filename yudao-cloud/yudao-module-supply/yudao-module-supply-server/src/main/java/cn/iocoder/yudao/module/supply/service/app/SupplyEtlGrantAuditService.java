package cn.iocoder.yudao.module.supply.service.app;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.monitor.TracerUtils;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.supply.dal.dataobject.appapi.AppApiDO;
import cn.iocoder.yudao.module.supply.dal.dataobject.appapi.SupplyEtlGrantAuditDO;
import cn.iocoder.yudao.module.supply.dal.mysql.appapi.SupplyEtlGrantAuditMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.supply.enums.grant.ErrorCodeConstants.GRANT_SUBJECT_INVALID;

/** 只负责 Supply 领域权限历史；无外部调用，审计失败必须回滚同次权限变更。 */
@Service
public class SupplyEtlGrantAuditService {

    @Resource
    private SupplyEtlGrantAuditMapper audits;

    /**
     * 追加供应ETL授权审计内容。
     *
     * @param action 待执行的操作
     * @param before 变更前状态
     * @param after 变更后状态
     * @param reason 操作原因
     * @param oldAppStatus 历史应用状态
     * @param newAppStatus 新增应用状态
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void append(String action, AppApiDO before, AppApiDO after, String reason,
            Integer oldAppStatus, Integer newAppStatus) {
        if (!SupplyGrantValidity.isEtlAdminGrant(before) && !SupplyGrantValidity.isEtlAdminGrant(after)) {
            return;
        }

        Long actor = SecurityFrameworkUtils.getLoginUserId();
        if (actor == null || actor <= 0 || after == null) {
            throw exception(GRANT_SUBJECT_INVALID);
        }
        var row = new SupplyEtlGrantAuditDO();
        row.setGrantId(after.getId());
        row.setAppId(after.getAppId());
        row.setApiId(after.getApiId());
        row.setActorId(actor);
        row.setRevision(after.getAggregateRevision());
        row.setAction(action);
        row.setResult("SUCCESS");
        row.setBeforeJson(JsonUtils.toJsonString(snapshot(before, oldAppStatus)));
        row.setAfterJson(JsonUtils.toJsonString(snapshot(after, newAppStatus)));
        row.setReason(reason);
        row.setTraceId(TracerUtils.getTraceId());
        if (audits.append(row) != 1) {
            throw new IllegalStateException("ETL grant audit was not persisted");
        }
    }

    /**
     * 读取供应ETL授权审计快照。
     *
     * @param grant 授权
     * @param appStatus 应用状态
     * @return 处理后的键值映射
     */
    private static Map<String, Object> snapshot(AppApiDO grant, Integer appStatus) {
        if (grant == null) {
            return Map.of();
        }
        var value = new LinkedHashMap<String, Object>();
        value.put("sourceType", grant.getSourceType());
        value.put("sourceId", grant.getSourceId());
        value.put("permissionUserId", grant.getPermissionUserId());
        value.put("approvedContractHash", grant.getApprovedContractHash());
        value.put("purpose", grant.getPurpose());
        value.put("apiVersion", grant.getApiVersion());
        value.put("grantStatus", grant.getGrantStatus());
        value.put("aggregateDisabled", grant.getAggregateDisabled());
        value.put("grantRevision", grant.getGrantRevision());
        value.put("aggregateRevision", grant.getAggregateRevision());
        value.put("validFrom", SupplyGrantValidity.effectiveFrom(grant));
        value.put("validTo", SupplyGrantValidity.effectiveTo(grant));
        value.put("foreverFlag", grant.getForeverFlag());
        if (appStatus != null) {
            value.put("applicationStatus", appStatus);
        }
        return value;
    }
}
