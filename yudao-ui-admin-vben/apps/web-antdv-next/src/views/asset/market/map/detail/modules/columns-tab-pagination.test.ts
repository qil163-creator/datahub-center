/* eslint-disable vue/one-component-per-file -- 分页回归隔离弹窗与原生分页渲染层 */
import type { App, PropType } from 'vue';

import type { DataAssetApi } from '#/api/asset/dataasset';

import {
  createApp,
  defineComponent,
  h,
  nextTick,
  reactive,
  shallowRef,
} from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import ColumnsTab from './columns-tab.vue';

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
        const change = (currentPage: number, pageSize = props.pageSize) =>
          emit('page-change', { currentPage, pageSize });
        return () =>
          h(
            'nav',
            {
              'data-pager': '',
              'data-current': props.currentPage,
              'data-size': props.pageSize,
              'data-total': props.total,
              'data-layouts': props.layouts.join(','),
            },
            [
              h(
                'button',
                {
                  'data-next': '',
                  disabled:
                    props.currentPage >=
                    Math.max(1, Math.ceil(props.total / props.pageSize)),
                  onClick: () => change(props.currentPage + 1),
                },
                '下一页',
              ),
              h(
                'button',
                {
                  'data-size': '20',
                  onClick: () => change(props.currentPage, 20),
                },
                '每页20条',
              ),
            ],
          );
      },
    }),
  ),
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({ hasAccessByCodes: () => false }),
}));
vi.mock('@vben/icons', () => ({ IconifyIcon: () => null }));
vi.mock('@vben/common-ui', () => ({
  useVbenModal: () => [defineComponent({ render: () => null }), {}],
}));
vi.mock('#/api/governance/level', () => ({ getSimpleLevelList: vi.fn() }));
vi.mock('./column-form.vue', () => ({
  default: defineComponent({ render: () => null }),
}));
vi.mock('./term-bind-form.vue', () => ({
  default: defineComponent({ render: () => null }),
}));

const initialId = '9007199254740993';
const columns: DataAssetApi.MetadataColumn[] = Array.from(
  { length: 25 },
  (_, index) => ({
    id: index === 0 ? initialId : String(1000 + index),
    columnName: `field-${String(index + 1).padStart(3, '0')}`,
    businessName: `字段${index + 1}`,
    dataType: 'VARCHAR',
  }),
);
let state: { assetId: string; columns: DataAssetApi.MetadataColumn[] };
let app: App | undefined;
let host: HTMLDivElement;

async function flush() {
  for (let index = 0; index < 10; index++) await Promise.resolve();
  await vi.dynamicImportSettled();
  await nextTick();
}
function rows() {
  return [
    ...host.querySelectorAll<HTMLElement>('.ant-table-tbody > .ant-table-row'),
  ];
}
function pager() {
  const node = host.querySelector<HTMLElement>('[data-pager]');
  expect(node).not.toBeNull();
  return node!;
}
async function nextPage() {
  pager().querySelector<HTMLButtonElement>('[data-next]')!.click();
  await flush();
}
async function filterColumns(value: string) {
  let input = document.querySelector<HTMLInputElement>(
    'input[placeholder="搜索字段名 / 中文名 / 术语"]',
  );
  if (!input) {
    host.querySelector<HTMLButtonElement>('[aria-label="表格设置"]')!.click();
    await flush();
    input = document.querySelector<HTMLInputElement>(
      'input[placeholder="搜索字段名 / 中文名 / 术语"]',
    );
  }
  expect(input).not.toBeNull();
  input!.value = value;
  input!.dispatchEvent(new Event('input', { bubbles: true }));
  await flush();
}

beforeEach(async () => {
  state = reactive({ assetId: initialId, columns: [...columns] });
  host = document.createElement('div');
  document.body.append(host);
  app = createApp({ setup: () => () => h(ColumnsTab, state) });
  app.directive('access', {});
  app.mount(host);
  await flush();
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
});

describe('资产地图字段统一项目分页', () => {
  it('复用统一布局并按真实字段翻页，调整容量返回首页', async () => {
    expect(pager().classList.contains('list-pagination')).toBe(true);
    expect(pager().dataset.layouts).toBe(
      'Total,Sizes,Home,PrevJump,PrevPage,Number,NextPage,NextJump,End',
    );
    expect(host.querySelector('.ant-pagination')).toBeNull();
    expect(pager().dataset.total).toBe('25');
    expect(rows()).toHaveLength(10);
    expect(rows()[0]?.dataset.rowKey).toBe(initialId);
    await nextPage();
    expect(rows()[0]?.textContent).toContain('field-011');
    pager().querySelector<HTMLButtonElement>('[data-size="20"]')!.click();
    await flush();
    expect(pager().dataset.current).toBe('1');
    expect(rows()).toHaveLength(20);
    expect(rows()[0]?.textContent).toContain('field-001');
    await nextPage();
    expect(rows()).toHaveLength(5);
    expect(rows()[0]?.textContent).toContain('field-021');
  });

  it('过滤字段时总数同步且回首页，空结果仍有零条分页', async () => {
    await nextPage();
    await nextPage();
    await filterColumns('field-002');
    expect(pager().dataset.current).toBe('1');
    expect(pager().dataset.total).toBe('1');
    expect(rows()).toHaveLength(1);
    expect(rows()[0]?.textContent).toContain('field-002');
    await filterColumns('missing-field');
    expect(pager().dataset.current).toBe('1');
    expect(pager().dataset.total).toBe('0');
    expect(rows()).toHaveLength(0);
    expect(
      pager().querySelector<HTMLButtonElement>('[data-next]')!.disabled,
    ).toBe(true);
  });

  it('字段减少时修正页码，切换资产身份回首页', async () => {
    await nextPage();
    await nextPage();
    state.columns = columns.slice(0, 12);
    await flush();
    expect(pager().dataset.current).toBe('2');
    expect(pager().dataset.total).toBe('12');
    expect(rows()).toHaveLength(2);
    expect(rows()[0]?.textContent).toContain('field-011');
    state.assetId = '9007199254740995';
    await flush();
    expect(pager().dataset.current).toBe('1');
    expect(rows()).toHaveLength(10);
    state.columns = [];
    await flush();
    expect(pager().dataset.total).toBe('0');
    expect(pager().dataset.current).toBe('1');
    expect(rows()).toHaveLength(0);
  });
});
