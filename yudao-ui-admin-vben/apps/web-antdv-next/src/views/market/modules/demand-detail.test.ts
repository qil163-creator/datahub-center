import type { App, SetupContext } from 'vue';

import type { MarketApi } from '#/api/asset/market';

import { createApp, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import DemandDetail from './demand-detail.vue';

const harness = vi.hoisted(() => ({
  access: {} as {
    accessCodes: string[];
    accessToken: string;
    loginExpired: boolean;
    refreshToken: string;
  },
  user: { userInfo: { userId: '1' }, userRoles: [] as string[] },
  data: {} as { id: MarketApi.Id; mode: 'hall' | 'match' | 'mine' },
  onOpenChange: (_open: boolean): Promise<void> => Promise.resolve(),
  getDemand: vi.fn(),
  getSuggestions: vi.fn(),
  getResources: vi.fn(),
  accept: vi.fn(),
  success: vi.fn(),
  setState: vi.fn(),
}));

vi.mock('@vben/stores', () => ({
  useAccessStore: () => harness.access,
  useUserStore: () => harness.user,
}));
vi.mock('@vben/common-ui', () => ({
  useVbenDrawer: (options: { onOpenChange: typeof harness.onOpenChange }) => {
    harness.onOpenChange = options.onOpenChange;
    return [
      (_: unknown, { slots }: SetupContext) => h('section', slots.default?.()),
      { getData: () => harness.data, setState: harness.setState },
    ];
  },
}));
vi.mock('antdv-next', () => ({
  Button: (_: unknown, { attrs, slots }: SetupContext) =>
    h('button', attrs, slots.default?.()),
  Empty: () => null,
  Input: () => null,
  Select: () => null,
  Tag: (_: unknown, { slots }: SetupContext) => h('span', slots.default?.()),
  TextArea: () => null,
  message: { success: vi.fn(), warning: vi.fn() },
}));
vi.mock('#/api/asset/market', () => ({
  acceptMarketDemand: harness.accept,
  confirmMarketDemandMatch: vi.fn(),
  getMarketDemand: harness.getDemand,
  getMarketResourcePage: harness.getResources,
  matchMarketDemand: vi.fn(),
  replyMarketDemand: vi.fn(),
  suggestMarketDemandAssets: harness.getSuggestions,
}));
vi.mock('../data', () => ({
  DELIVERY_OPTIONS: [],
  DEMAND_STATUS_OPTIONS: [],
  DEMAND_TYPE_OPTIONS: [],
  MATCH_STATUS_OPTIONS: [],
  REPLY_TYPE_OPTIONS: [],
  URGENCY_OPTIONS: [],
  formatMarketTime: () => '',
  optionColor: () => 'default',
  optionLabel: (_options: unknown, value?: number) => String(value ?? ''),
}));
vi.mock('#/views/modeling/modelcommon/detail-field-form.vue', () => ({
  default: Object.assign(
    (props: { fields: { value?: unknown }[] }) =>
      h(
        'div',
        props.fields.map((field) => String(field.value ?? '')).join(' '),
      ),
    { props: ['fields'] },
  ),
}));

let app: App | undefined;
let host: HTMLDivElement;
interface State {
  assetOptions: { label: string; value: string }[];
  assetsLoading: boolean;
  assetError: string;
  matchAssetId: MarketApi.Id | undefined;
  matchNote: string;
  searchAssets: (keyword: string) => Promise<void>;
  changeAsset: (value: MarketApi.Id) => void;
  openAssets: (open: boolean) => void;
  loadMoreAssets: (event: { target: HTMLElement }) => void;
}
let state: State;

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((finish) => {
    resolve = finish;
  });
  return { promise, resolve };
}

async function flush() {
  for (let index = 0; index < 6; index++) await nextTick();
}

async function mount() {
  host = document.createElement('div');
  app = createApp(DemandDetail, { onSuccess: harness.success });
  state = (app.mount(host).$ as unknown as { setupState: State }).setupState;
  await harness.onOpenChange(true);
  await nextTick();
}

function clickAccept() {
  const button = [...host.querySelectorAll('button')].find(
    (item) => item.textContent?.trim() === '确认验收',
  );
  expect(button).toBeDefined();
  button!.click();
}

beforeEach(() => {
  vi.clearAllMocks();
  harness.access = reactive({
    accessToken: '1',
    refreshToken: '',
    accessCodes: ['asset:market:query'],
    loginExpired: false,
  });
  harness.user = reactive({ userInfo: { userId: '1' }, userRoles: [] });
  harness.data = { id: '9223372036854775807', mode: 'mine' };
  harness.getDemand.mockImplementation(async (id: MarketApi.Id) => ({
    id,
    title: `需求${id}`,
    status: 3,
    acceptanceStatus: 0,
  }));
  harness.getSuggestions.mockResolvedValue([]);
  harness.getResources.mockResolvedValue({ list: [] });
  harness.accept.mockResolvedValue(undefined);
});

afterEach(() => {
  app?.unmount();
  app = undefined;
  host?.remove();
});

