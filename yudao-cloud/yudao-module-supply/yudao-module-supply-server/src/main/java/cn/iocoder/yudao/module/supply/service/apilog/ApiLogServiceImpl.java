package cn.iocoder.yudao.module.supply.service.apilog;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.supply.controller.admin.apilog.vo.ApiLogOverviewRespVO;
import cn.iocoder.yudao.module.supply.controller.admin.apilog.vo.ApiLogPageReqVO;
import cn.iocoder.yudao.module.supply.controller.admin.apilog.vo.ApiLogStatisticsRespVO;
import cn.iocoder.yudao.module.supply.dal.dataobject.apicategory.ApiCategoryDO;
import cn.iocoder.yudao.module.supply.dal.dataobject.apilog.ApiLogDO;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiDO;
import cn.iocoder.yudao.module.supply.dal.mysql.apicategory.ApiCategoryMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.apilog.ApiLogMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.dataapi.DataApiMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.foundation.enums.ErrorCodeConstants.API_LOG_NOT_EXISTS;

/**
 * 数据服务-API 调用记录 Service 实现类
 *
 * @author Li
 */
@Service
@Validated
public class ApiLogServiceImpl implements ApiLogService {

    @Resource
    private ApiLogMapper apiLogMapper;

    @Resource
    private ApiCategoryMapper apiCategoryMapper;

    @Resource
    private DataApiMapper dataApiMapper;

    /**
     * 获得调用记录分页（类目过滤含子级）
     *
     * @param pageReqVO 分页查询条件
     * @return 调用记录分页（类目过滤含子级）结果
     */
    @Override
    public PageResult<ApiLogDO> getApiLogPage(ApiLogPageReqVO pageReqVO) {
        return apiLogMapper.selectPage(pageReqVO, resolveCategoryIds(pageReqVO.getCategoryId()));
    }

    /**
     * 获得调用记录
     *
     * @param id 记录编号
     * @return API日志
     */
    @Override
    public ApiLogDO getApiLog(Long id) {
        ApiLogDO apiLog = apiLogMapper.selectById(id);
        if (apiLog == null) {
            throw exception(API_LOG_NOT_EXISTS);
        }
        return apiLog;
    }

    /**
     * 删除调用记录
     *
     * @param id 记录编号
     */
    @Override
    public void deleteApiLog(Long id) {
        getApiLog(id);
        apiLogMapper.deleteById(id);
    }

