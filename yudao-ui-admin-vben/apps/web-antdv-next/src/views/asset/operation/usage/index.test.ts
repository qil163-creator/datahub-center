/* eslint-disable vue/one-component-per-file -- 模拟表格和原生分页以验证页面事件 */
import type { App, PropType, SetupContext } from 'vue';

import { createApp, defineComponent, h, nextTick, shallowRef } from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

import Usage from './index.vue';

const harness = vi.hoisted(() => ({
  getPage: vi.fn(),
}));

vi.mock('@vben/access', () => ({
  useAccess: () => ({ hasAccessByCodes: () => false }),
}));
vi.mock('@vben/common-ui', () => ({
  Page: (_: unknown, { slots }: SetupContext) => h('div', slots.default?.()),
}));
vi.mock('@vben/utils', () => ({
  downloadFileFromBlobPart: vi.fn(),
  formatDateTime: () => '',
}));
vi.mock('#/api/asset/operation', () => ({
  exportAssetOperation: vi.fn(),
  getAssetOperationPage: harness.getPage,
  refreshAssetOperation: vi.fn(),
}));
vi.mock('./modules/trend-drawer.vue', () => ({ default: () => null }));
vi.mock('antdv-next', () => ({
  Alert: () => null,
  Button: (_: unknown, { attrs, slots }: SetupContext) =>
    h('button', attrs, slots.default?.()),
  Input: () => null,
  Select: () => null,
  Progress: () => null,
  Tag: () => null,
  Tooltip: () => null,
  Table: defineComponent({
    props: { pagination: { type: [Boolean, Object], default: undefined } },
    emits: ['change'],
    setup(props, { emit }) {
      return () => h('div', { 'data-table-pagination': String(props.pagination) }, [
        h('button', {
          'data-sort': '',
          onClick: () => emit('change', {}, {}, { columnKey: 'heatScore', order: 'ascend' }),
        }, '排序'),
      ]);
    },
  }),
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
    expect(host.querySelector('[data-table-pagination]')?.getAttribute('data-table-pagination')).toBe('false');
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
    [...host.querySelectorAll('button')].find((button) => button.textContent === '查询')!.click();
    await flush();
    expect(harness.getPage).toHaveBeenLastCalledWith(expect.objectContaining({ pageNo: 1, pageSize: 50, sortField: 'heatScore', sortOrder: 'asc' }));
    expect(pager().dataset.total).toBe('0');
  });
});
