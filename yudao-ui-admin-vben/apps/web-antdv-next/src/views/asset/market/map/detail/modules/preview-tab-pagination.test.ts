/* eslint-disable vue/one-component-per-file -- 使用分页桩验证真实页面交互 */
import type { App, PropType } from 'vue';

import { createApp, defineComponent, h, nextTick, shallowRef } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import PreviewTab from './preview-tab.vue';

const api = vi.hoisted(() => ({ preview: vi.fn() }));
vi.mock('#/api/asset/dataasset', () => ({ previewDataAsset: api.preview }));
vi.mock('@vben/access', () => ({
  useAccess: () => ({ hasAccessByCodes: () => true }),
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => ({
    accessToken: 'session',
    accessCodes: ['asset:data-asset:preview'],
  }),
  useUserStore: () => ({ userInfo: { userId: '1' }, userRoles: [] }),
}));
vi.mock('@vben/icons', () => ({ IconifyIcon: () => null }));
vi.mock('@vben/common-ui', () => ({
  useVbenModal: () => [
    defineComponent({ render: () => null }),
    { setState: vi.fn() },
  ],
}));
vi.mock('./row-editor.vue', () => ({ default: { render: () => null } }));
vi.mock('./row-history.vue', () => ({ default: { render: () => null } }));
vi.mock('#/adapter/vxe-table', () => ({
  NativeVxePager: shallowRef(
    defineComponent({
      props: {
        currentPage: { type: Number, default: 1 },
        pageSize: { type: Number, default: 10 },
        total: { type: Number, default: 0 },
        layouts: { type: Array as PropType<string[]>, default: () => [] },
      },
      emits: ['page-change'],
      setup(props, { emit }) {
        return () =>
          h('nav', {
            'data-pager': '',
            'data-page': props.currentPage,
            'data-size': props.pageSize,
            'data-total': props.total,
            'data-layouts': props.layouts.join(','),
          }, [
            h('button', {
              'data-next': '',
              onClick: () => emit('page-change', {
                currentPage: props.currentPage + 1,
                pageSize: props.pageSize,
              }),
            }, '下一页'),
            h('button', {
              'data-size-20': '',
              onClick: () => emit('page-change', {
                currentPage: props.currentPage,
                pageSize: 20,
              }),
            }, '每页20条'),
          ]);
      },
    }),
  ),
}));

let app: App | undefined;
let host: HTMLDivElement;
async function flush() {
  for (let index = 0; index < 10; index++) await Promise.resolve();
  await vi.dynamicImportSettled();
  await nextTick();
}
function pager() {
  const node = host.querySelector<HTMLElement>('[data-pager]');
  expect(node).not.toBeNull();
  return node!;
}

beforeEach(() => {
  api.preview.mockReset();
  api.preview.mockImplementation((_id, size, _filters, _unused, page) =>
    Promise.resolve({
      columns: [{ name: 'name' }],
      rows: [{ name: `第${page.pageNo}页-${size}条` }],
      total: 31,
    }),
  );
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(PreviewTab, { assetId: '9007199254740993', columns: [] });
  app.mount(host);
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
});

describe('资产预览统一分页', () => {
  it('翻页和修改每页数量时请求对应页，并显示服务端返回的数据', async () => {
    await flush();
    expect(pager().dataset.layouts).toBe(
      'Total,Sizes,Home,PrevJump,PrevPage,Number,NextPage,NextJump,End',
    );
    expect(pager().dataset.total).toBe('31');
    expect(host.querySelector('.ant-pagination')).toBeNull();
    pager().querySelector<HTMLButtonElement>('[data-next]')!.click();
    await flush();
    expect(api.preview).toHaveBeenLastCalledWith(
      '9007199254740993', 10, [], undefined, { pageNo: 2, pageSize: 10 },
    );
    expect(host.textContent).toContain('第2页-10条');
    pager().querySelector<HTMLButtonElement>('[data-size-20]')!.click();
    await flush();
    expect(api.preview).toHaveBeenLastCalledWith(
      '9007199254740993', 20, [], undefined, { pageNo: 1, pageSize: 20 },
    );
    expect(pager().dataset.page).toBe('1');
    expect(host.textContent).toContain('第1页-20条');
  });

  it('空结果显示零条分页', async () => {
    await flush();
    api.preview.mockResolvedValue({ columns: [], rows: [], total: 0 });
    [...host.querySelectorAll('button')].find((button) =>
      button.textContent?.includes('查询'),
    )!.click();
    await flush();
    expect(pager().dataset.total).toBe('0');
    expect(pager().dataset.page).toBe('1');
  });

  it('连续翻页时旧请求不会覆盖最新结果', async () => {
    await flush();
    let finishOld!: (value: unknown) => void;
    const old = new Promise((resolve) => { finishOld = resolve; });
    api.preview.mockImplementationOnce(() => old);
    pager().querySelector<HTMLButtonElement>('[data-next]')!.click();
    await flush();
    pager().querySelector<HTMLButtonElement>('[data-next]')!.click();
    await flush();
    expect(host.textContent).toContain('第3页-10条');
    finishOld({ columns: [{ name: 'name' }], rows: [{ name: '旧结果' }], total: 31 });
    await flush();
    expect(host.textContent).toContain('第3页-10条');
    expect(host.textContent).not.toContain('旧结果');
  });
});
