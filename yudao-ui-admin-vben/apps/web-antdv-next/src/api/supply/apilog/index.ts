import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace SupplyApiLogApi {
  export type Id = number | string;
  /** API 调用记录 */
  export interface ApiLog {
    id?: Id;
    apiId?: Id;
    apiName?: string;
    categoryId?: Id;
    appId?: Id;
    releaseId?: Id;
    rejectStage?: string;
    traceId?: string;
    callMode?: string;
    callerIp?: string;
    callPath?: string;
    method?: string;
    paramsJson?: string;
    callTime?: number | string;
    durationMs?: number;
    dataCount?: number;
    status?: number; // 1-成功 0-失败
    errorInfo?: string;
  }

  /** 数据概览 */
  export interface Overview {
    todayCount?: number;
    todaySuccessCount?: number;
    todayFailCount?: number;
    monthCount?: number;
    apiCount?: number;
    onlineApiCount?: number;
    trend?: { count?: number; date?: string }[];
    todayRank?: { apiId?: Id; name?: string; value?: number }[];
    monthRank?: { apiId?: Id; name?: string; value?: number }[];
  }

  /** 监控统计 */
  export interface Statistics {
    apiCount?: number;
    onlineApiCount?: number;
    totalCount?: number;
    todayCount?: number;
    successCount?: number;
    failCount?: number;
    successRate?: number;
    avgDurationMs?: number;
    trend?: {
      date?: string;
      failCount?: number;
      successCount?: number;
    }[];
    topApis?: { apiId?: Id; name?: string; value?: number }[];
    slowApis?: { apiId?: Id; name?: string; value?: number }[];
  }
}

/** 查询调用记录分页 */
export function getApiLogPage(params: PageParam) {
  return requestClient.get<PageResult<SupplyApiLogApi.ApiLog>>(
    '/supply/api-log/page',
    { params },
  );
}

/** 查询调用记录详情 */
export function getApiLog(id: SupplyApiLogApi.Id) {
  return requestClient.get<SupplyApiLogApi.ApiLog>('/supply/api-log/get', {
    params: { id },
  });
}

/** 删除调用记录 */
export function deleteApiLog(id: SupplyApiLogApi.Id) {
  return requestClient.delete('/supply/api-log/delete', { params: { id } });
}

/** 数据概览：今日/本月调用量、API 数、近 30 天趋势、调用排行 */
export function getApiLogOverview() {
  return requestClient.get<SupplyApiLogApi.Overview>(
    '/supply/api-log/overview',
  );
}

/** 监控统计：调用量/成功率/耗时/趋势/排行 */
export function getApiLogStatistics(days?: number) {
  return requestClient.get<SupplyApiLogApi.Statistics>(
    '/supply/api-log/statistics',
    { params: { days } },
  );
}
