package cn.iocoder.yudao.module.asset.service.market;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.asset.controller.admin.dataquery.vo.DataQueryColumnRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.dataquery.vo.DataQueryResultRespVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.monitor.AssetUsageLogDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceApplyMapper;
import cn.iocoder.yudao.module.asset.service.monitor.AssetMonitorService;
import cn.iocoder.yudao.module.asset.service.security.AssetMetadataIdentityGuard;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabasePreviewReqDTO;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabasePreviewRespDTO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.DATA_ASSET_NOT_EXISTS;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_FIELD_SCOPE_INVALID;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_FORBIDDEN;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_NOT_AUTHORIZED;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_PREVIEW_UNSUPPORTED;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_SCOPE_UNCONFIRMED;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_QUERY_AUTHORIZATION_CHANGED;

@Service
@Validated
public class MarketAccessServiceImpl implements MarketAccessService {

    @Resource
    private MarketConsumptionService consumptionService;

    /** 有界凭证：超限明确拒绝，不用被截断的来源集合计算权限。 */
    private static final int MAX_QUERY_SOURCES = 256;

    @Resource
    private MarketResourceApplyMapper applyMapper;

    @Resource
    private DataAssetMapper assetMapper;

    @Resource
    private MarketDisplayHelper displayHelper;

    @Resource
    private DatabaseApi databaseApi;

    @Resource
    private AssetMonitorService assetMonitorService;

    @Resource
    private PlatformTransactionManager transactionManager;

    @Resource
    private AssetMetadataIdentityGuard metadataIdentityGuard;

    /**
     * 判断市场访问是否生效。
     */
    @Override
    public boolean isEffective(MarketResourceApplyDO apply) {
        if (apply == null || apply.getSourceWorkOrderId() != null) {
            return false;
        }
        return isEffectiveAt(apply, applyMapper.databaseNow());
    }

    /**
     * 判断市场访问是否生效时点。
     */
    private boolean isEffectiveAt(MarketResourceApplyDO apply, LocalDateTime now) {
        if (now == null) {
            throw exception(MARKET_APPLY_NOT_AUTHORIZED);
        }
        if (apply == null) {
            return false;
        }
        return Objects.equals(apply.getStatus(), MarketResourceApplyDO.STATUS_APPROVED)
                && apply.getSourceWorkOrderId() == null
                && Objects.equals(apply.getIfAuth(), MarketResourceApplyDO.AUTH_YES)
                && !Objects.equals(apply.getPaused(), 1)
                && (apply.getApplyStartTime() == null || !now.isBefore(apply.getApplyStartTime()))
                && (apply.getApplyEndTime() == null || now.isBefore(apply.getApplyEndTime()));
    }

    /**
     * 查找生效申请。
     */
    @Override
    public MarketResourceApplyDO findEffectiveApply(Long userId, Long assetId) {
        if (userId == null || assetId == null) {
            return null;
        }
        LocalDateTime now = applyMapper.databaseNow();
        if (now == null) {
            throw exception(MARKET_APPLY_NOT_AUTHORIZED);
        }
        // 此处只定位入口申请；完整范围由后续独立快照查询合并，避免先加载全部历史申请。
        MarketResourceApplyDO apply = applyMapper.selectEffective(userId, assetId, now);
        // 不能把证据损坏静默当成“没有共享限制”，否则消费方可能走普通查询路径。
        if (apply != null) {
            MarketFieldScope.requireMatchesAsset(apply, assetMapper.selectById(assetId));
        }
        return apply;
    }

    /**
     * 同一数据库时点批量复核已发布资产的有效入口及冻结绑定，null 表示当前用户全部已发布候选。
     */
    @Override
    public Map<Long, MarketResourceApplyDO> findEffectiveApplies(Long userId, Collection<Long> assetIds) {
        if (userId == null || (assetIds != null && assetIds.isEmpty())) {
            return Map.of();
        }
        LocalDateTime now = applyMapper.databaseNow();
        if (now == null) {
            throw exception(MARKET_APPLY_NOT_AUTHORIZED);
        }
        Map<Long, MarketResourceApplyDO> latest = new LinkedHashMap<>();
        for (var apply : applyMapper.selectEffectiveForUser(userId, assetIds, now)) {
            latest.putIfAbsent(apply.getAssetId(), apply);
        }
        if (latest.isEmpty()) {
            return Map.of();
        }
        var assets = assetMapper.selectByIds(latest.keySet()).stream()
                .collect(java.util.stream.Collectors.toMap(DataAssetDO::getId, java.util.function.Function.identity()));
        latest.forEach((assetId, apply) -> MarketFieldScope.requireMatchesAsset(apply, assets.get(assetId)));
        return Map.copyOf(latest);
    }

