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
    /** 场景默认的豁免口径 */
    defaultWhitelistScene?: string;
    enabled: boolean;
    remark?: string;
    revision: number;
    updateTime?: number | string;
  }

  /** 更新场景开关；关闭时 remark 必填 */
  export interface MaskSceneUpdateReq {
    sceneCode: string;
    enabled: boolean;
    /** 白名单豁免口径：QUERY/SHARE/OPEN/EXPORT；不填取场景默认值 */
    whitelistScene?: string;
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

  /** 字段加密密钥引用（不含任何密钥材料） */
  export interface KeyRef {
    id: number | string;
    keyCode: string;
    keyName: string;
    provider: string;
    keyType: string;
    keyVersion?: string;
    status: number;
    remark?: string;
    createTime?: number | string;
  }

  export interface KeyRefCreateReq {
    keyCode: string;
    keyName: string;
    remark?: string;
  }

  export interface KeyCenterStatus {
    available: boolean;
    activeVersion?: string;
    inlineSecretRuleCount: number;
  }

  /** 异常访问告警（只含统计值，不含访问到的数据） */
  export interface AccessAlert {
    id: number | string;
    ruleCode: string;
    userId: number | string;
    userName?: string;
    windowStart: number | string;
    windowEnd: number | string;
    metricValue: number;
    threshold: number;
    status: 'CONFIRMED' | 'IGNORED' | 'OPEN';
    handlerId?: number | string;
    handleTime?: number | string;
    handleRemark?: string;
    revision: number;
    createTime?: number | string;
  }

  export interface AccessAlertHandleReq {
    id: number | string;
    status: 'CONFIRMED' | 'IGNORED';
    remark: string;
    expectedRevision: number;
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

/** 密钥引用列表 */
export function getKeyRefList() {
  return requestClient.get<SafetySecurityApi.KeyRef[]>('/governance/key-ref/list');
}

/** 启用状态的密钥引用（供加密规则选择） */
export async function getEnabledKeyRefList() {
  return (await getKeyRefList()).filter((item) => item.status === 0);
}

/** 密钥中心状态 */
export function getKeyCenterStatus() {
  return requestClient.get<SafetySecurityApi.KeyCenterStatus>(
    '/governance/key-ref/status',
  );
}

/** 新建密钥引用（派生盐由服务端生成） */
export function createKeyRef(data: SafetySecurityApi.KeyRefCreateReq) {
  return requestClient.post<number | string>('/governance/key-ref/create', data);
}

/** 启停密钥引用 */
export function updateKeyRefStatus(data: { id: number | string; status: number }) {
  return requestClient.put<boolean>('/governance/key-ref/update-status', data);
}

/** 异常访问告警分页 */
export function getAccessAlertPage(params: PageParam) {
  return requestClient.get<PageResult<SafetySecurityApi.AccessAlert>>(
    '/governance/access-alert/page',
    { params },
  );
}

/** 处理异常访问告警 */
export function handleAccessAlert(data: SafetySecurityApi.AccessAlertHandleReq) {
  return requestClient.put<boolean>('/governance/access-alert/handle', data);
}
