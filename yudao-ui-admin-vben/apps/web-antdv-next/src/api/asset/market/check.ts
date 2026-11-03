import type { PageParam, PageResult } from '@vben/request';

import type { DataAssetApi } from '#/api/asset/dataasset';
import type { MarketApi } from '#/api/asset/market';

import { requestClient } from '#/api/request';

export namespace MarketCheckApi {
  export type Id = number | string;
  export type Origin = 'NATIVE' | 'QDATA';
  export type Time = null | number | string;
  export interface Ticket {
    id: string;
    origin: Origin;
    displayId: string;
    sourceRecordId?: string;
    martName: string;
    applicantKey: string;
    applicantName: string;
    applicantDeptName?: string;
    applyType: number;
    startTime?: Time;
    endTime?: Time;
    applyTime?: Time;
    applyReason?: string;
    approvalReason?: string;
    status: string;
    statusLabel?: string;
    statusTone?: 'default' | 'error' | 'success' | 'warning';
    sourceStatus?: string;
    sourceStatusLabel?: string;
    revision: number;
    canApprove: boolean;
    canReject: boolean;
    canView: boolean;
    nativeApply?: MarketApi.Apply;
    detailSnapshot?: unknown;
  }
  export interface PageReq extends PageParam {
    martName?: string;
    status?: string;
    applicantKey?: string;
    sortBy?: 'applyTime' | 'displayId';
    sortOrder?: 'asc' | 'desc';
  }
  export interface Applicant {
    key: string;
    name: string;
  }
  export interface CheckReq {
    id: string;
    origin: Origin;
    status: '3' | '4';
    approvalReason: string;
    expectedRevision: number;
  }
  export interface Resource {
    sourceRecordId?: Id;
    assetName?: string;
    marketName?: string;
    marketCategoryName?: string;
    sourceDeptName?: string;
    ownerDeptName?: string;
    assetType?: number;
    frequency?: string;
    deliveryTypes?: number[];
    rowCount?: null | number | string;
    createTime?: Time;
  }
  export type Column = Omit<DataAssetApi.MetadataColumn, 'dataLength'> & {
    codeTableName?: string;
    createTime?: Time;
    creatorName?: string;
    dataLength?: Id;
    safetyLevelName?: string;
  };
  export interface DocumentField {
    name: string;
    required?: boolean;
    type?: null | string;
    description?: null | string;
    example?: unknown;
    defaultValue?: unknown;
    paramValue?: unknown;
  }
  export interface ApiDocument {
    name?: string;
    method?: string;
    path?: string;
    resultType?: string;
    requestParams?: DocumentField[];
    responseFields?: DocumentField[];
    returnData?: unknown;
  }
  export type Preview = Omit<DataAssetApi.Preview, 'total'> & {
    total?: Id;
  };
  export interface Detail {
    ticket: Ticket;
    resource?: Resource;
    columns?: Column[];
    preview?: Preview;
    apiDocument?: ApiDocument;
    canPreview?: boolean;
    canInvoke?: boolean;
  }
}

export function getMarketCheckPage(params: MarketCheckApi.PageReq) {
  return requestClient.get<PageResult<MarketCheckApi.Ticket>>(
    '/asset/market/check/page',
    { params },
  );
}

export function getMarketCheckDetail(
  id: MarketCheckApi.Id,
  origin: MarketCheckApi.Origin,
) {
  return requestClient.get<MarketCheckApi.Detail>('/asset/market/check/get', {
    params: { id, origin },
  });
}

export function getMarketCheckApplicants() {
  return requestClient.get<MarketCheckApi.Applicant[]>(
    '/asset/market/check/applicants',
  );
}

export function checkMarketCheck(data: MarketCheckApi.CheckReq) {
  return requestClient.put<boolean>('/asset/market/check/check', data);
}
