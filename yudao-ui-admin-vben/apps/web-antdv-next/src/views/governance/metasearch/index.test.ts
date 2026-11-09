/* eslint-disable vue/one-component-per-file -- 检索回归使用隔离的表格和操作组件 */
import type { App, PropType } from 'vue';

import type { MetaSearchHit } from '#/api/governance/metaobject';

import { createApp, defineComponent, h, nextTick } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { searchMetaObject } from '#/api/governance/metaobject';

import SearchPage from './index.vue';

interface DetailAction {
  auth?: string[];
  label: string;
  onClick: () => void;
}
interface GridOptions {
  formOptions: {
    schema: {
      component: string;
      componentProps?: { options?: { label: string; value: unknown }[] };
      fieldName: string;
    }[];
  };
  gridOptions: {
    proxyConfig: {
      ajax: {
        query: (
          context: { page: { currentPage: number; pageSize: number } },
          formValues: Record<string, unknown>,
        ) => Promise<unknown>;
      };
    };
    rowConfig: { keyField: string };
  };
}

const harness = vi.hoisted(() => ({
  actions: [] as DetailAction[],
  errors: [] as unknown[],
  options: undefined as GridOptions | undefined,
  permissions: new Set<string>(),
  push: vi.fn(),
  rows: [] as MetaSearchHit[],
}));

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: harness.push }),
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      codes.some((code) => harness.permissions.has(code)),
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
vi.mock('@vben/utils', () => ({ formatDate: String, formatDateTime: String }));
vi.mock('antdv-next', () => ({
  Tag: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('span', slots.default?.()),
  }),
}));
vi.mock('#/api/governance/metaobject', () => ({ searchMetaObject: vi.fn() }));
vi.mock('#/adapter/vxe-table', () => ({
  ACTION_ICON: { VIEW: 'view' },
  TableAction: defineComponent({
    props: {
      actions: { type: Array as PropType<DetailAction[]>, default: () => [] },
    },
    setup: (props) => () => {
      harness.actions = props.actions;
      return h(
        'div',
        props.actions.map((action) =>
          h('button', { onClick: action.onClick }, action.label),
        ),
      );
    },
  }),
  useVbenVxeGrid: (options: GridOptions) => {
    harness.options = options;
    return [
      defineComponent({
        setup:
          (_, { slots }) =>
          () =>
            h(
              'section',
              harness.rows.map((row) => slots.actions?.({ row })),
            ),
      }),
    ];
  },
}));

const tableId = '9007199254740995';
let app: App | undefined;
let host: HTMLDivElement;

beforeEach(() => {
  vi.resetAllMocks();
  harness.actions = [];
  harness.errors = [];
  harness.permissions = new Set(['governance:meta-table:query']);
  harness.rows = [
    { objectId: '9007199254740997', tableId, tableName: 'orders' },
  ];
  harness.push.mockResolvedValue(undefined);
  vi.mocked(searchMetaObject).mockResolvedValue({ list: [], total: 0 });
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(SearchPage);
  app.config.errorHandler = (error) => harness.errors.push(error);
  app.mount(host);
});

afterEach(() => {
  app?.unmount();
  host.remove();
});

describe('元数据检索详情入口', () => {
  it('使用命中记录的无损表编号打开独立详情页', () => {
    host.querySelector('button')!.click();
    expect(harness.push).toHaveBeenCalledExactlyOnceWith({
      name: 'GovernanceMetaSearchDetail',
      params: { id: tableId },
    });
    expect(harness.actions[0]?.auth).toEqual(['governance:meta-table:query']);
    expect(harness.errors).toEqual([]);
  });

  it.each([
    { id: undefined },
    { id: '' },
    { id: '0' },
    { id: '-1' },
    { id: '1.5' },
    { id: ['1'] },
    { id: 9_007_199_254_740_992 },
  ])('无效或有损表编号 $id 不触发导航', async ({ id }) => {
    harness.rows[0]!.tableId = id as MetaSearchHit['tableId'];
    await nextTick();
    harness.actions[0]!.onClick();
    expect(harness.push).not.toHaveBeenCalled();
    expect(harness.errors).toEqual([]);
  });

  it('已展示的详情回调在撤销查询权限后也不能导航', () => {
    const previouslyVisible = harness.actions[0]!.onClick;
    harness.permissions.clear();
    previouslyVisible();
    expect(harness.push).not.toHaveBeenCalled();
    expect(harness.errors).toEqual([]);
  });

  it('继续使用原检索条件、分页参数和稳定命中记录编号', async () => {
    const options = harness.options!;
    expect(options.gridOptions.rowConfig.keyField).toBe('objectId');
    expect(options.formOptions.schema.map((field) => field.fieldName)).toEqual([
      'keyword',
      'layer',
      'publishStatus',
      'ownerMissing',
    ]);
    expect(
      options.formOptions.schema
        .find((field) => field.fieldName === 'layer')
        ?.componentProps?.options?.map((option) => option.value),
    ).toEqual(['ODS', 'DWD', 'DWS', 'ADS', 'DIM']);
    expect(
      options.formOptions.schema.find(
        (field) => field.fieldName === 'ownerMissing',
      )?.componentProps?.options,
    ).toEqual([{ label: '仅看未认责', value: true }]);

    const filters = {
      keyword: '订单金额',
      layer: 'DWD',
      publishStatus: 1,
      ownerMissing: true,
    };
    const result = { list: harness.rows, total: 23 };
    vi.mocked(searchMetaObject).mockResolvedValueOnce(result);
    const received = await options.gridOptions.proxyConfig.ajax.query(
      { page: { currentPage: 3, pageSize: 20 } },
      filters,
    );
    expect(searchMetaObject).toHaveBeenCalledExactlyOnceWith({
      pageNo: 3,
      pageSize: 20,
      ...filters,
    });
    expect(received).toBe(result);
    expect(filters).toEqual({
      keyword: '订单金额',
      layer: 'DWD',
      publishStatus: 1,
      ownerMissing: true,
    });
    expect(harness.errors).toEqual([]);
  });
});