    /**
     * 判断市场访问是否具有生效访问。
     */
    @Override
    public boolean hasEffectiveAccess(Long userId, Long assetId) {
        MarketResourceApplyDO apply = findEffectiveApply(userId, assetId);
        if (apply == null) {
            return false;
        }
        requireQuerySnapshot(userId, apply.getId());
        return true;
    }

    /**
     * 查找生效申请用于表。
     */
    @Override
    public MarketResourceApplyDO findEffectiveApplyForTable(Long userId, Long datasourceId, String tableName) {
        if (userId == null || datasourceId == null || StrUtil.isBlank(tableName)) {
            return null;
        }
        DataAssetDO asset = findTableAsset(datasourceId, tableName);
        if (asset == null) {
            return null;
        }
        MarketResourceApplyDO apply = findEffectiveApply(userId, asset.getId());
        if (apply != null) {
            MarketFieldScope.requireMatchesAsset(apply, asset);
        }
        return apply;
    }

    /**
     * 物理 schema/table 已由 DataForge/DB Switch 解析，禁止再次去引号或折叠大小写。
     */
    @Override
    public MarketResourceApplyDO findEffectiveApplyForTable(Long userId, Long datasourceId,
            String schemaName, String tableName) {
        if (userId == null || datasourceId == null || StrUtil.isBlank(schemaName) || StrUtil.isBlank(tableName)) {
            throw exception(MARKET_APPLY_SCOPE_UNCONFIRMED);
        }
        List<DataAssetDO> assets = assetMapper.selectExactTableBindings(datasourceId, schemaName, tableName);
        if (assets.size() > 1 || assets.stream().anyMatch(asset -> StrUtil.isBlank(asset.getSchemaName()))) {
            throw exception(MARKET_APPLY_SCOPE_UNCONFIRMED);
        }
        if (assets.isEmpty()) {
            return null;
        }
        DataAssetDO asset = assets.getFirst();
        MarketResourceApplyDO apply = findEffectiveApply(userId, asset.getId());
        if (apply != null) {
            MarketFieldScope.requireMatchesAsset(apply, asset);
        }
        return apply;
    }

    /**
     * 查询已授权表名称。
     */
    @Override
    public List<String> listAuthorizedTableNames(Long userId, Long datasourceId) {
        if (userId == null || datasourceId == null) {
            return List.of();
        }
        LocalDateTime now = applyMapper.databaseNow();
        List<MarketResourceApplyDO> applies = applyMapper.selectApprovedAuthorizedByUser(userId).stream()
                .filter(candidate -> isEffectiveAt(candidate, now))
                .toList();
        if (applies.isEmpty()) {
            return List.of();
        }
        Map<Long, DataAssetDO> assets = displayHelper.assets(applies.stream()
                .map(MarketResourceApplyDO::getAssetId).toList());
        Set<String> tables = new LinkedHashSet<>();
        for (MarketResourceApplyDO apply : applies) {
            DataAssetDO asset = assets.get(apply.getAssetId());
            if (!MarketFieldScope.supportsExistingGrant(asset)
                    || !Objects.equals(asset.getDatasourceId(), datasourceId)
                    || !Objects.equals(asset.getAssetType(), DataAssetDO.TYPE_DATABASE_TABLE)
                    || StrUtil.isBlank(asset.getTableName())) {
                continue;
            }
            try {
                MarketFieldScope.requireMatchesAsset(apply, asset);
                tables.add(asset.getTableName());
            } catch (cn.iocoder.yudao.framework.common.exception.ServiceException unconfirmed) {
                // 表列表不展示缺少批准证据的授权；访问/审批入口会返回可复核的业务错误。
            }
        }
        return new ArrayList<>(tables);
    }

