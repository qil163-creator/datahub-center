/* eslint-disable vue/one-component-per-file -- 测试桩在同一文件集中声明 */
import type { App, ComponentPublicInstance } from 'vue';

import type { DataAssetApi } from '#/api/asset/dataasset';
import type { ModelingDataDomainApi } from '#/api/modeling/datadomain';

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

import Workspace from './index.vue';

const mock = vi.hoisted(() => ({
  access: {
    accessToken: 'session',
    loginExpired: false,
    refreshToken: undefined as string | undefined,
    accessCodes: ['asset:data-asset:query', 'asset:data-asset:create'],
  },
  user: { userInfo: { userId: '1' } as null | { userId: string } },
  page: vi.fn(),
  facets: vi.fn(),
  categories: vi.fn(),
  dataDomains: vi.fn(),
  managementCategories: vi.fn(),
  tags: vi.fn(),
  detail: vi.fn(),
  context: vi.fn(),
  update: vi.fn(),
  bind: vi.fn(),
  push: vi.fn(),
  publishData: vi.fn(),
  publishOpen: vi.fn(),
}));

vi.mock('#/adapter/form', () => ({
  useVbenForm: () => [
    () => null,
    { reset: vi.fn(), validateAndSubmit: vi.fn() },
  ],
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
vi.mock('vue-router', () => ({ useRouter: () => ({ push: mock.push }) }));
vi.mock('@vben/icons', () => ({ IconifyIcon: { render: () => null } }));
vi.mock('@vben/utils', () => ({
  formatDateTime: (value: unknown) => String(value ?? ''),
}));
vi.mock('#/api/asset/dataasset', () => ({
  getDataAssetPage: mock.page,
  getDataAssetDetail: mock.detail,
  getDataAssetFacets: mock.facets,
  getDataAssetNavigationCategories: mock.categories,
  ASSET_STATUS_OPTIONS: [
    { value: 0, label: '草稿', color: 'default' },
    { value: 1, label: '已发布', color: 'green' },
  ],
  ASSET_TYPE_OPTIONS: [{ value: 1, label: '数据库表' }],
  SHARE_ATTR_OPTIONS: [],
  createCatalogDraft: vi.fn(),
  deleteDataAsset: vi.fn(),
  refreshDataAsset: vi.fn(),
  stopDataAssetSupply: vi.fn(),
  unpublishDataAsset: vi.fn(),
  updateDataAsset: mock.update,
}));
vi.mock('#/api/modeling/datadomain', () => ({
  getDataDomainSimpleList: mock.dataDomains,
}));
vi.mock('#/api/asset/dataasset/registration', () => ({
  getAssetRegistrationOptions: mock.facets,
  getAssetRegistrationContext: mock.context,
}));
vi.mock('#/api/asset/categorydataasset', () => ({
  getCategoryDataAssetList: mock.managementCategories,
  deleteCategoryDataAsset: vi.fn(),
}));
vi.mock('#/api/asset/tag', () => ({
  getAssetTagList: mock.tags,
  bindAssetTags: mock.bind,
}));
vi.mock('../modules/publish-form.vue', () => ({
  default: { name: 'MarketPublishStub', render: () => null },
}));
vi.mock('../../tag/modules/form.vue', () => ({
  default: { render: () => null },
}));
vi.mock('./modules/category-form.vue', () => ({
  default: { render: () => null },
}));
vi.mock('@vben/common-ui', () => {
  const box = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('section', [slots.description?.(), slots.default?.()]),
  });
  const api = { setData: () => api, setState: () => api, open: vi.fn() };
  return {
    Page: box,
    useVbenModal: (options: { connectedComponent?: { name?: string } }) => {
      if (options.connectedComponent?.name === 'MarketPublishStub') {
        const publishApi = {
          setData: (value: unknown) => {
            mock.publishData(value);
            return publishApi;
          },
          setState: () => publishApi,
          open: mock.publishOpen,
        };
        return [box, publishApi];
      }
      return [box, api];
    },
  };
});
vi.mock('antdv-next', () => {
  const box = defineComponent({
    props: {
      message: { type: String, default: '' },
      description: { type: String, default: '' },
    },
    setup:
      (props, { slots }) =>
      () =>
        h('section', [props.message, props.description, slots.default?.()]),
  });
  return {
    Alert: box,
    Button: box,
    Card: box,
    ConfigProvider: box,
    Dropdown: box,
    Empty: box,
    Form: box,
    FormItem: box,
    Input: Object.assign(box, { TextArea: box }),
    Menu: Object.assign(box, { Item: box }),
    Modal: Object.assign(box, { confirm: vi.fn() }),
    Pagination: box,
    Select: box,
    Spin: box,
    Tag: box,
    Tooltip: box,
    Tree: box,
    TreeSelect: box,
    message: { info: vi.fn(), success: vi.fn(), warning: vi.fn() },
  };
});
interface State {
  editForm: Partial<DataAssetApi.DataAsset>;
  editOpen: boolean;
  editError: string;
  editLoading: boolean;
  openEdit: (asset: DataAssetApi.DataAsset) => Promise<void>;
  changeEditDomain: (value: unknown) => Promise<void>;
  saveEdit: () => Promise<void>;
  assets: DataAssetApi.DataAsset[];
  facets: DataAssetApi.Facets;
  loading: boolean;
  groupTree: { children?: any[]; key: string; title: string }[];
  categoryTreeForSelect: { title: string }[];
  expandedKeys: string[];
  isGroupExpanded: boolean;
  toggleExpandAll: () => void;
  query: {
    categoryId?: DataAssetApi.Id;
    dataDomainId?: DataAssetApi.Id;
    layer?: string;
    status?: number;
  };
  onGroupSelect: (keys: string[], info: { node: any }) => void;
  reset: () => void;
  selectAll: () => void;
  loadAssets: () => Promise<void>;
  loadFacets: () => Promise<void>;
  loadDataDomains: () => Promise<void>;
  openDetail: (asset: DataAssetApi.DataAsset) => void;
  openRegister: () => void;
  togglePublish: (asset: DataAssetApi.DataAsset) => void;
  openTags: (asset: DataAssetApi.DataAsset) => Promise<void>;
  saveTags: () => Promise<void>;
  selectedTagIds: DataAssetApi.Id[];
  tagOpen: boolean;
  tagError: string;
  tagSaving: boolean;
}
let app: App | undefined;
let host: HTMLDivElement;
let state: State;
const visible = ref(true);
const row = (name: string): DataAssetApi.DataAsset => ({
  id: '9007199254740993',
  assetName: name,
  status: 0,
  assetType: 1,
  catalogRevision: '0',
});
const page = (name: string) => ({ list: [row(name)], total: 1 });
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
  for (let index = 0; index < 10; index++) await Promise.resolve();
  await nextTick();
}
function capture(vm: ComponentPublicInstance | null) {
  if (vm) state = (vm.$ as unknown as { setupState: State }).setupState;
}
function mount(keepAlive = false) {
  visible.value = true;
  if (keepAlive) {
    app = createApp(
      defineComponent({
        setup: () => () =>
          h(KeepAlive, null, {
            default: () =>
              visible.value
                ? h(Workspace, {
                    ref: (vm) => capture(vm as ComponentPublicInstance),
                  })
                : undefined,
          }),
      }),
    );
    app.directive('access', {});
    app.mount(host);
  } else {
    app = createApp(Workspace);
    app.directive('access', {});
    capture(app.mount(host));
  }
}
beforeEach(() => {
  vi.resetAllMocks();
  mock.access = reactive({
    accessToken: 'session',
    loginExpired: false,
    refreshToken: undefined as string | undefined,
    accessCodes: ['asset:data-asset:query', 'asset:data-asset:create'],
  });
  mock.user = reactive({ userInfo: { userId: '1' } });
  mock.context.mockResolvedValue({
    dataDomains: [],
    subjectDomains: [],
    layers: [],
    entities: [],
    granularities: [],
    namingRules: [],
  });
  mock.update.mockResolvedValue(true);
  mock.page.mockResolvedValue(page('当前资产'));
  mock.facets.mockResolvedValue({
    total: 1,
    categories: [{ key: '11', label: '生产类目', count: 1 }],
    layers: [{ key: 'ODS', label: '贴源层', count: 1 }],
  });
  mock.categories.mockResolvedValue([
    { id: 11, parentId: 0, categoryName: '生产类目' },
  ]);
  mock.dataDomains.mockResolvedValue([
    { id: '9007199254740993', name: '水利板块', code: 'WATER', status: 0 },
    { id: '3', name: '停用板块', status: 1 },
  ]);
  mock.tags.mockResolvedValue([]);
  mock.bind.mockResolvedValue(true);
  host = document.createElement('div');
  document.body.append(host);
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
});

