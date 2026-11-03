import type { App, SetupContext } from 'vue';

import { createApp, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import Overview from './index.vue';

const harness = vi.hoisted(() => ({
  access: {} as { accessCodes: string[]; accessToken: string; refreshToken: string },
  user: {} as { userInfo: { userId: string }; userRoles: string[] },
  load: vi.fn(),
}));

vi.mock('@vben/stores', () => ({
  useAccessStore: () => harness.access,
  useUserStore: () => harness.user,
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({ hasAccessByCodes: (codes: string[]) => codes.some((code) => harness.access.accessCodes.includes(code)) }),
}));
vi.mock('@vben/common-ui', () => ({
  Page: (_: unknown, { slots }: SetupContext) => h('main', slots.default?.()),
}));
vi.mock('antdv-next', () => ({
  Button: (_: unknown, { attrs, slots }: SetupContext) => h('button', attrs, slots.default?.()),
  Alert: (props: { message?: string }, { slots }: SetupContext) => h('div', [props.message, slots.message?.()]),
}));
vi.mock('#/api/supply/apilog', () => ({ getApiLogOverview: () => harness.load() }));
vi.mock('./modules/metric-cards.vue', () => ({
  default: ({ items }: { items: { value: number }[] }) => h('div', `调用数：${items[0]?.value}`),
}));
vi.mock('./modules/rank-chart.vue', () => ({ default: () => null }));
vi.mock('./modules/trend-chart.vue', () => ({ default: () => null }));

let app: App | undefined;
let host: HTMLDivElement;
async function flush() {
  for (let index = 0; index < 5; index++) await nextTick();
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((ok, fail) => { resolve = ok; reject = fail; });
  return { promise, resolve, reject };
}
async function mount() {
  host = document.createElement('div');
  app = createApp(Overview);
  app.mount(host);
  await flush();
}
function click(text: string) {
  [...host.querySelectorAll('button')].find((button) => button.textContent?.includes(text))?.click();
}
beforeEach(() => {
  vi.clearAllMocks();
  harness.access = reactive({ accessCodes: ['supply:api-log:query'], accessToken: 'session-a', refreshToken: '' });
  harness.user = reactive({ userInfo: { userId: '1' }, userRoles: [] });
});
afterEach(() => { app?.unmount(); app = undefined; host?.remove(); });

describe('数据概览加载', () => {
  it('区分加载、失败和零值，并在重试成功后显示刷新时间', async () => {
    const first = deferred<{ todayCount: number }>();
    harness.load.mockReturnValueOnce(first.promise).mockResolvedValueOnce({ todayCount: 0 });
    await mount();
    expect(host.textContent).toContain('数据加载中');
    expect(host.textContent).not.toContain('调用数：0');
    first.reject(new Error('failed'));
    await flush();
    expect(host.textContent).toContain('数据加载失败');
    click('重试');
    await flush();
    expect(host.textContent).toContain('调用数：0');
    expect(host.textContent).toContain('最近成功刷新');
  });

  it('刷新失败保留已成功的数据，身份变化清空旧数据并忽略旧响应', async () => {
    const oldRequest = deferred<{ todayCount: number }>();
    const newRequest = deferred<{ todayCount: number }>();
    harness.load.mockResolvedValueOnce({ todayCount: 3 }).mockRejectedValueOnce(new Error('failed'))
      .mockReturnValueOnce(oldRequest.promise).mockReturnValueOnce(newRequest.promise);
    await mount();
    click('刷新');
    await flush();
    expect(host.textContent).toContain('刷新失败');
    expect(host.textContent).toContain('调用数：3');
    click('重试');
    harness.user.userInfo.userId = '2';
    await flush();
    expect(host.textContent).not.toContain('调用数：3');
    oldRequest.resolve({ todayCount: 8 });
    await flush();
    expect(host.textContent).not.toContain('调用数：8');
    newRequest.resolve({ todayCount: 9 });
    await flush();
    expect(host.textContent).toContain('调用数：9');
  });

  it('权限被撤销后清空数据，不再发送请求', async () => {
    harness.load.mockResolvedValueOnce({ todayCount: 3 });
    await mount();
    harness.access.accessCodes = [];
    await flush();
    expect(host.textContent).not.toContain('调用数：3');
    expect(host.textContent).toContain('暂无查看数据概览的权限');
    expect(harness.load).toHaveBeenCalledTimes(1);
  });
});