    /**
     * 预览已授权资产。
     */
    @Override
    public DatabasePreviewRespDTO previewAuthorizedAsset(Long assetId, Integer limit) {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        if (userId == null) {
            throw exception(MARKET_APPLY_FORBIDDEN);
        }
        MarketResourceApplyDO apply = findEffectiveApply(userId, assetId);
        if (apply == null) {
            throw exception(MARKET_APPLY_NOT_AUTHORIZED);
        }
        DataAssetDO asset = assetMapper.selectById(assetId);
        if (asset == null) {
            throw exception(DATA_ASSET_NOT_EXISTS);
        }
        if (!Objects.equals(asset.getAssetType(), DataAssetDO.TYPE_DATABASE_TABLE)
                || asset.getDatasourceId() == null
                || StrUtil.isBlank(asset.getTableName())) {
            throw exception(MARKET_APPLY_PREVIEW_UNSUPPORTED);
        }
        MarketQuerySnapshot authorization = requireQuerySnapshot(userId, apply.getId());
        return previewAuthorized(authorization, asset, limit);
    }

    /**
     * 我的资产按具体批准来源进入预览；不能改选同资产另一条有效授权替代失效申请。
     */
    @Override
    public DatabasePreviewRespDTO previewAuthorizedApply(Long applyId, Integer limit) {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        requireQueryContext(userId);
        MarketQuerySnapshot authorization = requireQuerySnapshot(userId, applyId);
        var entry = authorization.grants().stream().filter(grant -> grant.applyId() == applyId).findFirst()
                .orElseThrow(() -> exception(MARKET_APPLY_NOT_AUTHORIZED));
        if (entry.policy().apiId() != null) {
            throw exception(MARKET_APPLY_PREVIEW_UNSUPPORTED);
        }
        DataAssetDO asset = assetMapper.selectById(authorization.assetId());
        if (asset == null) {
            throw exception(DATA_ASSET_NOT_EXISTS);
        }
        return previewAuthorized(authorization, asset, limit);
    }

    /**
     * 预览已授权。
     */
    private DatabasePreviewRespDTO previewAuthorized(MarketQuerySnapshot authorization, DataAssetDO asset,
            Integer limit) {
        long userId = authorization.userId();
        DatabasePreviewReqDTO request = new DatabasePreviewReqDTO();
        request.setDatabaseId(authorization.databaseId());
        request.setSchemaName(authorization.schemaName());
        request.setTableName(authorization.tableName());
        request.setLimit(limit == null ? 20 : Math.max(1, Math.min(limit, 100)));
        MarketFieldScope scope = authorization.fieldScope();
        MarketFieldScope.applyToPreviewRequest(request, scope);
        return MarketConsumptionService.read(consumptionService, authorization, request, request.getLimit(),
                allowedLimit -> {
                    request.setLimit(allowedLimit);
                    DatabasePreviewRespDTO preview = databaseApi.previewTable(request).getCheckedData();
                    filterPreview(preview, scope);
                    assetMonitorService.recordUsageLog(asset, AssetUsageLogDO.ACTION_USAGE);
                    assertStillCurrent(authorization);
                    applyMapper.update(null,
                            new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MarketResourceApplyDO>()

                                    .in(MarketResourceApplyDO::getId,
                                            authorization.grants().stream().map(MarketQuerySnapshot.Grant::applyId)
                                                    .toList())
                                    .isNull(MarketResourceApplyDO::getFirstUsedTime)
                                    .set(MarketResourceApplyDO::getFirstUsedTime, authorization.evaluatedAt()));
                    return preview;
                }, preview -> preview.getRows() == null ? 0 : preview.getRows().size());
    }