describe('asset map workspace', () => {
  it('opens the map for a signed-in user without query or write permissions', async () => {
    mock.access.accessCodes = [];
    mount();
    await flush();
    expect(mock.page).toHaveBeenCalledOnce();
    expect(mock.facets).toHaveBeenCalledOnce();
    expect(mock.categories).toHaveBeenCalledOnce();
    expect(mock.dataDomains).toHaveBeenCalledOnce();
    expect(state.assets[0]?.assetName).toBe('当前资产');
    state.openRegister();
    state.togglePublish(row('草稿'));
    expect(mock.push).not.toHaveBeenCalled();
    expect(mock.publishOpen).not.toHaveBeenCalled();
    state.openDetail(row('草稿'));
    expect(mock.push).toHaveBeenCalledWith({
      name: 'AssetMarketMapDetail',
      params: { id: '9007199254740993' },
    });
  });
  it.each(['user', 'token', 'expired'] as const)(
    'rejects invalid login state: %s',
    async (change) => {
      if (change === 'user') mock.user.userInfo = null;
      if (change === 'token') mock.access.accessToken = '';
      if (change === 'expired') mock.access.loginExpired = true;
      mount();
      await flush();
      expect(mock.page).not.toHaveBeenCalled();
      expect(mock.facets).not.toHaveBeenCalled();
      expect(mock.categories).not.toHaveBeenCalled();
      expect(mock.dataDomains).not.toHaveBeenCalled();
      expect(host.textContent).toContain('登录身份已失效');
      state.openRegister();
      state.openDetail(row('草稿'));
      expect(mock.push).not.toHaveBeenCalled();
    },
  );
  it('loads navigation for a query-only map role without category management permission', async () => {
    mock.access.accessCodes = ['asset:data-asset:query'];
    mount();
    await flush();
    expect(mock.categories).toHaveBeenCalledWith();
    expect(mock.managementCategories).not.toHaveBeenCalled();
    expect(state.groupTree[0]).toMatchObject({
      title: '按数据板块',
      children: [expect.objectContaining({ title: '水利板块' })],
    });
    expect(state.categoryTreeForSelect).toEqual([
      expect.objectContaining({ title: '生产类目' }),
    ]);
    expect(state.assets[0]?.assetName).toBe('当前资产');
  });
  it('shows only data-board and warehouse-layer groups', async () => {
    mount();
    await flush();
    expect(state.groupTree.map((node) => node.key)).toEqual([
      'group:data-domain',
      'group:layer',
    ]);
    expect(state.groupTree.map((node) => node.title)).toEqual([
      '按数据板块',
      '按数仓分层',
    ]);
    expect(state.assets[0]?.status).toBe(0);
    expect(mock.tags).not.toHaveBeenCalled();
  });
  it('switches board and layer filters without losing large ids', async () => {
    mount();
    await flush();
    const board = state.groupTree[0]!.children![0];
    const layer = state.groupTree[1]!.children![0].children[0];
    state.onGroupSelect([board.key], { node: board });
    expect(mock.page).toHaveBeenLastCalledWith(expect.objectContaining({
      dataDomainId: '9007199254740993', categoryId: undefined, layer: undefined,
    }));
    state.onGroupSelect([layer.key], { node: layer });
    expect(mock.page).toHaveBeenLastCalledWith(expect.objectContaining({
      dataDomainId: undefined, categoryId: undefined, layer: 'ODS',
    }));
    state.onGroupSelect([board.key], { node: board });
    state.selectAll();
    expect(state.query).toMatchObject({ dataDomainId: undefined, categoryId: undefined, layer: undefined });
    state.onGroupSelect([board.key], { node: board });
    state.reset();
    expect(state.query).toMatchObject({ dataDomainId: undefined, categoryId: undefined, layer: undefined });
  });
  it('keeps navigation visible on failure and retries, while discarding old-session responses', async () => {
    mock.dataDomains.mockRejectedValueOnce(new Error('板块读取失败'));
    mount();
    await flush();
    expect(host.textContent).toContain('数据板块读取失败');
    expect(state.categoryTreeForSelect[0]?.title).toBe('生产类目');
    expect(state.groupTree[1]?.children?.[0]?.title).toBe('贴源层');
    await state.loadDataDomains();
    await flush();
    expect(state.groupTree[0]?.children?.[0]?.title).toBe('水利板块');
    expect(host.textContent).not.toContain('数据板块读取失败');

    const old = deferred<ModelingDataDomainApi.DataDomain[]>();
    mock.dataDomains.mockReturnValueOnce(old.promise);
    const pending = state.loadDataDomains();
    mock.access.accessToken = 'new-session';
    old.resolve([{ id: '2', name: '旧会话板块', code: 'OLD', status: 0 }]);
    await pending;
    await flush();
    expect(state.groupTree[0]?.children?.[0]?.title).toBe('水利板块');
  });
  it('can collapse and expand the remaining navigation groups', async () => {
    mount();
    await flush();
    expect(state.isGroupExpanded).toBe(false);
    state.toggleExpandAll();
    await flush();
    expect(state.expandedKeys).toContain('layer-group:source');
    expect(state.isGroupExpanded).toBe(true);
    state.toggleExpandAll();
    await flush();
    expect(state.expandedKeys).toEqual([]);
    expect(state.isGroupExpanded).toBe(false);
    state.toggleExpandAll();
    await flush();
    expect(state.expandedKeys).toEqual(expect.arrayContaining([
      'group:data-domain', 'group:layer',
    ]));
    expect(state.isGroupExpanded).toBe(true);
  });
  it('keeps registration and detail navigation in the map domain', async () => {
    mount();
    await flush();
    state.openRegister();
    expect(mock.push).toHaveBeenCalledWith({ path: '/asset/assetmap' });
    state.openDetail(row('目录'));
    expect(mock.push).toHaveBeenCalledWith({
      name: 'AssetMarketMapDetail',
      params: { id: '9007199254740993' },
    });
  });
  it('不展示资产地图顶部产品说明横幅', async () => {
    mount();
    await flush();
    expect(host.querySelector('.market-map-intro')).toBeNull();
    expect(host.textContent).not.toContain(
      '资产地图 — 全域数据资产的统一管理与全景洞察',
    );
  });

  it('opens the marketplace publication form for a draft and respects publish permission', async () => {
    mount();
    await flush();
    const asset = row('待发布资产');
    state.togglePublish(asset);
    expect(mock.publishOpen).not.toHaveBeenCalled();
    mock.access.accessCodes.push('asset:data-asset:publish');
    await flush();
    state.togglePublish(asset);
    expect(mock.publishOpen).toHaveBeenCalledOnce();
    expect(mock.publishData).toHaveBeenCalledWith({ asset });
  });
  it('transmits large category ids and draft filters without numeric conversion', async () => {
    mount();
    await flush();
    state.query.categoryId = '9007199254740993';
    state.query.status = 0;
    await state.loadAssets();
    expect(mock.page).toHaveBeenLastCalledWith(
      expect.objectContaining({ categoryId: '9007199254740993', status: 0 }),
    );
  });
  it.each(['resolve', 'reject'] as const)(
    'prevents a stale list %s from affecting the current request',
    async (outcome) => {
      mount();
      await flush();
      const old = deferred<ReturnType<typeof page>>();
      const latest = deferred<ReturnType<typeof page>>();
      mock.page
        .mockReturnValueOnce(old.promise)
        .mockReturnValueOnce(latest.promise);
      const first = state.loadAssets();
      const second = state.loadAssets();
      if (outcome === 'resolve') old.resolve(page('旧资产'));
      else old.reject(new Error('旧错误'));
      await first;
      expect(state.loading).toBe(true);
      expect(state.assets).toEqual([]);
      latest.resolve(page('新资产'));
      await second;
      expect(state.assets[0]?.assetName).toBe('新资产');
      expect(state.loading).toBe(false);
    },
  );
  it.each(['user', 'token', 'refresh', 'permission'] as const)(
    'invalidates pending data immediately after %s changes',
    async (change) => {
      mount();
      await flush();
      const old = deferred<ReturnType<typeof page>>();
      mock.page.mockReturnValueOnce(old.promise);
      const pending = state.loadAssets();
      if (change === 'user') mock.user.userInfo = { userId: '2' };
      if (change === 'token') mock.access.accessToken = 'other';
      if (change === 'refresh') mock.access.refreshToken = 'other';
      if (change === 'permission') mock.access.accessCodes = [];
      expect(state.assets).toEqual([]);
      old.resolve(page('旧身份资产'));
      await pending;
      await flush();
      expect(
        state.assets.some((asset) => asset.assetName === '旧身份资产'),
      ).toBe(false);
    },
  );
  it.each(['unmount', 'deactivate'] as const)(
    'ignores read responses after %s',
    async (change) => {
      mount(change === 'deactivate');
      await flush();
      const old = deferred<ReturnType<typeof page>>();
      mock.page.mockReturnValueOnce(old.promise);
      const pending = state.loadAssets();
      if (change === 'unmount') {
        app!.unmount();
        app = undefined;
      } else {
        visible.value = false;
        await flush();
      }
      old.resolve(page('旧资产'));
      await pending;
      expect(state.assets).toEqual([]);
      expect(state.loading).toBe(false);
      mock.page.mockClear();
      await state.loadAssets();
      expect(mock.page).not.toHaveBeenCalled();
    },
  );
  it('clears current data on failure and supports a subsequent retry', async () => {
    mount();
    await flush();
    mock.page.mockRejectedValueOnce(new Error('列表失败'));
    await state.loadAssets();
    await flush();
    expect(state.assets).toEqual([]);
    expect(host.textContent).toContain('资产读取失败');
    await state.loadAssets();
    expect(state.assets[0]?.assetName).toBe('当前资产');
  });
  it('keeps only the latest facet and category response', async () => {
    mount();
    await flush();
    const old = deferred<DataAssetApi.Facets>();
    mock.facets.mockReturnValueOnce(old.promise);
    const first = state.loadFacets();
    mock.facets.mockResolvedValueOnce({ total: 5 });
    await state.loadFacets();
    old.resolve({ total: 99 });
    await first;
    expect(state.facets.total).toBe(5);
  });
  it('loads fresh asset tags before replacing them and preserves long IDs', async () => {
    mock.access.accessCodes.push('asset:data-asset:tag');
    mock.detail.mockResolvedValue({
      asset: {
        ...row('最新资产'),
        catalogRevision: '9007199254740996',
        tags: [{ id: '9007199254740994', name: '河道水情' }],
      },
    });
    mount();
    await flush();
    await state.openTags({
      ...row('列表旧资产'),
      tags: [{ id: '2', name: '旧标签' }],
    });
    expect(state.selectedTagIds).toEqual(['9007199254740994']);
    await state.saveTags();
    expect(mock.bind).toHaveBeenCalledWith(
      '9007199254740993',
      ['9007199254740994'],
      '9007199254740996',
    );
  });
  it('rejects mismatched asset detail identity before enabling tag writes', async () => {
    mock.access.accessCodes.push('asset:data-asset:tag');
    mock.detail.mockResolvedValue({ asset: { id: '2', tags: [] } });
    mount();
    await flush();
    await state.openTags(row('当前'));
    expect(state.tagError).toContain('读取失败');
    await state.saveTags();
    expect(mock.bind).not.toHaveBeenCalled();
  });
  it('ignores tag lookup and binding results after the modal closes or identity changes', async () => {
    mock.access.accessCodes.push('asset:data-asset:tag');
    const pending = deferred<{ asset: DataAssetApi.DataAsset }>();
    mock.detail.mockReturnValue(pending.promise);
    mount();
    await flush();
    const loading = state.openTags(row('当前'));
    state.tagOpen = false;
    await flush();
    pending.resolve({
      asset: { ...row('旧'), tags: [{ id: '9', name: '旧' }] },
    });
    await loading;
    expect(state.selectedTagIds).toEqual([]);
    mock.detail.mockResolvedValue({ asset: row('当前') });
    await state.openTags(row('当前'));
    const binding = deferred<boolean>();
    mock.bind.mockReturnValue(binding.promise);
    const saving = state.saveTags();
    mock.access.accessToken = 'replacement';
    await flush();
    binding.resolve(true);
    await saving;
    expect(state.tagOpen).toBe(false);
    expect(state.tagSaving).toBe(false);
  });
});

