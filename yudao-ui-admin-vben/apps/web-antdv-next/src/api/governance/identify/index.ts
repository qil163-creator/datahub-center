import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace SafetyIdentifyApi {
  /** 敏感数据识别规则 */
  export interface IdentifyRule {
    id?: number | string;
    ruleName?: string;
    matchType?: string; // COLUMN_NAME / COLUMN_COMMENT / DATA_REGEX
    pattern?: string;
    categoryId?: number | string;
    categoryName?: string;
    levelId?: number | string;
    levelName?: string;
    minHitRatio?: number;
    sampleRows?: number;
    builtinFlag?: number;
    status?: number;
    sort?: number;
    description?: string;
    createTime?: number | string;
  }

  /** 扫描执行记录 */
  export interface ScanRun {
    id: number;
    databaseId?: number;
    tableScope?: string;
    runStatus?: number; // 2 运行中 3 成功 4 失败
    tableCount?: number;
    columnCount?: number;
    hitCount?: number;
    startTime?: string;
    endTime?: string;
    errorInfo?: string;
    createTime?: string;
  }

  /** 敏感字段打标 */
  export interface FieldTag {
    id?: number;
    databaseId?: number;
    tableName?: string;
    columnName?: string;
    categoryId?: number;
    categoryName?: string;
    levelId?: number;
    levelName?: string;
    levelColor?: string;
    identifyRuleId?: number;
    scanRunId?: number;
    source?: string; // AUTO / MANUAL
    tagStatus?: string; // PENDING / CONFIRMED / REJECTED
    hitDesc?: string;
    confirmTime?: string;
    remark?: string;
    createTime?: string;
  }
}

// ==================== 识别规则 ====================

/** 查询识别规则分页 */
export function getIdentifyRulePage(params: PageParam) {
  return requestClient.get<PageResult<SafetyIdentifyApi.IdentifyRule>>(
    '/governance/safety-identify-rule/page',
    { params },
  );
}

/** 查询识别规则详情 */
export function getIdentifyRule(id: number | string) {
  return requestClient.get<SafetyIdentifyApi.IdentifyRule>(
    `/governance/safety-identify-rule/get?id=${id}`,
  );
}

/** 新增识别规则 */
export function createIdentifyRule(data: SafetyIdentifyApi.IdentifyRule) {
  return requestClient.post('/governance/safety-identify-rule/create', data);
}

/** 修改识别规则 */
export function updateIdentifyRule(data: SafetyIdentifyApi.IdentifyRule) {
  return requestClient.put('/governance/safety-identify-rule/update', data);
}

/** 删除识别规则 */
export function deleteIdentifyRule(id: number | string) {
  return requestClient.delete(
    `/governance/safety-identify-rule/delete?id=${id}`,
  );
}

// ==================== 扫描执行 ====================

/** 触发一次敏感数据扫描（异步） */
export function triggerSafetyScan(data: {
  databaseId: number;
  tableNames?: string[];
}) {
  return requestClient.post<number>('/governance/safety-field-tag/scan', data);
}

/** 查询扫描记录分页 */
export function getScanRunPage(params: PageParam) {
  return requestClient.get<PageResult<SafetyIdentifyApi.ScanRun>>(
    '/governance/safety-field-tag/scan-run/page',
    { params },
  );
}

// ==================== 字段打标 ====================

/** 查询字段打标分页 */
export function getFieldTagPage(params: PageParam) {
  return requestClient.get<PageResult<SafetyIdentifyApi.FieldTag>>(
    '/governance/safety-field-tag/page',
    { params },
  );
}

/** 人工添加打标（直接确认并回写元数据） */
export function createFieldTag(data: SafetyIdentifyApi.FieldTag) {
  return requestClient.post('/governance/safety-field-tag/create', data);
}

/** 批量确认打标 */
export function confirmFieldTags(ids: number[]) {
  return requestClient.put('/governance/safety-field-tag/confirm', ids);
}

/** 批量驳回打标 */
export function rejectFieldTags(ids: number[]) {
  return requestClient.put('/governance/safety-field-tag/reject', ids);
}

/** 删除打标记录 */
export function deleteFieldTag(id: number) {
  return requestClient.delete(`/governance/safety-field-tag/delete?id=${id}`);
}
