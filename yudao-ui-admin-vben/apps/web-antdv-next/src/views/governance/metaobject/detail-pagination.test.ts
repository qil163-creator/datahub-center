/* eslint-disable vue/one-component-per-file -- 分页回归使用隔离的外部组件 */
import type { App, PropType } from 'vue';
import type { Router } from 'vue-router';

import type { GovernanceMetaObjectApi as Api } from '#/api/governance/metaobject';

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

import {
  getFinalizedMetaTable,
  getMetaColumnList,
  getMetaSnapshotList,
  getMetaTableDetail,
} from '#/api/governance/metaobject';

import DetailPage from './detail.vue';

vi.mock('#/adapter/vxe-table', () => ({
  TableAction: () => null,
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
    | { accessToken: string; finalized: boolean; refreshToken: string },
}));
vi.mock('@vben/stores', () => {
  harness.state = reactive({
    accessToken: 'session-a',
    refreshToken: '',
    finalized: false,
  });
  return { useAccessStore: () => harness.state };
});
vi.mock('@vben/access', () => ({
  useAccess: () => ({ hasAccessByCodes: () => true }),
}));
vi.mock('@vben/common-ui', () => ({
  Page: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('main', slots.default?.()),
  }),
  useVbenModal: () => [() => null, { setData: vi.fn(), open: vi.fn() }],
}));
vi.mock('@vben/icons', () => ({ IconifyIcon: () => null, addIcon: vi.fn() }));
vi.mock('./modules/column-biz-form.vue', () => ({ default: () => null }));
vi.mock('#/api/governance/metaobject', () => ({
  cancelFinalizeMetaTable: vi.fn(),
  finalizeMetaTable: vi.fn(),
  getFinalizedMetaTable: vi.fn(),
  getMetaColumnList: vi.fn(),
  getMetaSnapshotList: vi.fn(),
  getMetaTableDetail: vi.fn(),
}));

const initialId = '9007199254740993';
const nextId = '9007199254740995';
let app: App | undefined;
let host: HTMLDivElement;
let router: Router;

function fields(count: number, tableId = initialId): Api.MetaColumn[] {
  return Array.from({ length: count }, (_, index) => ({
    id: `9007199254741993${index}`,
    tableId,
    columnName: `field-${String(index + 1).padStart(3, '0')}`,
    dataType: 'VARCHAR',
  }));
}
function snapshots(count: number, tableId = initialId): Api.Snapshot[] {
  return Array.from({ length: count }, (_, index) => ({
    id: `9007199254742993${index}`,
    tableId,
    structVersion: `V1.0.${String(index + 1).padStart(3, '0')}`,
  }));
}
async function flush() {
  for (let index = 0; index < 12; index++) await Promise.resolve();
  await vi.dynamicImportSettled();
  await nextTick();
}
async function open(id = initialId) {
  await router.push({ name: 'GovernanceMetaTableDetailPage', params: { id } });
  await flush();
}
function activePanel() {
  const panel = host.querySelector<HTMLElement>('.ant-tabs-content-active');
  expect(panel).not.toBeNull();
  return panel!;
}
function rows() {
  return [
    ...activePanel().querySelectorAll<HTMLElement>(
      '.ant-table-tbody > .ant-table-row',
    ),
  ];
}
function pager() {
  const node = activePanel().querySelector<HTMLElement>('[data-native-pager]');
  expect(node).not.toBeNull();
  return node!;
}
async function selectTab(label: string) {
  const tab = [...host.querySelectorAll<HTMLElement>('[role="tab"]')].find(
    (item) => item.textContent?.includes(label),
  );
  expect(tab).toBeDefined();
  tab!.click();
  await flush();
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
async function refresh(label: string) {
  activePanel()
    .querySelector<HTMLButtonElement>(`[aria-label="${label}"]`)!
    .click();
  await flush();
}

beforeEach(async () => {
  vi.resetAllMocks();
  Object.assign(harness.state!, {
    accessToken: 'session-a',
    refreshToken: '',
    finalized: false,
  });
  vi.mocked(getMetaTableDetail).mockImplementation(async (id) => ({
    id,
    tableName: `table-${id}`,
    publishStatus: 0,
  }));
  vi.mocked(getMetaColumnList).mockResolvedValue([]);
  vi.mocked(getMetaSnapshotList).mockResolvedValue([]);
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/governance/metaobject', component: { render: () => null } },
      {
        path: '/governance/metaobject/detail/:id',
        name: 'GovernanceMetaTableDetailPage',
        component: defineComponent({
          setup: () => () =>
            h(DetailPage, { finalized: harness.state!.finalized }),
        }),
      },
    ],
  });
  await router.push('/governance/metaobject');
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
});

