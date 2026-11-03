import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace SupplyAppApi {
  export type Id = number | string;

  /** 应用信息 */
  export interface App {
    id?: Id;
    name?: string;
    appType?: string; // Web/App/小程序
    icon?: string;
    appKey?: string; // 应用 AppKey（认证标识）
    secret?: string; // 应用秘钥 AppSecret（签名认证用）
    publicFlag?: number; // 0-非公开 1-公开
    description?: string;
    remark?: string;
    creator?: string;
    createTime?: number | string;
    updater?: string;
    updateTime?: number | string;
    owningDeptId?: Id;
    systemCode?: string;
    ownerUserId?: Id;
    purpose?: string;
    environment?: string;
    appStatus?: number;
    credentialRevision?: number;
    signProtocol?: string;
  }

  /** 应用 API 授权 */
  export interface AppApi {
    id?: Id;
    appId?: Id;
    apiId?: Id;
    apiName?: string;
    apiDescription?: string;
    apiPath?: string;
    apiMethod?: string;
    foreverFlag?: number; // 0-否 1-是
    startDate?: string;
    endDate?: string;
    authStatus?: number; // 实际授权：1-有效 0-不可用/待核查 3-独立 ADMIN 已撤销
    grantStatus?: number;
    validFrom?: number | string;
    validTo?: number | string;
    approvedContractHash?: string;
    apiVersion?: string;
    grantRevision?: number;
    sourceType?: string;
    /** 聚合整体禁用，与逐来源撤销区分 */
    aggregateDisabled?: boolean;
    /** 聚合控制版本；提交控制开关时原样回传，禁止有损数值转换 */
    aggregateRevision?: Id;
    protocolVersion?: number;
    description?: string;
    remark?: string;
    creator?: string;
    createTime?: number | string;
  }

  /** 仅修改普通管理员授权的有效期与描述，身份和控制版本由详情读取。 */
  export interface AppApiUpdateRequest {
    id: Id;
    expectedRevision: Id;
    foreverFlag: number;
    startDate?: string;
    endDate?: string;
    description?: string;
  }

  export interface EtlGrantSnapshot {
    sourceType?: null | string;
    sourceId?: null | string;
    approvedContractHash?: null | string;
    purpose?: null | string;
    apiVersion?: null | string;
    permissionUserId?: Id | null;
    grantRevision?: Id | null;
    aggregateRevision?: Id | null;
    grantStatus?: null | number;
    applicationStatus?: null | number;
    aggregateDisabled?: boolean | null;
    foreverFlag?: null | number;
    validFrom?: null | number;
    validTo?: null | number;
  }

  export interface EtlGrantAudit {
    id: Id;
    grantId: Id;
    appId: Id;
    apiId: Id;
    actorId: Id;
    revision: Id;
    action: string;
    before?: EtlGrantSnapshot | null;
    after?: EtlGrantSnapshot | null;
    reason: string;
    traceId?: string;
    result: string;
    createTime: number;
  }
}

export function getEtlGrantAuditPage(params: {
  appId: SupplyAppApi.Id;
  beforeId?: SupplyAppApi.Id;
  grantId?: SupplyAppApi.Id;
  limit?: number;
}) {
  return requestClient.get<{
    hasMore: boolean;
    list: SupplyAppApi.EtlGrantAudit[];
    nextBeforeId?: SupplyAppApi.Id;
  }>('/supply/app/api-auth/etl-audit-page', { params });
}

/** 查询应用分页 */
export function getSupplyAppPage(params: PageParam) {
  return requestClient.get<PageResult<SupplyAppApi.App>>('/supply/app/page', {
    params,
  });
}

/** 查询应用详情 */
export function getSupplyApp(id: SupplyAppApi.Id) {
  return requestClient.get<SupplyAppApi.App>('/supply/app/get', {
    params: { id },
  });
}

/** 新增应用 */
export function createSupplyApp(data: SupplyAppApi.App) {
  return requestClient.post<SupplyAppApi.App>('/supply/app/create', data);
}

/** 修改应用 */
export function updateSupplyApp(data: SupplyAppApi.App) {
  return requestClient.put('/supply/app/update', data);
}

/** 重置应用秘钥 */
export function resetSupplyAppSecret(id: SupplyAppApi.Id) {
  return requestClient.put<string>('/supply/app/reset-secret', undefined, {
    params: { id },
  });
}

/** 删除应用 */
export function deleteSupplyApp(id: SupplyAppApi.Id) {
  return requestClient.delete('/supply/app/delete', { params: { id } });
}

export function updateSupplyAppStatus(id: SupplyAppApi.Id, appStatus: number) {
  return requestClient.put('/supply/app/update-status', undefined, {
    params: { appStatus, id },
  });
}

/** 查询应用的 API 授权列表 */
export function getAppApiList(appId: SupplyAppApi.Id) {
  return requestClient.get<SupplyAppApi.AppApi[]>('/supply/app/api-auth/list', {
    params: { appId },
  });
}

/** 新增应用 API 授权 */
export function createAppApi(data: SupplyAppApi.AppApi) {
  return requestClient.post<SupplyAppApi.Id>(
    '/supply/app/api-auth/create',
    data,
  );
}

/** 编辑前重新读取授权详情，获取当前控制版本。 */
export function getAppApi(id: SupplyAppApi.Id) {
  return requestClient.get<SupplyAppApi.AppApi>('/supply/app/api-auth/get', {
    params: { id },
  });
}

/** 修改普通管理员授权；ETL、市场来源及永久关闭的授权由后端拒绝。 */
export function updateAppApi(data: SupplyAppApi.AppApiUpdateRequest) {
  return requestClient.put<boolean>('/supply/app/api-auth/update', data);
}

/**
 * 整体启用或禁用授权聚合。
 * expectedRevision 必须是最近一次读取到的聚合版本，避免旧页面覆盖更新的决定；
 * 逐来源的撤销属于来源系统，这里不提供。
 */
export function setGrantControl(req: {
  disabled: boolean;
  expectedRevision: SupplyAppApi.Id;
  id: SupplyAppApi.Id;
}) {
  return requestClient.put('/supply/app/api-auth/control', undefined, {
    params: {
      disabled: req.disabled,
      expectedRevision: req.expectedRevision,
      id: req.id,
    },
  });
}

/** 永久关闭独立管理员授权；恢复访问须显式重新授权。 */
export function revokeAdminGrant(req: {
  expectedRevision: SupplyAppApi.Id;
  id: SupplyAppApi.Id;
}) {
  return requestClient.put<boolean>('/supply/app/api-auth/revoke', undefined, {
    params: { expectedRevision: req.expectedRevision, id: req.id },
  });
}
