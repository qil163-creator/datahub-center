/* eslint-disable vue/one-component-per-file -- Isolated controls exercise the real maintenance content and request lifecycle. */
import type { App, PropType } from 'vue';

import type { GovernanceMasterDataApi } from '#/api/governance/masterdata';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { getMasterDataPage } from '#/api/governance/masterdata';
import { submitMasterData } from '#/api/governance/mastermanage';

import DataContent from './data-content.vue';

interface Action {
  disabled?: boolean;
  ifShow?: boolean;
  label: string;
  onClick?: () => void;
}
interface GridState {
  data: GovernanceMasterDataApi.Row[];
  loading: boolean;
  pagerConfig: { currentPage: number; pageSize: number; total: number };
}

const harness = vi.hoisted(() => ({
  access: { accessToken: '1', refreshToken: '' },
  applied: [] as GovernanceMasterDataApi.DataCondition[],
  grid: undefined as GridState | undefined,
  modalOpen: vi.fn(),
  modalClose: vi.fn(),
  confirm: undefined as (() => Promise<void>) | undefined,
  setGridOptions: vi.fn(),
  user: { userInfo: { userId: '7' } },
}));

vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => harness.access,
  useUserStore: () => harness.user,
}));
vi.mock('@vben/common-ui', () => ({
  useVbenModal: (options: { onConfirm: () => Promise<void> }) => {
    harness.confirm = options.onConfirm;
    return [
    defineComponent({ setup: (_, { slots }) => () => h('section', slots.default?.()) }),
    {
      close: harness.modalClose,
      lock: vi.fn(),
      open: harness.modalOpen,
      unlock: vi.fn(),
    },
  ];
  },
}));
vi.mock('@vben/utils', () => ({ formatDateTime: String }));
vi.mock('@vben/icons', () => ({
  IconifyIcon: defineComponent(() => () => null),
}));
vi.mock('../../masterdata/data', () => ({ fieldTypePlaceholder: () => '' }));
vi.mock('#/api/governance/masterdata', () => ({ getMasterDataPage: vi.fn() }));
vi.mock('#/api/governance/mastermanage', () => ({ submitMasterData: vi.fn() }));
vi.mock('./data-filter.vue', () => ({
  default: defineComponent({
    props: { loading: Boolean },
    emits: ['apply'],
    setup:
      (props, { emit }) =>
      () =>
        h('div', [
          h('span', { 'data-filter-loading': '' }, String(props.loading)),
          h(
            'button',
            { onClick: () => emit('apply', harness.applied) },
            '应用筛选',
          ),
        ]),
  }),
}));
vi.mock('antdv-next', () => {
  const container = defineComponent({
    setup:
      (_, { attrs, slots }) =>
      () =>
        h('div', attrs, slots.default?.()),
  });
  return {
    Alert: defineComponent({
      props: { message: { default: '', type: String } },
      setup: (props) => () => h('div', { 'data-alert': '' }, props.message),
    }),
    Button: container,
    DatePicker: container,
    Form: container,
    FormItem: container,
    Input: defineComponent({
      props: { value: { type: [String, Number], default: undefined } },
      emits: ['update:value'],
      setup: (props, { attrs, emit }) => () => h('input', { ...attrs, value: props.value, onInput: (event: Event) => emit('update:value', (event.target as HTMLInputElement).value) }),
    }),
    InputNumber: container,
    Tabs: container,
    TabPane: container,
    TextArea: container,
    Tooltip: container,
    Modal: { confirm: vi.fn() },
    message: {
      error: vi.fn(),
      info: vi.fn(),
      success: vi.fn(),
      warning: vi.fn(),
    },
  };
});
vi.mock('#/adapter/vxe-table', () => ({
  ACTION_ICON: {},
  TableAction: defineComponent({
    props: {
      actions: { default: () => [], type: Array as PropType<Action[]> },
    },
    setup: (props) => () =>
      h(
        'div',
        props.actions
          ?.filter((action) => action.ifShow !== false)
          .map((action) =>
            h(
              'button',
              { disabled: action.disabled, onClick: action.onClick },
              action.label,
            ),
          ),
      ),
  }),
  useVbenVxeGrid: (options: {
    gridEvents: {
      pageChange: (page: { currentPage: number; pageSize: number }) => void;
    };
    gridOptions: {
      toolbarConfig: { refreshOptions: { queryMethod: () => Promise<void> } };
    };
  }) => [
    defineComponent({
      setup:
        (_, { slots }) =>
        () =>
          h('section', [
            slots['toolbar-actions']?.(),
            slots['toolbar-tools']?.(),
            h('pre', { 'data-rows': '' }, JSON.stringify(harness.grid!.data)),
            h(
              'span',
              { 'data-grid-loading': '' },
              String(harness.grid!.loading),
            ),
            ...harness.grid!.data.map((row) => slots.actions?.({ row })),
            h(
              'button',
              {
                onClick: () =>
                  options.gridEvents.pageChange({
                    currentPage: 3,
                    pageSize: 20,
                  }),
              },
              '第三页',
            ),
            h(
              'button',
              {
                onClick: () =>
                  void options.gridOptions.toolbarConfig.refreshOptions.queryMethod(),
              },
              '刷新',
            ),
          ]),
    }),
    { setGridOptions: harness.setGridOptions },
  ],
}));

