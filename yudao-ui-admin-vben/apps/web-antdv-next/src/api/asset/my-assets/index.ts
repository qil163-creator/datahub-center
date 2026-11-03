import type { PageParam, PageResult } from '@vben/request';

import type { DataAssetApi } from '#/api/asset/dataasset';
import type { MarketApi } from '#/api/asset/market';

import { consumptionHeaders } from '#/api/asset/consumption';
import { requestClient } from '#/api/request';

export namespace MyAssetsApi {
  export type Id = number | string;
  export type Time = null | number | string;
  export type AccessStatus =
    | 'AVAILABLE'
    | 'DELIVERY_PENDING'
    | 'DELIVERY_UNKNOWN'
    | 'EXPIRED'
    | 'NOT_STARTED'
    | 'PAUSED'
    | 'REVOKED'
    | 'UNAVAILABLE';

  export interface PageReq extends PageParam {
    keyword?: string;
    deliveryType?: number;
    marketCategoryId?: Id;
  }

  export interface ApplicationPageReq extends PageParam {
    keyword?: string;
    status?: number;
  }

  export interface Application {
    id: Id;
    applyNo?: string;
    title?: string;
    assetId?: Id;
    assetName?: string;
    assetType?: number;
    status?: number;
    applyStartTime?: Time;
    applyEndTime?: Time;
    checkNote?: string;
    checkTime?: Time;
    createTime?: Time;
    purpose?: string;
  }

  export type ApplicationDetail = Omit<MarketApi.Apply, 'checkTime' | 'createTime' | 'processes'> & {
    checkTime?: Time;
    createTime?: Time;
    processes?: (Omit<MarketApi.CheckProcess, 'createTime'> & { createTime?: Time })[];
  };

  export interface Asset {
    applyId: Id;
    applyNo?: string;
    assetId: Id;
    assetCode?: string;
    assetName?: string;
    marketName?: string;
    marketCategoryId?: Id;
    marketCategoryName?: string;
    deliveryType?: number;
    createTime?: Time;
    applyStartTime?: Time;
    applyEndTime?: Time;
    evaluatedAt?: Time;
    accessStatus?: AccessStatus;
    deliveryResolution?: string;
    updateCycle?: string;
    effective?: boolean;
    usable?: boolean;
    canPreview?: boolean;
    canViewApiDocument?: boolean;
    canDownload?: boolean;
    disabledReason?: string;
  }

  export interface Process {
    id?: Id;
    action?: string;
    fromStatus?: number;
    toStatus?: number;
    operatorName?: string;
    operatorUserId?: Id;
    note?: string;
    createTime?: Time;
  }

  export interface Detail extends Asset {
    description?: string;
    assetVersion?: string;
    purpose?: string;
    dutyMatter?: string;
    legalBasis?: string;
    approvedColumns?: string[];
    processes?: Process[];
  }

  export interface ApiDocument {
    applyId: Id;
    apiId: Id;
    name?: string;
    path?: string;
    method?: string;
    version?: string;
    requestParams?: {
      description?: string;
      name: string;
      required: boolean;
      type?: null | string;
    }[];
    responseFields?: {
      description?: string;
      name: string;
      type?: null | string;
    }[];
  }
}

/** 申请人范围由服务端从登录身份确定。 */
export function getMyApplicationsPage(params: MyAssetsApi.ApplicationPageReq) {
  return requestClient.get<PageResult<MyAssetsApi.Application>>(
    '/asset/market/my-assets/applications/page',
    { params: { pageNo: params.pageNo, pageSize: params.pageSize, keyword: params.keyword, status: params.status } },
  );
}

export function getMyApplication(id: MyAssetsApi.Id) {
  return requestClient.get<MyAssetsApi.ApplicationDetail>(
    '/asset/market/my-assets/applications/get',
    { params: { id } },
  );
}

/** 仅发送筛选与分页条件，授权人由服务端登录上下文确定。 */
export function getMyAssetsPage(params: MyAssetsApi.PageReq) {
  return requestClient.get<PageResult<MyAssetsApi.Asset>>(
    '/asset/market/my-assets/page',
    {
      params: {
        pageNo: params.pageNo,
        pageSize: params.pageSize,
        keyword: params.keyword,
        deliveryType: params.deliveryType,
        marketCategoryId: params.marketCategoryId,
      },
    },
  );
}

export function getMyAsset(id: MyAssetsApi.Id) {
  return requestClient.get<MyAssetsApi.Detail>('/asset/market/my-assets/get', {
    params: { id },
  });
}

/** 仅呈现当前批准范围内的调用文档，不读取服务管理配置。 */
export function getMyAssetApiDocument(id: MyAssetsApi.Id) {
  return requestClient.get<MyAssetsApi.ApiDocument>(
    '/asset/market/my-assets/api-document',
    { params: { id } },
  );
}

/** 消费入口按批准申请实时核验，保留申请编号与消费请求键。 */
export function previewMyAsset(
  id: MyAssetsApi.Id,
  limit = 20,
  consumptionKey?: string,
): Promise<DataAssetApi.Preview> {
  return requestClient.get<DataAssetApi.Preview>(
    '/asset/market/my-assets/preview',
    { params: { id, limit }, headers: consumptionHeaders(consumptionKey) },
  );
}
