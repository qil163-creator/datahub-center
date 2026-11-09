/* eslint-disable vue/one-component-per-file -- 分页回归使用隔离的外部组件 */
import type { App, PropType } from 'vue';
import type { Router } from 'vue-router';

import type { SupplyDataApiApi } from '#/api/supply/dataapi';

import {
  createApp,
  defineComponent,
  h,
  nextTick,
  reactive,
  shallowRef,
} from 'vue';
import { createMemoryHistory, createRouter, RouterView } from 'vue-router';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { getApiCategoryList } from '#/api/supply/apicategory';
import { getDataApi } from '#/api/supply/dataapi';

import DetailPage from './detail.vue';

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

const harness = vi.hoisted(() => ({
  state: undefined as
    | undefined
    | {
        accessCodes: string[];
        accessToken: string;
        refreshToken: string;
        userInfo: { userId: string };
      },
}));

vi.mock('@vben/stores', () => {
  harness.state = reactive({
    accessCodes: ['supply:data-api:query'],
    accessToken: 'session-a',
    refreshToken: '',
    userInfo: { userId: '9' },
  });
  return {
    useAccessStore: () => harness.state,
    useUserStore: () => harness.state,
  };
});
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      codes.some((code) => harness.state!.accessCodes.includes(code)),
  }),
}));
vi.mock('@vben/common-ui', () => ({
  Page: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('main', slots.default?.()),
  }),
}));
vi.mock('@vben/hooks', () => ({
  useTabs: () => ({ closeCurrentTab: vi.fn() }),
}));
vi.mock('@vben/icons', () => ({ IconifyIcon: () => null }));
vi.mock('#/api/supply/dataapi', () => ({ getDataApi: vi.fn() }));
vi.mock('#/api/supply/apicategory', () => ({ getApiCategoryList: vi.fn() }));
vi.mock('#/utils', () => ({ getRangePickerDefaultProps: () => ({}) }));
vi.mock('../utils/user', () => ({
  getUserNickname: (id?: string) => id ?? '-',
}));

const initialId = '9007199254740993';
const nextId = '9007199254740995';
let app: App | undefined;
let host: HTMLDivElement;
let router: Router;

function apiDetail(id = initialId, count = 0): SupplyDataApiApi.DataApi {
  return {
    id,
    name: `接口-${id}`,
    status: 1,
    method: 'POST',
    requestParams: Array.from({ length: count }, (_, index) => ({
      name: `request-${String(index + 1).padStart(3, '0')}`,
      paramType: 'STRING',
      nullable: 0,
    })),
    responseFields: Array.from({ length: count }, (_, index) => ({
      name: `response-${String(index + 1).padStart(3, '0')}`,
      dataType: 'VARCHAR',
    })),
  };
}

async function flush() {
  for (let index = 0; index < 10; index++) await Promise.resolve();
  await vi.dynamicImportSettled();
  await nextTick();
}

async function open(id = initialId) {
  await router.push({ name: 'SupplyDataApiDetailPage', params: { id } });
  await flush();
}

function activePanel() {
  const panel = host.querySelector<HTMLElement>('.ant-tabs-content-active');
  expect(panel).not.toBeNull();
  return panel!;
}

function rows() {
  return [
    ...activePanel().querySelectorAll('.ant-table-tbody > .ant-table-row'),
  ];
}

async function selectTab(label: string) {
  const tab = [...host.querySelectorAll<HTMLElement>('[role="tab"]')].find(
    (item) => item.textContent?.includes(label),
  );
  expect(tab).toBeDefined();
  tab!.click();
  await flush();
}

function pager() {
  const node = activePanel().querySelector<HTMLElement>('[data-native-pager]');
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
  vi.resetAllMocks();
  Object.assign(harness.state!, {
    accessCodes: ['supply:data-api:query'],
    accessToken: 'session-a',
    refreshToken: '',
    userInfo: { userId: '9' },
  });
  vi.mocked(getDataApi).mockImplementation(async (id) => apiDetail(String(id)));
  vi.mocked(getApiCategoryList).mockResolvedValue([]);
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/supply/data-api', component: { render: () => null } },
      {
        path: '/supply/data-api/detail/:id',
        name: 'SupplyDataApiDetailPage',
        component: DetailPage,
      },
    ],
  });
  await router.push('/supply/data-api');
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(RouterView);
  app.use(router);
  app.mount(host);
  await flush();
});

afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
  document.querySelectorAll('.ant-select-dropdown').forEach((node) => {
    node.remove();
  });
});

describe('数据API详情统一项目分页', () => {
  it('两张空表都使用项目分页布局，显示真实零条和完整翻页入口', async () => {
    await open();
    for (const label of ['请求参数', '返回字段']) {
      await selectTab(label);
      expect(pager().classList.contains('list-pagination')).toBe(true);
      expect(pager().dataset.layouts).toBe(
        'Total,Sizes,Home,PrevJump,PrevPage,Number,NextPage,NextJump,End',
      );
      expect(pager().dataset.total).toBe('0');
      expect(pager().dataset.current).toBe('1');
      expect(pager().dataset.size).toBe('10');
      expect(
        pager().querySelector<HTMLButtonElement>('[data-page="prev"]')!
          .disabled,
      ).toBe(true);
      expect(
        pager().querySelector<HTMLButtonElement>('[data-page="next"]')!
          .disabled,
      ).toBe(true);
      expect(
        [...pager().querySelectorAll('option')].map((node) => node.value),
      ).toEqual(['10', '20', '50', '100']);
      expect(activePanel().querySelector('.ant-pagination')).toBeNull();
      expect(rows()).toHaveLength(0);
    }
  });

  it('两个表格独立翻页和切换每页条数，显示对应真实数据', async () => {
    vi.mocked(getDataApi).mockResolvedValue(apiDetail(initialId, 25));
    await open();
    expect(rows()).toHaveLength(10);
    expect(rows()[0]?.textContent).toContain('request-001');
    expect(pager().dataset.total).toBe('25');
    await nextPage();
    expect(rows()).toHaveLength(10);
    expect(rows()[0]?.textContent).toContain('request-011');

    await selectTab('返回字段');
    expect(rows()).toHaveLength(10);
    expect(rows()[0]?.textContent).toContain('response-001');
    await selectPageSize(20);
    expect(rows()).toHaveLength(20);
    expect(rows()[0]?.textContent).toContain('response-001');
    await nextPage();
    expect(rows()).toHaveLength(5);
    expect(rows()[0]?.textContent).toContain('response-021');

    await selectTab('请求参数');
    expect(rows()).toHaveLength(10);
    expect(rows()[0]?.textContent).toContain('request-011');
    expect(pager().dataset.current).toBe('2');
    expect(pager().dataset.size).toBe('10');
  });

  it.each([0, 25])('%s 条数据时容量变化使当前页回首页', async (count) => {
    vi.mocked(getDataApi).mockResolvedValue(apiDetail(initialId, count));
    await open();
    for (const label of ['请求参数', '返回字段']) {
      await selectTab(label);
      if (count > 10) await nextPage();
      await selectPageSize(20);
      expect(pager().dataset.current).toBe('1');
      expect(pager().dataset.size).toBe('20');
      expect(rows()).toHaveLength(Math.min(count, 20));
      await selectPageSize(50);
      expect(rows()).toHaveLength(count);
      expect(pager().dataset.current).toBe('1');
      expect(pager().dataset.size).toBe('50');
    }
  });

  it.each(['route', 'session'] as const)(
    '%s 切换后清除旧详情及分页状态',
    async (change) => {
      vi.mocked(getDataApi).mockResolvedValueOnce(apiDetail(initialId, 25));
      await open();
      await selectPageSize(20);
      await nextPage();
      expect(rows()[0]?.textContent).toContain('request-021');

      if (change === 'route') await open(nextId);
      else {
        harness.state!.refreshToken = 'session-b';
        await flush();
      }
      expect(rows()).toHaveLength(0);
      expect(pager().dataset.total).toBe('0');
      expect(pager().dataset.current).toBe('1');
      expect(pager().dataset.size).toBe('10');
    },
  );
});
