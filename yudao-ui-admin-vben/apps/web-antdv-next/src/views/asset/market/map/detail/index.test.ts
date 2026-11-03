/* eslint-disable vue/one-component-per-file -- 测试桩集中模拟详情页边界 */
import type { App } from 'vue';

import type { DataAssetApi } from '#/api/asset/dataasset';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import DetailPage from './index.vue';

const mock = vi.hoisted(() => ({
  access: {
    accessToken: 'session',
    refreshToken: undefined as string | undefined,
    loginExpired: false,
    accessCodes: [] as string[],
  },
  user: { userInfo: { userId: '1' } as null | { userId: string } },
  route: { params: { id: '9007199254740993' }, meta: {} },
  detail: vi.fn(),
  publish: vi.fn(),
  unpublish: vi.fn(),
  refresh: vi.fn(),
  confirm: vi.fn(),
  apply: vi.fn(),
  modalState: vi.fn(),
  protectedTab: vi.fn(),
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
  useRoute: () => mock.route,
  useRouter: () => ({ push: vi.fn() }),
}));
vi.mock('@vben/hooks', () => ({
  useAntdDesignTokens: () => ({ tokens: { borderRadius: 8 } }),
  useTabs: () => ({ closeCurrentTab: vi.fn() }),
}));
vi.mock('@vben/icons', () => ({ IconifyIcon: { render: () => null } }));
vi.mock('@vben/utils', () => ({ formatDateTime: () => '' }));
vi.mock('#/api/asset/dataasset', () => ({
  ASSET_STATUS_OPTIONS: [{ value: 1, label: '已发布', color: 'green' }],
  getDataAssetDetail: mock.detail,
  publishDataAsset: mock.publish,
  unpublishDataAsset: mock.unpublish,
  refreshDataAsset: mock.refresh,
}));
vi.mock('#/views/market/modules/apply-form.vue', () => ({
  default: { render: () => null },
}));
vi.mock('./modules/catalog-tab.vue', () => ({
  default: {
    setup: () => {
      mock.protectedTab('catalog');
      return () => null;
    },
  },
}));
vi.mock('./modules/columns-tab.vue', () => ({
  default: { render: () => null },
}));
vi.mock('./modules/etl-batch-tab.vue', () => ({
  default: {
    setup: () => {
      mock.protectedTab('etl-batch');
      return () => null;
    },
  },
}));
vi.mock('./modules/overview-tab.vue', () => ({
  default: { render: () => null },
}));
vi.mock('./modules/preview-tab.vue', () => ({
  default: {
    setup: () => {
      mock.protectedTab('preview');
      return () => null;
    },
  },
}));
vi.mock('./modules/quality-tab.vue', () => ({
  default: { render: () => null },
}));
vi.mock('@vben/common-ui', () => {
  const box = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('section', slots.default?.()),
  });
  const api = {
    setData: () => api,
    setState: mock.modalState,
    open: mock.apply,
  };
  return { Page: box, useVbenModal: () => [box, api] };
});
vi.mock('antdv-next', () => {
  const box = defineComponent({
    props: { message: { type: String, default: '' } },
    setup:
      (props, { slots }) =>
      () =>
        h('section', [props.message, slots.default?.()]),
  });
  return {
    Alert: box,
    Button: box,
    Card: box,
    ConfigProvider: box,
    Descriptions: box,
    DescriptionsItem: box,
    Dropdown: box,
    Menu: Object.assign(box, { Item: box }),
    Tabs: box,
    Tag: box,
    Tooltip: box,
    theme: { defaultAlgorithm: {} },
    Modal: { confirm: mock.confirm },
    message: { success: vi.fn(), warning: vi.fn() },
  };
});

interface State {
  detail: DataAssetApi.Detail;
  tabItems: { key: string }[];
  activeTab: string;
  visited: Record<string, boolean>;
  handleTabChange: (key: string) => void;
  loadDetail: () => Promise<void>;
  handleApply: () => void;
  handleTogglePublish: () => void;
  handleRefreshMetadata: () => Promise<void>;
}
let app: App | undefined;
let host: HTMLDivElement;
let state: State;
const detail = (name = '目录详情'): DataAssetApi.Detail => ({
  asset: { id: '9007199254740993', assetName: name, status: 1, assetType: 1 },
});
async function flush() {
  for (let index = 0; index < 8; index++) await Promise.resolve();
  await nextTick();
}
function mount() {
  app = createApp(DetailPage);
  app.directive('access', {});
  state = (app.mount(host).$ as unknown as { setupState: State }).setupState;
}
beforeEach(() => {
  vi.resetAllMocks();
  mock.access = reactive({
    accessToken: 'session',
    refreshToken: undefined,
    loginExpired: false,
    accessCodes: [],
  });
  mock.user = reactive({ userInfo: { userId: '1' } });
  mock.route = reactive({ params: { id: '9007199254740993' }, meta: {} });
  mock.detail.mockResolvedValue(detail());
  mock.refresh.mockResolvedValue(true);
  host = document.createElement('div');
  document.body.append(host);
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
});

