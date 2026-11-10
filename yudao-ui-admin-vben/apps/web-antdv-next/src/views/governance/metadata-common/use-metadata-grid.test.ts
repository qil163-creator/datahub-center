/* eslint-disable vue/one-component-per-file -- 回归用父子组件验证缓存页面切换与身份重置。 */
import type { App } from 'vue';

import { createApp, defineComponent, h, KeepAlive, nextTick, reactive, ref } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { useMetadataGrid } from './use-metadata-grid';

const mock = vi.hoisted(() => ({
  access: { accessToken: 'a', refreshToken: '' },
  setGridOptions: vi.fn(),
  clearCheckboxRow: vi.fn(),
  resetForm: vi.fn(),
  setLatestSubmissionValues: vi.fn(),
  formValues: {} as { name?: string },
  grid: {} as { clearCheckboxRow?: () => void },
  createGrid: vi.fn((_options: unknown) => {}),
}));
vi.mock('@vben/stores', () => ({ useAccessStore: () => mock.access }));
vi.mock('#/adapter/vxe-table', () => ({
  useVbenVxeGrid: (options: unknown) => {
    mock.createGrid(options);
    return [
      () => null,
      {
        setGridOptions: mock.setGridOptions,
        grid: mock.grid,
        formApi: {
          reset: mock.resetForm,
          setLatestSubmissionValues: mock.setLatestSubmissionValues,
        },
      },
    ];
  },
}));

function deferred<T>() {
  let resolve!: (data: T) => void;
  const promise = new Promise<T>((yes) => {
    resolve = yes;
  });
  return { promise, resolve };
}
type Row = { id: number | string; name: string };
type Result = { list: Row[]; total: number };
let app: App | undefined;
let grid: ReturnType<typeof useMetadataGrid<Row>>;
let host: HTMLDivElement;
function mount(
  fetch: (page: { pageNo: number; pageSize: number }) => Promise<Result>,
  resetScopeFilters?: () => void,
  layout: 'content' | 'fill' = 'fill',
  nativeToolbar = false,
) {
  app = createApp({
    setup() {
      grid = useMetadataGrid<Row>([], fetch, undefined, resetScopeFilters, layout, nativeToolbar);
      return () => h('div');
    },
  });
  host = document.createElement('div');
  app.mount(host);
}
beforeEach(() => {
  vi.clearAllMocks();
  mock.access = reactive({ accessToken: 'a', refreshToken: '' });
  mock.grid = { clearCheckboxRow: mock.clearCheckboxRow };
  mock.formValues = {};
  mock.resetForm.mockImplementation(async () => {
    mock.formValues = {};
  });
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host?.remove();
});

