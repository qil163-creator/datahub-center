package cn.iocoder.yudao.module.asset.service.monitor;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.asset.controller.admin.monitor.vo.AssetInventoryRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.monitor.vo.AssetOperationPageReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.monitor.vo.AssetOperationRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.monitor.vo.AssetOperationTrendRespVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.categorydataasset.CategoryDataAssetDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.monitor.AssetOperationMetricDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.monitor.AssetUsageLogDO;
import cn.iocoder.yudao.module.asset.dal.mysql.categorydataasset.CategoryDataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.monitor.AssetOperationMetricMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.monitor.AssetOperationSourceMapper;
import cn.iocoder.yudao.module.supply.api.dataapi.SupplyDataApiApi;
import cn.iocoder.yudao.module.supply.api.dataapi.dto.SupplyApiCallStatReqDTO;
import cn.iocoder.yudao.module.supply.api.dataapi.dto.SupplyApiCallStatRespDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.ASSET_OPERATION_REFRESHING;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.DATA_ASSET_NOT_EXISTS;

/**
 * 资产运营 Service 实现。
 *
 * <p>指标完全由既有业务数据派生：使用行为日志（浏览/申请/预览查询）、集市收藏、申请授权、问题反馈，
 * 以及数据服务开放网关调用（经 supply RPC，只计 APP_GATEWAY 真实调用）。数据服务不可用时接口调用按 0 计，
 * 并在快照上标记 {@code apiStatsAvailable=false}，不阻断其余指标。</p>
 */
@Service
@Slf4j
public class AssetOperationServiceImpl implements AssetOperationService {

    /** 计算互斥的事务级咨询锁键（"ASSETOPS" 的 ASCII 十六进制） */
    static final long REFRESH_LOCK_KEY = 0x4153534554_4F5053L;

    private static final int TOP_N = 10;

    private final DataAssetMapper assetMapper;

    private final CategoryDataAssetMapper categoryMapper;

    private final AssetOperationMetricMapper metricMapper;

    private final AssetOperationSourceMapper sourceMapper;

    private final SupplyDataApiApi supplyDataApiApi;

    private final TransactionTemplate transactionTemplate;

    private final Clock clock;

    @Autowired
    public AssetOperationServiceImpl(DataAssetMapper assetMapper, CategoryDataAssetMapper categoryMapper,
            AssetOperationMetricMapper metricMapper, AssetOperationSourceMapper sourceMapper,
            SupplyDataApiApi supplyDataApiApi, TransactionTemplate transactionTemplate) {
        this(assetMapper, categoryMapper, metricMapper, sourceMapper, supplyDataApiApi, transactionTemplate,
                Clock.systemDefaultZone());
    }

