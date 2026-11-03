/* eslint-disable vue/one-component-per-file -- 测试桩集中复用详情载入边界 */
import type { App } from 'vue';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import DetailPage from './index.vue';
const mock = vi.hoisted(() => ({
  access: {
    accessToken: 'session',
    loginExpired: false,
    refreshToken: undefined as string | undefined,
    accessCodes: ['asset:market:query'],
  },
  user: { userInfo: { userId: '1' } as null | { userId: string } },
  route: { params: { id: '9223372036854775807' as unknown } },
  open: vi.fn(),
  close: vi.fn(),
  push: vi.fn(),
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => mock.access,
  useUserStore: () => mock.user,
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      codes.some((code) => mock.access.accessCodes.includes(code)),
  }),
}));
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: mock.push }),
  useRoute: () => mock.route,
}));
vi.mock('../modules/mart-detail.vue', () => ({
  default: defineComponent({
    setup: (_, { expose }) => {
      expose({ open: mock.open, close: mock.close });
      return () => h('div');
    },
  }),
}));
vi.mock('@vben/common-ui', () => ({
  Page: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('main', slots.default?.()),
  }),
}));
vi.mock('antdv-next', () => {
  const box = defineComponent({
    props: { message: { type: String, default: '' } },
    setup:
      (props, { slots }) =>
      () =>
        h('section', [props.message, slots.default?.()]),
  });
  return { Alert: box, Button: box };
});
let app: App | undefined;
let host: HTMLDivElement;
async function flush() {
  for (let index = 0; index < 8; index++) await Promise.resolve();
  await nextTick();
}
function mount() {
  app = createApp(DetailPage);
  app.mount(host);
}
beforeEach(() => {
  vi.resetAllMocks();
  mock.access = reactive({
    accessToken: 'session',
    loginExpired: false,
    refreshToken: undefined as string | undefined,
    accessCodes: ['asset:market:query'],
  });
  mock.user = reactive({ userInfo: { userId: '1' } });
  mock.route = reactive({ params: { id: '9223372036854775807' } });
  host = document.createElement('div');
  document.body.append(host);
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
});
describe('asset market full detail page', () => {
  it('opens shared detail with an exact long route id', async () => {
    mount();
    await flush();
    expect(mock.open).toHaveBeenCalledOnce();
    expect(mock.open).toHaveBeenCalledWith('9223372036854775807');
  });
  it('opens public detail for a signed-in user without query permissions', async () => {
    mock.access.accessCodes = [];
    mount();
    await flush();
    expect(mock.open).toHaveBeenCalledWith('9223372036854775807');
  });
  it.each(['user', 'token', 'expired'] as const)(
    'does not mount detail with invalid login state: %s',
    async (change) => {
      if (change === 'user') mock.user.userInfo = null;
      if (change === 'token') mock.access.accessToken = '';
      if (change === 'expired') mock.access.loginExpired = true;
      mount();
      await flush();
      expect(mock.open).not.toHaveBeenCalled();
      expect(host.textContent).toContain('登录身份已失效');
    },
  );
  it('rejects ambiguous array and unsafe numeric route ids', async () => {
    mock.route.params.id = ['1', '2'];
    mount();
    await flush();
    expect(mock.open).not.toHaveBeenCalled();
    mock.route.params.id = Number.MAX_SAFE_INTEGER + 1;
    await flush();
    expect(mock.open).not.toHaveBeenCalled();
  });
  it('closes previous detail and follows a changed route id', async () => {
    mount();
    await flush();
    mock.route.params.id = '9007199254740993';
    await flush();
    expect(mock.close).toHaveBeenCalled();
    expect(mock.open).toHaveBeenLastCalledWith('9007199254740993');
  });
  it('reloads shared authorization after identity changes and hides it after logout', async () => {
    mount();
    await flush();
    mock.access.refreshToken = 'new';
    await flush();
    expect(mock.open).toHaveBeenCalledTimes(2);
    mock.user.userInfo = null;
    await flush();
    expect(mock.open).toHaveBeenCalledTimes(2);
    expect(mock.close).toHaveBeenCalled();
  });
});
