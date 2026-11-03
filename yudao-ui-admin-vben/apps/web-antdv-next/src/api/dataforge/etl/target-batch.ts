import type { DataforgeEtlApi } from './index';

import { requestClient } from '#/api/request';

export interface EtlTargetBatch {
  id: DataforgeEtlApi.Id;
  recordId: DataforgeEtlApi.Id;
  nodeNo: string;
  qualityBatchId?: DataforgeEtlApi.Id;
  qualityTaskId?: DataforgeEtlApi.Id;
  qualityInstanceId?: DataforgeEtlApi.Id;
  batchKey: string;
  replayOfBatchId?: DataforgeEtlApi.Id;
  replayOfRecordId?: DataforgeEtlApi.Id;
  workOrderId?: DataforgeEtlApi.Id;
  workOrderSubmissionId?: DataforgeEtlApi.Id;
  replacedByBatchId?: DataforgeEtlApi.Id;
  inputCount: DataforgeEtlApi.Id;
  outputCount: DataforgeEtlApi.Id;
  admittedCount: DataforgeEtlApi.Id;
  rejectedCount: DataforgeEtlApi.Id;
  commitStatus: string;
  qualityStatus: string;
  attemptCount: number;
  nextAttemptAt?: number;
  lastError?: string;
  createTime: number;
}
export function getEtlTargetBatches(recordId: DataforgeEtlApi.Id) {
  return requestClient.get<EtlTargetBatch[]>(
    '/dataforge/etl/target-batch/list',
    { params: { recordId } },
  );
}
export function getEtlTargetRows(
  batchId: DataforgeEtlApi.Id,
  afterRow: DataforgeEtlApi.Id = 0,
) {
  return requestClient.get<{
    hasMore: boolean;
    nextAfterRow: DataforgeEtlApi.Id;
    rows: Record<string, unknown>[];
  }>('/dataforge/etl/target-batch/rows', {
    params: { batchId, afterRow, limit: 100 },
  });
}
export function retryEtlTargetBatch(data: {
  batchId: DataforgeEtlApi.Id;
  reason: string;
}) {
  return requestClient.post<boolean>('/dataforge/etl/target-batch/retry', data);
}
export interface EtlReplayStatus {
  recordId: DataforgeEtlApi.Id;
  sourceRecordId: DataforgeEtlApi.Id;
  workOrderId: DataforgeEtlApi.Id;
  submissionId: DataforgeEtlApi.Id;
  snapshotMode: 'LATEST_PUBLISHED' | 'ORIGINAL';
  attachStatus: string;
  attemptCount: number;
  nextAttemptAt?: number;
  lastError?: string;
}
export function getEtlReplayStatus(recordId: DataforgeEtlApi.Id) {
  return requestClient.get<EtlReplayStatus | null>(
    '/dataforge/etl/target-batch/replay-status',
    { params: { recordId } },
  );
}
export function retryEtlReplayEvidence(data: {
  reason: string;
  recordId: DataforgeEtlApi.Id;
}) {
  return requestClient.post<boolean>(
    '/dataforge/etl/target-batch/retry-recheck',
    data,
  );
}
