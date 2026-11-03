import type { PageParam, PageResult } from '@vben/request';

import type { MarketCheckApi } from './check';

import { requestClient } from '#/api/request';

export namespace DepartmentAssetsApi {
  export interface Asset {
    id: MarketCheckApi.Id;
    displayId: string;
    assetId?: MarketCheckApi.Id;
    marketName?: string;
    marketCategoryId?: MarketCheckApi.Id;
    marketCategoryName?: string;
    deliveryTypes?: number[];
    applyTime?: MarketCheckApi.Time;
    startTime?: MarketCheckApi.Time;
    endTime?: MarketCheckApi.Time;
    applyType?: number;
    status?: number;
    statusLabel?: string;
    statusTone?: string;
    frequency?: string;
    canView: boolean;
    usable?: boolean;
    canInvoke?: boolean;
  }
  export interface PageReq extends PageParam {
    keyword?: string;
    deliveryType?: number;
    marketCategoryId?: MarketCheckApi.Id;
    sortField?: 'applyId' | 'applyTime';
    sortOrder?: 'asc' | 'desc';
  }
  export interface Category {
    id: MarketCheckApi.Id;
    parentId?: MarketCheckApi.Id;
    name: string;
  }
  export interface Detail {
    asset: Asset;
    resource?: MarketCheckApi.Resource;
    columns?: MarketCheckApi.Column[];
    preview?: MarketCheckApi.Detail['preview'];
    apiDocument?: MarketCheckApi.ApiDocument;
    canPreview?: boolean;
    canViewApiDocument?: boolean;
    canInvoke?: boolean;
  }
}

export function getDepartmentAssetsPage(params: DepartmentAssetsApi.PageReq) {
  return requestClient.get<PageResult<DepartmentAssetsApi.Asset>>(
    '/asset/market/department-assets/page',
    { params },
  );
}
export function getDepartmentAsset(id: MarketCheckApi.Id) {
  return requestClient.get<DepartmentAssetsApi.Detail>(
    '/asset/market/department-assets/get',
    {
      params: { id },
    },
  );
}
export function getDepartmentAssetCategories() {
  return requestClient.get<DepartmentAssetsApi.Category[]>(
    '/asset/market/department-assets/categories',
  );
}
export function getDepartmentAssetPreview(id: MarketCheckApi.Id, limit = 20) {
  return requestClient.get<MarketCheckApi.Detail['preview']>(
    '/asset/market/department-assets/preview',
    { params: { id, limit } },
  );
}
export function getDepartmentAssetApiDocument(id: MarketCheckApi.Id) {
  return requestClient.get<MarketCheckApi.ApiDocument>(
    '/asset/market/department-assets/api-document',
    { params: { id } },
  );
}
