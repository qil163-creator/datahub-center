import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace AssetOperationApi {
  export type ValueLevel = 'HIGH' | 'IDLE' | 'LOW' | 'MEDIUM';
  export type Suggestion =
    | 'COMPLETE_METADATA'
    | 'IMPROVE_QUALITY'
    | 'KEEP'
    | 'PROMOTE'
    | 'RETIRE_REVIEW';
  export type SortField =
    | 'apiCallCount'
    | 'heatScore'
    | 'idleDays'
    | 'usageCount'
    | 'valueScore';

  /** 单个资产的运营指标（统计窗口内） */
  export interface Metric {
    activeGrantCount: number;
    apiAppCount: number;
    apiCallCount: number;
    apiStatsAvailable: boolean;
    apiSuccessCount: number;
    applyCount: number;
    assetCode?: string;
    assetId: number | string;
    assetName: string;
    assetStatus?: number;
    assetType?: number;
    breadthScore: number;
    browseCount: number;
    browseUserCount: number;
    categoryId?: number | string;
    categoryName?: string;
    completenessScore: number;
    favoriteCount: number;
    feedbackCount: number;
    grantDeptCount: number;
    heatScore: number;
    idleDays: number;
    lastUsedTime?: string;
    layer?: string;
    ownerDeptId?: number | string;
    qualityScore?: null | number;
    sourceSystemName?: string;
    statDate: string;
    suggestion: Suggestion;
    usageCount: number;
    usageUserCount: number;
    valueLevel: ValueLevel;
    valueScore: number;
    windowDays: number;
  }

  export interface PageReq extends PageParam {
    assetStatus?: number;
    assetType?: number;
    categoryId?: number | string;
    keyword?: string;
    sortField?: SortField;
    sortOrder?: 'asc' | 'desc';
    statDate?: string;
    suggestion?: Suggestion;
    valueLevel?: ValueLevel;
  }

  export interface TrendPoint {
    applyCount: number;
    browseCount: number;
    date: string;
    heatScore?: null | number;
    usageCount: number;
    valueScore?: null | number;
  }

  export interface Trend {
    assetId: number | string;
    assetName: string;
    points: TrendPoint[];
  }

  export interface CategoryItem {
    avgValue?: null | number;
    categoryId?: null | number | string;
    categoryName: string;
    count: number;
    idleCount: number;
    publishedCount: number;
  }

  /** 资产盘点报告 */
  export interface Inventory {
    activeUserCount: number;
    apiCallTotal: number;
    apiStatsAvailable?: boolean;
    applyTotal: number;
    avgCompleteness?: null | number;
    avgQuality?: null | number;
    avgValue?: null | number;
    browseTotal: number;
    categoryDistribution: CategoryItem[];
    descriptionRate?: null | number;
    levelDistribution: Partial<Record<ValueLevel, number>>;
    longestIdle: Metric[];
    offlineCount: number;
    ownerRate?: null | number;
    publishedCount: number;
    qualityAssessedRate?: null | number;
    statDate?: null | string;
    suggestionDistribution: Partial<Record<Suggestion, number>>;
    topHot: Metric[];
    topValue: Metric[];
    totalCount: number;
    typeDistribution: Record<string, number>;
    unpublishedCount: number;
    usageTotal: number;
    usedCount: number;
    windowDays: number;
  }
}

/** 资产运营指标分页（默认最近一次快照） */
export function getAssetOperationPage(params: AssetOperationApi.PageReq) {
  return requestClient.get<PageResult<AssetOperationApi.Metric>>(
    '/asset/operation/page',
    { params },
  );
}

/** 单个资产的逐日使用与评分趋势 */
export function getAssetOperationTrend(assetId: number | string, days = 30) {
  return requestClient.get<AssetOperationApi.Trend>('/asset/operation/trend', {
    params: { assetId, days },
  });
}

/** 资产盘点报告 */
export function getAssetInventory(statDate?: string) {
  return requestClient.get<AssetOperationApi.Inventory>(
    '/asset/operation/inventory',
    { params: statDate ? { statDate } : {} },
  );
}

/** 立即重新计算今天的运营指标，返回资产数 */
export function refreshAssetOperation() {
  return requestClient.post<number>('/asset/operation/refresh');
}

/** 导出资产盘点清单 */
export function exportAssetOperation(
  params: Omit<AssetOperationApi.PageReq, 'pageNo' | 'pageSize'>,
) {
  return requestClient.download('/asset/operation/export-excel', { params });
}
