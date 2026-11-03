/* eslint-disable vue/one-component-per-file -- 测试桩只模拟页面容器，不引入真实表格及弹窗运行时 */
import type { App } from 'vue';

import { createApp, defineComponent, h, nextTick, reactive, ref } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import CheckPage from './index.vue';

const mock = vi.hoisted(() => ({
  access: { accessToken: 'session', refreshToken: '' },
  getPage: vi.fn(),
  getApplicants: vi.fn(),
  setGridOptions: vi.fn(),
  options: {} as {
    gridEvents: {
      sortChange: (value: {
        field: string;
        order: 'asc' | 'desc' | null;
      }) => void;
    };
  },
}));

vi.mock('#/adapter/form', () => ({
  useVbenForm: () => [
    () => null,
    { reset: vi.fn(), validateAndSubmit: vi.fn() },
  ],
}));
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@vben/access', () => ({
  useAccess: () => ({ hasAccessByCodes: () => true }),
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => mock.access,
  useUserStore: () => ({ userInfo: { userId: '1' } }),
}));
vi.mock('@vueuse/core', () => ({ useLocalStorage: () => ref(false) }));
vi.mock('#/api/asset/market/check', () => ({
  getMarketCheckPage: mock.getPage,
  getMarketCheckApplicants: mock.getApplicants,
  checkMarketCheck: vi.fn(),
}));
vi.mock('#/adapter/vxe-table', () => ({
  useVbenVxeGrid: (options: typeof mock.options) => {
    mock.options = options;
    return [() => null, { setGridOptions: mock.setGridOptions, grid: {} }];
  },
}));
vi.mock('@vben/common-ui', () => ({
  Page: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('main', slots.default?.()),
  }),
  useVbenModal: () => [
    () => null,
    { close: vi.fn(), unlock: vi.fn(), lock: vi.fn() },
  ],
}));
vi.mock('antdv-next', () => {
  const box = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.()),
  });
  return {
    Alert: box,
    Button: box,
    Input: box,
    message: { success: vi.fn() },
    Modal: box,
    Select: box,
    TextArea: box,
    Tooltip: box,
  };
});

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((yes) => {
    resolve = yes;
  });
  return { promise, resolve };
}
let app: App | undefined;
let host: HTMLDivElement;
function mount() {
  host = document.createElement('div');
  app = createApp(CheckPage);
  app.mount(host);
}
beforeEach(() => {
  vi.clearAllMocks();
  mock.access = reactive({ accessToken: 'session', refreshToken: '' });
  mock.getPage.mockResolvedValue({ list: [], total: 0 });
  mock.getApplicants.mockResolvedValue([]);
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host?.remove();
});

describe('服务审批分页与请求隔离', () => {
  it('排序发送受控远程参数并回到第一页', async () => {
    mount();
    await nextTick();
    mock.options.gridEvents.sortChange({ field: 'applyTime', order: 'asc' });
    expect(mock.getPage).toHaveBeenLastCalledWith(
      expect.objectContaining({
        pageNo: 1,
        sortBy: 'applyTime',
        sortOrder: 'asc',
      }),
    );
    mock.options.gridEvents.sortChange({ field: 'unexpected', order: null });
    expect(mock.getPage).toHaveBeenLastCalledWith(
      expect.objectContaining({ sortBy: 'displayId', sortOrder: 'desc' }),
    );
  });

  it('较早的排序响应不会覆盖新的结果与来源身份', async () => {
    const first = deferred<{
      list: { id: string; origin: string }[];
      total: number;
    }>();
    const second = deferred<{
      list: { id: string; origin: string }[];
      total: number;
    }>();
    mock.getPage
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(second.promise);
    mount();
    mock.options.gridEvents.sortChange({ field: 'applyTime', order: 'desc' });
    second.resolve({
      list: [{ id: '9223372036854775807', origin: 'QDATA' }],
      total: 1,
    });
    await nextTick();
    first.resolve({ list: [{ id: '1', origin: 'NATIVE' }], total: 1 });
    await nextTick();
    const rows = mock.setGridOptions.mock.calls.filter(
      ([options]) => options.data?.length,
    );
    expect(rows).toEqual([
      [
        {
          data: [
            {
              id: '9223372036854775807',
              origin: 'QDATA',
              rowKey: 'QDATA:9223372036854775807',
            },
          ],
          pagerConfig: { total: 1 },
        },
      ],
    ]);
  });

  it('会话切换立即清空记录并丢弃旧会话响应', async () => {
    const pending = deferred<{
      list: { id: string; origin: string }[];
      total: number;
    }>();
    mock.getPage.mockReturnValueOnce(pending.promise);
    mount();
    mock.access.accessToken = 'new-session';
    expect(mock.setGridOptions).toHaveBeenCalledWith({
      data: [],
      loading: false,
      pagerConfig: { total: 0 },
    });
    pending.resolve({ list: [{ id: '1', origin: 'QDATA' }], total: 1 });
    await nextTick();
    expect(
      mock.setGridOptions.mock.calls.some(([options]) => options.data?.length),
    ).toBe(false);
  });
});
