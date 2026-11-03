import { beforeEach, describe, expect, it, vi } from 'vitest';

import { requestClient } from '#/api/request';

import * as api from './index';

vi.mock('#/api/request', () => ({
  requestClient: { delete: vi.fn(), get: vi.fn(), post: vi.fn(), put: vi.fn() },
}));

beforeEach(() => vi.clearAllMocks());

describe('access command transport', () => {
  it('reads authenticated group status and posts recovery without client termination claims', async () => {
    const recordId = '9007199254740995';
    const status = { recordId, rowVersion: '9007199254740997', applicable: true, ready: true, released: false, message: '可提交恢复' };
    vi.mocked(requestClient.get).mockResolvedValue(status);
    expect(await api.getAccessGroupRecovery(recordId)).toEqual(status);
    expect(requestClient.get).toHaveBeenCalledWith('/dataforge/access/group-recovery/get', { params: { recordId } });
    const command = { recordId, expectedVersion: status.rowVersion, requestId: 'recover-1', reason: '恢复已冻结组', evidenceRef: 'audit/synthetic-group' };
    await api.recoverAccessGroups(command);
    expect(requestClient.post).toHaveBeenCalledWith('/dataforge/access/group-recover-command', command);
    expect(command).not.toHaveProperty('confirmedStopped');
  });
  it('posts a stable run identity and returns the unwrapped receipt', async () => {
    const command = { taskId: '9007199254740993', requestId: 'run-1' };
    const receipt = { recordId: '9007199254740995', replayed: true, runStatus: 1, stopRequested: false };
    vi.mocked(requestClient.post).mockResolvedValue(receipt);
    expect(await api.runAccess(command)).toEqual(receipt);
    expect(requestClient.post).toHaveBeenCalledWith('/dataforge/access/run-command', command);
  });

  it('stops the exact record with the same request identity and optional reason', async () => {
    const command = { recordId: '9007199254740995', requestId: 'stop-1', reason: '人工取消' };
    await api.stopAccess(command);
    expect(requestClient.post).toHaveBeenCalledWith('/dataforge/access/stop-command', command);
  });

  it('submits evidence, confirmations and a lossless expected version', async () => {
    const command = { recordId: '9007199254740995', requestId: 'resolve-1', expectedVersion: '9007199254740997', confirmedStopped: true as const, outputReviewed: true as const, outcome: 'FAILED' as const, reason: '已核查部分输出', evidenceRef: 'audit/record-1' };
    expect(api).toHaveProperty('resolveAccess');
    await api.resolveAccess(command);
    expect(requestClient.post).toHaveBeenCalledWith('/dataforge/access/resolve-command', command);
  });
});
