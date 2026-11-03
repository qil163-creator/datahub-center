import { beforeEach, describe, expect, it, vi } from 'vitest';

import { requestClient } from '#/api/request';

import {
  cancelEtlPreviewSession,
  createEtlPreviewSession,
  getEtlPreviewCapabilities,
  getEtlPreviewSession,
} from './preview-session';
vi.mock('#/api/request', () => ({
  requestClient: { get: vi.fn(), post: vi.fn() },
}));
beforeEach(() => vi.clearAllMocks());
describe('etl实例预览会话API', () => {
  it('创建使用服务端实例和受理窗口；查询取消使用同一稳定请求编号', async () => {
    const key = {
      instanceId: 'instance-fixture',
      requestId: 'request-fixture',
    };
    const request = {
      ...key,
      issuedAt: 1_800_000_000_000,
      environmentId: '9007199254740993',
      nodes: [],
      edges: [],
      targetNodeId: 'source',
      limit: 10,
    };
    await getEtlPreviewCapabilities();
    await createEtlPreviewSession(request);
    await getEtlPreviewSession(key);
    await cancelEtlPreviewSession(key);
    expect(requestClient.get).toHaveBeenCalledWith(
      '/dataforge/etl-pipeline/preview-session/capabilities',
    );
    expect(requestClient.post).toHaveBeenCalledWith(
      '/dataforge/etl-pipeline/preview-session',
      request,
    );
    expect(requestClient.get).toHaveBeenCalledWith(
      '/dataforge/etl-pipeline/preview-session',
      { params: key },
    );
    expect(requestClient.post).toHaveBeenCalledWith(
      '/dataforge/etl-pipeline/preview-session/cancel',
      key,
    );
  });
});