describe('demand detail mutation refresh', () => {
  it('refreshes the parent after a successful acceptance and reloads the same lossless ID', async () => {
    await mount();
    clickAccept();
    await flush();
    expect(harness.accept).toHaveBeenCalledWith('9223372036854775807');
    expect(harness.success).toHaveBeenCalledOnce();
    expect(harness.getDemand.mock.calls).toEqual([
      ['9223372036854775807'],
      ['9223372036854775807'],
    ]);
  });

  it('refreshes the parent when acceptance completes after closing without repopulating detail', async () => {
    const pending = deferred<undefined>();
    harness.accept.mockReturnValueOnce(pending.promise);
    await mount();
    clickAccept();
    await harness.onOpenChange(false);
    pending.resolve(undefined);
    await flush();
    expect(harness.success).toHaveBeenCalledOnce();
    expect(harness.getDemand).toHaveBeenCalledOnce();
    expect(host.textContent).not.toContain('需求9223372036854775807');
  });

  it('refreshes the parent after switching demand without reloading the previous ID', async () => {
    const pending = deferred<undefined>();
    harness.accept.mockReturnValueOnce(pending.promise);
    await mount();
    clickAccept();
    harness.data = { id: '9223372036854775806', mode: 'mine' };
    await harness.onOpenChange(true);
    pending.resolve(undefined);
    await flush();
    expect(harness.success).toHaveBeenCalledOnce();
    expect(harness.getDemand.mock.calls).toEqual([
      ['9223372036854775807'],
      ['9223372036854775806'],
    ]);
    expect(host.textContent).toContain('需求9223372036854775806');
    expect(host.textContent).not.toContain('需求9223372036854775807');
  });

  it('refreshes for every concurrent successful mutation', async () => {
    const first = deferred<undefined>();
    const second = deferred<undefined>();
    harness.accept
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(second.promise);
    await mount();
    clickAccept();
    clickAccept();
    first.resolve(undefined);
    await flush();
    expect(harness.success).toHaveBeenCalledOnce();
    expect(harness.getDemand).toHaveBeenCalledOnce();
    second.resolve(undefined);
    await flush();
    expect(harness.success).toHaveBeenCalledTimes(2);
    expect(harness.getDemand).toHaveBeenCalledTimes(2);
  });

  it('ignores acceptance completed after switching session and back', async () => {
    const pending = deferred<undefined>();
    harness.accept.mockReturnValueOnce(pending.promise);
    await mount();
    clickAccept();
    harness.access.refreshToken = '2';
    harness.access.refreshToken = '';
    pending.resolve(undefined);
    await flush();
    expect(harness.success).not.toHaveBeenCalled();
    expect(harness.getDemand).toHaveBeenCalledOnce();
  });

  it('ignores acceptance completed after unmounting', async () => {
    const pending = deferred<undefined>();
    harness.accept.mockReturnValueOnce(pending.promise);
    await mount();
    clickAccept();
    app?.unmount();
    app = undefined;
    pending.resolve(undefined);
    await flush();
    expect(harness.success).not.toHaveBeenCalled();
    expect(harness.getDemand).toHaveBeenCalledOnce();
  });

  it('refreshes new mutations after reopening under another session', async () => {
    await mount();
    harness.access.refreshToken = '2';
    await harness.onOpenChange(true);
    await nextTick();
    clickAccept();
    await flush();
    expect(harness.success).toHaveBeenCalledOnce();
    expect(harness.getDemand).toHaveBeenCalledTimes(3);
  });

  it('keeps loaded demand and recommendations visible when asset options fail', async () => {
    harness.getSuggestions.mockResolvedValue([
      { id: '9223372036854775805', assetName: '已推荐资产' },
    ]);
    harness.getResources.mockRejectedValueOnce(new Error('资产选项暂不可用'));
    await expect(mount()).resolves.toBeUndefined();
    await nextTick();
    expect(host.textContent).toContain('需求9223372036854775807');
    expect(host.textContent).toContain('已推荐资产');
    expect(harness.setState).toHaveBeenLastCalledWith({ loading: false });
  });
});

