import type { App, ComponentPublicInstance } from 'vue';

import type { DataAssetApi } from '#/api/asset/dataasset';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import Preview from './preview-drawer.vue';

const mock = vi.hoisted(() => ({
  original: vi.fn(),
  loader: vi.fn(),
  state: vi.fn(),
  data: { requestId: '9223372036854775807', title: '已授权资产' } as {
    assetId?: string;
    requestId?: string;
    title?: string;
  },
  options: undefined as undefined | { onOpenChange: (open: boolean) => void },
  access: {
    accessToken: 'session',
    refreshToken: '',
    accessCodes: ['asset:market:my-assets:query'],
    loginExpired: false,
  },
  user: { userInfo: { userId: '1' }, userRoles: [] as string[] },
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => mock.access,
  useUserStore: () => mock.user,
}));
vi.mock('#/api/asset/market', () => ({ previewMarketAsset: mock.original }));
vi.mock('@vben/common-ui', () => ({
  useVbenModal: (options: { onOpenChange: (open: boolean) => void }) => {
    mock.options = options;
    return [
      defineComponent({
        setup:
          (_, { slots }) =>
          () =>
            h('div', slots.default?.()),
      }),
      { getData: () => mock.data, setState: mock.state },
    ];
  },
}));
vi.mock('antdv-next', () => {
  const box = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.()),
  });
  return Object.fromEntries(
    ['Alert', 'Button', 'InputNumber', 'Table', 'Tooltip'].map((name) => [
      name,
      box,
    ]),
  );
});
interface State {
  preview: DataAssetApi.Preview;
  loading: boolean;
  consumptionKey: string;
  load: () => Promise<void>;
}
let app: App;
let host: HTMLDivElement;
function mount(loader = true) {
  app = createApp(Preview, loader ? { previewLoader: mock.loader } : {});
  return (
    (app.mount(host) as ComponentPublicInstance).$ as unknown as {
      setupState: State;
    }
  ).setupState;
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((yes) => {
    resolve = yes;
  });
  return { promise, resolve };
}
async function flush() {
  for (let index = 0; index < 10; index++) await Promise.resolve();
  await nextTick();
}
beforeEach(() => {
  vi.resetAllMocks();
  mock.options = undefined;
  mock.data = { requestId: '9223372036854775807', title: '已授权资产' };
  mock.access = reactive({
    accessToken: 'session',
    refreshToken: '',
    accessCodes: ['asset:market:my-assets:query'],
    loginExpired: false,
  });
  mock.user = reactive({
    userInfo: { userId: '1' },
    userRoles: [] as string[],
  });
  host = document.createElement('div');
  document.body.append(host);
});
afterEach(() => {
  app?.unmount();
  host.remove();
});
describe('共享消费预览', () => {
  it('新页注入申请消费入口，旧页保留默认资产入口，消费键不丢失', async () => {
    mock.loader.mockResolvedValue({ rows: [{ column: 'approved' }] });
    const state = mount();
    mock.options?.onOpenChange(true);
    await flush();
    expect(mock.loader).toHaveBeenCalledWith(
      '9223372036854775807',
      20,
      state.consumptionKey,
    );
    expect(state.consumptionKey).toBeTruthy();
    expect(mock.original).not.toHaveBeenCalled();
    app.unmount();
    mock.data = { assetId: '9007199254740993' };
    mock.original.mockResolvedValue({ rows: [] });
    mount(false);
    mock.options?.onOpenChange(true);
    await flush();
    expect(mock.original).toHaveBeenCalledWith(
      '9007199254740993',
      20,
      expect.any(String),
    );
  });
  it('关闭或身份变化后清除预览，晚到响应不能恢复敏感数据', async () => {
    const pending = deferred<DataAssetApi.Preview>();
    mock.loader.mockReturnValueOnce(pending.promise);
    const state = mount();
    mock.options?.onOpenChange(true);
    mock.access.accessToken = 'new-session';
    await flush();
    expect(mock.state).toHaveBeenCalledWith({ isOpen: false });
    expect(state.preview).toEqual({});
    expect(state.consumptionKey).toBe('');
    pending.resolve({ rows: [{ secret: 'old' }] });
    await flush();
    expect(state.preview).toEqual({});
    expect(state.loading).toBe(false);
  });
});
