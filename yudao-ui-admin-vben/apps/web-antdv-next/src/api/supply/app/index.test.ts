import { beforeEach, describe, expect, it, vi } from 'vitest';

import { requestClient } from '#/api/request';

import * as api from './index';

vi.mock('#/api/request', () => ({
  requestClient: { delete: vi.fn(), get: vi.fn(), post: vi.fn(), put: vi.fn() },
}));

beforeEach(() => vi.clearAllMocks());

describe('supply grant control transport', () => {
  it('closes an independent admin grant with its exact id and revision', async () => {
    const req = {
      id: '9223372036854775807',
      expectedRevision: '9007199254740995',
    };
    vi.mocked(requestClient.put).mockResolvedValue(true);
    expect(await api.revokeAdminGrant(req)).toBe(true);
    expect(requestClient.put).toHaveBeenCalledExactlyOnceWith(
      '/supply/app/api-auth/revoke',
      undefined,
      { params: req },
    );
  });
  it('编辑前按编号读取详情并保留大编号和当前控制版本', async () => {
    const id = '9223372036854775807';
    const grant = {
      id,
      appId: '9007199254740993',
      apiId: 10,
      aggregateRevision: '9007199254740995',
      protocolVersion: 0,
      sourceType: 'ADMIN',
      foreverFlag: 1,
    };
    vi.mocked(requestClient.get).mockResolvedValue(grant);
    expect(await api.getAppApi(id)).toEqual(grant);
    expect(requestClient.get).toHaveBeenCalledWith('/supply/app/api-auth/get', {
      params: { id },
    });
  });

  it('sends the expected aggregate revision verbatim so a stale page cannot win', async () => {
    await api.setGrantControl({
      id: '9223372036854775807',
      expectedRevision: '9007199254740995',
      disabled: true,
    });
    expect(requestClient.put).toHaveBeenCalledWith(
      '/supply/app/api-auth/control',
      undefined,
      {
        params: {
          disabled: true,
          expectedRevision: '9007199254740995',
          id: '9223372036854775807',
        },
      },
    );
  });

  it('re-enables through the same control endpoint rather than a separate optimistic path', async () => {
    await api.setGrantControl({
      id: 7,
      expectedRevision: 4,
      disabled: false,
    });
    expect(requestClient.put).toHaveBeenCalledWith(
      '/supply/app/api-auth/control',
      undefined,
      { params: { disabled: false, expectedRevision: 4, id: 7 } },
    );
  });
});