it('updates real modeling ownership independently from the asset category using fresh detail revision', async () => {
  mock.access.accessCodes.push('asset:data-asset:update');
  mock.detail.mockResolvedValue({
    asset: {
      ...row('最新名称'),
      categoryId: '99',
      catalogRevision: '7',
      subjectDomainId: '81',
      registrationConfig: {
        dataDomainId: '9007199254740994',
        entityId: '82',
        granularityEntityIds: ['83'],
        tableType: 'DWD',
      },
    },
    columns: [],
    qualityTasks: [],
  });
  mount();
  await flush();
  await state.openEdit(row('过期卡片名称'));
  expect(state.editForm.registrationConfig?.dataDomainId).toBe(
    '9007199254740994',
  );
  await state.changeEditDomain('9007199254740995');
  expect(mock.context).toHaveBeenLastCalledWith({
    dataDomainId: '9007199254740995',
    tableType: 'DWD',
  });
  expect(state.editForm.registrationConfig?.entityId).toBeUndefined();
  expect(state.editForm.subjectDomainId).toBeUndefined();
  state.editForm.subjectDomainId = '9007199254740996';
  await state.saveEdit();
  expect(mock.update).toHaveBeenCalledWith(
    expect.objectContaining({
      id: '9007199254740993',
      assetName: '最新名称',
      categoryId: '99',
      expectedRevision: '7',
      subjectDomainId: '9007199254740996',
      registrationConfig: expect.objectContaining({
        dataDomainId: '9007199254740995',
      }),
    }),
  );
});
it('fails closed when edit detail belongs to another asset', async () => {
  mock.access.accessCodes.push('asset:data-asset:update');
  mock.detail.mockResolvedValue({
    asset: { id: '2', assetName: '其他资产' },
    columns: [],
    qualityTasks: [],
  });
  mount();
  await flush();
  await state.openEdit(row('目标'));
  await state.saveEdit();
  expect(state.editError).toBeTruthy();
  expect(mock.update).not.toHaveBeenCalled();
  expect(mock.context).not.toHaveBeenCalled();
});
it('ignores late modeling options and blocks writes after closing edit', async () => {
  mock.access.accessCodes.push('asset:data-asset:update');
  mock.detail.mockResolvedValue({
    asset: { ...row('目标'), catalogRevision: '1' },
    columns: [],
    qualityTasks: [],
  });
  mount();
  await flush();
  await state.openEdit(row('目标'));
  const work = deferred<unknown>();
  mock.context.mockReturnValueOnce(work.promise);
  const pending = state.changeEditDomain('33');
  state.editOpen = false;
  work.resolve({
    dataDomains: [],
    subjectDomains: [],
    layers: [],
    entities: [],
    granularities: [],
    namingRules: [],
  });
  await pending;
  await state.saveEdit();
  expect(state.editLoading).toBe(false);
  expect(mock.update).not.toHaveBeenCalled();
});
it('does not submit a stale tag selection after the binding dialog closes', async () => {
  mock.access.accessCodes.push('asset:data-asset:tag');
  mock.detail.mockResolvedValue({
    asset: { ...row('目标'), tags: [] },
    columns: [],
    qualityTasks: [],
  });
  mount();
  await flush();
  await state.openTags(row('目标'));
  state.tagOpen = false;
  await state.saveTags();
  expect(mock.bind).not.toHaveBeenCalled();
});

it('requires a fresh catalog revision and keeps a conflicting tag draft until it is refreshed', async () => {
  mock.access.accessCodes.push('asset:data-asset:tag');
  mock.detail.mockResolvedValueOnce({
    asset: { ...row('目标'), catalogRevision: undefined, tags: [] },
  });
  mount();
  await flush();
  await state.openTags(row('目标'));
  await state.saveTags();
  expect(mock.bind).not.toHaveBeenCalled();
  expect(state.tagError).toBeTruthy();
  mock.detail.mockResolvedValue({
    asset: { ...row('目标'), catalogRevision: '9', tags: [] },
  });
  await state.openTags(row('目标'));
  state.selectedTagIds = ['9007199254740994'];
  mock.bind.mockRejectedValueOnce(new Error('stale revision'));
  await state.saveTags();
  expect(state.tagOpen).toBe(true);
  expect(state.selectedTagIds).toEqual(['9007199254740994']);
  expect(state.tagError).toContain('已更新');
  await state.saveTags();
  expect(mock.bind).toHaveBeenCalledOnce();
  await state.openTags(row('目标'));
  mock.bind.mockResolvedValue(true);
  await state.saveTags();
  expect(mock.bind).toHaveBeenLastCalledWith('9007199254740993', [], '9');
});