    /**
     * 以有效申请定位资产，合并同一主体、资产、兼容批准条件的所有有效来源并保留逐来源凭证。
     */
    @Override
    public MarketQuerySnapshot requireQuerySnapshot(Long userId, Long applyId) {
        requireQueryContext(userId);
        if (applyId == null || applyId <= 0) {
            throw exception(MARKET_APPLY_NOT_AUTHORIZED);
        }
        // 两次读取均开启独立、短、只读事务，不能继承调用方的旧 REPEATABLE_READ 快照。
        // 事务内只有本地读，不持锁，也不执行 DataForge/Governance/System RPC。
        var transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setReadOnly(true);
        transaction.setTimeout(10);
        QueryResolution resolution = transaction.execute(status -> {
            MarketResourceApplyDO apply = applyMapper.selectForAccess(applyId, userId);
            if (apply == null || !Objects.equals(apply.getApplyUserId(), userId)) {
                throw exception(MARKET_APPLY_NOT_AUTHORIZED);
            }
            DataAssetDO asset = assetMapper.selectById(apply.getAssetId());
            if (!MarketFieldScope.supportsExistingGrant(asset)
                    || !Objects.equals(asset.getAssetType(), DataAssetDO.TYPE_DATABASE_TABLE)) {
                throw exception(MARKET_APPLY_NOT_AUTHORIZED);
            }
            MarketFieldScope.requireMatchesAsset(apply, asset);
            LocalDateTime now = applyMapper.databaseNow();
            if (!isEffectiveAt(apply, now)) {
                throw exception(MARKET_APPLY_NOT_AUTHORIZED);
            }
            List<MarketResourceApplyDO> sources = applyMapper.selectQuerySources(asset.getId(),
                    userId, now, MAX_QUERY_SOURCES + 1);
            if (sources.size() > MAX_QUERY_SOURCES) {
                throw exception(MARKET_APPLY_SCOPE_UNCONFIRMED);
            }
            List<MarketFieldScope> scopes = new ArrayList<>();
            List<MarketQuerySnapshot.Grant> grants = new ArrayList<>();
            Set<Long> ids = new LinkedHashSet<>();
            MarketQuerySnapshot.Policy policy = null;
            for (MarketResourceApplyDO source : sources) {
                if (source.getId() == null || !ids.add(source.getId())
                        || !Objects.equals(source.getApplyUserId(), userId)
                        || !Objects.equals(source.getAssetId(), asset.getId()) || !isEffectiveAt(source, now)
                        || source.getSourceRevision() == null || source.getSourceRevision() <= 0
                        || source.getRevision() == null || source.getRevision() <= 0) {
                    throw exception(MARKET_APPLY_SCOPE_UNCONFIRMED);
                }
                scopes.add(MarketFieldScope.requireMatchesAsset(source, asset));
                MarketQuerySnapshot.Policy sourcePolicy = queryPolicy(source);
                if (policy != null && !policy.equals(sourcePolicy)) {
                    throw exception(MARKET_APPLY_SCOPE_UNCONFIRMED);
                }
                policy = sourcePolicy;
                grants.add(
                        new MarketQuerySnapshot.Grant(source.getId(), source.getSourceRevision(), source.getRevision(),
                                source.getSnapshot(), sourcePolicy, source.getApplyStartTime(),
                                source.getApplyEndTime(),
                                new cn.iocoder.yudao.module.supply.api.app.dto.SupplyQuotaDTO.Source("asset",
                                        "asset:apply:" + source.getId(),
                                        MarketQuotaPolicy.quota(source.getApplyUseType(), source.getApplyUseTimes()),
                                        List.copyOf(scopes.get(scopes.size() - 1).getColumns()))));
            }
            if (!ids.contains(applyId)) {
                throw exception(MARKET_APPLY_NOT_AUTHORIZED);
            }
            var snapshot = new MarketQuerySnapshot(userId, applyId, asset.getId(), grants,
                    MarketFieldScope.unionApproved(scopes), asset.getDatasourceId(), asset.getSchemaName(),
                    asset.getTableName(), now);
            return new QueryResolution(snapshot, BeanUtils.toBean(asset, DataAssetDO.class));
        });
        // 元数据 RPC 在本地快照事务结束后执行；独立 Guard 代理还会挂起调用方事务。
        metadataIdentityGuard.assertCurrent(Objects.requireNonNull(resolution).asset());
        return resolution.snapshot();
    }

    private record QueryResolution(MarketQuerySnapshot snapshot, DataAssetDO asset) {

    }