describe('元数据列表请求隔离', () => {
  it('原生工具栏刷新保留当前页并沿用原查询', async () => {
    const fetch = vi.fn().mockResolvedValue({ list: [], total: 0 });
    mount(fetch, undefined, 'fill', true);
    const options = mock.createGrid.mock.lastCall?.[0] as {
      gridEvents: { pageChange: (event: { currentPage: number; pageSize: number }) => void };
      gridOptions: { toolbarConfig: { custom: boolean; refresh: boolean; refreshOptions: { queryMethod: (event: object) => Promise<void> }; search: boolean; zoom: boolean } };
    };
    expect(options.gridOptions.toolbarConfig).toMatchObject({ custom: true, refresh: true, search: true, zoom: false });
    options.gridEvents.pageChange({ currentPage: 3, pageSize: 20 });
    await options.gridOptions.toolbarConfig.refreshOptions.queryMethod({});
    expect(fetch).toHaveBeenLastCalledWith({ pageNo: 3, pageSize: 20 });
  });
  it('主列表按可用高度填满且允许内容自然换行', () => {
    mount(async () => ({ list: [], total: 0 }));
    expect(mock.createGrid).toHaveBeenCalledWith(
      expect.objectContaining({
        gridOptions: expect.objectContaining({
          height: 'auto',
          showOverflow: false,
        }),
      }),
    );
  });
  it('详情内嵌列表保留自然高度和原有溢出提示', () => {
    mount(async () => ({ list: [], total: 0 }), undefined, 'content');
    expect(mock.createGrid).toHaveBeenCalledWith(
      expect.objectContaining({
        gridOptions: expect.objectContaining({
          height: undefined,
          showOverflow: 'tooltip',
        }),
      }),
    );
  });
  it('身份变化同时清理真实表单、提交快照和外部筛选，下一次查询不沿用旧条件', async () => {
    const filters = reactive({ name: '旧身份搜索' });
    const fetch = vi.fn(async () => {
      return { list: [{ id: 1, name: filters.name }], total: 1 };
    });
    mount(fetch, () => { filters.name = ''; });
    mock.formValues = { name: filters.name };
    mock.access.accessToken = 'b';
    expect(filters.name).toBe('');
    expect(mock.formValues).toEqual({});
    expect(mock.resetForm).toHaveBeenCalledOnce();
    expect(mock.setLatestSubmissionValues).toHaveBeenCalledExactlyOnceWith({});
    await grid.load();
    await expect(fetch.mock.results.at(-1)?.value).resolves.toEqual({ list: [{ id: 1, name: '' }], total: 1 });
    expect(mock.setGridOptions).toHaveBeenCalledWith({ data: [], loading: false, pagerConfig: { currentPage: 1, total: 0 } });
  });
  it('只切走再返回保留原有搜索条件，不触发身份重置回调', async () => {
    const shown = ref(true);
    const resetScopeFilters = vi.fn();
    const child = defineComponent({
      setup() {
        useMetadataGrid<Row>([], async () => ({ list: [], total: 0 }), undefined, resetScopeFilters);
        return () => h('div');
      },
    });
    app = createApp({ setup: () => () => h(KeepAlive, () => shown.value ? h(child) : null) });
    host = document.createElement('div');
    app.mount(host);
    mock.formValues = { name: '保留条件' };
    shown.value = false;
    await nextTick();
    shown.value = true;
    await nextTick();
    expect(mock.formValues).toEqual({ name: '保留条件' });
    expect(mock.resetForm).not.toHaveBeenCalled();
    expect(resetScopeFilters).not.toHaveBeenCalled();
  });
  it('表格尚未挂载或已解除实例时没有清理方法也能安全发起查询', async () => {
    mock.grid = {};
    const fetch = vi.fn().mockResolvedValue({ list: [], total: 0 });
    mount(fetch);
    await expect(grid.load(true)).resolves.toBeUndefined();
    expect(fetch).toHaveBeenCalled();
    expect(mock.clearCheckboxRow).not.toHaveBeenCalled();
  });
  it('快速筛选只显示最后一次响应，保留字符串 ID', async () => {
    const first = deferred<Result>();
    const second = deferred<Result>();
    const fetch = vi
      .fn()
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(second.promise);
    mount(fetch);
    const pending = grid.load(true);
    const row = { id: '9007199254740993', name: '当前结果' };
    second.resolve({ list: [row], total: 1 });
    await pending;
    first.resolve({ list: [{ id: 1, name: '过期结果' }], total: 1 });
    await nextTick();
    const dataCalls = mock.setGridOptions.mock.calls.filter(
      ([options]) => options.data?.length,
    );
    expect(dataCalls).toEqual([[{ data: [row], pagerConfig: { total: 1 } }]]);
  });
  it('换登录会话时立即清空行和选择，旧响应不能重新显示数据', async () => {
    const result = deferred<Result>();
    mount(() => result.promise);
    grid.selected.value = [{ id: '9007199254740993', name: '已选' }];
    mock.access.accessToken = 'b';
    expect(grid.selected.value).toEqual([]);
    result.resolve({ list: [{ id: 1, name: '旧会话' }], total: 1 });
    await nextTick();
    expect(mock.setGridOptions).toHaveBeenCalledWith({
      data: [],
      loading: false,
      pagerConfig: { total: 0 },
    });
    expect(
      mock.setGridOptions.mock.calls.some(([options]) => options.data?.length),
    ).toBe(false);
  });
  it('卸载后的请求不写表格状态', async () => {
    const result = deferred<Result>();
    mount(() => result.promise);
    app?.unmount();
    app = undefined;
    const before = mock.setGridOptions.mock.calls.length;
    result.resolve({ list: [{ id: 1, name: '已离开页面' }], total: 1 });
    await nextTick();
    expect(mock.setGridOptions).toHaveBeenCalledTimes(before);
  });
});