let app: App | undefined;
let host: HTMLDivElement;
let model: GovernanceMasterDataApi.Model;

async function flush() {
  for (let index = 0; index < 4; index++) {
    await Promise.resolve();
    await nextTick();
  }
}
async function mount() {
  app = createApp(DataContent, { model });
  app.directive('access', {});
  app.mount(host);
  await flush();
}
function button(label: string) {
  const result = [...host.querySelectorAll('button')].find(
    (item) => item.textContent === label,
  );
  expect(result).toBeDefined();
  return result!;
}
function deferred() {
  let resolve!: (result: GovernanceMasterDataApi.DataPageResult) => void;
  const promise = new Promise<GovernanceMasterDataApi.DataPageResult>(
    (done) => {
      resolve = done;
    },
  );
  return { promise, resolve };
}

beforeEach(() => {
  vi.clearAllMocks();
  harness.access = reactive({ accessToken: '1', refreshToken: '' });
  harness.user = reactive({ userInfo: { userId: '7' } });
  harness.applied = [];
  harness.grid = reactive({
    data: [],
    loading: false,
    pagerConfig: { currentPage: 1, pageSize: 10, total: 0 },
  });
  harness.setGridOptions.mockImplementation((options: Partial<GridState>) => {
    Object.assign(harness.grid!, options);
  });
  model = reactive({
    id: '9007199254740993',
    tableName: 'md_customer',
    maintenanceSupported: true,
    columns: [
      { name: 'id', pk: 1, fieldType: 5 },
      { name: 'name', fieldType: 2 },
    ],
  });
  vi.mocked(getMasterDataPage).mockResolvedValue({
    list: [{ id: '9007199254740995', name: '已载入数据' }],
    total: 1,
  });
  host = document.createElement('div');
  document.body.append(host);
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
});

