import { requestClient } from '#/api/request';

export type OperationState =
  | 'CONFIRMED_NOT_APPLIED'
  | 'CONFIRMED_SUCCEEDED'
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'UNKNOWN';
export interface WriteOperation {
  id: number | string;
  apiId: number | string;
  appId: number | string;
  releaseId: number | string;
  operationKey: string;
  state: OperationState;
  traceId?: string;
  createTime?: number;
  updateTime?: number;
  resolvedBy?: number | string;
  resolution?: string;
  evidence?: string;
  resolvedAt?: number;
}

export function getWriteOperations(params: {
  afterId?: number | string;
  apiId?: number | string;
  operationKey?: string;
  state?: OperationState;
}) {
  return requestClient.get<WriteOperation[]>(
    '/supply/data-api/operation/list',
    {
      params: {
        afterId: params.afterId,
        apiId: params.apiId,
        operationKey: params.operationKey,
        state: params.state,
      },
    },
  );
}

export function resolveWriteOperation(data: {
  decision: 'CONFIRMED_NOT_APPLIED' | 'CONFIRMED_SUCCEEDED';
  evidence: string;
  id: number | string;
  reason: string;
}) {
  return requestClient.post<WriteOperation>(
    '/supply/data-api/operation/resolve',
    {
      id: data.id,
      decision: data.decision,
      reason: data.reason,
      evidence: data.evidence,
    },
  );
}
