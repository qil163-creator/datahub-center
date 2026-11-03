/* eslint-disable vue/one-component-per-file -- 测试桩集中模拟弹窗与表单 */
import type { App, ComponentPublicInstance } from 'vue';

import type { MarketApi } from '#/api/asset/market';

import { createApp, defineComponent, h, nextTick, reactive, ref } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import PublishForm from './publish-form.vue';
interface ModalOptions {
  onOpenChange: (open: boolean) => void;
  onConfirm: () => Promise<void>;
}
const mock = vi.hoisted(() => ({
  access: {
    accessToken: 'session',
    loginExpired: false,
    refreshToken: undefined as string | undefined,
    accessCodes: ['asset:data-asset:publish'],
  },
  user: { userInfo: { userId: '1' } as null | { userId: string } },
  listing: vi.fn(),
  categories: vi.fn(),
  publish: vi.fn(),
  validate: vi.fn(),
  lock: vi.fn(),
  unlock: vi.fn(),
  success: vi.fn(),
  message: vi.fn(),
  options: undefined as ModalOptions | undefined,
  data: undefined as unknown,
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
vi.mock('@vben/utils', () => ({
  handleTree: (values: unknown[]) => values,
  formatDateTime: vi.fn(),
}));
vi.mock('#/api/asset/market', () => ({
  getMarketMartListing: mock.listing,
  publishMarketMart: mock.publish,
}));
vi.mock('#/api/asset/marketcategory', () => ({
  getMarketCategorySimpleList: mock.categories,
}));
vi.mock('#/api/asset/dataasset', () => ({
  ASSET_TYPE_OPTIONS: [
    { value: 1, label: '数据库表' },
    { value: 3, label: '文件' },
  ],
}));
vi.mock('@vben/common-ui', () => ({
  useVbenModal: (options: ModalOptions) => {
    mock.options = options;
    const visible = ref(false);
    const api = {
      getData: () => mock.data,
      lock: mock.lock,
      unlock: mock.unlock,
      close: async () => api.setState({ isOpen: false }),
      setState: (value: { isOpen: boolean }) => {
        visible.value = value.isOpen;
        options.onOpenChange(value.isOpen);
        return api;
      },
    };
    return [
      defineComponent({
        setup:
          (_, { slots }) =>
          () =>
            h('aside', slots.default?.()),
      }),
      api,
    ];
  },
}));
vi.mock('antdv-next', () => {
  const box = defineComponent({
    props: {
      message: { type: String, default: '' },
      value: { type: [String, Number], default: undefined },
    },
    setup:
      (props, { slots }) =>
      () =>
        h('section', [
          props.message,
          String(props.value ?? ''),
          slots.default?.(),
        ]),
  });
  const form = defineComponent({
    setup: (_, { slots, expose }) => {
      expose({ validate: mock.validate, clearValidate: vi.fn() });
      return () => h('form', slots.default?.());
    },
  });
  return {
    Alert: box,
    Button: box,
    CheckboxGroup: box,
    ConfigProvider: box,
    Form: form,
    FormItem: box,
    Input: box,
    Select: box,
    TextArea: box,
    TreeSelect: box,
    theme: { defaultAlgorithm: {} },
    message: { success: mock.message },
  };
});
interface State {
  model: MarketApi.MartListing;
  loading: boolean;
  saving: boolean;
  loadError: string;
}
let app: App | undefined;
let host: HTMLDivElement;
let state: State;
const listing = (assetId = '9223372036854775807'): MarketApi.MartListing => ({
  assetId,
  marketCategoryId: '9007199254740993',
  name: '市场展示名',
  description: '市场描述',
  frequency: '每天',
  deliveryTypes: [1, 3],
  expectedRevision: '9007199254740997',
  revision: 0,
});
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
function mount() {
  app = createApp(PublishForm, { onSuccess: mock.success });
  const vm = app.mount(host) as ComponentPublicInstance;
  state = (vm.$ as unknown as { setupState: State }).setupState;
}
function open(id = '9223372036854775807', assetType = 1) {
  mock.data = {
    asset: { id, assetName: '资产本体', assetType, catalogRevision: 12 },
  };
  mock.options!.onOpenChange(true);
}
beforeEach(() => {
  vi.resetAllMocks();
  mock.access = reactive({
    accessToken: 'session',
    loginExpired: false,
    refreshToken: undefined as string | undefined,
    accessCodes: ['asset:data-asset:publish'],
  });
  mock.user = reactive({ userInfo: { userId: '1' } });
  mock.listing.mockResolvedValue(listing());
  mock.categories.mockResolvedValue([
    {
      id: '9007199254740993',
      parentId: 0,
      name: '独立集市类目',
      status: 0,
      sort: 0,
    },
  ]);
  mock.publish.mockResolvedValue(true);
  mock.validate.mockResolvedValue({});
  host = document.createElement('div');
  document.body.append(host);
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
});
describe('asset market publish form', () => {
  it('loads authoritative listing revisions and the separate enabled market categories', async () => {
    mount();
    open();
    await flush();
    expect(mock.listing).toHaveBeenCalledWith('9223372036854775807');
    expect(state.model.expectedRevision).toBe('9007199254740997');
    expect(state.model.revision).toBe(0);
    expect(state.model.marketCategoryId).toBe('9007199254740993');
    expect(mock.publish).not.toHaveBeenCalled();
  });
  it('sends both revisions and exact ids only after validation', async () => {
    mount();
    open();
    await flush();
    await mock.options!.onConfirm();
    expect(mock.publish).toHaveBeenCalledWith({
      assetId: '9223372036854775807',
      marketCategoryId: '9007199254740993',
      name: '市场展示名',
      description: '市场描述',
      frequency: '每天',
      deliveryTypes: [1, 3],
      expectedRevision: '9007199254740997',
      expectedListingRevision: 0,
    });
    expect(mock.success).toHaveBeenCalledOnce();
  });
  it('does not submit invalid form data', async () => {
    mount();
    open();
    await flush();
    mock.validate.mockRejectedValueOnce(new Error('invalid'));
    await mock.options!.onConfirm();
    expect(mock.publish).not.toHaveBeenCalled();
  });
  it('does not load or submit without publishing permission', async () => {
    mock.access.accessCodes = ['asset:data-asset:query'];
    mount();
    open();
    await flush();
    await mock.options!.onConfirm();
    expect(mock.listing).not.toHaveBeenCalled();
    expect(mock.publish).not.toHaveBeenCalled();
    expect(state.loadError).not.toBe('');
  });
  it('rejects an expired session even when cached publish permissions remain', async () => {
    mock.access.loginExpired = true;
    mount();
    open();
    await flush();
    await mock.options!.onConfirm();
    expect(mock.listing).not.toHaveBeenCalled();
    expect(mock.publish).not.toHaveBeenCalled();
  });
  it('clears loaded publication state immediately when the session expires', async () => {
    mount();
    open();
    await flush();
    mock.access.loginExpired = true;
    expect(state.model.assetId).toBe('');
    await mock.options!.onConfirm();
    expect(mock.publish).not.toHaveBeenCalled();
  });
  it('rejects mismatched listing identity and missing listing revision', async () => {
    mock.listing.mockResolvedValueOnce(listing('12'));
    mount();
    open();
    await flush();
    expect(state.loadError).not.toBe('');
    await mock.options!.onConfirm();
    expect(mock.publish).not.toHaveBeenCalled();
    mock.listing.mockResolvedValueOnce({ ...listing(), revision: undefined });
    open();
    await flush();
    expect(state.loadError).not.toBe('');
  });
  it('rejects categories that no longer exist among enabled choices', async () => {
    mount();
    open();
    await flush();
    state.model.marketCategoryId = '19';
    await mock.options!.onConfirm();
    expect(mock.publish).not.toHaveBeenCalled();
  });
  it('deduplicates submission during save and releases its lock after rejection', async () => {
    mount();
    open();
    await flush();
    const pending = deferred<boolean>();
    mock.publish.mockReturnValueOnce(pending.promise);
    const first = mock.options!.onConfirm();
    await flush();
    await mock.options!.onConfirm();
    expect(mock.publish).toHaveBeenCalledTimes(1);
    expect(state.saving).toBe(true);
    pending.resolve(true);
    await first;
    expect(state.saving).toBe(false);
    expect(mock.unlock).toHaveBeenCalled();
  });
  it('retains edits after a failed save and permits a retry', async () => {
    mount();
    open();
    await flush();
    mock.publish.mockRejectedValueOnce(new Error('conflict'));
    await expect(mock.options!.onConfirm()).rejects.toThrow('conflict');
    expect(mock.success).not.toHaveBeenCalled();
    expect(state.saving).toBe(false);
    await mock.options!.onConfirm();
    expect(mock.publish).toHaveBeenCalledTimes(2);
  });
  it('drops old owner prefill responses and clears secret-free form state', async () => {
    const pending = deferred<MarketApi.MartListing>();
    mock.listing.mockReturnValueOnce(pending.promise);
    mount();
    open();
    mock.access.accessToken = 'new';
    pending.resolve(listing());
    await flush();
    expect(state.model.assetId).toBe('');
    expect(state.model.name).toBeUndefined();
    await mock.options!.onConfirm();
    expect(mock.publish).not.toHaveBeenCalled();
  });
  it('ignores a late save success after closing the modal', async () => {
    mount();
    open();
    await flush();
    const pending = deferred<boolean>();
    mock.publish.mockReturnValueOnce(pending.promise);
    const first = mock.options!.onConfirm();
    await flush();
    mock.options!.onOpenChange(false);
    pending.resolve(true);
    await first;
    expect(mock.success).not.toHaveBeenCalled();
    expect(mock.message).not.toHaveBeenCalled();
  });
  it('uses the actual asset type in read-only metadata', async () => {
    mount();
    open('9223372036854775807', 3);
    await flush();
    expect(host.textContent).toContain('文件');
  });
  it('invalidates loading after unmount', async () => {
    const pending = deferred<MarketApi.MartListing>();
    mock.listing.mockReturnValueOnce(pending.promise);
    mount();
    open();
    app!.unmount();
    app = undefined;
    pending.resolve(listing());
    await flush();
    expect(state.model.assetId).toBe('');
    expect(state.loading).toBe(false);
  });
});