    /**
     * 冻结当前查询所需的治理字段权限策略。
     */
    @SuppressWarnings("unchecked")
    private MarketQuerySnapshot.Policy queryPolicy(MarketResourceApplyDO apply) {
        MarketQuotaPolicy.requireFrozen(apply);
        if ((apply.getAppId() == null) != (apply.getApiId() == null)) {
            throw exception(MARKET_APPLY_SCOPE_UNCONFIRMED);
        }
        Map<String, Object> evidence = JsonUtils.parseObject(apply.getSnapshot(), Map.class);
        String contract = evidenceText(evidence, "approvedContractHash");
        String apiVersion = evidenceText(evidence, "apiVersion");
        String release = evidenceText(evidence, "activeReleaseId");
        if (!Objects.equals(evidenceText(evidence, "appId"),
                apply.getAppId() == null ? "" : String.valueOf(apply.getAppId()))
                || !Objects.equals(evidenceText(evidence, "apiId"),
                apply.getApiId() == null ? "" : String.valueOf(apply.getApiId()))
                || !Objects.equals(evidenceText(evidence, "dutyMatter"),
                StrUtil.blankToDefault(apply.getDutyMatter(), ""))) {
            throw exception(MARKET_APPLY_SCOPE_UNCONFIRMED);
        }
        if (apply.getAppId() != null && (StrUtil.isBlank(contract) || StrUtil.isBlank(apiVersion))) {
            throw exception(MARKET_APPLY_SCOPE_UNCONFIRMED);
        }
        return new MarketQuerySnapshot.Policy(apply.getAppId(), apply.getApiId(), contract, apiVersion, release,
                StrUtil.trimToEmpty(apply.getPurpose()), StrUtil.trimToEmpty(apply.getDutyMatter()),
                StrUtil.trimToEmpty(apply.getLegalBasis()));
    }

    /**
     * 从授权证据中提取必须存在的文本字段。
     */
    private static String evidenceText(Map<String, Object> evidence, String name) {
        Object value = evidence.get(name);
        if (value == null) {
            return "";
        }
        if (!(value instanceof String text)) {
            throw exception(MARKET_APPLY_SCOPE_UNCONFIRMED);
        }
        return text;
    }

    /**
     * 校验市场访问的仍然当前，不满足约束时拒绝当前操作。
     */
    @Override
    public void assertStillCurrent(MarketQuerySnapshot snapshot) {
        if (snapshot == null) {
            throw exception(MARKET_QUERY_AUTHORIZATION_CHANGED);
        }
        requireQueryContext(snapshot.userId());
        MarketQuerySnapshot current;
        try {
            current = requireQuerySnapshot(snapshot.userId(), snapshot.applyId());
        } catch (cn.iocoder.yudao.framework.common.exception.ServiceException invalid) {
            throw exception(MARKET_QUERY_AUTHORIZATION_CHANGED);
        }
        if (!snapshot.sameGrant(current) || current.evaluatedAt().isBefore(snapshot.evaluatedAt())) {
            throw exception(MARKET_QUERY_AUTHORIZATION_CHANGED);
        }
    }

    /**
     * 校验市场访问的查询上下文，不满足约束时拒绝当前操作。
     */
    private static void requireQueryContext(Long userId) {

        if (userId == null || userId <= 0 || !Objects.equals(userId, SecurityFrameworkUtils.getLoginUserId())) {
            throw exception(MARKET_APPLY_FORBIDDEN);
        }
    }

    /**
     * 解析市场访问的字段范围。
     */
    @Override
    public Set<String> parseFieldScope(String fieldScope) {
        return MarketFieldScope.parse(fieldScope).getColumns();
    }

    /**
     * 校验市场访问的查询字段允许，不满足约束时拒绝当前操作。
     */
    @Override
    public void assertQueryColumnsAllowed(MarketFieldScope scope, boolean wildcard, Collection<String> sourceColumns) {
        if (scope == null) {
            throw exception(MARKET_APPLY_FIELD_SCOPE_INVALID);
        }
        if (scope.getMode() == MarketFieldScope.Mode.ALL) {
            return;
        }
        if (scope.getMode() == MarketFieldScope.Mode.EMPTY) {
            throw exception(MARKET_APPLY_FIELD_SCOPE_INVALID);
        }
        if (wildcard) {
            return;
        }
        if (CollUtil.isEmpty(sourceColumns)) {
            throw exception(MARKET_APPLY_FIELD_SCOPE_INVALID);
        }
        for (String column : sourceColumns) {
            if (!scope.allows(column)) {
                throw exception(MARKET_APPLY_FIELD_SCOPE_INVALID);
            }
        }
    }

