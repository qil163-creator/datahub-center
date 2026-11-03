/* eslint-disable vue/one-component-per-file -- 测试桩集中模拟页面依赖 */
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

import { marketCount } from './data';
import Market from './index.vue';
const mock = vi.hoisted(() => ({
  access: {
    accessToken: 'session',
    loginExpired: false,
    refreshToken: undefined as string | undefined,
    accessCodes: [
      'asset:market:query',
      'asset:market:favorite',
      'asset:market:apply',
    ],
  },
  user: { userInfo: { userId: '1' } as null | { userId: string } },
  page: vi.fn(),
  stats: vi.fn(),
  categories: vi.fn(),
  topics: vi.fn(),
  favorite: vi.fn(),
  close: vi.fn(),
  open: vi.fn(),
  push: vi.fn(),
  apply: vi.fn(),
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
vi.mock('@vben/utils', () => ({ handleTree: (values: unknown[]) => values }));
vi.mock('#/api/asset/market', () => ({
  getMarketMartPage: mock.page,
  getMarketMartStatistics: mock.stats,
  getMarketMartTopics: mock.topics,
  updateMarketMartFavorite: mock.favorite,
}));
vi.mock('#/api/asset/marketcategory', () => ({
  getMarketCategorySimpleList: mock.categories,
}));
vi.mock('./modules/mart-apply-form.vue', () => ({
  default: { render: () => null },
}));
vi.mock('./modules/resource-detail.vue', () => ({
  default: defineComponent({
    setup: (_, { expose }) => {
      expose({ close: mock.close, open: mock.open });
      return () => null;
    },
  }),
}));
vi.mock('@vben/common-ui', () => {
  const box = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('section', slots.default?.()),
  });
  const api = {
    setData: (data: unknown) => {
      mock.apply(data);
      return api;
    },
    open: vi.fn(),
    push: vi.fn(),
    setState: vi.fn(),
  };
  return { Page: box, useVbenModal: () => [box, api] };
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
  return Object.fromEntries(
    [
      'Alert',
      'Button',
      'Checkbox',
      'CheckboxGroup',
      'Empty',
      'Input',
      'Pagination',
      'RadioGroup',
      'Select',
      'Spin',
      'Tag',
      'Tooltip',
      'Tree',
    ].map((name) => [name, box]),
  );
});
interface State {
  rows: MarketApi.Resource[];
  statistics?: MarketApi.MartStatistics;
  query: MarketApi.MartPageReq;
  keyword: string;
  loading: boolean;
  listError: string;
  favoriteBusy: Map<string, symbol>;
  loadAssets: () => Promise<void>;
  refresh: () => void;
  search: () => void;
  selectTopic: (key: string) => void;
  selectCategory: (keys: MarketApi.Id[]) => void;
  setSort: (value: 'LATEST' | 'POPULAR') => void;
  toggleFavorite: (row: MarketApi.Resource) => Promise<void>;
  openDetail: (row: MarketApi.Resource) => void;
  applyAsset: (row: MarketApi.Resource) => void;
}
let app: App | undefined;
let host: HTMLDivElement;
let state: State;
const visible = ref(true);
const row = (name = '新资产'): MarketApi.Resource => ({
  id: '9223372036854775807',
  assetName: name,
  marketName: '公开展示名',
  assetType: 1,
  status: 1,
  favorite: false,
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
  for (let index = 0; index < 12; index++) await Promise.resolve();
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
                ? h(Market, {
                    ref: (vm) => capture(vm as ComponentPublicInstance),
                  })
                : undefined,
          }),
      }),
    );
    app.mount(host);
  } else {
    app = createApp(Market);
    capture(app.mount(host));
  }
}
beforeEach(() => {
  vi.resetAllMocks();
  mock.access = reactive({
    accessToken: 'session',
    loginExpired: false,
    refreshToken: undefined as string | undefined,
    accessCodes: [
      'asset:market:query',
      'asset:market:favorite',
      'asset:market:apply',
    ],
  });
  mock.user = reactive({ userInfo: { userId: '1' } });
  mock.page.mockResolvedValue(page('当前资产'));
  mock.stats.mockResolvedValue({
    totalAssetCount: '9007199254740993',
    authorizedAssetCount: 2,
    favoriteAssetCount: 3,
    applyingAssetCount: 4,
  });
  mock.categories.mockResolvedValue([]);
  mock.topics.mockResolvedValue([]);
  mock.favorite.mockResolvedValue(true);
  host = document.createElement('div');
  document.body.append(host);
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
});
describe('asset marketplace', () => {
  it('恢复原图文介绍并保留类型筛选、搜索与热搜交互', async () => {
    mount();
    await flush();
    expect(host.querySelector('.mart-banner__intro h1')?.textContent).toBe(
      '数据资产集市',
    );
    expect(
      [...host.querySelectorAll<HTMLImageElement>('.mart-feature img')].map(
        (image) => image.getAttribute('src'),
      ),
    ).toEqual([
      '/assets/qdata-market/feature-rich.png',
      '/assets/qdata-market/feature-compliant.png',
      '/assets/qdata-market/feature-open.png',
    ]);
    expect(host.querySelector('.mart-banner__type')).not.toBeNull();
    expect(host.querySelector('[aria-label="搜索集市资产"]')).not.toBeNull();
    const hotSearch = [
      ...host.querySelectorAll<HTMLButtonElement>('.mart-hot-search button'),
    ].find((button) => button.textContent?.trim() === '水位预警')!;
    hotSearch.click();
    await flush();
    expect(mock.page).toHaveBeenLastCalledWith(
      expect.objectContaining({ keyword: '水位预警' }),
    );
  });

  it('opens the public directory for a signed-in user without query or write permissions', async () => {
    mock.access.accessCodes = [];
    mount();
    await flush();
    expect(mock.page).toHaveBeenCalledOnce();
    expect(mock.stats).toHaveBeenCalledOnce();
    expect(mock.categories).toHaveBeenCalledOnce();
    expect(state.rows[0]?.assetName).toBe('当前资产');
    await state.toggleFavorite(state.rows[0]!);
    state.applyAsset(state.rows[0]!);
    expect(mock.favorite).not.toHaveBeenCalled();
    expect(mock.apply).not.toHaveBeenCalled();
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
      expect(mock.stats).not.toHaveBeenCalled();
      expect(mock.categories).not.toHaveBeenCalled();
      expect(host.textContent).toContain('登录身份已失效');
    },
  );
  it('uses real counts and display fields without fabricated ratings', async () => {
    mount();
    await flush();
    expect(host.textContent).toContain('9,007,199,254,740,993');
    expect(host.textContent).toContain('公开展示名');
    expect(host.textContent).not.toContain('98.5');
    expect(state.rows[0]?.id).toBe('9223372036854775807');
  });
  it('passes independent market taxonomy, type arrays and search text without numeric id conversion', async () => {
    mount();
    await flush();
    state.query.assetTypes = [1, 3];
    state.query.authorizedOnly = true;
    state.query.favoriteOnly = true;
    state.keyword = '  业务  ';
    state.selectCategory(['9007199254740993']);
    await flush();
    expect(mock.page).toHaveBeenLastCalledWith(
      expect.objectContaining({
        keyword: '业务',
        marketCategoryId: '9007199254740993',
        assetTypes: [1, 3],
        authorizedOnly: true,
        favoriteOnly: true,
        pageNo: 1,
      }),
    );
    state.query.sort = 'FAVORITE';
    state.search();
    await flush();
    expect(mock.page).toHaveBeenLastCalledWith(
      expect.objectContaining({ sort: 'FAVORITE' }),
    );
  });
  it('ignores older list successes and failures', async () => {
    mount();
    await flush();
    const old = deferred<{ list: MarketApi.Resource[]; total: number }>();
    mock.page
      .mockReturnValueOnce(old.promise)
      .mockResolvedValueOnce(page('最新资产'));
    const previous = state.loadAssets();
    await state.loadAssets();
    old.reject(new Error('old'));
    await previous;
    expect(state.rows[0]?.assetName).toBe('最新资产');
    expect(state.listError).toBe('');
    expect(state.loading).toBe(false);
  });
  it('persists a favorite once and reloads list and counts after success', async () => {
    mount();
    await flush();
    const pending = deferred<boolean>();
    mock.favorite.mockReturnValue(pending.promise);
    const target = state.rows[0]!;
    const first = state.toggleFavorite(target);
    await state.toggleFavorite(target);
    expect(mock.favorite).toHaveBeenCalledTimes(1);
    expect(mock.favorite).toHaveBeenCalledWith({
      assetId: '9223372036854775807',
      favorite: true,
    });
    expect(mock.page).toHaveBeenCalledTimes(1);
    pending.resolve(true);
    await first;
    expect(mock.page).toHaveBeenCalledTimes(2);
    expect(mock.stats).toHaveBeenCalledTimes(2);
  });
  it('does not optimistically mark a failed favorite and permits a retry', async () => {
    mount();
    await flush();
    mock.favorite.mockRejectedValueOnce(new Error('failure'));
    const target = state.rows[0]!;
    await expect(state.toggleFavorite(target)).rejects.toThrow('failure');
    expect(target.favorite).toBe(false);
    expect(state.favoriteBusy.size).toBe(0);
    await state.toggleFavorite(target);
    expect(mock.favorite).toHaveBeenCalledTimes(2);
  });
  it('does not expose favorite writes without permission or for absent rows', async () => {
    mock.access.accessCodes = ['asset:market:query'];
    mount();
    await flush();
    await state.toggleFavorite(state.rows[0]!);
    expect(mock.favorite).not.toHaveBeenCalled();
    mock.access.accessCodes.push('asset:market:favorite');
    await flush();
    await state.toggleFavorite({ ...row(), id: '123' });
    expect(mock.favorite).not.toHaveBeenCalled();
  });
  it('rechecks apply permission and publication state before opening the shared form', async () => {
    mount();
    await flush();
    state.applyAsset({ ...row(), publicationNotice: '下架核验中' });
    expect(mock.apply).not.toHaveBeenCalled();
    state.applyAsset(row());
    expect(mock.apply).toHaveBeenCalledWith({
      assetId: '9223372036854775807',
      assetName: '公开展示名',
    });
    mock.access.accessCodes = ['asset:market:query'];
    await flush();
    mock.apply.mockClear();
    state.applyAsset(row());
    expect(mock.apply).not.toHaveBeenCalled();
  });
  it.each(['accessToken', 'refreshToken'] as const)(
    'invalidates stale results after a %s change',
    async (field) => {
      mount();
      await flush();
      const pending = deferred<{ list: MarketApi.Resource[]; total: number }>();
      mock.page
        .mockReturnValueOnce(pending.promise)
        .mockResolvedValue(page('新会话'));
      const previous = state.loadAssets();
      mock.access[field] = 'changed';
      await flush();
      pending.resolve(page('旧会话'));
      await previous;
      expect(state.rows[0]?.assetName).toBe('新会话');
    },
  );
  it('clears lists and dependent detail after logout', async () => {
    mount();
    await flush();
    const pending = deferred<{ list: MarketApi.Resource[]; total: number }>();
    mock.page.mockReturnValueOnce(pending.promise);
    const previous = state.loadAssets();
    mock.user.userInfo = null;
    expect(state.rows).toEqual([]);
    pending.resolve(page('旧权限'));
    await previous;
    expect(state.rows).toEqual([]);
    expect(state.statistics).toBeUndefined();
  });
  it('invalidates requests on deactivation and reloads on activation', async () => {
    mount(true);
    await flush();
    const pending = deferred<{ list: MarketApi.Resource[]; total: number }>();
    mock.page.mockReturnValueOnce(pending.promise);
    const previous = state.loadAssets();
    visible.value = false;
    await flush();
    pending.resolve(page('停用页面'));
    await previous;
    expect(state.rows).toEqual([]);
    visible.value = true;
    await flush();
    expect(state.rows[0]?.assetName).toBe('当前资产');
  });
  it('does not reload after an old owner favorite completes', async () => {
    mount();
    await flush();
    const pending = deferred<boolean>();
    mock.favorite.mockReturnValueOnce(pending.promise);
    const previous = state.toggleFavorite(state.rows[0]!);
    mock.access.accessToken = 'new';
    await flush();
    const requests = mock.page.mock.calls.length;
    pending.resolve(true);
    await previous;
    expect(mock.page).toHaveBeenCalledTimes(requests);
  });
  it('invalidates outstanding responses when unmounted', async () => {
    mount();
    await flush();
    const pending = deferred<{ list: MarketApi.Resource[]; total: number }>();
    mock.page.mockReturnValueOnce(pending.promise);
    const previous = state.loadAssets();
    app?.unmount();
    app = undefined;
    pending.resolve(page('已卸载'));
    await previous;
    expect(state.rows).toEqual([]);
  });
  it('formats long counts exactly and rejects unsafe numeric input', () => {
    expect(marketCount('9223372036854775807')).toBe(
      '9,223,372,036,854,775,807',
    );
    expect(marketCount(Number.MAX_SAFE_INTEGER + 1)).toBe('--');
    expect(marketCount(-1)).toBe('--');
    expect(marketCount(undefined)).toBe('--');
  });
  it('专题切换保持排序和资产筛选，回到第一页刷新', async () => {
    mount();
    await flush();
    state.query.sort = 'FAVORITE';
    state.query.pageNo = 2;
    state.selectTopic('NEW');
    await flush();
    expect(mock.page).toHaveBeenLastCalledWith(
      expect.objectContaining({ sort: 'FAVORITE', pageNo: 1 }),
    );
  });
  it('显示文件真实名称类型与服务标识', async () => {
    mock.page.mockResolvedValue({
      list: [
        {
          ...row(),
          id: '1',
          assetType: 1,
          primaryDeliveryType: 3,
          fileName: '水位.csv',
          fileType: 'CSV文件(.csv)',
        },
        {
          ...row(),
          id: '2',
          assetType: 1,
          primaryDeliveryType: 2,
          serviceIdentifier: '49',
        },
      ],
      total: 2,
    });
    mount();
    await flush();
    expect(host.textContent).toContain('水位.csv');
    expect(host.textContent).toContain('CSV文件(.csv)');
    expect(host.textContent).toContain('服务标识：49');
  });
});
