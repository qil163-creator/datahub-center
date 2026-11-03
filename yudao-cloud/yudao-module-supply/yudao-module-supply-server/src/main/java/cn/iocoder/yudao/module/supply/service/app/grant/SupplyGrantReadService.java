package cn.iocoder.yudao.module.supply.service.app.grant;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceCommandDTO.Scope;
import cn.iocoder.yudao.module.supply.controller.admin.app.vo.AppApiGrantScopeRespVO;
import cn.iocoder.yudao.module.supply.dal.dataobject.app.SupplyAppDO;
import cn.iocoder.yudao.module.supply.dal.dataobject.appapi.AppApiDO;
import cn.iocoder.yudao.module.supply.dal.dataobject.appapi.SupplyGrantSourceDO;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiDO;
import cn.iocoder.yudao.module.supply.dal.mysql.app.SupplyAppMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.appapi.AppApiMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.appapi.SupplyGrantSourceMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.dataapi.DataApiMapper;
import cn.iocoder.yudao.module.supply.service.app.SupplyGrantValidity;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.foundation.enums.ErrorCodeConstants.SUPPLY_APP_API_NOT_EXISTS;
import static cn.iocoder.yudao.module.supply.enums.grant.ErrorCodeConstants.*;

/** 来源授权运行时读取：网关/门户执行前后的权威判定，以及管理端的实际范围视图。 */
@Service
@RequiredArgsConstructor
public class SupplyGrantReadService {

    private final SupplyAppMapper appMapper;

    private final AppApiMapper aggregateMapper;

    private final SupplyGrantSourceMapper sourceMapper;

    private final DataApiMapper apiMapper;

    private final AdminUserApi adminUserApi;

    private final PlatformTransactionManager transactionManager;

    /**
     * 读取供应授权读取快照。
     *
     * @param appId 应用编号
     * @param apiId API 编号
     * @return 有效授权快照
     */
    public EffectiveGrantSnapshot snapshot(long appId, long apiId) {
        if (appId <= 0 || apiId <= 0) {
            throw new IllegalArgumentException("Invalid grant identity");
        }
        return withoutCallerTransaction(() -> {
            var snapshot = readLocal(appId, apiId);
            requireActiveSubject(snapshot.permissionUserId());
            return snapshot;
        });
    }

    /**
     * 确认仍然当前。
     *
     * @param snapshot 快照
     */
    public void assertStillCurrent(EffectiveGrantSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        withoutCallerTransaction(() -> {
            // 远端主体核验不持本地事务/锁；本地最终读取放在其后，避免慢 RPC 遮蔽到期。
            requireActiveSubject(snapshot.permissionUserId());
            EffectiveGrantSnapshot current;
            try {
                current = readLocal(snapshot.appId(), snapshot.apiId());
            } catch (ServiceException failure) {
                if (Objects.equals(failure.getCode(), GRANT_NOT_EFFECTIVE.getCode())
                        || Objects.equals(failure.getCode(), GRANT_SCOPE_UNCONFIRMED.getCode())) {
                    throw exception(GRANT_SCOPE_CHANGED);
                }
                throw failure;
            }
            if (!snapshot.fieldScoped() || snapshot.evaluatedAt() == null
                    || current.evaluatedAt().isBefore(snapshot.evaluatedAt())
                    || (snapshot.nextBoundary() != null && !current.evaluatedAt().isBefore(snapshot.nextBoundary()))
                    || current.aggregateRevision() != snapshot.aggregateRevision()
                    || !Objects.equals(current.apiControlVersion(), snapshot.apiControlVersion())
                    || !Objects.equals(current.permissionUserId(), snapshot.permissionUserId())
                    || !Objects.equals(current.binding(), snapshot.binding())
                    || !Objects.equals(current.contractHash(), snapshot.contractHash())
                    || !Objects.equals(current.apiVersion(), snapshot.apiVersion())
                    || !current.approvedColumns().equals(snapshot.approvedColumns())
                    || !current.sourceVersions().equals(snapshot.sourceVersions())) {
                throw exception(GRANT_SCOPE_CHANGED);
            }
            return null;
        });
    }

