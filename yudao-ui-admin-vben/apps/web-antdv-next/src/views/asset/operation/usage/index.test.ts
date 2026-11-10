/* eslint-disable vue/one-component-per-file -- 模拟表格和分页以验证页面事件 */
import type { App, PropType, SetupContext } from 'vue';

import { createApp, defineComponent, h, nextTick, shallowRef } from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

import Usage from './index.vue';

const harness = vi.hoisted(() => ({
  getPage: vi.fn(),
  exportList: vi.fn(),
  refresh: vi.fn(),
  openTrend: vi.fn(),
  allowed: false,
  gridOptions: undefined as any,
  formOptions: undefined as any,
}));

vi.mock('@vben/access', () => ({
  useAccess: () => ({ hasAccessByCodes: () => harness.allowed }),
}));
vi.mock('@vben/common-ui', () => ({
  Page: (_: unknown, { slots }: SetupContext) => h('div', slots.default?.()),
}));
vi.mock('@vben/icons', () => ({ IconifyIcon: () => null }));
vi.mock('#/adapter/form', () => ({
  useVbenForm: (options: any) => {
    harness.formOptions = options;
    return [(_: unknown, { slots }: SetupContext) => h('div', slots['reset-before']?.()), { isMounted: false, reset: vi.fn(), setValues: vi.fn(), validateAndSubmit: () => options.handleSubmit() }];
  },
}));
vi.mock('#/adapter/business-vxe-table', () => ({
  NativeVxeButton: (_: unknown, { attrs }: SetupContext) => h('button', attrs),
  TableAction: defineComponent({ props: { actions: { type: Array as PropType<any[]>, default: () => [] } }, setup: (props) => () => h('div', props.actions.map((action) => h('button', { onClick: action.onClick }, action.label))) }),
  useVbenVxeGrid: (options: any) => {
    harness.gridOptions = options;
    return [defineComponent({
      props: { tableData: { type: Array as PropType<any[]>, default: () => [] } },
      setup(props, { slots }) {
        return () => h('div', [
          h('button', { 'data-sort': '', onClick: () => options.gridEvents.sortChange({ field: 'heatScore', order: 'asc' }) }, '排序'),
          slots['toolbar-tools']?.(),
          ...props.tableData.map((row, rowIndex) => h('div', [
            h('span', { 'data-sequence': '' }, slots.body?.({ column: { field: 'sequence' }, row, rowIndex })),
            slots.body?.({ column: { field: 'actions' }, row, rowIndex }),
          ])),
          slots.pager?.(),
        ]);
      },
    }), { setLoading: vi.fn() }];
  },
}));
vi.mock('@vben/utils', () => ({
  downloadFileFromBlobPart: vi.fn(),
  formatDateTime: () => '',
}));
vi.mock('#/api/asset/operation', () => ({
  exportAssetOperation: harness.exportList,
  getAssetOperationPage: harness.getPage,
  refreshAssetOperation: harness.refresh,
}));
vi.mock('./modules/trend-drawer.vue', () => ({ default: defineComponent({ setup(_props, { expose }) { expose({ open: harness.openTrend }); return () => null; } }) }));
vi.mock('antdv-next', () => ({
  Alert: () => null,
  Button: (_: unknown, { attrs, slots }: SetupContext) =>
    h('button', attrs, slots.default?.()),
  Input: () => null,
  Select: () => null,
  Progress: () => null,
  Tag: () => null,
  Tooltip: () => null,
  message: { success: vi.fn() },
}));
vi.mock('#/adapter/vxe-table', () => ({
  NativeVxePager: shallowRef(defineComponent({
    props: {
      currentPage: { type: Number, default: 1 },
      pageSize: { type: Number, default: 20 },
      total: { type: Number, default: 0 },
      layouts: { type: Array as PropType<string[]>, default: () => [] },
    },
    emits: ['page-change'],
    setup(props, { emit }) {
      return () => h('nav', {
        'data-pager': '',
        'data-current': props.currentPage,
        'data-size': props.pageSize,
        'data-total': props.total,
        'data-layouts': props.layouts.join(','),
      }, [
        h('button', { 'data-next': '', onClick: () => emit('page-change', { currentPage: 2, pageSize: props.pageSize }) }, '下一页'),
        h('button', { 'data-size-50': '', onClick: () => emit('page-change', { currentPage: props.currentPage, pageSize: 50 }) }, '每页50条'),
      ]);
    },
  })),
}));

let app: App | undefined;
let host: HTMLDivElement;
async function flush() {
  for (let index = 0; index < 10; index++) await Promise.resolve();
  await vi.dynamicImportSettled();
  await nextTick();
}
afterEach(() => {
  app?.unmount();
  app = undefined;
  host?.remove();
  vi.clearAllMocks();
  harness.allowed = false;
});

