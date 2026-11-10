/* eslint-disable vue/one-component-per-file -- 测试桩在同一文件集中声明 */
import type { App, ComponentPublicInstance } from 'vue';

import type { MarketApi } from '#/api/asset/market';

import {
  createApp,
  defineComponent,
  h,
  KeepAlive,
  nextTick,
  reactive,
  ref,
} from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import ResourceDetail from './resource-detail.vue';

interface ModalOptions {
  onClosed?: () => void;
  onOpenChange?: (open: boolean) => void;
  placement?: string;
}
interface ModalApi {
  close: () => Promise<void>;
  open: () => void;
  setData: (value: unknown) => ModalApi;
  setState: (value: { isOpen?: boolean; title?: string }) => ModalApi;
}
const mock = vi.hoisted(() => ({
  access: {
    accessToken: '1',
    loginExpired: false,
    refreshToken: undefined as string | undefined,
    accessCodes: ['asset:market:query', 'asset:market:apply'],
  },
  user: { userInfo: { userId: '1' } as null | { userId: string } },
  page: vi.fn(),
  facets: vi.fn(),
  detail: vi.fn(),
  check: vi.fn(),
  comments: vi.fn(),
  modals: [] as {
    api: ModalApi;
    data?: unknown;
    kind: 'drawer' | 'modal';
    options: ModalOptions;
    visible: { value: boolean };
  }[],
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
vi.mock('#/api/asset/market', () => ({
  getMarketResourcePage: mock.page,
  getMarketResourceFacets: mock.facets,
  getMarketResourceDetail: mock.detail,
  checkMarketAccess: mock.check,
}));
vi.mock('#/api/asset/dataasset', () => ({
  ASSET_TYPE_OPTIONS: [{ label: '库表', value: 1 }],
}));
vi.mock('@vben/icons', () => ({ IconifyIcon: { render: () => null } }));
vi.mock('#/views/asset/market/modules/apply-form.vue', () => ({
  default: { render: () => null },
}));
vi.mock('#/views/asset/market/modules/preview-drawer.vue', () => ({
  default: { render: () => null },
}));
vi.mock('#/views/asset/market/modules/comment-panel.vue', () => ({
  default: {
    setup: () => {
      mock.comments();
      return () => null;
    },
  },
}));
vi.mock('#/views/modeling/modelcommon/detail-field-form.vue', () => ({
  default: defineComponent({
    props: { fields: { type: Array, default: () => [] } },
    setup: (props) => () => h('section', JSON.stringify(props.fields)),
  }),
}));
vi.mock('@vben/common-ui', () => {
  function createPopup(kind: 'drawer' | 'modal', options: ModalOptions) {
    const visible = ref(false);
    const item: (typeof mock.modals)[number] = {
      api: undefined as unknown as ModalApi,
      kind,
      options,
      visible,
    };
    const api: ModalApi = {
      close: async () => {
        api.setState({ isOpen: false });
      },
      open: () => {
        api.setState({ isOpen: true });
      },
      setData: (value) => {
        item.data = value;
        return api;
      },
      setState: (value) => {
        if (value.isOpen !== undefined && value.isOpen !== visible.value) {
          visible.value = value.isOpen;
          options.onOpenChange?.(value.isOpen);
        }
        return api;
      },
    };
    item.api = api;
    mock.modals.push(item);
    return [
      defineComponent({
        setup:
          (_, { slots }) =>
          () =>
            visible.value ? h('aside', slots.default?.()) : null,
      }),
      api,
    ];
  }
  return {
    Page: defineComponent({
      setup:
        (_, { slots }) =>
        () =>
          h('main', [slots.description?.(), slots.default?.()]),
    }),
    useVbenModal: (options: ModalOptions) => createPopup('modal', options),
    useVbenDrawer: (options: ModalOptions) => createPopup('drawer', options),
  };
});
vi.mock('antdv-next', () => {
  const box = defineComponent({
    props: {
      title: { type: String, default: '' },
      message: { type: String, default: '' },
      description: { type: String, default: '' },
      spinning: Boolean,
      options: { type: Array, default: () => [] },
    },
    setup:
      (props, { slots }) =>
      () =>
        h('section', [
          String(props.message ?? props.description ?? ''),
          slots.title?.(),
          slots.default?.(),
        ]),
  });
  return {
    Alert: box,
    Card: box,
    Empty: box,
    Input: box,
    Pagination: box,
    Rate: box,
    Select: box,
    Spin: box,
    Tag: box,
    Button: defineComponent({
      setup:
        (_, { slots }) =>
        () =>
          h('button', slots.default?.()),
    }),
  };
});

type State = {
  applyAsset: () => void;
  close: () => void;
  current?: MarketApi.Resource;
  detail?: MarketApi.ConsumerDetail;
  detailLoading: boolean;
  open: (assetId: MarketApi.Id) => Promise<void>;
  openPreview: () => void;
};
let app: App | undefined;
let host: HTMLDivElement;
let state: State;
const active = ref(true);
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: Error) => void;
  const promise = new Promise<T>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}
async function flush() {
  for (let index = 0; index < 8; index++) await Promise.resolve();
  await nextTick();
}
const row = (id = '1', name = '资产甲'): MarketApi.Resource => ({
  id,
  assetName: name,
  assetType: 1,
});
function capture(vm: ComponentPublicInstance | null) {
  if (vm) state = (vm.$ as unknown as { setupState: State }).setupState;
}
function mount(keepAlive = false, embedded = false) {
  active.value = true;
  if (keepAlive) {
    app = createApp(
      defineComponent({
        setup: () => () =>
          h(KeepAlive, null, {
            default: () =>
              active.value
                ? h(ResourceDetail, {
                    ref: (vm) => capture(vm as ComponentPublicInstance),
                  })
                : undefined,
          }),
      }),
    );
    app.mount(host);
  } else {
    app = createApp(ResourceDetail, { embedded });
    capture(app.mount(host));
  }
}
beforeEach(() => {
  vi.resetAllMocks();
  mock.modals.length = 0;
  mock.access = reactive({
    accessToken: '1',
    loginExpired: false,
    refreshToken: undefined as string | undefined,
    accessCodes: ['asset:market:query', 'asset:market:apply'],
  });
  mock.user = reactive({ userInfo: { userId: '1' } });
  mock.detail.mockResolvedValue({
    asset: row('1', '详情甲'),
    columns: [{ columnName: 'old_field' }],
  });
  mock.check.mockResolvedValue(true);
  host = document.createElement('div');
  document.body.append(host);
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
});

describe('shared market consumer detail', () => {
  it('opens a right drawer and retains separate application and preview dialogs', async () => {
    mount();
    await state.open('1');
    await flush();
    expect(mock.modals[2]).toMatchObject({
      kind: 'drawer',
      options: { placement: 'right' },
    });
    expect(mock.modals[2]!.visible.value).toBe(true);
    expect(mock.modals[0]!.kind).toBe('modal');
    expect(mock.modals[1]!.kind).toBe('modal');
    expect(host.textContent).toContain('old_field');
  });
  it('shows market display fields while keeping delivery labels separate from access permission', async () => {
    mount(false, true);
    mock.detail.mockResolvedValueOnce({
      asset: {
        ...row('1'),
        marketName: '市场展示名称',
        marketDescription: '市场发布简介',
        marketCategoryName: '独立集市类目',
        frequency: '每周',
        deliveryTypes: [1, 3],
        description: '原资产说明',
      },
    });
    mock.check.mockResolvedValueOnce(false);
    await state.open('1');
    await flush();
    expect(host.textContent).toContain('市场展示名称');
    expect(host.textContent).toContain('市场发布简介');
    expect(host.textContent).toContain('独立集市类目');
    expect(host.textContent).toContain('每周');
    expect(host.textContent).toContain('数据库表 / 文件');
    state.openPreview();
    expect(mock.modals[1]!.visible.value).toBe(false);
  });
  it('reuses fresh detail and independent preview guards in the embedded full page', async () => {
    mount(false, true);
    await state.open('1');
    await flush();
    expect(host.querySelector('.market-detail-page')).not.toBeNull();
    expect(
      host.querySelector('.market-detail-page__title')?.textContent,
    ).toContain('详情甲');
    expect(mock.detail).toHaveBeenCalledWith('1');
    expect(mock.check).toHaveBeenCalledWith('1');
    mock.access.accessCodes = [];
    await flush();
    expect(state.current).toBeUndefined();
    expect(host.textContent).not.toContain('old_field');
  });
  it.each(['resolve', 'reject'] as const)(
    'discards a late detail %s after another selection',
    async (outcome) => {
      mount();
      const old = deferred<MarketApi.ConsumerDetail>();
      const current = deferred<MarketApi.ConsumerDetail>();
      mock.detail
        .mockReturnValueOnce(old.promise)
        .mockReturnValueOnce(current.promise);
      const first = state.open('1');
      const second = state.open('2');
      if (outcome === 'resolve')
        old.resolve({ asset: row(), columns: [{ columnName: 'stale_field' }] });
      else old.reject(new Error('old failure'));
      await first;
      await flush();
      expect(state.detailLoading).toBe(true);
      expect(host.textContent).not.toContain('stale_field');
      expect(host.textContent).not.toContain('详情读取失败');
      current.resolve({ asset: row('2', '当前详情') });
      await second;
      expect(state.current?.id).toBe('2');
      expect(state.detailLoading).toBe(false);
    },
  );
  it.each([
    'close',
    'user',
    'token',
    'refresh',
    'permission',
    'unmount',
    'deactivate',
  ] as const)('discards a pending access check after %s', async (change) => {
    mount(change === 'deactivate');
    const check = deferred<boolean>();
    mock.check.mockReturnValueOnce(check.promise);
    const pending = state.open('1');
    await flush();
    if (change === 'close') state.close();
    if (change === 'user') mock.user.userInfo = { userId: '2' };
    if (change === 'token') mock.access.accessToken = '2';
    if (change === 'refresh') mock.access.refreshToken = '2';
    if (change === 'permission') mock.access.accessCodes = [];
    if (change === 'unmount') {
      app!.unmount();
      app = undefined;
    }
    if (change === 'deactivate') {
      active.value = false;
      await flush();
    }
    check.resolve(true);
    await pending;
    state.openPreview();
    expect(state.current).toBeUndefined();
    expect(mock.modals[1]!.visible.value).toBe(false);
  });
  it('keeps public detail available when protected preview checking fails', async () => {
    mount();
    mock.check.mockRejectedValueOnce(new Error('access unavailable'));
    await state.open('1');
    await flush();
    state.openPreview();
    expect(state.current?.assetName).toBe('详情甲');
    expect(mock.modals[1]!.visible.value).toBe(false);
    expect(host.textContent).toContain('预览授权未确认');
    expect(host.textContent).toContain('old_field');
    expect(host.textContent).not.toContain('详情读取失败');
  });
  it.each([2, 3, undefined])(
    'does not request table preview access for resource type %s',
    async (assetType) => {
      mount();
      mock.detail.mockResolvedValueOnce({ asset: { ...row(), assetType } });
      await state.open('1');
      expect(state.current?.id).toBe('1');
      expect(mock.check).not.toHaveBeenCalled();
      state.openPreview();
      expect(mock.modals[1]!.visible.value).toBe(false);
    },
  );
  it('validates returned identity before checking access', async () => {
    mount();
    mock.detail.mockResolvedValueOnce({ asset: row('2') });
    await state.open('1');
    await flush();
    expect(state.current).toBeUndefined();
    expect(mock.check).not.toHaveBeenCalled();
    expect(host.textContent).not.toContain('身份不一致');
  });
  it.each(['resolve', 'reject'] as const)(
    'discards stale access %s after another detail opens',
    async (outcome) => {
      mount();
      const old = deferred<boolean>();
      mock.check.mockReturnValueOnce(old.promise);
      const first = state.open('1');
      await flush();
      mock.detail.mockResolvedValueOnce({ asset: row('2', '当前详情') });
      mock.check.mockResolvedValueOnce(false);
      await state.open('2');
      if (outcome === 'resolve') old.resolve(true);
      else old.reject(new Error('old access failure'));
      await first;
      await flush();
      state.openPreview();
      expect(state.current?.id).toBe('2');
      expect(mock.modals[1]!.visible.value).toBe(false);
      expect(host.textContent).not.toContain('预览授权未确认');
    },
  );
  it('invalidates a detail on close before any close animation finishes', async () => {
    mount();
    const pending = deferred<MarketApi.ConsumerDetail>();
    mock.detail.mockReturnValueOnce(pending.promise);
    const request = state.open('1');
    await mock.modals[2]!.api.close();
    pending.resolve({ asset: row(), columns: [{ columnName: 'stale_field' }] });
    await request;
    expect(state.current).toBeUndefined();
    expect(state.detail).toBeUndefined();
  });
  it('clears previous columns during reload and after a failed detail request', async () => {
    mount();
    await state.open('1');
    const pending = deferred<MarketApi.ConsumerDetail>();
    mock.detail.mockReturnValueOnce(pending.promise);
    const request = state.open('2');
    await flush();
    expect(host.textContent).not.toContain('old_field');
    pending.reject(new Error('detail failed'));
    await request;
    await flush();
    expect(state.current).toBeUndefined();
    expect(host.textContent).not.toContain('详情读取失败');
  });
  it('requires an exact boolean access grant instead of trusting asset flags', async () => {
    mount();
    mock.detail.mockResolvedValueOnce({
      asset: { ...row(), authorized: true, usable: true },
    });
    mock.check.mockResolvedValueOnce('true' as unknown as boolean);
    await state.open('1');
    await flush();
    state.openPreview();
    expect(mock.check).toHaveBeenCalledWith('1');
    expect(mock.modals[1]!.visible.value).toBe(false);
    expect(host.textContent).toContain('预览授权未确认');
  });
  it('preserves large ids for detail, access, application, and preview', async () => {
    const id = '9007199254740993';
    mount();
    mock.detail.mockResolvedValueOnce({ asset: row(id) });
    await state.open(id);
    state.openPreview();
    state.applyAsset();
    expect(mock.detail).toHaveBeenCalledWith(id);
    expect(mock.check).toHaveBeenCalledWith(id);
    expect(mock.modals[0]!.data).toMatchObject({ assetId: id });
    expect(mock.modals[1]!.data).toMatchObject({ assetId: id });
  });
  it('closes dependent dialogs immediately when identity changes', async () => {
    mount();
    await state.open('1');
    state.applyAsset();
    state.openPreview();
    expect(mock.modals[0]!.visible.value).toBe(true);
    expect(mock.modals[1]!.visible.value).toBe(true);
    mock.user.userInfo = { userId: '2' };
    expect(mock.modals.every((modal) => !modal.visible.value)).toBe(true);
    expect(state.current).toBeUndefined();
  });
  it('opens public detail without query permissions and preserves application and preview checks', async () => {
    mock.access.accessCodes = [];
    mock.check.mockResolvedValue(false);
    mount();
    await state.open('1');
    expect(mock.detail).toHaveBeenCalledWith('1');
    expect(mock.check).toHaveBeenCalledWith('1');
    expect(mock.comments).not.toHaveBeenCalled();
    state.applyAsset();
    state.openPreview();
    expect(mock.modals[0]!.visible.value).toBe(false);
    expect(mock.modals[1]!.visible.value).toBe(false);
    app!.unmount();
    app = undefined;
    mock.detail.mockClear();
    mock.check.mockClear();
    await state.open('2');
    expect(mock.detail).not.toHaveBeenCalled();
    expect(mock.check).not.toHaveBeenCalled();
  });
  it('mounts the comment reader only for the existing query permission', async () => {
    mount();
    await state.open('1');
    await flush();
    expect(mock.comments).toHaveBeenCalledOnce();
  });
  it.each(['user', 'token', 'expired'] as const)(
    'does not request detail with invalid login state: %s',
    async (change) => {
      if (change === 'user') mock.user.userInfo = null;
      if (change === 'token') mock.access.accessToken = '';
      if (change === 'expired') mock.access.loginExpired = true;
      mount();
      await state.open('1');
      expect(mock.detail).not.toHaveBeenCalled();
      expect(mock.check).not.toHaveBeenCalled();
    },
  );
});
