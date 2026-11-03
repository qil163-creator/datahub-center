import type { RequestClient } from '@vben/request';

import { beforeEach, describe, expect, it, vi } from 'vitest';

const state = vi.hoisted(() => ({
  access: {
    accessToken: 'session-token',
    tenantId: 12,
    visitTenantId: 34,
  },
  tenantEnable: false,
}));

vi.mock('@vben/hooks', () => ({
  isTenantEnable: () => state.tenantEnable,
  useAppConfig: () => ({ apiURL: '/admin-api' }),
}));
vi.mock('@vben/preferences', () => ({
  preferences: { app: { enableRefreshToken: false, locale: 'zh-CN' } },
}));
vi.mock('@vben/stores', () => ({ useAccessStore: () => state.access }));
vi.mock('@vben/utils', async (importOriginal) => ({
  ...(await importOriginal<Record<string, unknown>>()),
  createApiEncrypt: () => ({ getEncryptHeader: () => 'x-api-encrypt' }),
}));
vi.mock('antdv-next', () => ({ message: { error: vi.fn() } }));
vi.mock('#/store', () => ({ useAuthStore: () => ({ logout: vi.fn() }) }));
vi.mock('./core', () => ({ refreshTokenApi: vi.fn() }));

beforeEach(() => {
  vi.resetModules();
  state.tenantEnable = false;
});

async function requestHeaders(client: RequestClient) {
  let sentHeaders: Record<string, unknown> = {};
  client.instance.defaults.adapter = async (config) => {
    sentHeaders = config.headers.toJSON();
    return {
      config,
      data: { code: 0, data: true },
      headers: {},
      status: 200,
      statusText: 'OK',
    };
  };
  await client.get('/system/auth/get-permission-info', {
    headers: { 'tenant-id': '56', 'visit-tenant-id': '78' },
  });
  return sentHeaders;
}

describe('租户开关的请求契约', () => {
  it('关闭开关后普通请求忽略历史租户缓存与调用方租户头，保留登录身份', async () => {
    const { requestClient } = await import('./request');
    const headers = await requestHeaders(requestClient);

    expect(headers).not.toHaveProperty('tenant-id');
    expect(headers).not.toHaveProperty('visit-tenant-id');
    expect(headers.Authorization).toBe('Bearer session-token');
  }, 20_000);

  it('关闭开关后刷新令牌和验证码的基础客户端不再携带租户头', async () => {
    const { baseRequestClient } = await import('./request');
    const headers = await requestHeaders(baseRequestClient);

    expect(headers).not.toHaveProperty('tenant-id');
    expect(headers).not.toHaveProperty('visit-tenant-id');
  });

  it('开启开关时两个客户端仍按当前访问上下文设置租户头', async () => {
    state.tenantEnable = true;
    const { baseRequestClient, requestClient } = await import('./request');

    for (const client of [requestClient, baseRequestClient]) {
      const headers = await requestHeaders(client);
      expect(String(headers['tenant-id'])).toBe('12');
      expect(String(headers['visit-tenant-id'])).toBe('34');
    }
  });
});

describe('集中请求客户端的业务失败契约', () => {
  it('业务拒绝以HTTP 200经过完整拦截器和终端catch后保留顶层code与msg', async () => {
    const { requestClient } = await import('./request');
    const adapter = requestClient.instance.defaults.adapter;
    const rejection = {
      code: 4_000_000,
      data: null,
      msg: '该授权记录不属于当前用户或尚未批准',
    };
    requestClient.instance.defaults.adapter = async (config) => ({
      config,
      data: rejection,
      headers: {},
      status: 200,
      statusText: 'OK',
    });

    try {
      await expect(
        requestClient.get('/asset/market/my-assets/get', {
          params: { id: '9223372036854775807' },
        }),
      ).rejects.toEqual(rejection);
    } finally {
      requestClient.instance.defaults.adapter = adapter;
    }
  });
});