    /**
     * 筛选预览。
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> T filterPreview(T preview, MarketFieldScope scope) {
        if (scope == null) {
            throw exception(MARKET_APPLY_FIELD_SCOPE_INVALID);
        }
        if (preview == null || scope.getMode() == MarketFieldScope.Mode.ALL) {
            return preview;
        }
        if (scope.getMode() == MarketFieldScope.Mode.EMPTY) {
            throw exception(MARKET_APPLY_FIELD_SCOPE_INVALID);
        }
        if (preview instanceof DatabasePreviewRespDTO dto) {
            filterDatabasePreview(dto, scope);
        } else if (preview instanceof DataQueryResultRespVO vo) {
            filterQueryResult(vo, scope);
        } else if (preview instanceof List<?> list && !list.isEmpty()
                && list.get(0) instanceof DataQueryColumnRespVO) {
            return (T) filterQueryColumns((List<DataQueryColumnRespVO>) list, scope);
        }
        return preview;
    }

    /**
     * 筛选数据库预览。
     */
    private void filterDatabasePreview(DatabasePreviewRespDTO preview, MarketFieldScope scope) {
        if (preview.getColumns() == null) {
            throw exception(MARKET_APPLY_FIELD_SCOPE_INVALID);
        }
        List<DatabasePreviewRespDTO.Column> matched = preview.getColumns().stream()
                .filter(column -> column != null && scope.allows(column.getName()))
                .toList();
        if (matched.isEmpty()) {
            throw exception(MARKET_APPLY_FIELD_SCOPE_INVALID);
        }
        Set<String> keys = new LinkedHashSet<>();
        matched.forEach(column -> keys.add(column.getName()));
        preview.setColumns(matched);
        if (preview.getRows() == null) {
            return;
        }
        preview.setRows(preview.getRows().stream().map(row -> {
            Map<String, Object> filtered = new LinkedHashMap<>();
            keys.forEach(key -> filtered.put(key, row.get(key)));
            return filtered;
        }).toList());
    }

    /**
     * 筛选查询结果。
     */
    private void filterQueryResult(DataQueryResultRespVO result, MarketFieldScope scope) {
        if (result.getColumns() == null) {
            throw exception(MARKET_APPLY_FIELD_SCOPE_INVALID);
        }
        List<DataQueryResultRespVO.Column> matched = result.getColumns().stream()
                .filter(column -> column != null && scope.allows(column.getName()))
                .toList();
        if (matched.isEmpty()) {
            throw exception(MARKET_APPLY_FIELD_SCOPE_INVALID);
        }
        Set<String> keys = new LinkedHashSet<>();
        matched.forEach(column -> keys.add(column.getName()));
        result.setColumns(matched);
        if (result.getRows() == null) {
            return;
        }
        result.setRows(result.getRows().stream().map(row -> {
            Map<String, Object> filtered = new LinkedHashMap<>();
            keys.forEach(key -> filtered.put(key, row.get(key)));
            return filtered;
        }).toList());
    }

    /**
     * 筛选查询字段。
     */
    private List<DataQueryColumnRespVO> filterQueryColumns(List<DataQueryColumnRespVO> columns,
            MarketFieldScope scope) {
        List<DataQueryColumnRespVO> matched = columns.stream()
                .filter(column -> scope.allows(column.getColumnName()))
                .toList();
        if (matched.isEmpty()) {
            throw exception(MARKET_APPLY_FIELD_SCOPE_INVALID);
        }
        return matched;
    }

    /**
     * 查找表资产。
     */
    private DataAssetDO findTableAsset(Long datasourceId, String tableName) {
        List<DataAssetDO> candidates = assetMapper.selectTablesByDatasourceId(datasourceId);
        List<DataAssetDO> exact = candidates.stream()
                .filter(asset -> Objects.equals(asset.getTableName(), tableName)).toList();
        if (exact.size() > 1 || (exact.isEmpty() && candidates.stream()
                .anyMatch(asset -> StrUtil.equalsIgnoreCase(asset.getTableName(), tableName)))) {
            // 未提供完整 schema 或标识符大小写不确定时，不猜测第一个资产，也不回退无共享限制。
            throw exception(MARKET_APPLY_SCOPE_UNCONFIRMED);
        }
        return exact.isEmpty() ? null : exact.getFirst();
    }

}