describe('asset map authenticated detail', () => {
  it('exposes only basic read tabs and never mounts protected tabs without their permissions', async () => {
    mount();
    await flush();
    expect(state.tabItems.map((tab) => tab.key)).toEqual([
      'columns',
      'quality',
      'lineage',
      'overview',
    ]);
    for (const key of ['preview', 'catalog', 'etl-batch']) {
      state.handleTabChange(key);
      expect(state.activeTab).toBe('columns');
      state.visited[key] = true;
    }
    await nextTick();
    expect(mock.protectedTab).not.toHaveBeenCalled();
  });
  it('opens an explicitly granted preview tab without granting management tabs', async () => {
    mock.access.accessCodes = ['asset:data-asset:preview'];
    mount();
    await flush();
    state.handleTabChange('preview');
    await nextTick();
    expect(state.activeTab).toBe('preview');
    expect(mock.protectedTab).toHaveBeenCalledWith('preview');
    state.handleTabChange('catalog');
    expect(state.activeTab).toBe('preview');
    expect(mock.protectedTab).not.toHaveBeenCalledWith('catalog');
    mock.access.accessCodes = [];
    expect(state.activeTab).toBe('columns');
    expect(state.visited.preview).toBe(false);
  });
  it('opens detail without query codes while keeping writes and applications protected', async () => {
    mount();
    await flush();
    expect(mock.detail).toHaveBeenCalledWith('9007199254740993');
    expect(host.textContent).toContain('目录详情');
    expect(host.textContent).not.toContain('申请使用');
    state.handleApply();
    state.handleTogglePublish();
    await state.handleRefreshMetadata();
    expect(mock.apply).not.toHaveBeenCalled();
    expect(mock.confirm).not.toHaveBeenCalled();
    expect(mock.refresh).not.toHaveBeenCalled();
  });
  it.each(['user', 'token', 'expired'] as const)(
    'rejects invalid login state: %s',
    async (change) => {
      if (change === 'user') mock.user.userInfo = null;
      if (change === 'token') mock.access.accessToken = '';
      if (change === 'expired') mock.access.loginExpired = true;
      mount();
      await flush();
      expect(mock.detail).not.toHaveBeenCalled();
      expect(host.textContent).toContain('登录身份已失效');
    },
  );
  it('permits only explicitly granted actions and rechecks a pending confirmation', async () => {
    mock.access.accessCodes = [
      'asset:market:apply',
      'asset:data-asset:update',
      'asset:data-asset:publish',
    ];
    mount();
    await flush();
    state.handleApply();
    await state.handleRefreshMetadata();
    state.handleTogglePublish();
    expect(mock.apply).toHaveBeenCalledOnce();
    expect(mock.refresh).toHaveBeenCalledWith('9007199254740993');
    const confirm = mock.confirm.mock.calls[0]![0] as {
      onOk: () => Promise<void>;
    };
    mock.access.accessCodes = [];
    await confirm.onOk();
    expect(mock.publish).not.toHaveBeenCalled();
    expect(mock.unpublish).not.toHaveBeenCalled();
  });
  it('clears detail immediately and ignores a pending response after logout', async () => {
    mount();
    await flush();
    let resolve!: (value: DataAssetApi.Detail) => void;
    mock.detail.mockReturnValueOnce(
      new Promise<DataAssetApi.Detail>((yes) => {
        resolve = yes;
      }),
    );
    const pending = state.loadDetail();
    mock.access.loginExpired = true;
    expect(state.detail).toEqual({});
    expect(mock.modalState).toHaveBeenCalledWith({ isOpen: false });
    resolve(detail('旧会话详情'));
    await pending;
    expect(state.detail).toEqual({});
    expect(host.textContent).not.toContain('旧会话详情');
  });
  it('discards a previous route response and loads the new exact id', async () => {
    mount();
    await flush();
    let resolve!: (value: DataAssetApi.Detail) => void;
    mock.detail.mockReturnValueOnce(
      new Promise<DataAssetApi.Detail>((yes) => {
        resolve = yes;
      }),
    );
    const pending = state.loadDetail();
    mock.route.params.id = '9223372036854775807';
    await flush();
    expect(mock.detail).toHaveBeenLastCalledWith('9223372036854775807');
    resolve(detail('旧路由详情'));
    await pending;
    expect(state.detail.asset?.assetName).toBe('目录详情');
  });
});
