/* eslint-disable vue/one-component-per-file -- 仅隔离分页底层渲染，保留真实表格与分页适配器 */
import type { App, Component, PropType } from 'vue';

import type { MarketCheckApi } from '#/api/asset/market/check';

import {
  createApp,
  defineComponent,
  h,
  nextTick,
  reactive,
  shallowRef,
} from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

import FieldsTab from './fields-tab.vue';
import PreviewTab from './preview-tab.vue';

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

let app: App | undefined;
let host: HTMLDivElement;

async function flush() {
  for (let index = 0; index < 10; index++) await Promise.resolve();
  await vi.dynamicImportSettled();
  await nextTick();
}

async function mount(component: Component, props: Record<string, unknown>) {
  host = document.createElement('div');
  document.body.append(host);
  app = createApp({ render: () => h(component, props) });
  app.mount(host);
  await flush();
}

afterEach(() => {
  app?.unmount();
  app = undefined;
  host?.remove();
});

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

async function changeSize(size: number) {
  const select = pager().querySelector<HTMLSelectElement>('select')!;
  select.value = String(size);
  select.dispatchEvent(new Event('change', { bubbles: true }));
  await flush();
}

function fields(count: number): MarketCheckApi.Column[] {
  return Array.from({ length: count }, (_, index) => ({
    id: String(index + 1),
    columnName: `FIELD_${String(index + 1).padStart(3, '0')}`,
    businessName: `字段-${index + 1}`,
  }));
}

function preview(count: number): MarketCheckApi.Preview {
  return {
    columns: [{ name: 'ID', label: '编号' }],
    rows: Array.from({ length: count }, (_, index) => ({
      ID: String(index + 1001),
    })),
  };
}

describe('审批快照统一项目分页', () => {
  it.each([
    { name: '字段', component: FieldsTab, props: { columns: [] } },
    { name: '预览', component: PreviewTab, props: { preview: preview(0) } },
  ])('$name 空态仍显示统一分页与真实零总数', async ({ component, props }) => {
    await mount(component, props);
    expect(pager().classList.contains('list-pagination')).toBe(true);
    expect(pager().dataset.layouts).toBe(
      'Total,Sizes,Home,PrevJump,PrevPage,Number,NextPage,NextJump,End',
    );
    expect(pager().dataset.total).toBe('0');
    expect(pager().dataset.current).toBe('1');
    expect(host.querySelector('.ant-pagination')).toBeNull();
    expect(rows()).toHaveLength(0);
  });

  it('字段表在全量数据排序后分页，容量及新字段快照重置当前页', async () => {
    const props = reactive({ columns: fields(25) });
    await mount(FieldsTab, props);
    expect(rows()).toHaveLength(10);
    await nextPage();
    expect(rows()[0]?.textContent).toContain('FIELD_011');
    expect(pager().dataset.current).toBe('2');
    const header = [...host.querySelectorAll<HTMLElement>('th')].find((item) =>
      item.textContent?.includes('编号'),
    )!;
    header.click();
    await flush();
    header.click();
    await flush();
    expect(pager().dataset.current).toBe('2');
    expect(rows()[0]?.textContent).toContain('FIELD_015');
    await nextPage();
    expect(rows()[0]?.textContent).toContain('FIELD_005');
    await changeSize(20);
    expect(pager().dataset.current).toBe('1');
    expect(rows()).toHaveLength(20);
    await nextPage();
    expect(rows()).toHaveLength(5);
    props.columns = fields(1);
    await flush();
    expect(pager().dataset.current).toBe('1');
    expect(pager().dataset.total).toBe('1');
    expect(rows()).toHaveLength(1);
  });

  it('预览分页跟随真实行数，查询及新快照回首页并保留筛选交互', async () => {
    const props = reactive({ preview: preview(25) });
    await mount(PreviewTab, props);
    await nextPage();
    expect(rows()[0]?.textContent).toContain('1011');
    await changeSize(20);
    expect(rows()).toHaveLength(20);
    expect(pager().dataset.current).toBe('1');
    await nextPage();
    expect(rows()).toHaveLength(5);
    host
      .querySelector<HTMLButtonElement>('[aria-label="添加筛选条件"]')!
      .click();
    await flush();
    const value = host.querySelector<HTMLInputElement>(
      'input[placeholder="请输入值"]',
    )!;
    value.value = '1025';
    value.dispatchEvent(new Event('input', { bubbles: true }));
    await flush();
    const query = [...host.querySelectorAll<HTMLButtonElement>('button')].find(
      (item) => item.textContent?.includes('查询'),
    )!;
    query.click();
    await flush();
    expect(pager().dataset.current).toBe('1');
    expect(pager().dataset.total).toBe('1');
    expect(rows()).toHaveLength(1);
    expect(rows()[0]?.textContent).toContain('1025');
    props.preview = preview(0);
    await flush();
    expect(pager().dataset.total).toBe('0');
    expect(pager().dataset.current).toBe('1');
    expect(rows()).toHaveLength(0);
  });
});
