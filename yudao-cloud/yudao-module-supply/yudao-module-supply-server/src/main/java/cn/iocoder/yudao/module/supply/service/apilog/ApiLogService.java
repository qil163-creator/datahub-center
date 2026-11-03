package cn.iocoder.yudao.module.supply.service.apilog;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.supply.controller.admin.apilog.vo.ApiLogOverviewRespVO;
import cn.iocoder.yudao.module.supply.controller.admin.apilog.vo.ApiLogPageReqVO;
import cn.iocoder.yudao.module.supply.controller.admin.apilog.vo.ApiLogStatisticsRespVO;
import cn.iocoder.yudao.module.supply.dal.dataobject.apilog.ApiLogDO;

/**
 * 数据服务-API 调用记录 Service 接口
 *
 * @author Li
 */
public interface ApiLogService {

    /**
     * 获得调用记录分页（类目过滤含子级）
     */
    PageResult<ApiLogDO> getApiLogPage(ApiLogPageReqVO pageReqVO);

    /**
     * 获得调用记录
     */
    ApiLogDO getApiLog(Long id);

    /**
     * 删除调用记录
     */
    void deleteApiLog(Long id);

    /**
     * 监控统计：调用量/成功率/耗时/趋势/排行（近 N 天）
     */
    ApiLogStatisticsRespVO getStatistics(Integer days);

    /**
     * 数据概览：今日/本月调用量、API 数、近 30 天趋势、今日/本月调用排行
     */
    ApiLogOverviewRespVO getOverview();

}
