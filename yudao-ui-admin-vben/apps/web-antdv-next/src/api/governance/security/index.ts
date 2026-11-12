import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace SafetySecurityApi {
  /** 敏感数据访问审计日志 */
  export interface AccessLog {
    id: number | string;
    userId?: number | string;
    userName?: string;
    appId?: number | string;
    accessType?: string; // API / STATIC_TASK / QUERY / DEV / EXPLORE / ACCESS
    resourceName?: string;
    databaseId?: number | string;
    tableName?: string;
    sensitiveColumns?: string;
    columnCount?: number;
    maskedFlag?: number; // 1 已脱敏 0 明文豁免
    whitelistId?: number | string;
    callerIp?: string;
    accessTime?: number | string;
  }

  /** 动态脱敏场景开关 */
  export interface MaskScene {
    id?: number | string;
    sceneCode: string;
    sceneName?: string;
    whitelistScene?: string;
    enabled: boolean;
    remark?: string;
    revision: number;
    updateTime?: number | string;
  }

  /** 更新场景开关；关闭时 remark 必填 */
  export interface MaskSceneUpdateReq {
    sceneCode: string;
    enabled: boolean;
    remark?: string;
    expectedRevision: number;
  }

  /** 策略试算请求 */
  export interface MaskPreviewReq {
    userId: number | string;
    sceneCode: string;
    metaTableId: number | string;
    sampleValue?: string;
  }

  /** 策略试算：各列处理方式 */
  export interface MaskPreviewColumn {
    columnName: string;
    masked: boolean;
    reason?: string;
    categoryNames?: string[];
    sampleResult?: string;
  }

  export interface MaskPreview {
    sceneEnabled: boolean;
    globallyExempt: boolean;
    policyFingerprint?: string;
    columns: MaskPreviewColumn[];
  }
}

/** 查询访问审计分页 */
export function getAccessLogPage(params: PageParam) {
  return requestClient.get<PageResult<SafetySecurityApi.AccessLog>>(
    '/governance/safety-access-log/page',
    { params },
  );
}

/** 动态脱敏场景列表 */
export function getMaskSceneList() {
  return requestClient.get<SafetySecurityApi.MaskScene[]>(
    '/governance/dynamic-mask/scene/list',
  );
}

/** 启停动态脱敏场景（乐观锁） */
export function updateMaskScene(data: SafetySecurityApi.MaskSceneUpdateReq) {
  return requestClient.put<boolean>('/governance/dynamic-mask/scene/update', data);
}

/** 试算某用户在某场景读取某张表时各列的处理方式 */
export function previewMaskPolicy(data: SafetySecurityApi.MaskPreviewReq) {
  return requestClient.post<SafetySecurityApi.MaskPreview>(
    '/governance/dynamic-mask/preview',
    data,
  );
}