describe('主数据维护筛选与表格接线', () => {
  it.each(['REJECTED', 'APPLIED'] as const)('写入回执%s按真实结果保留或关闭表单', async (status) => {
    await mount();
    button('修改').click(); await flush();
    const input = [...host.querySelectorAll('input')].find((item) => item.value === '已载入数据')!;
    input.value = '仍需修正的输入'; input.dispatchEvent(new Event('input')); await flush();
    harness.modalClose.mockClear();
    vi.mocked(submitMasterData).mockResolvedValue({ id: '9007199254740997', status, approvalRequired: false, message: '原数据已变化' });
    await harness.confirm!(); await flush();
    expect(submitMasterData).toHaveBeenCalledWith(expect.objectContaining({ operation: 'UPDATE', rows: [{ id: '9007199254740995', name: '仍需修正的输入' }] }));
    expect(harness.modalClose).toHaveBeenCalledTimes(status === 'REJECTED' ? 0 : 1);
    expect(input.value).toBe('仍需修正的输入');
    expect(host.textContent?.includes('原数据已变化')).toBe(status === 'REJECTED');
  });
  it('修改先按完整主键读取当前行，超大主键保持原字符串', async () => {
    await mount();
    button('修改').click();
    await flush();
    expect(getMasterDataPage).toHaveBeenLastCalledWith({
      modelId: '9007199254740993',
      pageNo: 1,
      pageSize: 2,
      conditions: [{ name: 'id', operator: 'EQ', value: '9007199254740995' }],
    });
    expect(harness.modalOpen).toHaveBeenCalledOnce();
  });
  it('重新读取的行已删除或身份变化时不会打开旧行表单', async () => {
    await mount();
    vi.mocked(getMasterDataPage).mockResolvedValueOnce({ list: [], total: 0 });
    button('修改').click();
    await flush();
    expect(harness.modalOpen).not.toHaveBeenCalled();
    const pending = deferred();
    vi.mocked(getMasterDataPage).mockReturnValueOnce(pending.promise);
    button('详情').click();
    harness.access.accessToken = 'other-session';
    await flush();
    pending.resolve({
      list: [{ id: '9007199254740995', name: '迟到旧行' }],
      total: 1,
    });
    await flush();
    expect(harness.modalOpen).not.toHaveBeenCalled();
  });
  it('应用筛选回到第一页，翻页与刷新只使用已应用的独立快照', async () => {
    await mount();
    button('第三页').click();
    await flush();
    harness.applied = [
      { name: 'name', operator: 'CONTAINS', value: 'Alice' },
      { name: 'id', operator: 'GT', value: '9007199254740993' },
      { name: 'name', operator: 'IS_NOT_NULL' },
    ];
    button('应用筛选').click();
    harness.applied[0]!.value = '尚未应用的草稿';
    harness.applied[1]!.operator = 'LT';
    await flush();

    expect(getMasterDataPage).toHaveBeenLastCalledWith({
      modelId: '9007199254740993',
      pageNo: 1,
      pageSize: 20,
      conditions: [
        { name: 'name', operator: 'CONTAINS', value: 'Alice' },
        { name: 'id', operator: 'GT', value: '9007199254740993' },
        { name: 'name', operator: 'IS_NOT_NULL' },
      ],
    });
    button('第三页').click();
    await flush();
    button('刷新').click();
    await flush();
    expect(getMasterDataPage).toHaveBeenLastCalledWith({
      modelId: '9007199254740993',
      pageNo: 3,
      pageSize: 20,
      conditions: [
        { name: 'name', operator: 'CONTAINS', value: 'Alice' },
        { name: 'id', operator: 'GT', value: '9007199254740993' },
        { name: 'name', operator: 'IS_NOT_NULL' },
      ],
    });

    harness.applied = [];
    button('应用筛选').click();
    await flush();
    expect(getMasterDataPage).toHaveBeenLastCalledWith(
      expect.objectContaining({ pageNo: 1, conditions: [] }),
    );
  });

  it('仅明确支持时开放维护，不支持显示后端原因，缺失能力显示待刷新', async () => {
    await mount();
    expect(button('新增').disabled).toBe(false);
    expect(button('修改')).toBeDefined();
    expect(host.querySelector('[data-alert]')).toBeNull();

    model.maintenanceSupported = false;
    model.maintenanceUnsupportedReason = '当前 MySQL 数据源暂不支持原子维护';
    await flush();
    expect(button('新增').disabled).toBe(true);
    expect(host.textContent).toContain(model.maintenanceUnsupportedReason);
    expect(host.textContent).not.toContain('修改');
    expect(host.textContent).not.toContain('删除');
    button('新增').click();
    expect(harness.modalOpen).not.toHaveBeenCalled();

    model.maintenanceSupported = undefined;
    model.maintenanceUnsupportedReason = undefined;
    await flush();
    expect(button('新增').disabled).toBe(true);
    expect(host.textContent).toContain('尚未获取维护能力');
  });

  it.each(['old-first', 'new-first'])(
    '快速连续筛选仅当前请求写入行与 loading：%s',
    async (order) => {
      await mount();
      const old = deferred();
      const current = deferred();
      vi.mocked(getMasterDataPage)
        .mockReturnValueOnce(old.promise)
        .mockReturnValueOnce(current.promise);
      harness.applied = [{ name: 'name', value: 'old' }];
      button('应用筛选').click();
      harness.applied = [{ name: 'name', value: 'current' }];
      button('应用筛选').click();
      await flush();
      const oldResult = { list: [{ id: '1', name: '旧查询行' }], total: 99 };
      const currentResult = {
        list: [{ id: '9007199254740995', name: '当前查询行' }],
        total: 1,
      };

      if (order === 'old-first') old.resolve(oldResult);
      else current.resolve(currentResult);
      await flush();
      for (const marker of ['data-grid-loading', 'data-filter-loading'])
        expect(host.querySelector(`[${marker}]`)!.textContent).toBe(
          String(order === 'old-first'),
        );
      expect(host.textContent).not.toContain('旧查询行');

      if (order === 'old-first') current.resolve(currentResult);
      else old.resolve(oldResult);
      await flush();
      expect(host.querySelector('[data-rows]')!.textContent).toContain(
        '当前查询行',
      );
      expect(host.textContent).not.toContain('旧查询行');
      expect(harness.grid!.pagerConfig.total).toBe(1);
      expect(harness.grid!.loading).toBe(false);
      expect(harness.grid!.data[0]!.id).toBe('9007199254740995');
    },
  );

  it('卸载后迟到列表结果不再写入表格', async () => {
    const pending = deferred();
    vi.mocked(getMasterDataPage).mockReturnValueOnce(pending.promise);
    await mount();
    app!.unmount();
    app = undefined;
    const calls = harness.setGridOptions.mock.calls.length;
    pending.resolve({ list: [{ id: '1', name: '迟到数据' }], total: 1 });
    await flush();

    expect(harness.setGridOptions).toHaveBeenCalledTimes(calls);
    expect(host.textContent).toBe('');
  });
});
