import { beforeEach, describe, expect, it, vi } from 'vitest';

import { requestClient } from '#/api/request';
import { publishDataApi, updateDataApiStatus } from '#/api/supply/dataapi';

import { getWriteOperations, resolveWriteOperation } from './index';

vi.mock('#/api/request', () => ({ requestClient: { get: vi.fn(), post: vi.fn(), put: vi.fn() } }));

describe('写操作与发布的无损契约', () => {
  beforeEach(() => vi.clearAllMocks());
  it('发布和下线都携带看到的控制版本及无损ID', async () => {
    const id = '9223372036854775806';
    await publishDataApi(id, 7);
    expect(requestClient.post).toHaveBeenCalledWith('/supply/data-api/publish', { id, expectedRowVersion: 7 });
    await updateDataApiStatus(id, 0, 7);
    expect(requestClient.put).toHaveBeenCalledWith('/supply/data-api/update-status', { id, status: 0, expectedRowVersion: 7 });
  });
  it('游标和编号不转number，忽略调用方注入的tenantId', async () => {
    const input = { apiId: '9223372036854775806', afterId: '9007199254740993', operationKey: 'permit-case-001', tenantId: 999 };
    await getWriteOperations(input);
    expect(requestClient.get).toHaveBeenCalledWith('/supply/data-api/operation/list', {
      params: { apiId: input.apiId, afterId: input.afterId, operationKey: input.operationKey, state: undefined },
    });
  });
  it('核查只提交结论和证据，不接受客户端操作者或重试指令', async () => {
    const request = { id: '9007199254740993', decision: 'CONFIRMED_NOT_APPLIED' as const,
      reason: '核对办件登记流水', evidence: 'case-check-001', actor: 999, retry: true };
    await resolveWriteOperation(request);
    expect(requestClient.post).toHaveBeenCalledWith('/supply/data-api/operation/resolve', {
      id: request.id, decision: request.decision, reason: request.reason, evidence: request.evidence,
    });
  });
});