    /**
     * 监控统计：调用量/成功率/耗时/趋势/排行（近 N 天）
     *
     * @param days 天数
     * @return API日志统计响应数据
     */
    @Override
    public ApiLogStatisticsRespVO getStatistics(Integer days) {
        int rangeDays = days == null || days < 1 ? 7 : Math.min(days, 90);
        LocalDate startDate = LocalDate.now().minusDays(rangeDays - 1L);
        LocalDateTime start = startDate.atStartOfDay();
        List<ApiLogDO> logs = apiLogMapper.selectList(new LambdaQueryWrapperX<ApiLogDO>()
                .ge(ApiLogDO::getCallTime, start));

        ApiLogStatisticsRespVO result = new ApiLogStatisticsRespVO();
        result.setApiCount(dataApiMapper.selectCount());
        result.setOnlineApiCount(dataApiMapper.selectCount(DataApiDO::getStatus, DataApiDO.STATUS_ONLINE));

        long total = logs.size();
        long success = 0;
        long fail = 0;
        long todayCount = 0;
        long durationSum = 0;
        LocalDate today = LocalDate.now();
        // 按天趋势：先补全区间内的所有日期
        Map<String, long[]> trendMap = new LinkedHashMap<>();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        for (int i = 0; i < rangeDays; i++) {
            trendMap.put(startDate.plusDays(i).format(formatter), new long[2]);
        }
        Map<Long, long[]> apiAgg = new LinkedHashMap<>();
        Map<Long, String> apiNames = new LinkedHashMap<>();
        for (ApiLogDO logEntry : logs) {
            boolean ok = logEntry.getStatus() != null && logEntry.getStatus() == ApiLogDO.STATUS_SUCCESS;
            if (ok) {
                success++;
            } else {
                fail++;
            }
            if (logEntry.getDurationMs() != null) {
                durationSum += logEntry.getDurationMs();
            }
            if (logEntry.getCallTime() != null) {
                LocalDate day = logEntry.getCallTime().toLocalDate();
                if (day.equals(today)) {
                    todayCount++;
                }
                long[] counter = trendMap.get(day.format(formatter));
                if (counter != null) {
                    counter[ok ? 0 : 1]++;
                }
            }
            Long apiId = logEntry.getApiId() == null ? -1L : logEntry.getApiId();
            apiNames.putIfAbsent(apiId, logEntry.getApiName() == null ? "API-" + apiId : logEntry.getApiName());
            long[] agg = apiAgg.computeIfAbsent(apiId, key -> new long[2]);
            agg[0]++;
            agg[1] += logEntry.getDurationMs() == null ? 0 : logEntry.getDurationMs();
        }
        result.setTotalCount(total);
        result.setSuccessCount(success);
        result.setFailCount(fail);
        result.setTodayCount(todayCount);
        result.setSuccessRate(total == 0 ? 100.0 : Math.round(success * 10000.0 / total) / 100.0);
        result.setAvgDurationMs(total == 0 ? 0 : durationSum / total);

        List<ApiLogStatisticsRespVO.TrendItem> trend = new ArrayList<>();
        trendMap.forEach((date, counter) -> {
            ApiLogStatisticsRespVO.TrendItem item = new ApiLogStatisticsRespVO.TrendItem();
            item.setDate(date);
            item.setSuccessCount(counter[0]);
            item.setFailCount(counter[1]);
            trend.add(item);
        });
        result.setTrend(trend);

        result.setTopApis(buildRank(apiAgg, apiNames, entry -> entry.getValue()[0]));
        result.setSlowApis(buildRank(apiAgg, apiNames,
                entry -> entry.getValue()[0] == 0 ? 0 : entry.getValue()[1] / entry.getValue()[0]));
        return result;
    }

    /**
     * 数据概览：今日/本月调用量、API 数、近 30 天趋势、今日/本月调用排行
     *
     * @return 概览
     */
    @Override
    public ApiLogOverviewRespVO getOverview() {
        LocalDate today = LocalDate.now();
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate trendStart = today.minusDays(29);
        // 一次查询覆盖「本月」与「近 30 天」两个区间
        LocalDate queryStart = monthStart.isBefore(trendStart) ? monthStart : trendStart;
        List<ApiLogDO> logs = apiLogMapper.selectList(new LambdaQueryWrapperX<ApiLogDO>()
                .ge(ApiLogDO::getCallTime, queryStart.atStartOfDay()));

        ApiLogOverviewRespVO result = new ApiLogOverviewRespVO();
        result.setApiCount(dataApiMapper.selectCount());
        result.setOnlineApiCount(dataApiMapper.selectCount(DataApiDO::getStatus, DataApiDO.STATUS_ONLINE));

        long todayCount = 0;
        long todaySuccess = 0;
        long todayFail = 0;
        long monthCount = 0;
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        // 趋势：补全近 30 天的所有日期
        Map<String, long[]> trendMap = new LinkedHashMap<>();
        for (int i = 0; i < 30; i++) {
            trendMap.put(trendStart.plusDays(i).format(formatter), new long[1]);
        }
        Map<Long, long[]> todayAgg = new LinkedHashMap<>();
        Map<Long, long[]> monthAgg = new LinkedHashMap<>();
        Map<Long, String> apiNames = new LinkedHashMap<>();
        for (ApiLogDO logEntry : logs) {
            if (logEntry.getCallTime() == null) {
                continue;
            }
            LocalDate day = logEntry.getCallTime().toLocalDate();
            Long apiId = logEntry.getApiId() == null ? -1L : logEntry.getApiId();
            apiNames.putIfAbsent(apiId, logEntry.getApiName() == null ? "API-" + apiId : logEntry.getApiName());
            if (!day.isBefore(monthStart)) {
                monthCount++;
                monthAgg.computeIfAbsent(apiId, key -> new long[1])[0]++;
            }
            if (day.equals(today)) {
                todayCount++;
                boolean ok = logEntry.getStatus() != null && logEntry.getStatus() == ApiLogDO.STATUS_SUCCESS;
                if (ok) {
                    todaySuccess++;
                } else {
                    todayFail++;
                }
                todayAgg.computeIfAbsent(apiId, key -> new long[1])[0]++;
            }
            long[] counter = trendMap.get(day.format(formatter));
            if (counter != null) {
                counter[0]++;
            }
        }
        result.setTodayCount(todayCount);
        result.setTodaySuccessCount(todaySuccess);
        result.setTodayFailCount(todayFail);
        result.setMonthCount(monthCount);

        List<ApiLogOverviewRespVO.TrendItem> trend = new ArrayList<>();
        trendMap.forEach((date, counter) -> {
            ApiLogOverviewRespVO.TrendItem item = new ApiLogOverviewRespVO.TrendItem();
            item.setDate(date);
            item.setCount(counter[0]);
            trend.add(item);
        });
        result.setTrend(trend);

        result.setTodayRank(buildOverviewRank(todayAgg, apiNames));
        result.setMonthRank(buildOverviewRank(monthAgg, apiNames));
        return result;
    }

