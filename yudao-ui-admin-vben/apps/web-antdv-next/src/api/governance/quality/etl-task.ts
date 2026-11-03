import type { QualityWorkOrderApi } from './workorder';

import { requestClient } from '#/api/request';

export interface CreateEtlQualityTaskRequest {
  requestId: string;
  taskName: string;
  databaseId: QualityWorkOrderApi.Id;
  schemaName: string;
  tableName: string;
  ruleIds: QualityWorkOrderApi.Id[];
  activate: boolean;
}
export interface CreateEtlQualityTaskReceipt {
  requestId: string;
  taskId: QualityWorkOrderApi.Id;
  checkObjectId: QualityWorkOrderApi.Id;
  taskName: string;
  taskStatus: number;
  replayed: boolean;
}
export function createEtlQualityTask(data: CreateEtlQualityTaskRequest) {
  return requestClient.post<CreateEtlQualityTaskReceipt>(
    '/governance/quality-task/create-for-etl',
    data,
  );
}
export function getEtlQualityTaskCreation(requestId: string) {
  return requestClient.get<CreateEtlQualityTaskReceipt | null>(
    '/governance/quality-task/etl-create-result',
    { params: { requestId } },
  );
}