describe('资产使用与价值分页', () => {
  it('统一分页展示、按服务端分页请求并保留排序', async () => {
    harness.getPage.mockResolvedValue({ list: [], total: 80 });
    host = document.createElement('div');
    document.body.append(host);
    app = createApp(Usage);
    app.mount(host);
    await flush();

    const pager = () => host.querySelector<HTMLElement>('[data-pager]')!;
    expect(harness.gridOptions.gridOptions.pagerConfig.enabled).toBe(false);
    expect(harness.gridOptions.gridOptions.toolbarConfig).toMatchObject({ refresh: true, custom: true, zoom: false });
    expect(pager().dataset.layouts).toBe('Total,Sizes,Home,PrevJump,PrevPage,Number,NextPage,NextJump,End');
    expect(pager().dataset.total).toBe('80');
    expect(harness.getPage).toHaveBeenLastCalledWith(expect.objectContaining({ pageNo: 1, pageSize: 20, sortField: 'valueScore', sortOrder: 'desc' }));

    pager().querySelector<HTMLButtonElement>('[data-next]')!.click();
    await flush();
    expect(harness.getPage).toHaveBeenLastCalledWith(expect.objectContaining({ pageNo: 2, pageSize: 20 }));
    host.querySelector<HTMLButtonElement>('[data-sort]')!.click();
    await flush();
    expect(harness.getPage).toHaveBeenLastCalledWith(expect.objectContaining({ pageNo: 2, pageSize: 20, sortField: 'heatScore', sortOrder: 'asc' }));

    pager().querySelector<HTMLButtonElement>('[data-size-50]')!.click();
    await flush();
    expect(harness.getPage).toHaveBeenLastCalledWith(expect.objectContaining({ pageNo: 1, pageSize: 50, sortField: 'heatScore', sortOrder: 'asc' }));

    pager().querySelector<HTMLButtonElement>('[data-next]')!.click();
    await flush();
    harness.getPage.mockResolvedValue({ list: [], total: 0 });
    [...host.querySelectorAll('button')].find((button) => button.textContent?.trim() === '查询')!.click();
    await flush();
    expect(harness.getPage).toHaveBeenLastCalledWith(expect.objectContaining({ pageNo: 1, pageSize: 50, sortField: 'heatScore', sortOrder: 'asc' }));
    expect(pager().dataset.total).toBe('0');
  });
  it('筛选、导出、刷新指标和列表刷新使用现有接口', async () => {
    harness.allowed = true;
    harness.getPage.mockResolvedValue({ list: [], total: 0 });
    harness.exportList.mockResolvedValue(new Blob());
    harness.refresh.mockResolvedValue(3);
    host = document.createElement('div');
    document.body.append(host);
    app = createApp(Usage);
    app.mount(host);
    await flush();

    harness.formOptions.handleValuesChange({ keyword: ' 测试 ', assetType: 1, valueLevel: 'HIGH', suggestion: 'KEEP' });
    [...host.querySelectorAll('button')].find((button) => button.textContent?.trim() === '查询')!.click();
    await flush();
    expect(harness.getPage).toHaveBeenLastCalledWith(expect.objectContaining({ pageNo: 1, keyword: '测试', assetType: 1, valueLevel: 'HIGH', suggestion: 'KEEP' }));

    [...host.querySelectorAll('button')].find((button) => button.textContent?.trim() === '导出清单')!.click();
    await flush();
    expect(harness.exportList).toHaveBeenCalledWith(expect.objectContaining({ keyword: '测试', assetType: 1, valueLevel: 'HIGH', suggestion: 'KEEP' }));
    expect(harness.exportList.mock.calls[0]![0]).not.toHaveProperty('pageNo');

    [...host.querySelectorAll('button')].find((button) => button.textContent?.trim() === '刷新指标')!.click();
    await flush();
    expect(harness.refresh).toHaveBeenCalledOnce();
    expect(harness.getPage).toHaveBeenLastCalledWith(expect.objectContaining({ pageNo: 1, keyword: '测试' }));
    const calls = harness.getPage.mock.calls.length;
    await harness.gridOptions.gridOptions.toolbarConfig.refreshOptions.queryMethod();
    expect(harness.getPage).toHaveBeenCalledTimes(calls + 1);

    [...host.querySelectorAll('button')].find((button) => button.textContent?.trim() === '重置')!.click();
    await flush();
    expect(harness.getPage).toHaveBeenLastCalledWith(expect.objectContaining({ keyword: undefined, assetType: undefined, valueLevel: undefined, suggestion: undefined }));
  });

  it('编号根据页码和每页条数连续显示', async () => {
    harness.getPage.mockResolvedValue({ list: [{ assetId: '1' }, { assetId: '2' }], total: 80 });
    host = document.createElement('div');
    document.body.append(host);
    app = createApp(Usage);
    app.mount(host);
    await flush();
    const sequences = () => [...host.querySelectorAll('[data-sequence]')].map((node) => node.textContent?.trim());
    expect(sequences()).toEqual(['1', '2']);
    [...host.querySelectorAll('button')].find((button) => button.textContent?.trim() === '趋势')!.click();
    expect(harness.openTrend).toHaveBeenCalledWith({ assetId: '1' });
    host.querySelector<HTMLButtonElement>('[data-next]')!.click();
    await flush();
    expect(sequences()).toEqual(['21', '22']);
    host.querySelector<HTMLButtonElement>('[data-size-50]')!.click();
    await flush();
    expect(sequences()).toEqual(['1', '2']);
    host.querySelector<HTMLButtonElement>('[data-next]')!.click();
    await flush();
    expect(sequences()).toEqual(['51', '52']);
  });

});
