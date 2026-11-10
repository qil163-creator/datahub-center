/* eslint-disable vue/one-component-per-file -- 集中声明消费页测试桩 */
import type { App, ComponentPublicInstance } from 'vue';

import type { MyAssetsApi } from '#/api/asset/my-assets';

import {
  computed,
  createApp,
  defineComponent,
  h,
  KeepAlive,
  nextTick,
  onBeforeUnmount,
  onMounted,
  reactive,
  ref,
} from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { accessState, canOpenDelivery } from './data';
import MyAssets from './index.vue';

const mock = vi.hoisted(() => ({
  access: {
    accessToken: 'session',
    refreshToken: '',
    loginExpired: false,
    accessCodes: ['asset:market:my-assets:query'],
  },
  user: {
    userInfo: { userId: '1' } as null | { userId: string },
    userRoles: [] as string[],
  },
  page: vi.fn(),
  categories: vi.fn(),
  preview: vi.fn(),
  detailOpen: vi.fn(),
  detailClose: vi.fn(),
  documentOpen: vi.fn(),
  documentClose: vi.fn(),
  detailCurrentId: undefined as string | undefined,
  route: { name: 'AssetMarketMyAssets', query: {} as Record<string, unknown> },
  modalData: vi.fn(),
  modalOpen: vi.fn(),
  modalState: vi.fn(),
  viewport: { isMobile: false },
  gridOptions: undefined as any,
  setGridOptions: vi.fn(),
  setLoading: vi.fn(),
}));

vi.mock('#/adapter/business-vxe-table', () => ({
  useVbenVxeGrid: (options: { gridOptions: any }) => {
    mock.gridOptions = options.gridOptions;
    const Grid = defineComponent({
      props: { tableData: { type: Array, default: () => [] } },
      setup: (props, { slots }) => () =>
        h('div', { 'data-testid': 'asset-grid' }, [
          props.tableData.length
            ? props.tableData.flatMap((row) =>
                options.gridOptions.columns.map((column: { field: string }) =>
                  h('div', { 'data-field': column.field }, slots.body?.({ column, row })),
                ),
              )
            : slots.empty?.(),
        ]),
    });
    return [Grid, { setGridOptions: mock.setGridOptions, setLoading: mock.setLoading }];
  },
}));

