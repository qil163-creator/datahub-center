import type { DataforgeEtlApi } from './index';

import { requestClient } from '#/api/request';

export interface EtlPreviewSessionKey {
  instanceId: string;
  requestId: string;
}
export interface EtlPreviewCapabilities {
  instanceId: string;
  mode: 'INSTANCE_LOCAL';
  issuedAt: number;
  retentionSeconds: number;
  executionSeconds: number;
  concurrency: number;
}
export interface EtlPreviewSessionRequest
  extends EtlPreviewSessionKey, DataforgeEtlApi.PreviewRequest {
  issuedAt: number;
}
export interface EtlPreviewSession extends EtlPreviewSessionKey {
  mode: 'INSTANCE_LOCAL';
  status:
    | 'CANCEL_REQUESTED'
    | 'CANCELLED'
    | 'EXPIRED'
    | 'FAILED'
    | 'RESULT_RESTRICTED'
    | 'RUNNING'
    | 'SUCCEEDED'
    | 'TERMINATION_UNCONFIRMED';
  cleanupStatus: 'CONFIRMED' | 'RUNNING' | 'UNCONFIRMED';
  workerExited: boolean;
  expiresAt: number;
  message?: null | string;
  result?: DataforgeEtlApi.PreviewResult | null;
}
const base = '/dataforge/etl-pipeline/preview-session';
export function getEtlPreviewCapabilities() {
  return requestClient.get<EtlPreviewCapabilities>(`${base}/capabilities`);
}
export function createEtlPreviewSession(data: EtlPreviewSessionRequest) {
  return requestClient.post<EtlPreviewSession>(base, data);
}
export function getEtlPreviewSession(params: EtlPreviewSessionKey) {
  return requestClient.get<EtlPreviewSession>(base, { params });
}
export function cancelEtlPreviewSession(data: EtlPreviewSessionKey) {
  return requestClient.post<EtlPreviewSession>(`${base}/cancel`, data);
}
