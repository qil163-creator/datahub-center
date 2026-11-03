package cn.iocoder.yudao.module.supply.service.app;

import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.api.etl.EtlTargetBatchApi;
import cn.iocoder.yudao.module.dataforge.api.etl.dto.EtlTargetBatchDescribeReqDTO;
import cn.iocoder.yudao.module.supply.dal.dataobject.appapi.AppApiDO;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiDO;
import cn.iocoder.yudao.module.supply.dal.mysql.appapi.AppApiMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.dataapi.DataApiMapper;
import cn.iocoder.yudao.module.supply.service.dataapi.DataApiContract;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.supply.enums.grant.ErrorCodeConstants.*;

/** 独立管理员 ETL 授权的远程预检；调用者必须在任何本地行锁之前调用。 */
@Service
public class SupplyEtlGrantPreflight {

    @Resource
    private DataApiMapper apis;

    @Resource
    private AppApiMapper grants;

    @Resource
    private EtlTargetBatchApi batches;

    @Resource
    private AdminUserApi users;

    public record Prepared(long apiId, Long releaseId, Integer rowVersion,
                           String configHash, String contractHash, long actor, Long grantId, Long aggregateRevision,
                           Integer grantRevision) {

    }

    /**
     * 准备创建。
     *
     * @param apiId API 编号
     * @return 已准备
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Prepared prepareCreate(Long apiId) {
        DataApiDO api = apis.selectGrantControl(apiId);
        if (api == null || !DataApiDO.CONFIG_TYPE_ETL_BATCH.equals(api.getConfigType())) {
            return null;
        }
        return prepare(api, SecurityFrameworkUtils.getLoginUserId(), null);
    }

    /**
     * 准备启用。
     *
     * @param grantId 授权编号
     * @return 已准备
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Prepared prepareEnable(Long grantId) {
        AppApiDO grant = grants.selectGrantControlById(grantId);
        if (!SupplyGrantValidity.isEtlAdminGrant(grant)) {
            return null;
        }
        DataApiDO api = apis.selectGrantControl(grant.getApiId());
        if (api == null || !Objects.equals(grant.getApprovedContractHash(), api.getContractHash())
                || !DataApiDO.CONFIG_TYPE_ETL_BATCH.equals(api.getConfigType())) {
            throw exception(GRANT_SCOPE_CHANGED);
        }
        return prepare(api, grant.getPermissionUserId(), grant);
    }

    /**
     * 校验有效主体。
     *
     * @param actor 操作人编号
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void requireActiveSubject(Long actor) {
        if (actor == null || actor <= 0) {
            throw exception(GRANT_SUBJECT_INVALID);
        }
        var user = users.getUser(actor).getCheckedData();
        if (user == null || !Objects.equals(actor, user.getId()) || !Integer.valueOf(0).equals(user.getStatus())) {
            throw exception(GRANT_SUBJECT_INVALID);
        }
    }

    /**
     * 准备供应ETL授权预检所需的配置和状态。
     *
     * @param api 本次使用的 API 定义
     * @param actor 操作人编号
     * @param grant 授权
     * @return 已准备
     */
    private Prepared prepare(DataApiDO api, Long actor, AppApiDO grant) {
        if (!Integer.valueOf(DataApiDO.STATUS_ONLINE).equals(api.getStatus()) || api.getActiveReleaseId() == null
                || api.getContractHash() == null || !api.getContractHash().matches("[0-9a-f]{64}")) {
            throw exception(GRANT_SCOPE_UNCONFIRMED);
        }
        requireActiveSubject(actor);
        var request = new EtlTargetBatchDescribeReqDTO();
        request.setBatchId(api.getEtlBatchId());
        request.setExecutionUserId(actor);
        var description = batches.describe(request).getCheckedData();
        if (description == null || !Boolean.TRUE.equals(description.getAvailable())
                || !Objects.equals(api.getEtlBatchId(), description.getBatchId())
                || !Objects.equals(api.getEtlBatchHash(), description.getPayloadHash())
                || !Objects.equals(api.getDatabaseId(), description.getDatabaseId())
                || !Objects.equals(api.getEtlSchemaName(), description.getSchemaName())
                || !Objects.equals(api.getTableName(), description.getTableName())) {
            throw exception(GRANT_SCOPE_CHANGED);
        }
        return new Prepared(api.getId(), api.getActiveReleaseId(), api.getRowVersion(),
                DataApiContract.configHash(api), api.getContractHash(), actor,
                grant == null ? null : grant.getId(), grant == null ? null : grant.getAggregateRevision(),
                grant == null ? null : grant.getGrantRevision());
    }

    /**
     * 确认当前。
     *
     * @param proof 证明
     * @param api 本次使用的 API 定义
     * @param grant 授权
     * @return 确认当前数值
     */
    public static Long assertCurrent(Prepared proof, DataApiDO api, AppApiDO grant) {
        if (proof == null) {
            if (DataApiDO.CONFIG_TYPE_ETL_BATCH.equals(api.getConfigType()) || SupplyGrantValidity.isEtlAdminGrant(
                    grant)) {
                throw exception(GRANT_CONTROL_CONFLICT);
            }
            return null;
        }
        if (!Objects.equals(proof.apiId(), api.getId())
                || !Objects.equals(proof.releaseId(), api.getActiveReleaseId())
                || !Objects.equals(proof.rowVersion(), api.getRowVersion())
                || !Objects.equals(proof.configHash(), DataApiContract.configHash(api))
                || !Objects.equals(proof.contractHash(), api.getContractHash())
                || !Integer.valueOf(DataApiDO.STATUS_ONLINE).equals(api.getStatus())) {
            throw exception(GRANT_CONTROL_CONFLICT);
        }
        if (proof.grantId() != null && (grant == null || !SupplyGrantValidity.isEtlAdminGrant(grant)
                || !Objects.equals(proof.grantId(), grant.getId())
                || !Objects.equals(proof.aggregateRevision(), grant.getAggregateRevision())
                || !Objects.equals(proof.grantRevision(), grant.getGrantRevision())
                || !Objects.equals(proof.actor(), grant.getPermissionUserId())
                || !Objects.equals(proof.contractHash(), grant.getApprovedContractHash()))) {
            throw exception(GRANT_CONTROL_CONFLICT);
        }
        return proof.actor();
    }


}