describe('demand matching asset candidates', () => {
  function bottom() {
    state.loadMoreAssets({
      target: {
        scrollHeight: 200,
        scrollTop: 180,
        clientHeight: 20,
      } as HTMLElement,
    });
  }

  it('loads assets after the first twenty with lossless option values and deduplicates overlapping pages', async () => {
    const firstPage = Array.from({ length: 20 }, (_, i) => ({
      id: String(i + 1),
      assetName: `资产${i + 1}`,
    }));
    harness.getResources
      .mockResolvedValueOnce({ list: firstPage, total: 22 })
      .mockResolvedValueOnce({
        list: [
          firstPage[19],
          { id: '9223372036854775807', assetName: '第21条' },
          { id: '9223372036854775806', assetName: '第22条' },
        ],
        total: 22,
      });
    await mount();
    bottom();
    await flush();
    expect(harness.getResources).toHaveBeenLastCalledWith({
      pageNo: 2,
      pageSize: 20,
      keyword: undefined,
    });
    expect(state.assetOptions).toHaveLength(22);
    expect(state.assetOptions).toContainEqual({
      label: '第21条',
      value: '9223372036854775807',
    });
    bottom();
    expect(harness.getResources).toHaveBeenCalledTimes(2);
  });

  it('uses server search and ignores out-of-order results while retaining the selected label', async () => {
    harness.getResources.mockResolvedValueOnce({
      list: [{ id: '9223372036854775807', assetName: '已选择资产' }],
      total: 1,
    });
    await mount();
    state.matchAssetId = '9223372036854775807';
    state.changeAsset(state.matchAssetId);
    const first = deferred<{ list: object[]; total: number }>();
    const last = deferred<{ list: object[]; total: number }>();
    harness.getResources
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(last.promise);
    const a = state.searchAssets('河流');
    const b = state.searchAssets('水库');
    last.resolve({
      list: [{ id: '9223372036854775806', assetName: '水库资产' }],
      total: 1,
    });
    await b;
    first.resolve({ list: [{ id: '1', assetName: '旧搜索资产' }], total: 1 });
    await a;
    expect(harness.getResources).toHaveBeenLastCalledWith({
      pageNo: 1,
      pageSize: 20,
      keyword: '水库',
    });
    expect(state.assetOptions).toEqual([
      { label: '水库资产', value: '9223372036854775806' },
      { label: '已选择资产', value: '9223372036854775807' },
    ]);
    expect(state.matchAssetId).toBe('9223372036854775807');
    expect(state.assetsLoading).toBe(false);
  });

  it.each(['close', 'session', 'permission', 'user'] as const)(
    'rejects a candidate response after %s changes',
    async (boundary) => {
      await mount();
      const pending = deferred<{ list: object[]; total: number }>();
      harness.getResources.mockReturnValueOnce(pending.promise);
      const task = state.searchAssets('pending');
      if (boundary === 'close') await harness.onOpenChange(false);
      if (boundary === 'session') harness.access.refreshToken = 'new';
      if (boundary === 'permission') harness.access.accessCodes = [];
      if (boundary === 'user') harness.user.userInfo.userId = '2';
      pending.resolve({
        list: [{ id: '9223372036854775807', assetName: '旧上下文资产' }],
        total: 1,
      });
      await task;
      expect(state.assetOptions).toEqual([]);
      expect(state.assetsLoading).toBe(false);
    },
  );

  it('keeps the selected asset, label and note across a same-user token refresh', async () => {
    harness.getResources.mockResolvedValueOnce({
      list: [{ id: '9223372036854775807', assetName: '已选择资产' }],
      total: 1,
    });
    await mount();
    state.matchAssetId = '9223372036854775807';
    state.changeAsset(state.matchAssetId);
    state.matchNote = '尚未提交的匹配说明';
    const pending = deferred<{ list: object[]; total: number }>();
    harness.getResources.mockReturnValueOnce(pending.promise);
    const task = state.searchAssets('下一条');
    harness.access.accessToken = 'refreshed-access';
    harness.access.refreshToken = 'refreshed-refresh';
    pending.resolve({ list: [{ id: '1', assetName: '旧会话响应' }], total: 1 });
    await task;
    expect(state.matchAssetId).toBe('9223372036854775807');
    expect(state.matchNote).toBe('尚未提交的匹配说明');
    expect(state.assetOptions).toEqual([
      { value: '9223372036854775807', label: '已选择资产' },
    ]);
    expect(state.assetsLoading).toBe(false);
    expect(host.textContent).toContain('需求9223372036854775807');
  });

  it('retries the same failed continuation page without losing loaded or selected candidates', async () => {
    const firstPage = Array.from({ length: 20 }, (_, i) => ({
      id: String(i + 1),
      assetName: `资产${i + 1}`,
    }));
    harness.getResources.mockResolvedValueOnce({ list: firstPage, total: 21 });
    await mount();
    state.matchAssetId = '1';
    state.changeAsset('1');
    harness.getResources
      .mockRejectedValueOnce(new Error('暂不可用'))
      .mockResolvedValueOnce({
        list: [{ id: '21', assetName: '资产21' }],
        total: 21,
      });
    bottom();
    await flush();
    expect(state.assetOptions).toHaveLength(20);
    expect(state.assetError).toContain('读取失败');
    state.openAssets(true);
    await flush();
    expect(
      harness.getResources.mock.calls
        .slice(-2)
        .map(([params]) => params.pageNo),
    ).toEqual([2, 2]);
    expect(state.assetOptions).toHaveLength(21);
    expect(state.matchAssetId).toBe('1');
    expect(state.assetError).toBe('');
  });

  it('uses an existing match name when displaying a selected asset outside the current page', async () => {
    harness.getDemand.mockResolvedValueOnce({
      id: '1',
      matches: [{ assetId: '9223372036854775807', assetName: '已有匹配资产' }],
    });
    await mount();
    state.matchAssetId = '9223372036854775807';
    expect(state.assetOptions).toContainEqual({
      value: '9223372036854775807',
      label: '已有匹配资产',
    });
  });
});