    AssetOperationServiceImpl(DataAssetMapper assetMapper, CategoryDataAssetMapper categoryMapper,
            AssetOperationMetricMapper metricMapper, AssetOperationSourceMapper sourceMapper,
            SupplyDataApiApi supplyDataApiApi, TransactionTemplate transactionTemplate, Clock clock) {
        this.assetMapper = assetMapper;
        this.categoryMapper = categoryMapper;
        this.metricMapper = metricMapper;
        this.sourceMapper = sourceMapper;
        this.supplyDataApiApi = supplyDataApiApi;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    // ==================== 计算 ====================

    @Override
    public int refresh(LocalDate statDate) {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime since = statDate.minusDays(WINDOW_DAYS - 1L).atStartOfDay();
        // 汇总读取放在事务外；网关调用统计经 RPC，不能占用写事务和咨询锁。
        List<DataAssetDO> assets = assetMapper.selectList();
        Map<Long, Map<Integer, AssetOperationSourceMapper.ActionRow>> actions = new HashMap<>();
        for (AssetOperationSourceMapper.ActionRow row : sourceMapper.selectActionStats(since)) {
            actions.computeIfAbsent(row.getAssetId(), id -> new HashMap<>()).put(row.getAction(), row);
        }
        Map<Long, LocalDateTime> lastUsed = toMap(sourceMapper.selectLastUsed(),
                AssetOperationSourceMapper.LastRow::getAssetId, AssetOperationSourceMapper.LastRow::getLastTime);
        Map<Long, Long> favorites = toMap(sourceMapper.selectFavoriteCounts(),
                AssetOperationSourceMapper.CountRow::getAssetId, AssetOperationSourceMapper.CountRow::getTotal);
        Map<Long, AssetOperationSourceMapper.GrantRow> grants = toMap(sourceMapper.selectActiveGrants(now),
                AssetOperationSourceMapper.GrantRow::getAssetId, Function.identity());
        Map<Long, Long> feedback = toMap(sourceMapper.selectFeedbackCounts(since),
                AssetOperationSourceMapper.CountRow::getAssetId, AssetOperationSourceMapper.CountRow::getTotal);
        ApiCalls apiCalls = loadApiCalls(since);

        List<AssetOperationMetricDO> metrics = new ArrayList<>(assets.size());
        for (DataAssetDO asset : assets) {
            Map<Integer, AssetOperationSourceMapper.ActionRow> byAction = actions.getOrDefault(asset.getId(), Map.of());
            AssetOperationSourceMapper.GrantRow grant = grants.get(asset.getId());
            ApiCallTotal api = apiCalls.byAsset().getOrDefault(asset.getId(), ApiCallTotal.EMPTY);
            AssetOperationScoring.Signals signals = new AssetOperationScoring.Signals(
                    total(byAction, AssetUsageLogDO.ACTION_BROWSE), users(byAction, AssetUsageLogDO.ACTION_BROWSE),
                    total(byAction, AssetUsageLogDO.ACTION_USAGE), users(byAction, AssetUsageLogDO.ACTION_USAGE),
                    total(byAction, AssetUsageLogDO.ACTION_APPLY), favorites.getOrDefault(asset.getId(), 0L),
                    grant == null ? 0 : nz(grant.getTotal()), grant == null ? 0 : nz(grant.getDepts()),
                    api.calls(), api.apps());
            LocalDateTime used = latest(lastUsed.get(asset.getId()), api.lastCall());
            int idleDays = idleDays(used, asset, statDate);
            AssetOperationScoring.Result result = AssetOperationScoring.evaluate(signals, asset, idleDays);
            metrics.add(AssetOperationMetricDO.builder().statDate(statDate).windowDays(WINDOW_DAYS)
                    .assetId(asset.getId()).assetName(asset.getAssetName()).assetCode(asset.getAssetCode())
                    .assetType(asset.getAssetType()).assetStatus(asset.getStatus()).categoryId(asset.getCategoryId())
                    .layer(asset.getLayer()).sourceSystemName(asset.getSourceSystemName())
                    .ownerDeptId(asset.getOwnerDeptId())
                    .browseCount(signals.browse()).browseUserCount(signals.browseUsers())
                    .applyCount(signals.apply()).usageCount(signals.usage()).usageUserCount(signals.usageUsers())
                    .favoriteCount(signals.favorite()).activeGrantCount(signals.activeGrants())
                    .grantDeptCount(signals.grantDepts()).apiCallCount(api.calls()).apiSuccessCount(api.success())
                    .apiAppCount(api.apps()).feedbackCount(feedback.getOrDefault(asset.getId(), 0L))
                    .lastUsedTime(used).idleDays(idleDays)
                    .heatScore(result.heat()).breadthScore(result.breadth()).qualityScore(result.quality())
                    .completenessScore(result.completeness()).valueScore(result.value())
                    .valueLevel(result.level()).suggestion(result.suggestion())
                    .apiStatsAvailable(apiCalls.available()).build());
        }
        transactionTemplate.executeWithoutResult(status -> {
            if (!metricMapper.tryRefreshLock(REFRESH_LOCK_KEY)) {
                throw exception(ASSET_OPERATION_REFRESHING);
            }
            metricMapper.deleteByStatDate(statDate);
            metrics.forEach(metricMapper::insert);
            metricMapper.deleteBefore(statDate.minusDays(RETENTION_DAYS));
        });
        return metrics.size();
    }

    /** 接口资产经授权申请关联到数据 API，按 500 个一批向数据服务统计开放网关调用。 */
    private ApiCalls loadApiCalls(LocalDateTime since) {
        Map<Long, Set<Long>> assetsByApi = new HashMap<>();
        for (AssetOperationSourceMapper.ApiBindingRow row : sourceMapper.selectApiBindings()) {
            assetsByApi.computeIfAbsent(row.getApiId(), id -> new LinkedHashSet<>()).add(row.getAssetId());
        }
        if (assetsByApi.isEmpty()) {
            return new ApiCalls(Map.of(), true);
        }
        Map<Long, ApiCallTotal> byAsset = new HashMap<>();
        List<Long> apiIds = new ArrayList<>(new TreeSet<>(assetsByApi.keySet()));
        try {
            for (int from = 0; from < apiIds.size(); from += SupplyApiCallStatReqDTO.MAX_API_IDS) {
                SupplyApiCallStatReqDTO req = new SupplyApiCallStatReqDTO();
                req.setApiIds(apiIds.subList(from, Math.min(apiIds.size(), from + SupplyApiCallStatReqDTO.MAX_API_IDS)));
                req.setSince(since);
                List<SupplyApiCallStatRespDTO> stats = supplyDataApiApi.listApiCallStatistics(req).getCheckedData();
                for (SupplyApiCallStatRespDTO stat : stats == null ? List.<SupplyApiCallStatRespDTO>of() : stats) {
                    for (Long assetId : assetsByApi.getOrDefault(stat.getApiId(), Set.of())) {
                        byAsset.merge(assetId, new ApiCallTotal(nz(stat.getCallCount()), nz(stat.getSuccessCount()),
                                nz(stat.getAppCount()), stat.getLastCallTime()), ApiCallTotal::plus);
                    }
                }
            }
            return new ApiCalls(byAsset, true);
        } catch (RuntimeException ex) {
            log.warn("[loadApiCalls][数据服务调用统计不可用，接口调用按 0 计]", ex);
            return new ApiCalls(Map.of(), false);
        }
    }

    static int idleDays(LocalDateTime lastUsed, DataAssetDO asset, LocalDate statDate) {
        LocalDateTime anchor = lastUsed != null ? lastUsed
                : asset.getPublishTime() != null ? asset.getPublishTime() : asset.getCreateTime();
        if (anchor == null) {
            return 0;
        }
        return (int) Math.max(0, ChronoUnit.DAYS.between(anchor.toLocalDate(), statDate));
    }

    // ==================== 查询 ====================

    @Override
    public PageResult<AssetOperationRespVO> getPage(AssetOperationPageReqVO reqVO) {
        LocalDate statDate = reqVO.getStatDate() != null ? reqVO.getStatDate() : metricMapper.selectLatestStatDate();
        if (statDate == null) {
            return PageResult.empty();
        }
        PageResult<AssetOperationMetricDO> page = metricMapper.selectPage(statDate, reqVO);
        return new PageResult<>(withCategoryNames(page.getList()), page.getTotal());
    }

    @Override
    public List<AssetOperationRespVO> getExportList(AssetOperationPageReqVO reqVO) {
        reqVO.setPageNo(1);
        reqVO.setPageSize(PageParam.PAGE_SIZE_NONE);
        return getPage(reqVO).getList();
    }

    @Override
    public AssetOperationTrendRespVO getTrend(Long assetId, int days) {
        DataAssetDO asset = assetMapper.selectById(assetId);
        if (asset == null) {
            throw exception(DATA_ASSET_NOT_EXISTS);
        }
        int span = Math.max(7, Math.min(180, days));
        LocalDate today = LocalDate.now(clock);
        LocalDate from = today.minusDays(span - 1L);
        Map<LocalDate, AssetOperationMetricDO> snapshots = toMap(metricMapper.selectTrend(assetId, from),
                AssetOperationMetricDO::getStatDate, Function.identity());
        Map<LocalDate, Map<Integer, Long>> daily = new HashMap<>();
        for (AssetOperationSourceMapper.DailyRow row : sourceMapper.selectDailyActions(assetId, from.atStartOfDay())) {
            daily.computeIfAbsent(row.getDay(), d -> new HashMap<>()).put(row.getAction(), nz(row.getTotal()));
        }
        List<AssetOperationTrendRespVO.Point> points = new ArrayList<>(span);
        for (LocalDate date = from; !date.isAfter(today); date = date.plusDays(1)) {
            Map<Integer, Long> counts = daily.getOrDefault(date, Map.of());
            AssetOperationMetricDO snapshot = snapshots.get(date);
            AssetOperationTrendRespVO.Point point = new AssetOperationTrendRespVO.Point();
            point.setDate(date);
            point.setBrowseCount(counts.getOrDefault(AssetUsageLogDO.ACTION_BROWSE, 0L));
            point.setApplyCount(counts.getOrDefault(AssetUsageLogDO.ACTION_APPLY, 0L));
            point.setUsageCount(counts.getOrDefault(AssetUsageLogDO.ACTION_USAGE, 0L));
            point.setHeatScore(snapshot == null ? null : snapshot.getHeatScore());
            point.setValueScore(snapshot == null ? null : snapshot.getValueScore());
            points.add(point);
        }
        AssetOperationTrendRespVO resp = new AssetOperationTrendRespVO();
        resp.setAssetId(assetId);
        resp.setAssetName(asset.getAssetName());
        resp.setPoints(points);
        return resp;
    }

    @Override
    public AssetInventoryRespVO getInventory(LocalDate requestedDate) {
        AssetInventoryRespVO report = new AssetInventoryRespVO();
        LocalDate statDate = requestedDate != null ? requestedDate : metricMapper.selectLatestStatDate();
        report.setStatDate(statDate);
        report.setWindowDays(WINDOW_DAYS);
        List<AssetOperationMetricDO> rows = statDate == null ? List.of() : metricMapper.selectListByStatDate(statDate);
        if (rows.isEmpty()) {
            report.setStatDate(null);
            report.setTypeDistribution(Map.of());
            report.setLevelDistribution(Map.of());
            report.setSuggestionDistribution(Map.of());
            report.setCategoryDistribution(List.of());
            report.setTopHot(List.of());
            report.setTopValue(List.of());
            report.setLongestIdle(List.of());
            return report;
        }
        report.setApiStatsAvailable(rows.stream().allMatch(row -> Boolean.TRUE.equals(row.getApiStatsAvailable())));
        report.setTotalCount(rows.size());
        report.setPublishedCount(count(rows, row -> Integer.valueOf(DataAssetDO.STATUS_PUBLISHED).equals(row.getAssetStatus())));
        report.setUnpublishedCount(count(rows, row -> Integer.valueOf(DataAssetDO.STATUS_UNPUBLISHED).equals(row.getAssetStatus())));
        report.setOfflineCount(count(rows, row -> Integer.valueOf(DataAssetDO.STATUS_OFFLINE).equals(row.getAssetStatus())));
        report.setUsedCount(count(rows, row -> nz(row.getUsageCount()) + nz(row.getApplyCount()) + nz(row.getApiCallCount()) > 0));
        Long activeUsers = sourceMapper.selectActiveUserCount(statDate.minusDays(WINDOW_DAYS - 1L).atStartOfDay());
        report.setActiveUserCount(nz(activeUsers));
        report.setBrowseTotal(sum(rows, AssetOperationMetricDO::getBrowseCount));
        report.setUsageTotal(sum(rows, AssetOperationMetricDO::getUsageCount));
        report.setApplyTotal(sum(rows, AssetOperationMetricDO::getApplyCount));
        report.setApiCallTotal(sum(rows, AssetOperationMetricDO::getApiCallCount));

        // 覆盖率基于资产当前编目信息，与快照同口径：描述、责任人取资产表最新值。
        Map<Long, DataAssetDO> assets = toMap(assetMapper.selectByIds(rows.stream().map(AssetOperationMetricDO::getAssetId).toList()),
                DataAssetDO::getId, Function.identity());
        report.setDescriptionRate(rate(rows, row -> {
            DataAssetDO asset = assets.get(row.getAssetId());
            return asset != null && StrUtil.isNotBlank(asset.getDescription());
        }));
        report.setOwnerRate(rate(rows, row -> {
            DataAssetDO asset = assets.get(row.getAssetId());
            return asset != null && (asset.getTechLeaderUserId() != null || asset.getBusinessLeaderUserId() != null);
        }));
        report.setQualityAssessedRate(rate(rows, row -> row.getQualityScore() != null));
        report.setAvgCompleteness(avg(rows.stream().map(AssetOperationMetricDO::getCompletenessScore).toList()));
        report.setAvgQuality(avg(rows.stream().map(AssetOperationMetricDO::getQualityScore).filter(Objects::nonNull).toList()));
        report.setAvgValue(avg(rows.stream().map(AssetOperationMetricDO::getValueScore).toList()));
        report.setTypeDistribution(rows.stream().collect(Collectors.groupingBy(
                row -> Objects.requireNonNullElse(row.getAssetType(), 0), TreeMap::new, Collectors.counting())));
        report.setLevelDistribution(rows.stream().collect(Collectors.groupingBy(
                AssetOperationMetricDO::getValueLevel, TreeMap::new, Collectors.counting())));
        report.setSuggestionDistribution(rows.stream().collect(Collectors.groupingBy(
                AssetOperationMetricDO::getSuggestion, TreeMap::new, Collectors.counting())));
        report.setCategoryDistribution(categoryDistribution(rows));
        report.setTopHot(top(rows, Comparator.comparing(AssetOperationMetricDO::getHeatScore).reversed(), row -> true));
        report.setTopValue(top(rows, Comparator.comparing(AssetOperationMetricDO::getValueScore).reversed(), row -> true));
        report.setLongestIdle(top(rows, Comparator.comparing(AssetOperationMetricDO::getIdleDays).reversed(),
                row -> Integer.valueOf(DataAssetDO.STATUS_PUBLISHED).equals(row.getAssetStatus())));
        return report;
    }

    private List<AssetInventoryRespVO.CategoryItem> categoryDistribution(List<AssetOperationMetricDO> rows) {
        Map<Long, String> names = categoryNames(rows);
        Map<Optional<Long>, List<AssetOperationMetricDO>> grouped = rows.stream()
                .collect(Collectors.groupingBy(row -> Optional.ofNullable(row.getCategoryId())));
        List<AssetInventoryRespVO.CategoryItem> items = new ArrayList<>();
        grouped.forEach((category, members) -> {
            AssetInventoryRespVO.CategoryItem item = new AssetInventoryRespVO.CategoryItem();
            item.setCategoryId(category.orElse(null));
            item.setCategoryName(category.map(id -> names.getOrDefault(id, "类目#" + id)).orElse("未归类"));
            item.setCount(members.size());
            item.setPublishedCount(count(members,
                    row -> Integer.valueOf(DataAssetDO.STATUS_PUBLISHED).equals(row.getAssetStatus())));
            item.setIdleCount(count(members, row -> AssetOperationScoring.LEVEL_IDLE.equals(row.getValueLevel())));
            item.setAvgValue(avg(members.stream().map(AssetOperationMetricDO::getValueScore).toList()));
            items.add(item);
        });
        items.sort(Comparator.comparingLong(AssetInventoryRespVO.CategoryItem::getCount).reversed()
                .thenComparing(item -> Objects.requireNonNullElse(item.getCategoryId(), Long.MAX_VALUE)));
        return items;
    }

    private List<AssetOperationRespVO> top(List<AssetOperationMetricDO> rows,
            Comparator<AssetOperationMetricDO> order, Predicate<AssetOperationMetricDO> filter) {
        return withCategoryNames(rows.stream().filter(filter)
                .sorted(order.thenComparing(AssetOperationMetricDO::getAssetId)).limit(TOP_N).toList());
    }

    private List<AssetOperationRespVO> withCategoryNames(List<AssetOperationMetricDO> rows) {
        Map<Long, String> names = categoryNames(rows);
        List<AssetOperationRespVO> list = BeanUtils.toBean(rows, AssetOperationRespVO.class);
        list.forEach(item -> item.setCategoryName(item.getCategoryId() == null ? null : names.get(item.getCategoryId())));
        return list;
    }

    private Map<Long, String> categoryNames(List<AssetOperationMetricDO> rows) {
        Set<Long> ids = rows.stream().map(AssetOperationMetricDO::getCategoryId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return toMap(categoryMapper.selectByIds(ids), CategoryDataAssetDO::getId, CategoryDataAssetDO::getCategoryName);
    }

    // ==================== 工具 ====================

    private static long total(Map<Integer, AssetOperationSourceMapper.ActionRow> rows, int action) {
        AssetOperationSourceMapper.ActionRow row = rows.get(action);
        return row == null ? 0 : nz(row.getTotal());
    }

    private static long users(Map<Integer, AssetOperationSourceMapper.ActionRow> rows, int action) {
        AssetOperationSourceMapper.ActionRow row = rows.get(action);
        return row == null ? 0 : nz(row.getUsers());
    }

    private static long nz(Long value) {
        return value == null ? 0 : value;
    }

    private static LocalDateTime latest(LocalDateTime a, LocalDateTime b) {
        return a == null ? b : b == null ? a : a.isAfter(b) ? a : b;
    }

    private static long count(List<AssetOperationMetricDO> rows, Predicate<AssetOperationMetricDO> filter) {
        return rows.stream().filter(filter).count();
    }

    private static long sum(List<AssetOperationMetricDO> rows, Function<AssetOperationMetricDO, Long> field) {
        return rows.stream().map(field).mapToLong(AssetOperationServiceImpl::nz).sum();
    }

    private static BigDecimal rate(List<AssetOperationMetricDO> rows, Predicate<AssetOperationMetricDO> filter) {
        return rows.isEmpty() ? BigDecimal.ZERO : BigDecimal.valueOf(count(rows, filter) * 100.0 / rows.size())
                .setScale(1, RoundingMode.HALF_UP);
    }

    private static BigDecimal avg(List<BigDecimal> values) {
        List<BigDecimal> present = values.stream().filter(Objects::nonNull).toList();
        if (present.isEmpty()) {
            return null;
        }
        return present.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(present.size()), 1, RoundingMode.HALF_UP);
    }

    private static <T, K, V> Map<K, V> toMap(Collection<T> rows, Function<T, K> key, Function<T, V> value) {
        Map<K, V> map = new HashMap<>();
        for (T row : rows) {
            K k = key.apply(row);
            V v = value.apply(row);
            if (k != null && v != null) {
                map.put(k, v);
            }
        }
        return map;
    }

    record ApiCallTotal(long calls, long success, long apps, LocalDateTime lastCall) {

        static final ApiCallTotal EMPTY = new ApiCallTotal(0, 0, 0, null);

        ApiCallTotal plus(ApiCallTotal other) {
            return new ApiCallTotal(calls + other.calls, success + other.success, apps + other.apps,
                    latest(lastCall, other.lastCall));
        }
    }

    record ApiCalls(Map<Long, ApiCallTotal> byAsset, boolean available) {
    }

}
