import type { DataforgeEtlApi } from './index';

import { requestClient } from '#/api/request';

export interface EtlFileOutput {
  id: DataforgeEtlApi.Id;
  recordId: DataforgeEtlApi.Id;
  nodeNo: string;
  storageKey: string;
  fileName: string;
  state: string;
  recoveryStatus: string;
  attempts: number;
  revision: DataforgeEtlApi.Id;
  payloadBytes?: DataforgeEtlApi.Id;
  payloadHash?: string;
  lastErrorCode?: string;
  nextAttemptAt?: number;
  createTime?: number;
  retentionStatus?: 'KEEP' | 'PURGED' | 'REQUESTED' | null;
  retireAfter?: null | number;
  purgePhase?: 'COMPLETE' | 'INTENT' | 'NONE' | 'TARGET_REMOVED' | null;
  retirementRequestedBy?: DataforgeEtlApi.Id | null;
  retirementReason?: null | string;
}

export interface EtlFileRetirementRequest {
  confirmDeletion: true;
  expectedRevision: DataforgeEtlApi.Id;
  id: DataforgeEtlApi.Id;
  reason: string;
  retireAfter: number;
}

export function getEtlFileOutputs(recordId: DataforgeEtlApi.Id) {
  return requestClient.get<EtlFileOutput[]>('/dataforge/etl-file-output/list', {
    params: { recordId },
  });
}

export function downloadEtlFileOutput(id: DataforgeEtlApi.Id) {
  return requestClient.download<Blob>('/dataforge/etl-file-output/download', {
    params: { id },
    // The component presents a local fallback message while the request layer suppresses a duplicate toast.
    silentError: true,
  });
}

export function retryEtlFileOutput(data: {
  expectedRevision: DataforgeEtlApi.Id;
  id: DataforgeEtlApi.Id;
  reason: string;
}) {
  return requestClient.post<boolean>('/dataforge/etl-file-output/retry', data);
}

export function retireEtlFileOutput(data: EtlFileRetirementRequest) {
  return requestClient.post<EtlFileOutput>(
    '/dataforge/etl-file-output/retire',
    data,
  );
}