    /**
     * 管理端“实际范围”视图：字段并集由与查询端相同的权威读取路径给出，不做任何乐观推断。
     * 整体禁用、无有效来源、证据待复核必须彼此可分辨；来源清单只是展示用的伴随读取，
     * 返回前必须在最后一次主体 RPC 之后复验本地控制、版本与数据库时钟；
     * 发生变化直接沿用 GRANT_SCOPE_CHANGED，不重试或用较新来源重算旧视图。
     */
    public AppApiGrantScopeRespVO describe(long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("Invalid grant identity");
        }
        // 管理端的初读和历史来源展示也不得继承调用方的旧 MVCC 快照/SESSION 缓存。
        return withoutCallerTransaction(() -> describeCurrent(id));
    }

    /**
     * 读取当前。
     *
     * @param id 记录编号
     * @return 应用 API 授权授权范围响应数据
     */
    private AppApiGrantScopeRespVO describeCurrent(long id) {
        AdminControl initial = readAdminControl(id);
        AppApiDO aggregate = initial.aggregate();
        if (aggregate == null) {
            throw exception(SUPPLY_APP_API_NOT_EXISTS);
        }
        AppApiGrantScopeRespVO view = new AppApiGrantScopeRespVO();
        view.setId(aggregate.getId());
        view.setAppId(aggregate.getAppId());
        view.setApiId(aggregate.getApiId());
        view.setProtocolVersion(aggregate.getProtocolVersion());
        view.setAggregateRevision(aggregate.getAggregateRevision());
        view.setApprovedColumns(List.of());
        view.setFieldScoped(Integer.valueOf(2).equals(aggregate.getProtocolVersion())
                && "CONFIRMED".equals(aggregate.getMigrationStatus()));
        EffectiveGrantSnapshot snapshot = resolveMode(view, initial);

        view.setSources(describeSources(aggregate, snapshot));
        if (snapshot != null) {
            // 放在伴随读取之后；慢 RPC、到期或读期间撤销不能留下 ACTIVE/旧字段/旧贡献标记。
            assertStillCurrent(snapshot);
        } else if (AppApiGrantScopeRespVO.MODE_ACTIVE.equals(view.getMode())) {
            // legacy ADMIN 没有 v2 来源快照；独立复验旧控制，不将历史来源并为批准字段。
            assertLegacyStillCurrent(initial);
        }
        return view;
    }

    /**
     * 解析模式。
     *
     * @param view 视图
     * @param control 控制
     * @return 有效授权快照
     */
    private EffectiveGrantSnapshot resolveMode(AppApiGrantScopeRespVO view, AdminControl control) {
        AppApiDO aggregate = control.aggregate();
        if (Boolean.TRUE.equals(aggregate.getAggregateDisabled())) {
            // 禁用是管理端开关，不是“没有授权”；解除后按来源账本恢复。
            block(view, AppApiGrantScopeRespVO.MODE_DISABLED, "AGGREGATE_DISABLED");
            return null;
        }
        SupplyAppDO app = control.app();
        DataApiDO api = control.api();
        if (app == null || !Integer.valueOf(1).equals(app.getAppStatus())
                || api == null || !Integer.valueOf(1).equals(api.getStatus())) {
            block(view, AppApiGrantScopeRespVO.MODE_NONE, "APP_OR_API_INACTIVE");
            return null;
        }
        if (!Boolean.TRUE.equals(view.getFieldScoped())) {
            if (SupplyGrantValidity.isEtlAdminGrant(aggregate)) {
                try {
                    requireActiveSubject(aggregate.getPermissionUserId());
                } catch (ServiceException failure) {
                    if (!Objects.equals(failure.getCode(), GRANT_SUBJECT_INVALID.getCode())) {
                        throw failure;
                    }
                    block(view, AppApiGrantScopeRespVO.MODE_NONE, "SUBJECT_INVALID");
                    return null;
                }
            }
            describeLegacy(view, aggregate, app, api, control.evaluatedAt());
            return null;
        }
        try {
            EffectiveGrantSnapshot snapshot = snapshot(aggregate.getAppId(), aggregate.getApiId());
            view.setMode(AppApiGrantScopeRespVO.MODE_ACTIVE);
            view.setAggregateRevision(snapshot.aggregateRevision());
            view.setApprovedColumns(snapshot.approvedColumns());
            return snapshot;
        } catch (ServiceException failure) {
            if (Objects.equals(failure.getCode(), GRANT_SCOPE_UNCONFIRMED.getCode())) {
                block(view, AppApiGrantScopeRespVO.MODE_REVIEW_REQUIRED, "SCOPE_UNCONFIRMED");
            } else if (Objects.equals(failure.getCode(), GRANT_SUBJECT_INVALID.getCode())) {
                block(view, AppApiGrantScopeRespVO.MODE_NONE, "SUBJECT_INVALID");
            } else if (Objects.equals(failure.getCode(), GRANT_NOT_EFFECTIVE.getCode())) {
                block(view, AppApiGrantScopeRespVO.MODE_NONE, "NO_EFFECTIVE_SOURCE");
            } else {
                throw failure; // 未预期的失败不能被渲染成某种"正常"状态
            }
        }
        return null;
    }

    /**
     * 读取历史。
     *
     * @param view 视图
     * @param aggregate 聚合
     * @param app 调用应用信息
     * @param api 本次使用的 API 定义
     * @param nowMillis 当前时间毫秒数
     */
    private static void describeLegacy(AppApiGrantScopeRespVO view, AppApiDO aggregate,
            SupplyAppDO app, DataApiDO api, long nowMillis) {
        boolean admin = SupplyGrantValidity.isIndependentAdminGrant(aggregate);
        if (!admin) {
            // 历史市场聚合没有来源账本证据，不能按当前元数据补成可用授权。
            block(view, AppApiGrantScopeRespVO.MODE_REVIEW_REQUIRED, "LEGACY_PROTOCOL_UNCONFIRMED");
            return;
        }
        if (SupplyGrantValidity.isEtlAdminGrant(aggregate)
                && (!DataApiDO.CONFIG_TYPE_ETL_BATCH.equals(api.getConfigType()) || api.getActiveReleaseId() == null
                || !Objects.equals(aggregate.getApprovedContractHash(), api.getContractHash()))) {
            block(view, AppApiGrantScopeRespVO.MODE_REVIEW_REQUIRED, "SCOPE_UNCONFIRMED");
            return;
        }
        if (!SupplyGrantValidity.isEffective(app, aggregate, local(nowMillis))) {
            block(view, AppApiGrantScopeRespVO.MODE_NONE, "LEGACY_GRANT_NOT_EFFECTIVE");
            return;
        }
        if (!SupplyGrantValidity.matchesCurrentContract(aggregate, api.getContractHash())) {
            // 网关已拒绝此合同；管理端不能仍标有效，也不能按当前合同补造批准。
            block(view, AppApiGrantScopeRespVO.MODE_REVIEW_REQUIRED, "SCOPE_UNCONFIRMED");
            return;
        }
        // 旧 ADMIN 授权有效但没有冻结字段证据：fieldScoped=false 表示字段未受限，空列表不代表零字段。
        view.setMode(AppApiGrantScopeRespVO.MODE_ACTIVE);
    }

    /** 管理端控制面初读/末读：一份短本地快照，不持锁、不执行主体 RPC。 */
    private AdminControl readAdminControl(long id) {
        var transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setReadOnly(true);
        transaction.setTimeout(5);
        return transaction.execute(status -> {
            AppApiDO aggregate = aggregateMapper.selectGrantControlById(id);
            SupplyAppDO app = aggregate == null || aggregate.getAppId() == null ? null
                    : appMapper.selectGrantControl(aggregate.getAppId());
            DataApiDO api = aggregate == null || aggregate.getApiId() == null ? null
                    : apiMapper.selectGrantControl(aggregate.getApiId());
            return new AdminControl(aggregate, app, api, aggregateMapper.databaseTimeMillis());
        });
    }

    /**
     * 确认历史仍然当前。
     *
     * @param initial 初始
     */
    private void assertLegacyStillCurrent(AdminControl initial) {
        AdminControl current = readAdminControl(initial.aggregate().getId());
        AppApiDO before = initial.aggregate();
        AppApiDO after = current.aggregate();
        if (after == null || current.app() == null || current.api() == null
                || !Integer.valueOf(1).equals(current.app().getAppStatus())
                || !Integer.valueOf(1).equals(current.api().getStatus())
                || current.evaluatedAt() < initial.evaluatedAt()
                || !Objects.equals(before.getId(), after.getId())
                || !Objects.equals(before.getAppId(), after.getAppId())
                || !Objects.equals(before.getApiId(), after.getApiId())
                || !Objects.equals(before.getProtocolVersion(), after.getProtocolVersion())
                || !Objects.equals(before.getSourceType(), after.getSourceType())
                || !Objects.equals(before.getPermissionUserId(), after.getPermissionUserId())
                || !Objects.equals(before.getAggregateRevision(), after.getAggregateRevision())
                || !Objects.equals(before.getAggregateDisabled(), after.getAggregateDisabled())
                || !Objects.equals(before.getGrantRevision(), after.getGrantRevision())
                || !Objects.equals(before.getGrantStatus(), after.getGrantStatus())
                || !Objects.equals(before.getForeverFlag(), after.getForeverFlag())
                || !Objects.equals(SupplyGrantValidity.effectiveFrom(before), SupplyGrantValidity.effectiveFrom(after))
                || !Objects.equals(SupplyGrantValidity.effectiveTo(before), SupplyGrantValidity.effectiveTo(after))
                || !Objects.equals(before.getApprovedContractHash(), after.getApprovedContractHash())
                || !Objects.equals(before.getApiVersion(), after.getApiVersion())
                || !Objects.equals(initial.app().getRowVersion(), current.app().getRowVersion())
                || !Objects.equals(initial.api().getRowVersion(), current.api().getRowVersion())
                || !Objects.equals(initial.api().getActiveReleaseId(), current.api().getActiveReleaseId())
                || !Objects.equals(initial.api().getContractHash(), current.api().getContractHash())
                || !Objects.equals(initial.api().getVersion(), current.api().getVersion())
                || !SupplyGrantValidity.isEffective(current.app(), after, local(current.evaluatedAt()))
                || !SupplyGrantValidity.matchesCurrentContract(after, current.api().getContractHash())) {
            throw exception(GRANT_SCOPE_CHANGED);
        }
    }

    private record AdminControl(AppApiDO aggregate, SupplyAppDO app, DataApiDO api, long evaluatedAt) {

    }

    /**
     * 读取来源。
     *
     * @param aggregate 聚合
     * @param snapshot 快照
     * @return 来源结果集合
     */
    private List<AppApiGrantScopeRespVO.GrantSourceRespVO> describeSources(AppApiDO aggregate,
            EffectiveGrantSnapshot snapshot) {
        List<SupplyGrantSourceDO> rows = sourceMapper.selectAggregateSources(aggregate.getAppId(),
                aggregate.getApiId());
        List<AppApiGrantScopeRespVO.GrantSourceRespVO> result = new ArrayList<>(rows.size());
        for (SupplyGrantSourceDO row : rows) {
            AppApiGrantScopeRespVO.GrantSourceRespVO item = new AppApiGrantScopeRespVO.GrantSourceRespVO();
            item.setSourceSystem(row.getSourceSystem());
            item.setSourceKey(row.getSourceKey());
            item.setSourceRevision(row.getSourceRevision());
            item.setStatus(displayStatus(row.getSourceStatus()));
            item.setApprovedColumns(List.of());
            item.setContributing(false);
            Scope scope = readableScope(row);
            if (scope != null) {
                item.setApprovedColumns(scope.approvedColumns());
                item.setForever(scope.forever());
                item.setValidFrom(local(scope.validFromMillis()));
                item.setValidTo(scope.forever() ? null : local(scope.validToMillis()));
                // “当前贡献”必须属于已通过聚合/主体/合同校验的同一权威快照。
                // 阻断或旧 ADMIN 路径没有来源快照；伴随读取的较新来源版本也不能冒充已参与计算。
                // 冻结字段与期限仍完整展示，false 不表示删除或撤销这份历史批准。
                item.setContributing(snapshot != null
                        && Objects.equals(snapshot.sourceVersions().get(new GrantSourceState.Identity(
                        row.getSourceSystem(), row.getSourceKey())), row.getSourceRevision())
                        && GrantSourceState.Status.ACTIVE.name().equals(item.getStatus())
                        && scope.validFromMillis() <= snapshot.evaluatedAt().toEpochMilli()
                        && (scope.forever() || snapshot.evaluatedAt().toEpochMilli() < scope.validToMillis()));
            }
            result.add(item);
        }
        return result;
    }

    /** 损坏的持久批准既不显示字段，也不回退成全列；不返回 JSON 原文。 */
    private static Scope readableScope(SupplyGrantSourceDO row) {
        try {
            return SupplyGrantCoordinator.state(row).scope();
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * 获取供应授权读取的展示状态。
     *
     * @param stored 已存储
     * @return 供应授权读取的展示状态文本
     */
    private static String displayStatus(String stored) {
        try {
            return GrantSourceState.Status.valueOf(stored).name();
        } catch (RuntimeException unknown) {
            return GrantSourceState.Status.REVIEW_REQUIRED.name();
        }
    }

    /**
     * 在授权视图中记录阻断模式及原因，并清空已批准列展示。
     *
     * @param view 视图
     * @param mode 处理模式
     * @param reason 操作原因
     */
    private static void block(AppApiGrantScopeRespVO view, String mode, String reason) {
        view.setMode(mode);
        view.setBlockReason(reason);
        view.setApprovedColumns(List.of());
    }

    /**
     * 获取供应授权读取的本地。
     *
     * @param millis 毫秒数
     * @return 本地日期时间
     */
    private static LocalDateTime local(long millis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), SupplyGrantValidity.ZONE);
    }

    /**
     * 读取本地。
     *
     * @param appId 应用编号
     * @param apiId API 编号
     * @return 有效授权快照
     */
    private EffectiveGrantSnapshot readLocal(long appId, long apiId) {
        var transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setReadOnly(true);
        transaction.setTimeout(5);
        return transaction.execute(status -> {
            var app = appMapper.selectGrantControl(appId);
            var aggregate = aggregateMapper.selectGrantControl(appId, apiId);
            var api = apiMapper.selectGrantControl(apiId);
            if (app == null || !Integer.valueOf(1).equals(app.getAppStatus())
                    || aggregate == null || Boolean.TRUE.equals(aggregate.getAggregateDisabled())
                    || api == null || !Integer.valueOf(1).equals(api.getStatus())) {
                throw exception(GRANT_NOT_EFFECTIVE);
            }
            if (!Integer.valueOf(2).equals(aggregate.getProtocolVersion())
                    || !"CONFIRMED".equals(aggregate.getMigrationStatus())
                    || aggregate.getAggregateRevision() == null || aggregate.getAggregateRevision() < 0
                    || api.getRowVersion() == null || api.getRowVersion() < 0
                    || api.getActiveReleaseId() == null || !"TABLE".equals(api.getConfigType())
                    || !"QUERY".equals(api.getApiType())) {
                throw exception(GRANT_SCOPE_UNCONFIRMED);
            }
            // 所有 SQL 在同一个 MVCC 快照中；不使用 FOR UPDATE，外部查询不持授权锁。
            var rows = sourceMapper.selectAggregateSources(appId, apiId);
            List<GrantSourceState> sources;
            try {
                sources = rows.stream().map(SupplyGrantCoordinator::state).toList();
            } catch (RuntimeException invalidScope) {
                // 损坏的持久批准不能降级为全列，也不把原 JSON/异常内容返回给调用方。
                throw exception(GRANT_SCOPE_UNCONFIRMED);
            }
            EffectiveGrantSnapshot result;
            try {
                result = SupplyGrantPolicy.evaluate(appId, apiId, aggregate.getAggregateRevision(),
                        false, sources, Instant.ofEpochMilli(aggregateMapper.databaseTimeMillis()));
            } catch (SupplyGrantPolicy.Rejected rejected) {
                throw exception(rejected.reason() == SupplyGrantPolicy.Reason.NO_EFFECTIVE_SCOPE
                        ? GRANT_NOT_EFFECTIVE : GRANT_SCOPE_UNCONFIRMED);
            }
            if (!Objects.equals(api.getContractHash(), result.contractHash())
                    || !Objects.equals(api.getVersion(), result.apiVersion())
                    || !Objects.equals(api.getDatabaseId(), result.binding().databaseId())
                    || !Objects.equals(api.getTableName(), result.binding().tableName())) {
                throw exception(GRANT_SCOPE_UNCONFIRMED);
            }
            return result.withApiControlVersion(api.getRowVersion());
        });
    }

    /**
     * 校验有效主体。
     *
     * @param userId 用户编号
     */
    private void requireActiveSubject(Long userId) {
        if (userId == null || userId <= 0) {
            throw exception(GRANT_SUBJECT_INVALID);
        }
        var user = adminUserApi.getUser(userId).getCheckedData();
        if (user == null || !Objects.equals(user.getId(), userId) || !Integer.valueOf(0).equals(user.getStatus())) {
            throw exception(GRANT_SUBJECT_INVALID);
        }
    }

    /**
     * 获取供应授权读取的未包含调用方事务。
     *
     * @param <T> 处理数据的类型
     * @param action 待执行的操作
     * @return 供应授权读取
     */
    private <T> T withoutCallerTransaction(Supplier<T> action) {
        var transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        return transaction.execute(status -> action.get());
    }


}
