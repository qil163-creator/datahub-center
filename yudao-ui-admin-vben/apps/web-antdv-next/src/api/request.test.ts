import type { AxiosAdapter } from '@vben/request';

import { nextTick } from 'vue';

import { message } from 'antdv-next';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { requestClient } from './request';

vi.mock('@vben/hooks', () => ({ isTenantEnable: () => false, useAppConfig: () => ({ apiURL: '' }) }));
vi.mock('@vben/preferences', () => ({ preferences: { app: { locale: 'zh-CN', enableRefreshToken: false, loginExpiredMode: 'modal' } } }));
vi.mock('@vben/stores', () => ({ useAccessStore: () => ({ accessToken: 'token', refreshToken: '', isAccessChecked: true, setAccessToken: vi.fn(), setLoginExpired: vi.fn() }) }));
vi.mock('@vben/utils', async (importOriginal) => ({
  ...await importOriginal<typeof import('@vben/utils')>(),
  createApiEncrypt: () => ({ getEncryptHeader: () => 'x-encrypted' }),
}));
vi.mock('#/store', () => ({ useAuthStore: () => ({ logout: vi.fn() }) }));
vi.mock('./core', () => ({ refreshTokenApi: vi.fn() }));

const serverError = 'Unable to find instance for dataforge-server';
const adapter: AxiosAdapter = async (config) => {
  const unauthorized = config.url === '/unauthorized';
  const text = config.url === '/different' ? '另一个错误' : serverError;
  const data = unauthorized ? { code: 401, msg: '账号未登录' } : { code: 503, message: text };
  const response = { config, status: unauthorized ? 401 : 503, statusText: '', headers: {}, data };
  throw Object.assign(new Error(text), { config, response, data, isAxiosError: true });
};
const previousAdapter = requestClient.instance.defaults.adapter;
async function flush() {
  for (let i = 0; i < 20; i++) { await Promise.resolve(); await nextTick(); }
}
async function closeNotices() {
  message.destroy();
  await flush();
  await expect.poll(() => notices().length, { timeout: 2000 }).toBe(0);
}
function notices() {
  return [...document.querySelectorAll<HTMLElement>('.ant-message-notice')];
}
beforeEach(() => {
  requestClient.instance.defaults.adapter = adapter;
});
afterEach(async () => {
  requestClient.instance.defaults.adapter = previousAdapter;
  await closeNotices();
});

describe('请求错误提示', () => {
  it('并发相同失败只显示一条，不同错误仍分别显示，关闭后可再次出现', async () => {
    const results = await Promise.allSettled(['/page', '/statistics', '/categories'].map((url) => requestClient.get(url)));
    await flush();
    expect(results.every((result) => result.status === 'rejected')).toBe(true);
    expect(notices().filter((notice) => notice.textContent?.includes(serverError))).toHaveLength(1);

    await expect(requestClient.get('/different')).rejects.toBeDefined();
    await flush();
    expect(notices().filter((notice) => notice.textContent?.includes('另一个错误'))).toHaveLength(1);

    message.error(serverError);
    await flush();
    expect(notices().filter((notice) => notice.textContent?.includes(serverError))).toHaveLength(2);

    await closeNotices();
    await expect(requestClient.get('/page')).rejects.toBeDefined();
    await flush();
    expect(notices().filter((notice) => notice.textContent?.includes(serverError))).toHaveLength(1);
  });

  it('静默失败和未登录不会显示或影响后续同文案错误', async () => {
    await expect(requestClient.get('/silent', { silentError: true })).rejects.toBeDefined();
    await expect(requestClient.get('/unauthorized')).rejects.toBeDefined();
    await flush();
    expect(notices()).toHaveLength(0);
    await expect(requestClient.get('/page')).rejects.toBeDefined();
    await flush();
    expect(notices().filter((notice) => notice.textContent?.includes(serverError))).toHaveLength(1);
  });
});