    /**
     * 构造概览排名。
     *
     * @param apiAgg API聚合
     * @param apiNames API名称
     * @return 构造概览排名结果集合
     */
    private List<ApiLogOverviewRespVO.RankItem> buildOverviewRank(Map<Long, long[]> apiAgg,
            Map<Long, String> apiNames) {
        return apiAgg.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<Long, long[]> entry) -> entry.getValue()[0]).reversed())
                .limit(10)
                .map(entry -> {
                    ApiLogOverviewRespVO.RankItem item = new ApiLogOverviewRespVO.RankItem();
                    item.setApiId(entry.getKey() < 0 ? null : entry.getKey());
                    item.setName(apiNames.getOrDefault(entry.getKey(), "API-" + entry.getKey()));
                    item.setValue(entry.getValue()[0]);
                    return item;
                })
                .toList();
    }

    /**
     * 构造排名。
     *
     * @param apiAgg API聚合
     * @param apiNames API名称
     * @param valueExtractor 值提取器
     * @return 构造排名结果集合
     */
    private List<ApiLogStatisticsRespVO.RankItem> buildRank(
            Map<Long, long[]> apiAgg,
            Map<Long, String> apiNames,
            java.util.function.ToLongFunction<Map.Entry<Long, long[]>> valueExtractor) {
        return apiAgg.entrySet().stream()
                .sorted(Comparator.comparingLong(valueExtractor).reversed())
                .limit(10)
                .map(entry -> {
                    ApiLogStatisticsRespVO.RankItem item = new ApiLogStatisticsRespVO.RankItem();
                    item.setApiId(entry.getKey() < 0 ? null : entry.getKey());
                    item.setName(apiNames.getOrDefault(entry.getKey(), "API-" + entry.getKey()));
                    item.setValue(valueExtractor.applyAsLong(entry));
                    return item;
                })
                .toList();
    }

    /**
     * 类目过滤：包含选中类目及其全部子孙
     */
    private Collection<Long> resolveCategoryIds(Long categoryId) {
        if (categoryId == null) {
            return null;
        }
        List<ApiCategoryDO> all = apiCategoryMapper.selectList();
        Set<Long> ids = new LinkedHashSet<>();
        ids.add(categoryId);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (ApiCategoryDO category : all) {
                if (category.getParentId() != null && ids.contains(category.getParentId())
                        && ids.add(category.getId())) {
                    changed = true;
                }
            }
        }
        return ids;
    }

}
