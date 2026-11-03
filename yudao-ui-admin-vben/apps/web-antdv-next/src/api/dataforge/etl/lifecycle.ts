import type { DataforgeEtlApi } from './index';

import { requestClient } from '#/api/request';

export interface EtlArchive {
  recordId: DataforgeEtlApi.Id;
  actorId: DataforgeEtlApi.Id;
  reason: string;
  archivedAt: number;
  evidenceAccessUntil: number;
  restoredAt?: number;
  restoredBy?: DataforgeEtlApi.Id;
}
const base = '/dataforge/etl/target-batch';
export const getEtlArchive = (recordId: DataforgeEtlApi.Id) =>
  requestClient.get<EtlArchive | null>(`${base}/archive`, {
    params: { recordId },
  });
export const archiveEtlRecord = (data: {
  reason: string;
  recordId: DataforgeEtlApi.Id;
  retentionDays: number;
}) => requestClient.post<boolean>(`${base}/archive`, data);
export const restoreEtlArchive = (data: {
  reason: string;
  recordId: DataforgeEtlApi.Id;
}) => requestClient.post<boolean>(`${base}/restore-archive`, data);
