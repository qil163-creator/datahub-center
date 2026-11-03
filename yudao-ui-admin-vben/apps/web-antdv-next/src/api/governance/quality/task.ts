import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace QualityTaskApi {
  export type Id = number | string;
  export interface Object {
    id?: Id;
    clientKey: string;
    objectName: string;
    datasourceConnId: Id;
    datasourceConnName?: string;
    datasourceType?: string;
    datasourceInstance?: string;
    formatName?: string;
    tableName: string;
    tableId?: Id;
    remark?: string;
  }
  export interface Evaluation {
    id?: Id;
    objectKey: string;
    evaluationName: string;
    ruleCode: string;
    strategyKey: string;
    qualityDim: string;
    severity: 'CRITICAL' | 'LOW' | 'MID';
    status: number;
    description?: string;
    errorExample?: string;
    repairAdvice?: string;
    whereClause?: string;
    columnNames: string[];
    config: Record<string, unknown>;
  }
  export interface CatalogRule {
    ruleCode: string;
    strategyKey: string;
    ruleName: string;
    qualityDim: string;
    description: string;
    enabled: boolean;
    sampleSupported: boolean;
  }
  export interface Task {
    id?: Id;
    taskName?: string;
    taskCode?: string;
    taskCategory?: string;
    executeStrategy?: string;
    scheduleType?: string;
    cronExpression?: string;
    timeWindow?: string;
    taskStatus?: number;
    priority?: string;
    retryCount?: number;
    retryInterval?: number;
    delayExecTime?: number;
    workerGroup?: string;
    ruleSetId?: Id;
    ruleSetName?: string;
    orgField?: string;
    groupField?: string;
    ownerUserId?: Id;
    ownerUserName?: string;
    description?: string;
    remark?: string;
    objectCount?: number;
    ruleCount?: number;
    lastRunStatus?: string;
    lastRunTime?: number;
    objectIds?: Id[];
    ruleIds?: Id[];
    objects?: Object[];
    evaluations?: Evaluation[];
    creator?: string;
    createTime?: number;
    updateTime?: number;
  }
  export interface PageReq extends PageParam {
    ruleSetId?: Id;
    scheduleType?: string;
    taskCategory?: string;
    taskName?: string;
    taskStatus?: number;
  }
  export interface SpotCheck {
    columns: string[];
    problemRows: Record<string, null | string>[];
    normalRows: Record<string, null | string>[];
  }
}

const base = '/governance/quality-task';
export function getQualityTaskPage(params: QualityTaskApi.PageReq) {
  return requestClient.get<PageResult<QualityTaskApi.Task>>(`${base}/page`, {
    params,
  });
}
export function getQualityTask(id: QualityTaskApi.Id) {
  return requestClient.get<QualityTaskApi.Task>(`${base}/get`, {
    params: { id },
  });
}
export function getQualityTaskSimpleList() {
  return requestClient.get<QualityTaskApi.Task[]>(`${base}/simple-list`);
}
export function createQualityTask(data: QualityTaskApi.Task) {
  return requestClient.post<QualityTaskApi.Id>(`${base}/create`, data);
}
export function updateQualityTask(data: QualityTaskApi.Task) {
  return requestClient.put<boolean>(`${base}/update`, data);
}
export function updateQualityTaskStatus(
  id: QualityTaskApi.Id,
  taskStatus: number,
) {
  return requestClient.put<boolean>(`${base}/update-status`, null, {
    params: { id, taskStatus },
  });
}
export function deleteQualityTask(id: QualityTaskApi.Id) {
  return requestClient.delete<boolean>(`${base}/delete`, { params: { id } });
}
export function executeQualityTask(id: QualityTaskApi.Id) {
  return requestClient.post<QualityTaskApi.Id>(`${base}/execute`, null, {
    params: { id },
  });
}
export function getQualityTaskRuleCatalog() {
  return requestClient.get<QualityTaskApi.CatalogRule[]>(
    `${base}/rule-catalog`,
  );
}
export function sampleQualityTaskRule(data: {
  evaluation: QualityTaskApi.Evaluation;
  sampleData: string;
}) {
  return requestClient.post<{ message: string; passed: boolean }>(
    `${base}/sample`,
    data,
  );
}
export function spotCheckQualityTaskRule(data: {
  evaluation: QualityTaskApi.Evaluation;
  limit?: number;
  object: QualityTaskApi.Object;
  objects?: QualityTaskApi.Object[];
}) {
  return requestClient.post<QualityTaskApi.SpotCheck>(
    `${base}/spot-check`,
    data,
  );
}
export function suggestQualityTaskRules(objects: QualityTaskApi.Object[]) {
  return requestClient.post<QualityTaskApi.Evaluation[]>(
    `${base}/suggest-rules`,
    { objects },
  );
}