vi.mock('#/adapter/form', () => ({
  useVbenForm: (options: {
    handleSubmit: () => void;
    handleValuesChange: (values: Record<string, unknown>) => void;
    submitOnEnter?: boolean;
  }) => {
    const values = reactive<Record<string, unknown>>({});
    const api = {
      isMounted: false,
      reset: async () => {
        for (const key of Object.keys(values)) values[key] = undefined;
        options.handleValuesChange({ ...values });
      },
      setValues: async (nextValues: Record<string, unknown>) => {
        Object.assign(values, nextValues);
        options.handleValuesChange({ ...values });
      },
      validateAndSubmit: async () => {
        options.handleValuesChange({ ...values });
        options.handleSubmit();
      },
    };
    const Form = defineComponent({
      setup: (_, { slots }) => {
        onMounted(() => {
          api.isMounted = true;
        });
        onBeforeUnmount(() => {
          api.isMounted = false;
        });
        return () =>
          h('section', [
            h('input', {
              'data-testid': 'native-search-keyword',
              value: values.keyword ?? '',
              onInput: (event: Event) => {
                values.keyword = (event.target as HTMLInputElement).value;
                options.handleValuesChange({ ...values });
              },
              onKeydown: (event: KeyboardEvent) => {
                if (event.key === 'Enter' && options.submitOnEnter) {
                  event.preventDefault();
                  void api.validateAndSubmit();
                }
              },
            }),
            slots['reset-before']?.(),
          ]);
      },
    });
    return [Form, api];
  },
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
vi.mock('@vben/icons', () => ({ IconifyIcon: () => null }));
vi.mock('vue-router', () => ({
  useRoute: () => mock.route,
  useRouter: vi.fn(),
}));
vi.mock('@vben/hooks', () => ({
  useIsMobile: () => ({ isMobile: computed(() => mock.viewport.isMobile) }),
}));
vi.mock('#/api/asset/my-assets', () => ({
  getMyAssetsPage: mock.page,
  previewMyAsset: mock.preview,
}));
vi.mock('#/api/asset/marketcategory', () => ({
  getMarketCategorySimpleList: mock.categories,
}));
vi.mock('./modules/detail.vue', () => ({
  default: defineComponent({
    setup: (_, { expose }) => {
      expose({
        open: mock.detailOpen,
        close: mock.detailClose,
        isCurrent: (row: MyAssetsApi.Detail) =>
          String(row.applyId) === mock.detailCurrentId,
      });
      return () => null;
    },
  }),
}));
vi.mock('./modules/api-document.vue', () => ({
  default: defineComponent({
    setup: (_, { expose }) => {
      expose({ open: mock.documentOpen, close: mock.documentClose });
      return () => null;
    },
  }),
}));
vi.mock('./modules/my-applications.vue', () => ({
  default: { render: () => null },
}));
vi.mock('#/views/asset/market/modules/preview-drawer.vue', () => ({
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
    setData: (data: unknown) => {
      mock.modalData(data);
      return api;
    },
    open: mock.modalOpen,
    setState: mock.modalState,
  };
  return { Page: box, useVbenModal: () => [box, api] };
});
vi.mock('antdv-next', () => {
  const box = defineComponent({
    props: { message: String, description: String },
    setup:
      (props, { slots }) =>
      () =>
        h('div', [props.message, props.description, slots.default?.()]),
  });
  return Object.fromEntries(
    [
      'Alert',
      'Button',
      'Empty',
      'Input',
      'Pagination',
      'Select',
      'Table',
      'Tag',
      'Tooltip',
      'TreeSelect',
    ].map((name) => [name, box]),
  );
});

interface State {
  activeTab: 'applications' | 'assets';
  columns: { field: string; fixed?: string; width: number }[];
  rows: MyAssetsApi.Asset[];
  query: MyAssetsApi.PageReq;
  filters: {
    deliveryType?: number;
    keyword: string;
    marketCategoryId?: MyAssetsApi.Id;
  };
  loading: boolean;
  listError: string;
  categoryError: string;
  childEpoch: number;
  categories: unknown[];
  total: number;
  linkedAssetError: string;
  selectedDetailId?: string;
  loadAssets: () => Promise<void>;
  loadCategories: () => Promise<void>;
  search: () => void;
  resetFilters: () => void;
  changePage: (page: number, size: number) => void;
  openDetail: (row: MyAssetsApi.Asset) => void;
  openPreview: (row: MyAssetsApi.Asset) => void;
  openApiDocument: (row: MyAssetsApi.Asset) => void;
  openDetailPreview: (row: MyAssetsApi.Detail) => void;
  openDetailApiDocument: (row: MyAssetsApi.Detail) => void;
  detailClosed: (id: string) => void;
}
let app: App;
let host: HTMLDivElement;
let state: State;
const visible = ref(true);
function capture(vm: ComponentPublicInstance | null) {
  if (vm) state = (vm.$ as unknown as { setupState: State }).setupState;
}
function mount(kept = false) {
  visible.value = true;
  app = kept
    ? createApp(
        defineComponent({
          setup: () => () =>
            h(KeepAlive, null, {
              default: () =>
                visible.value
                  ? h(MyAssets, {
                      ref: (vm) => capture(vm as ComponentPublicInstance),
                    })
                  : undefined,
            }),
        }),
      )
    : createApp(MyAssets);
  const vm = app.mount(host);
  if (!kept) capture(vm);
}
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
function asset(applyId = '9223372036854775807'): MyAssetsApi.Asset {
  return {
    applyId,
    assetId: '9007199254740993',
    marketName: '门诊资产',
    deliveryType: 1,
    accessStatus: 'AVAILABLE',
    usable: true,
    canPreview: true,
  };
}
beforeEach(() => {
  vi.resetAllMocks();
  mock.route = reactive({
    name: 'AssetMarketMyAssets',
    query: {} as Record<string, unknown>,
  });
  mock.detailCurrentId = undefined;
  mock.detailOpen.mockImplementation((id: string) => {
    mock.detailCurrentId = id;
  });
  mock.detailClose.mockImplementation(() => {
    mock.detailCurrentId = undefined;
  });
  mock.viewport = reactive({ isMobile: false });
  mock.access = reactive({
    accessToken: 'session',
    refreshToken: '',
    loginExpired: false,
    accessCodes: ['asset:market:my-assets:query'],
  });
  mock.user = reactive({
    userInfo: { userId: '1' },
    userRoles: [] as string[],
  });
  mock.page.mockResolvedValue({ list: [asset()], total: 1 });
  mock.categories.mockResolvedValue([]);
  host = document.createElement('div');
  document.body.append(host);
});
afterEach(() => {
  app?.unmount();
  host.remove();
});

describe('我的资产工作台', () => {
  it('业务表格展示当前资产并保留按申请编号打开预览', async () => {
    mount();
    await flush();
    expect(mock.gridOptions.rowConfig.keyField).toBe('applyId');
    expect(mock.gridOptions.scrollbarConfig.height).toBe(6);
    const actionCell = host.querySelector('[data-field="actions"]')!;
    expect(actionCell.textContent).toContain('数据预览');
    const preview = [...actionCell.querySelectorAll('div')]
      .filter((element) => element.textContent?.trim() === '数据预览')
      .at(-1);
    preview?.click();
    expect(mock.modalData).toHaveBeenCalledWith({
      requestId: '9223372036854775807',
      title: '门诊资产 · 数据预览',
    });
  });
  it('空列表保留资产空状态', async () => {
    mock.page.mockResolvedValue({ list: [], total: 0 });
    mount();
    await flush();
    expect(host.querySelector('[data-testid="asset-grid"]')?.textContent).toContain(
      '暂无符合条件的已批准资产服务',
    );
  });
  it('从申请记录跳转授权资产深链时切换页签并打开对应详情', async () => {
    mount();
    await flush();
    state.activeTab = 'applications';
    mock.route.query.applyId = '9223372036854775807';
    await flush();
    expect(state.activeTab).toBe('assets');
    expect(mock.detailOpen).toHaveBeenCalledWith('9223372036854775807');
  });
  function inputKeyword(value: string) {
    const input = host.querySelector<HTMLInputElement>(
      '[data-testid="native-search-keyword"]',
    )!;
    input.value = value;
    input.dispatchEvent(new Event('input', { bubbles: true }));
    return input;
  }
  it('原生搜索表单保留 Enter 提交并使用当前输入', async () => {
    mount();
    await flush();
    state.query.pageNo = 3;
    const input = inputKeyword('  门诊新条件  ');
    input.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }),
    );
    await flush();
    expect(mock.page).toHaveBeenLastCalledWith(
      expect.objectContaining({ pageNo: 1, keyword: '门诊新条件' }),
    );
  });
  it('身份清理同步原生表单内部值，后续提交不会恢复旧筛选', async () => {
    mount();
    await flush();
    inputKeyword('旧身份条件');
    mock.access.accessToken = 'new-session';
    await flush();
    const input = host.querySelector<HTMLInputElement>(
      '[data-testid="native-search-keyword"]',
    )!;
    expect(input.value).toBe('');
    input.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }),
    );
    await flush();
    expect(mock.page).toHaveBeenLastCalledWith(
      expect.objectContaining({ keyword: undefined }),
    );
    expect(state.filters.keyword).toBe('');
  });
  it('缓存切页保留未提交的搜索输入，显式重置才清空表单和查询', async () => {
    mount(true);
    await flush();
    inputKeyword('未提交条件');
    visible.value = false;
    await flush();
    visible.value = true;
    await flush();
    expect(
      host.querySelector<HTMLInputElement>(
        '[data-testid="native-search-keyword"]',
      )!.value,
    ).toBe('未提交条件');
    expect(state.filters.keyword).toBe('未提交条件');
    expect(state.query.keyword).toBeUndefined();
    state.resetFilters();
    await flush();
    expect(
      host.querySelector<HTMLInputElement>(
        '[data-testid="native-search-keyword"]',
      )!.value,
    ).toBe('');
    expect(state.filters.keyword).toBe('');
    expect(state.query.keyword).toBeUndefined();
  });

  it('深链按ID打开非当前页记录，保留筛选且不自动执行消费', async () => {
    mock.route.query.applyId = '9223372036854775800';
    mount();
    await flush();
    expect(mock.detailOpen).toHaveBeenCalledTimes(1);
    expect(mock.detailOpen).toHaveBeenCalledWith('9223372036854775800');
    expect(
      state.rows.every((row) => row.applyId !== '9223372036854775800'),
    ).toBe(true);
    expect(mock.modalOpen).not.toHaveBeenCalled();
    expect(mock.documentOpen).not.toHaveBeenCalled();
    state.filters.keyword = '保留条件';
    state.query.pageNo = 3;
    mock.route.query.applyId = '9223372036854775801';
    await flush();
    expect(mock.detailOpen).toHaveBeenLastCalledWith('9223372036854775801');
    expect(state.filters.keyword).toBe('保留条件');
    expect(state.query.pageNo).toBe(3);
    expect(mock.page).toHaveBeenCalledTimes(1);
    mock.route.query.applyId = '9223372036854775801';
    await flush();
    expect(mock.detailOpen).toHaveBeenCalledTimes(2);
    mock.route.query.applyId = '9223372036854775800';
    await flush();
    expect(mock.detailOpen).toHaveBeenCalledTimes(3);
  });
  it.each([null, '', '0', 'abc', ['1'], '9223372036854775808'])(
    '非法深链%s提示而不打开其它资产',
    async (id) => {
      mock.route.query.applyId = id;
      mount();
      await flush();
      expect(state.linkedAssetError).toContain('编号无效');
      expect(mock.detailOpen).not.toHaveBeenCalled();
      expect(mock.modalOpen).not.toHaveBeenCalled();
    },
  );
  it('同批路由切换只打开最新记录，其它页面query不触发缓存页详情', async () => {
    mount();
    await flush();
    mock.route.query.applyId = '2';
    mock.route.query.applyId = '3';
    await flush();
    expect(mock.detailOpen).toHaveBeenCalledTimes(1);
    expect(mock.detailOpen).toHaveBeenLastCalledWith('3');
    mock.route.name = 'MarketApply';
    mock.route.query.applyId = '4';
    await flush();
    expect(mock.detailOpen).toHaveBeenCalledTimes(1);
    expect(state.selectedDetailId).toBeUndefined();
    mock.route.name = 'AssetMarketMyAssets';
    await flush();
    expect(mock.detailOpen).toHaveBeenLastCalledWith('4');
  });
  it('非当前分页详情可显式消费，已关闭或旧详情事件不能使用其它申请', async () => {
    const target = { ...asset('9223372036854775800') };
    mock.route.query.applyId = target.applyId;
    mount();
    await flush();
    state.openDetailPreview(target);
    expect(mock.modalData).toHaveBeenCalledWith({
      requestId: target.applyId,
      title: '门诊资产 · 数据预览',
    });
    state.openDetailApiDocument({
      ...target,
      deliveryType: 2,
      canViewApiDocument: true,
    });
    expect(mock.documentOpen).toHaveBeenCalledWith(target.applyId);
    mock.modalOpen.mockClear();
    state.detailClosed(String(target.applyId));
    state.openDetailPreview(target);
    expect(mock.modalOpen).not.toHaveBeenCalled();
    mock.route.query.applyId = '9223372036854775801';
    await flush();
    state.openDetailPreview(target);
    expect(mock.modalOpen).not.toHaveBeenCalled();
  });
  it('深链待nextTick时撤权取消打开，恢复权限后仅按当前上下文读取一次', async () => {
    mount();
    await flush();
    mock.route.query.applyId = '8';
    mock.access.accessCodes = [];
    await flush();
    expect(mock.detailOpen).not.toHaveBeenCalled();
    mock.access.accessCodes = ['asset:market:my-assets:query'];
    await flush();
    expect(mock.detailOpen).toHaveBeenCalledTimes(1);
    expect(mock.detailOpen).toHaveBeenLastCalledWith('8');
    mock.access.accessToken = 'next-user';
    mock.user.userInfo = { userId: '2' };
    await flush();
    expect(mock.detailOpen).toHaveBeenCalledTimes(2);
  });
  it('窄屏保留右侧固定操作并收紧编号列，恢复宽屏时保持固定', async () => {
    mount();
    await flush();
    expect(
      state.columns.find((column) => column.field === 'actions')?.fixed,
    ).toBe('right');
    mock.viewport.isMobile = true;
    await flush();
    expect(
      state.columns.find((column) => column.field === 'actions')?.fixed,
    ).toBe('right');
    expect(
      state.columns.find((column) => column.field === 'applyNo')?.width,
    ).toBe(112);
    mock.viewport.isMobile = false;
    await flush();
    expect(
      state.columns.find((column) => column.field === 'actions')?.fixed,
    ).toBe('right');
  });
  it('只需个人消费权限，详情和预览传申请ID而不是资产ID', async () => {
    mount();
    await flush();
    expect(mock.page).toHaveBeenCalledTimes(1);
    state.openDetail(state.rows[0]!);
    state.openPreview(state.rows[0]!);
    expect(mock.detailOpen).toHaveBeenCalledWith('9223372036854775807');
    expect(mock.modalData).toHaveBeenCalledWith({
      requestId: '9223372036854775807',
      title: '门诊资产 · 数据预览',
    });
  });
  it('未持个人权限不读取列表和类目', async () => {
    mock.access.accessCodes = ['asset:market:query'];
    mount();
    await flush();
    expect(mock.page).not.toHaveBeenCalled();
    expect(mock.categories).not.toHaveBeenCalled();
    expect(host.textContent).toContain('当前身份无权查看');
  });
  it('接口文档只按该申请的可用标记打开，交付未确认时保守拒绝', async () => {
    const apiAsset = { ...asset(), deliveryType: 2, canViewApiDocument: true };
    mock.page.mockResolvedValue({ list: [apiAsset], total: 1 });
    mount();
    await flush();
    state.openApiDocument(state.rows[0]!);
    expect(mock.documentOpen).toHaveBeenCalledWith('9223372036854775807');
    mock.documentOpen.mockClear();
    state.rows[0]!.accessStatus = 'DELIVERY_UNKNOWN';
    state.openApiDocument(state.rows[0]!);
    expect(mock.documentOpen).not.toHaveBeenCalled();
  });
  it('新查询回第一页，分页不夹带尚未提交的筛选，改变页容量重置第一页', async () => {
    mount();
    await flush();
    state.filters.keyword = '  门诊  ';
    state.filters.deliveryType = 2;
    state.filters.marketCategoryId = '9223372036854775806';
    state.query.pageNo = 8;
    state.search();
    await flush();
    expect(mock.page).toHaveBeenLastCalledWith({
      pageNo: 1,
      pageSize: 10,
      keyword: '门诊',
      deliveryType: 2,
      marketCategoryId: '9223372036854775806',
    });
    state.filters.keyword = '未提交';
    state.changePage(2, 10);
    await flush();
    expect(mock.page).toHaveBeenLastCalledWith(
      expect.objectContaining({ keyword: '门诊', pageNo: 2 }),
    );
    state.changePage(2, 20);
    await flush();
    expect(state.query.pageNo).toBe(1);
    state.resetFilters();
    await flush();
    expect(mock.page).toHaveBeenLastCalledWith({
      pageNo: 1,
      pageSize: 20,
      keyword: undefined,
      deliveryType: undefined,
      marketCategoryId: undefined,
    });
  });
  it('旧列表晚到和旧请求finally不能覆盖新查询或提前停止loading', async () => {
    const older = deferred<{ list: MyAssetsApi.Asset[]; total: number }>();
    const newer = deferred<{ list: MyAssetsApi.Asset[]; total: number }>();
    mock.page
      .mockReturnValueOnce(older.promise)
      .mockReturnValueOnce(newer.promise);
    mount();
    await flush();
    state.search();
    await flush();
    older.resolve({ list: [asset('2')], total: 100 });
    await flush();
    expect(state.rows).toEqual([]);
    expect(state.loading).toBe(true);
    newer.resolve({ list: [asset('3')], total: 1 });
    await flush();
    expect(state.rows[0]?.applyId).toBe('3');
    expect(state.loading).toBe(false);
  });
  it('失败显示重试状态且可以恢复，不把失败显示成空资产', async () => {
    mock.page.mockRejectedValueOnce(new Error('timeout'));
    mock.categories.mockRejectedValueOnce(new Error('403'));
    mount();
    await flush();
    expect(state.listError).toContain('读取失败');
    expect(state.categoryError).toContain('读取失败');
    await state.loadAssets();
    await state.loadCategories();
    expect(state.listError).toBe('');
    expect(state.categoryError).toBe('');
    expect(state.rows).toHaveLength(1);
  });
  it('撤销权限立即清空列表/弹窗，旧用户请求迟到不能恢复数据', async () => {
    const request = deferred<{ list: MyAssetsApi.Asset[]; total: number }>();
    mount();
    await flush();
    state.openDetail(state.rows[0]!);
    state.openPreview(state.rows[0]!);
    mock.page.mockReturnValueOnce(request.promise);
    void state.loadAssets();
    mock.access.accessCodes = [];
    await flush();
    expect(state.rows).toEqual([]);
    expect(mock.detailClose).toHaveBeenCalled();
    expect(mock.modalState).toHaveBeenCalledWith({ isOpen: false });
    request.resolve({ list: [asset()], total: 1 });
    await flush();
    expect(state.rows).toEqual([]);
    expect(state.total).toBe(0);
  });
  it('停用缓存页面清空数据且重新激活重新读取，旧行事件不能打开交付', async () => {
    mount(true);
    await flush();
    const old = state.rows[0]!;
    visible.value = false;
    await flush();
    expect(state.rows).toEqual([]);
    state.openPreview(old);
    expect(mock.modalOpen).not.toHaveBeenCalled();
    visible.value = true;
    await flush();
    expect(mock.page).toHaveBeenCalledTimes(2);
  });
  it('期限或暂停历史行保留详情但不开放消费，未知状态也不能消费', async () => {
    mock.page.mockResolvedValue({
      list: [{ ...asset(), accessStatus: 'EXPIRED', usable: true }],
      total: 1,
    });
    mount();
    await flush();
    state.openDetail(state.rows[0]!);
    state.openPreview(state.rows[0]!);
    expect(mock.detailOpen).toHaveBeenCalled();
    expect(mock.modalOpen).not.toHaveBeenCalled();
    expect(
      canOpenDelivery({ ...asset(), accessStatus: 'PAUSED' }, 'preview'),
    ).toBe(false);
    expect(canOpenDelivery({ ...asset(), canPreview: false }, 'preview')).toBe(
      false,
    );
    expect(
      canOpenDelivery(
        { ...asset(), deliveryType: 2, canViewApiDocument: true },
        'document',
      ),
    ).toBe(true);
    expect(accessState('NEW_STATE').label).toContain('待确认');
  });
});