describe('元数据详情统一项目分页', () => {
  it('两张空表显示真实零条，使用完整原生分页布局并关闭 Ant 分页', async () => {
    await open();
    for (const label of ['字段列表', '版本与变更']) {
      await selectTab(label);
      expect(pager().classList.contains('list-pagination')).toBe(true);
      expect(pager().dataset.layouts).toBe(
        'Total,Sizes,Home,PrevJump,PrevPage,Number,NextPage,NextJump,End',
      );
      expect(pager().dataset.total).toBe('0');
      expect(pager().dataset.current).toBe('1');
      expect(pager().dataset.size).toBe('10');
      expect(
        [...pager().querySelectorAll('option')].map((node) => node.value),
      ).toEqual(['10', '20', '50', '100']);
      expect(
        pager().querySelector<HTMLButtonElement>('[data-page="next"]')!
          .disabled,
      ).toBe(true);
      expect(activePanel().querySelector('.ant-pagination')).toBeNull();
      expect(rows()).toHaveLength(0);
    }
  });

  it('两表独立翻页及切换容量，每页显示对应真实行且保留无损 ID', async () => {
    vi.mocked(getMetaColumnList).mockResolvedValue(fields(25));
    vi.mocked(getMetaSnapshotList).mockResolvedValue(snapshots(25));
    await open();
    expect(getMetaColumnList).toHaveBeenCalledWith(initialId);
    expect(rows()).toHaveLength(10);
    await nextPage();
    expect(rows()[0]?.textContent).toContain('field-011');
    expect(rows()[0]?.dataset.rowKey).toBe('900719925474199310');

    await selectTab('版本与变更');
    expect(rows()).toHaveLength(10);
    expect(rows()[0]?.textContent).toContain('V1.0.001');
    await nextPage();
    await selectPageSize(20);
    expect(pager().dataset.current).toBe('1');
    expect(rows()).toHaveLength(20);
    await nextPage();
    expect(rows()).toHaveLength(5);
    expect(rows()[0]?.textContent).toContain('V1.0.021');

    await selectTab('字段列表');
    expect(pager().dataset.current).toBe('2');
    expect(pager().dataset.size).toBe('10');
    expect(rows()[0]?.textContent).toContain('field-011');
    await selectPageSize(50);
    expect(pager().dataset.current).toBe('1');
    expect(rows()).toHaveLength(25);
  });

  it('刷新数据减少时，两表均回到最后一个有效页且清空后仍显示零条分页', async () => {
    vi.mocked(getMetaColumnList).mockResolvedValue(fields(25));
    vi.mocked(getMetaSnapshotList).mockResolvedValue(snapshots(25));
    await open();
    await nextPage();
    await nextPage();
    await selectTab('版本与变更');
    await nextPage();
    await nextPage();
    vi.mocked(getMetaColumnList).mockResolvedValue(fields(15));
    vi.mocked(getMetaSnapshotList).mockResolvedValue(snapshots(15));
    await refresh('刷新版本');
    expect(pager().dataset.current).toBe('2');
    expect(rows()).toHaveLength(5);
    expect(rows()[0]?.textContent).toContain('V1.0.011');
    await selectTab('字段列表');
    expect(pager().dataset.current).toBe('2');
    expect(rows()[0]?.textContent).toContain('field-011');
    vi.mocked(getMetaColumnList).mockResolvedValue([]);
    vi.mocked(getMetaSnapshotList).mockResolvedValue([]);
    await refresh('刷新字段');
    for (const label of ['字段列表', '版本与变更']) {
      await selectTab(label);
      expect(pager().dataset.current).toBe('1');
      expect(pager().dataset.total).toBe('0');
      expect(rows()).toHaveLength(0);
    }
  });

  it('对象切换重置两表分页，新对象加载后旧响应不能回写', async () => {
    vi.mocked(getMetaColumnList).mockResolvedValue(fields(25));
    vi.mocked(getMetaSnapshotList).mockResolvedValue(snapshots(25));
    await open();
    await selectPageSize(20);
    await nextPage();
    await selectTab('版本与变更');
    await selectPageSize(20);
    await nextPage();
    let resolveOld!: (value: Api.MetaColumn[]) => void;
    vi.mocked(getMetaColumnList).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveOld = resolve;
        }),
    );
    await refresh('刷新版本');
    vi.mocked(getMetaColumnList).mockResolvedValue(fields(1, nextId));
    vi.mocked(getMetaSnapshotList).mockResolvedValue(snapshots(1, nextId));
    await open(nextId);
    resolveOld(fields(25));
    await flush();
    for (const label of ['字段列表', '版本与变更']) {
      await selectTab(label);
      expect(pager().dataset.current).toBe('1');
      expect(pager().dataset.size).toBe('10');
      expect(pager().dataset.total).toBe('1');
      expect(rows()).toHaveLength(1);
    }
  });

  it('定版详情继续展示完整只读字段，不引入原本没有的分页或版本页签', async () => {
    harness.state!.finalized = true;
    vi.mocked(getFinalizedMetaTable).mockResolvedValue({
      table: { id: initialId, tableName: '定版表' },
      columns: fields(25),
      snapshot: { id: '9007199254743993', structVersion: 'V1.0.1' },
    });
    await open();
    expect(rows()).toHaveLength(25);
    expect(host.querySelector('[data-native-pager]')).toBeNull();
    expect(host.querySelector('.ant-pagination')).toBeNull();
    expect(host.textContent).not.toContain('版本与变更');
  });
});
