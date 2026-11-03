/* eslint-disable vue/one-component-per-file -- 测试桩集中声明 */
import type { App, ComponentPublicInstance } from 'vue';

import type { AssetRegistrationApi } from '#/api/asset/dataasset/registration';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, expect, it, vi } from 'vitest';

import Picker from './metadata-picker.vue';
const mock = vi.hoisted(() => ({
  access: {
    accessToken: 'session',
    loginExpired: false,
    accessCodes: ['asset:data-asset:create'],
  },
  user: { userInfo: { userId: '1' } },
  api: vi.fn(),
  data: { assetType: 1, excludedIds: [] as string[] },
  options: {} as {
    onConfirm: () => Promise<void>;
    onOpenChange: (value: boolean) => Promise<void>;
  },
  close: vi.fn(),
  warning: vi.fn(),
  confirm: vi.fn(),
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
vi.mock('#/api/asset/dataasset/registration', () => ({
  getAssetRegistrationCandidates: mock.api,
}));
vi.mock('@vben/common-ui', () => ({
  useVbenModal: (options: typeof mock.options) => {
    mock.options = options;
    return [
      defineComponent({
        setup:
          (_, { slots }) =>
          () =>
            h('section', slots.default?.()),
      }),
      { getData: () => mock.data, close: mock.close },
    ];
  },
}));
vi.mock('antdv-next', () => {
  const box = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('section', slots.default?.()),
  });
  return {
    Alert: box,
    ConfigProvider: box,
    theme: { defaultAlgorithm: undefined },
    Button: box,
    Empty: box,
    Input: box,
    message: { warning: mock.warning },
    Spin: box,
    Table: box,
    Tag: box,
    Tree: box,
  };
});
interface State {
  allRows: AssetRegistrationApi.Candidate[];
  selectedRowKeys: string[];
  errorText: string;
  loading: boolean;
  rowSelection: {
    getCheckboxProps(row: AssetRegistrationApi.Candidate): {
      disabled: boolean;
    };
    onChange(keys: string[]): void;
  };
}
let app: App;
let host: HTMLDivElement;
let state: State;
function mount() {
  app = createApp(Picker, { onConfirm: mock.confirm });
  state = Reflect.get(
    (app.mount(host) as ComponentPublicInstance).$,
    'setupState',
  ) as State;
}
async function flush() {
  for (let i = 0; i < 8; i++) await Promise.resolve();
  await nextTick();
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((yes) => {
    resolve = yes;
  });
  return { promise, resolve };
}
beforeEach(() => {
  vi.resetAllMocks();
  mock.access = reactive({
    accessToken: 'session',
    loginExpired: false,
    accessCodes: ['asset:data-asset:create'],
  });
  mock.user = reactive({ userInfo: { userId: '1' } });
  mock.data = { assetType: 1, excludedIds: [] };
  mock.api.mockResolvedValue({ list: [], total: 0 });
  host = document.createElement('div');
  document.body.append(host);
});
afterEach(() => {
  app?.unmount();
  host.remove();
});
it.each([1, 2, 3])(
  'reads the actual provider candidate page for type %s',
  async (type) => {
    mock.data.assetType = type;
    mock.api.mockResolvedValue({
      list: [{ id: '9007199254740993', assetType: type, name: 'provider' }],
      total: 1,
    });
    mount();
    await mock.options.onOpenChange(true);
    expect(mock.api).toHaveBeenCalledWith(
      expect.objectContaining({ assetType: type, pageNo: 1, pageSize: 200 }),
    );
    expect(state.allRows[0]?.id).toBe('9007199254740993');
  },
);
it('blocks registered and already selected objects even when selection callbacks are invoked directly', async () => {
  mock.data.excludedIds = ['9007199254740993'];
  mock.api.mockResolvedValue({
    list: [
      { id: '9007199254740993' },
      { id: '2', registered: true },
      { id: '3' },
    ],
    total: 3,
  });
  mount();
  await mock.options.onOpenChange(true);
  expect(state.rowSelection.getCheckboxProps(state.allRows[0]!)).toEqual({
    disabled: true,
  });
  state.rowSelection.onChange(['9007199254740993', '2', '3']);
  await mock.options.onConfirm();
  expect(mock.confirm).toHaveBeenCalledWith([
    expect.objectContaining({ id: '3' }),
  ]);
});
it('ignores reads resolved after the picker closes', async () => {
  const work = deferred<{ list: { id: string }[]; total: number }>();
  mock.api.mockReturnValue(work.promise);
  mount();
  const pending = mock.options.onOpenChange(true);
  await mock.options.onOpenChange(false);
  work.resolve({ list: [{ id: '9' }], total: 1 });
  await pending;
  expect(state.allRows).toEqual([]);
  expect(state.loading).toBe(false);
});
it('closes and clears selections on session changes', async () => {
  mock.api.mockResolvedValue({ list: [{ id: '1' }], total: 1 });
  mount();
  await mock.options.onOpenChange(true);
  state.selectedRowKeys = ['1'];
  mock.access.accessToken = 'replacement';
  await flush();
  expect(mock.close).toHaveBeenCalled();
  expect(state.allRows).toEqual([]);
  await mock.options.onConfirm();
  expect(mock.confirm).not.toHaveBeenCalled();
});
it('fails closed when the provider returns an incomplete page or an oversized result', async () => {
  mock.api.mockResolvedValue({ list: [], total: 1 });
  mount();
  await mock.options.onOpenChange(true);
  expect(state.errorText).toContain('读取失败');
  expect(state.allRows).toEqual([]);
  mock.api.mockResolvedValue({ list: [{ id: '1' }], total: 5001 });
  await mock.options.onOpenChange(true);
  expect(state.errorText).toContain('缩小');
  expect(state.allRows).toEqual([]);
});
it('does not issue candidate reads without create permission', async () => {
  mock.access.accessCodes = [];
  mount();
  await mock.options.onOpenChange(true);
  expect(mock.api).not.toHaveBeenCalled();
});
