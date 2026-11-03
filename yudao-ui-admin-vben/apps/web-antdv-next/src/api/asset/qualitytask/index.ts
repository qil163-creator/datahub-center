import type { QualityTaskApi } from '#/api/governance/quality/task';

import { requestClient } from '#/api/request';

export namespace AssetQualityTaskApi {
  export type Evaluation = Omit<QualityTaskApi.Evaluation, 'id' | 'objectKey'>;
  export interface QualityRule {
    id?: number | string;
    taskId?: number | string;
    auditRuleId?: number | string;
    qualityRuleId: number | string;
    ruleName: string;
    dimensionName?: string;
    metaColumnId?: number | string;
    columnName?: string;
    remark?: string;
  }

  export interface QualityTask {
    id?: number | string;
    assetId: number | string;
    qualityTaskId?: number | string;
    taskName: string;
    scheduleType: number;
    cronExpression?: string;
    enabled: number;
    runStatus?: number;
    lastRunTime?: number | string;
    lastFinishTime?: number | string;
    resultAvailable?: boolean;
    resultStatus?: string;
    remoteError?: string;
    pendingSync?: boolean;
    executionUnknown?: boolean;
    totalCount?: number;
    issueCount?: number;
    qualityScore?: number;
    remark?: string;
    createTime?: number | string;
    rules: QualityRule[];
    evaluations?: Evaluation[];
  }
}

export function getAssetQualityTaskList(assetId: number | string) {
  return requestClient.get<AssetQualityTaskApi.QualityTask[]>(
    '/asset/quality-task/list',
    { params: { assetId } },
  );
}

export function createAssetQualityTask(data: AssetQualityTaskApi.QualityTask) {
  return requestClient.post<number | string>(
    '/asset/quality-task/create',
    data,
  );
}

export function updateAssetQualityTask(data: AssetQualityTaskApi.QualityTask) {
  return requestClient.put('/asset/quality-task/update', data);
}

export function deleteAssetQualityTask(id: number | string) {
  return requestClient.delete(`/asset/quality-task/delete?id=${id}`);
}

export function executeAssetQualityTask(id: number | string) {
  return requestClient.post<number | string>(
    `/asset/quality-task/execute?id=${id}`,
  );
}
