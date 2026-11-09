/* eslint-disable vue/one-component-per-file -- 分页回归使用隔离的弹窗与图标组件 */
import type { App, PropType } from 'vue';

import {
  createApp,
  defineComponent,
  h,
  nextTick,
  reactive,
  shallowRef,
} from 'vue';

import { useAccessStore } from '@vben/stores';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { getAppApiList, getSupplyApp } from '#/api/supply/app';

import Detail from './detail.vue';

vi.mock('#/adapter/vxe-table', () => ({
  NativeVxePager: shallowRef(
    defineComponent({
      props: {
        currentPage: { type: Number, default: 1 },
        pageSize: { type: Number, default: 10 },
        total: { type: Number, default: 0 },
        pageSizes: { type: Array as PropType<number[]>, default: () => [] },
        layouts: { type: Array as PropType<string[]>, default: () => [] },
      },
      emits: ['page-change'],
      setup(props, { emit }) {
        const change = (currentPage: number, pageSize = props.pageSize) =>
          emit('page-change', { currentPage, pageSize });
        return () =>
          h(
            'div',
            {
              'data-native-pager': '',
              'data-layouts': props.layouts.join(','),
              'data-current': props.currentPage,
              'data-size': props.pageSize,
              'data-total': props.total,
            },
            [
              h('span', `共 ${props.total} 条记录`),
              h(
                'select',
                {
                  'aria-label': '每页条数',
                  value: props.pageSize,
                  onChange: (event: Event) =>
                    change(
                      props.currentPage,
                      Number((event.target as HTMLSelectElement).value),
                    ),
                },
                props.pageSizes.map((size) =>
                  h('option', { value: size }, `${size}条/页`),
                ),
              ),
              h(
                'button',
                {
                  'data-page': 'prev',
                  disabled: props.currentPage <= 1,
                  onClick: () => change(props.currentPage - 1),
                },
                '上一页',
              ),
              h('span', { 'data-page-number': '' }, String(props.currentPage)),
              h(
                'button',
                {
                  'data-page': 'next',
                  disabled:
                    props.currentPage >=
                    Math.max(1, Math.ceil(props.total / props.pageSize)),
                  onClick: () => change(props.currentPage + 1),
                },
                '下一页',
              ),
            ],
          );
      },
    }),
  ),
}));

vi.mock('@vben/stores', () => {
  const state = reactive({
    accessCodes: ['supply:app:query'],
    accessToken: 'session-a',
    refreshToken: '',
    userInfo: { userId: '9' },
  });
  return {
    useAccessStore: () => state,
    useUserStore: () => state,
  };
});
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) => codes.includes('supply:app:query'),
  }),
}));
vi.mock('@vben/common-ui', () => {
  const usePopup = () => [
    defineComponent({ render: () => null }),
    {
      close: vi.fn(),
      getData: vi.fn(),
      lock: vi.fn(),
      open: vi.fn(),
      setState: vi.fn(),
      unlock: vi.fn(),
    },
  ];
  return {
    confirm: vi.fn(),
    useVbenDrawer: usePopup,
    useVbenModal: usePopup,
  };
});
vi.mock('@vben/icons', () => ({ IconifyIcon: () => null }));
vi.mock('#/api/supply/app', () => ({
  createAppApi: vi.fn(),
  getAppApi: vi.fn(),
  getAppApiList: vi.fn(),
  getSupplyApp: vi.fn(),
  revokeAdminGrant: vi.fn(),
  setGrantControl: vi.fn(),
  updateAppApi: vi.fn(),
}));
vi.mock('#/api/supply/dataapi', () => ({ getDataApiSimpleList: vi.fn() }));
vi.mock('#/utils', () => ({ getRangePickerDefaultProps: () => ({}) }));
vi.mock('../data', () => ({
  getUserNickname: (id?: string) => id ?? '-',
}));
vi.mock('#/views/modeling/modelcommon/detail-field-form.vue', () => ({
  default: defineComponent({ render: () => null }),
}));

let app: App | undefined;
let host: HTMLDivElement;

async function flush() {
  for (let index = 0; index < 10; index++) await Promise.resolve();
  await vi.dynamicImportSettled();
  await nextTick();
}

function rows() {
  return [...host.querySelectorAll('.ant-table-tbody > .ant-table-row')];
}

function pager() {
  const node = host.querySelector<HTMLElement>('[data-native-pager]');
  expect(node).not.toBeNull();
  return node!;
}

async function nextPage() {
  pager().querySelector<HTMLButtonElement>('[data-page="next"]')!.click();
  await flush();
}

async function selectPageSize(size: number) {
  const select = pager().querySelector<HTMLSelectElement>('select')!;
  select.value = String(size);
  select.dispatchEvent(new Event('change', { bubbles: true }));
  await flush();
}

beforeEach(async () => {
  vi.clearAllMocks();
  useAccessStore().refreshToken = '';
  vi.mocked(getSupplyApp).mockResolvedValue({ id: '3', name: '应用分页测试' });
  vi.mocked(getAppApiList).mockResolvedValue(
    Array.from({ length: 25 }, (_, index) => ({
      id: String(index + 1),
      appId: '3',
      apiId: String(index + 1),
      apiName: `授权-${String(index + 1).padStart(3, '0')}`,
      authStatus: 1,
      foreverFlag: 1,
      sourceType: 'ADMIN',
    })),
  );
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(Detail, { appId: '3', embedded: true });
  app.mount(host);
  await flush();
});

afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
});

describe('应用授权统一项目分页', () => {
  it('复用项目分页布局，翻页及容量变化驱动真实表格', async () => {
    expect(pager().classList.contains('list-pagination')).toBe(true);
    expect(pager().dataset.layouts).toBe(
      'Total,Sizes,Home,PrevJump,PrevPage,Number,NextPage,NextJump,End',
    );
    expect(host.querySelector('.ant-pagination')).toBeNull();
    expect(pager().dataset.total).toBe('25');
    expect(rows()).toHaveLength(10);
    await nextPage();
    expect(rows()[0]?.textContent).toContain('授权-011');
    expect(pager().dataset.current).toBe('2');
    await selectPageSize(20);
    expect(rows()).toHaveLength(20);
    expect(rows()[0]?.textContent).toContain('授权-001');
    expect(pager().dataset.current).toBe('1');
    await nextPage();
    expect(rows()).toHaveLength(5);
    expect(rows()[0]?.textContent).toContain('授权-021');
  });

  it('在全量授权数据上排序后再分页，外部分页与行数据保持一致', async () => {
    const header = [...host.querySelectorAll<HTMLElement>('th')].find((node) =>
      node.textContent?.includes('API编号'),
    )!;
    header.click();
    await flush();
    header.click();
    await flush();
    expect(rows()[0]?.textContent).toContain('授权-025');
    await nextPage();
    expect(rows()[0]?.textContent).toContain('授权-015');
    expect(rows()).toHaveLength(10);
  });

  it('会话切换后清空旧分页，空授权列表仍有真实零条分页', async () => {
    await selectPageSize(20);
    await nextPage();
    vi.mocked(getAppApiList).mockResolvedValue([]);
    useAccessStore().refreshToken = 'session-b';
    await flush();
    expect(rows()).toHaveLength(0);
    expect(pager().dataset.total).toBe('0');
    expect(pager().dataset.current).toBe('1');
    expect(pager().dataset.size).toBe('10');
    expect(
      pager().querySelector<HTMLButtonElement>('[data-page="prev"]')!.disabled,
    ).toBe(true);
    expect(
      pager().querySelector<HTMLButtonElement>('[data-page="next"]')!.disabled,
    ).toBe(true);
  });
});
