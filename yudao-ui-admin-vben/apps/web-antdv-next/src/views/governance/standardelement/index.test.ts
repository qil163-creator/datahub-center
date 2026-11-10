/* eslint-disable vue/one-component-per-file -- 用轻量控件验证列表请求、筛选和会话隔离。 */
import type { App, PropType } from 'vue';

import type { ActionItem } from '#/components/table-action';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  getStandardElement,
  getStandardElementPage,
  updateStandardElementStatus,
} from '#/api/governance/standardelement';

import ElementList from './index.vue';

const harness = vi.hoisted(() => ({
  access: {
    accessToken: 'actor-a',
    refreshToken: '',
    accessCodes: [
      'governance:standard-element:query',
      'governance:standard-element:update',
    ],
  },
  confirm: vi.fn(),
}));
vi.mock('@vben/stores', () => ({ useAccessStore: () => harness.access }));
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      codes.every((code) => harness.access.accessCodes.includes(code)),
  }),
}));
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@vben/utils', () => ({
  formatDate: String,
  downloadFileFromBlobPart: vi.fn(),
}));
vi.mock('@vben/icons', () => ({
  IconifyIcon: defineComponent({ setup: () => () => null }),
}));
vi.mock('@vben/common-ui', () => ({
  confirm: (...args: unknown[]) => harness.confirm(...args),
  Page: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.()),
  }),
  useVbenModal: () => [
    defineComponent({ setup: () => () => null }),
    { open: vi.fn(), setData: vi.fn() },
  ],
}));
vi.mock('./data', () => ({
  formatCategoryName: String,
  formatLifecycleStatus: String,
  formatPersonName: String,
  loadElementNameCaches: async () => {},
  LIFECYCLE_STATUS_OPTIONS: [],
}));
vi.mock('./modules/form.vue', () => ({
  default: defineComponent({ setup: () => () => null }),
}));
vi.mock('../standardcommon/standard-list-layout.vue', () => ({
  default: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', [slots.tree?.(), slots.default?.()]),
  }),
}));
vi.mock('#/adapter/vxe-table', () => ({
  ACTION_ICON: {},
  TableAction: defineComponent({
    props: {
      actions: { type: Array as PropType<ActionItem[]>, default: () => [] },
      dropDownActions: {
        type: Array as PropType<ActionItem[]>,
        default: () => [],
      },
    },
    setup: (props) => () =>
      h(
        'div',
        [...(props.actions ?? []), ...(props.dropDownActions ?? [])]
          .filter((action) => action.ifShow !== false)
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
    formOptions: { handleSubmit: () => Promise<void> };
    gridEvents: {
      pageChange: (page: { currentPage: number; pageSize: number }) => void;
    };
    gridOptions: { columns: { field?: string; visible?: boolean }[] };
  }) => {
    const state = reactive({
      columns: options.gridOptions.columns,
      data: [] as { name: string }[],
      pagerConfig: { currentPage: 1, pageSize: 10 },
    });
    const values = reactive({ name: '' });
    const api = {
      formApi: {
        getValues: async () => ({ ...values }),
        reset: async () => {
          values.name = '';
        },
      },
      grid: { clearCheckboxRow: vi.fn(), sort: vi.fn() },
      setGridOptions: (next: Partial<typeof state>) => {
        if (next.data) state.data = next.data;
        if (next.columns) state.columns = next.columns;
        Object.assign(state.pagerConfig, next.pagerConfig);
      },
    };
    return [
      defineComponent({
        setup:
          (_, { slots }) =>
          () =>
            h('div', [
              h(
                'form',
                {
                  onSubmit: (event: Event) => {
                    event.preventDefault();
                    void options.formOptions.handleSubmit();
                  },
                },
                [
                  h('input', {
                    value: values.name,
                    onInput: (event: Event) => {
                      values.name = (event.target as HTMLInputElement).value;
                    },
                  }),
                ],
              ),
              slots['toolbar-tools']?.(),
              slots.top?.(),
              state.columns.some(
                (column) => column.field === 'selection' && column.visible !== false,
              )
                ? h('input', { type: 'checkbox', 'data-selection': '' })
                : null,
              h(
                'div',
                { 'data-rows': '' },
                state.data.map((row) =>
                  h('div', [row.name, slots.status?.({ row })]),
                ),
              ),
              h(
                'button',
                {
                  'data-next-page': '',
                  onClick: () =>
                    options.gridEvents.pageChange({
                      currentPage: state.pagerConfig.currentPage + 1,
                      pageSize: state.pagerConfig.pageSize,
                    }),
                },
                'next',
              ),
            ]),
      }),
      api,
    ];
  },
}));
vi.mock('./components/category-tree.vue', () => ({
  default: defineComponent({
    emits: ['select'],
    setup: (_, { emit, expose }) => {
      expose({ reset: vi.fn() });
      return () =>
        h(
          'button',
          {
            'data-category': '',
            onClick: () => emit('select', '9007199254740993'),
          },
          'category',
        );
    },
  }),
}));
vi.mock('#/api/governance/standardelement', () => ({
  getStandardElementPage: vi.fn(),
  getStandardElement: vi.fn(),
  getStandardElementStatistics: vi.fn(),
  updateStandardElement: vi.fn(),
  updateStandardElementStatus: vi.fn(),
  deleteStandardElement: vi.fn(),
  deleteStandardElementList: vi.fn(),
  deprecateStandardElement: vi.fn(),
  exportStandardElement: vi.fn(),
  publishStandardElement: vi.fn(),
}));
vi.mock('antdv-next', () => {
  const container = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.()),
  });
  return {
    Alert: container,
    Dropdown: container,
    Menu: container,
    MenuItem: container,
    Select: container,
    Tag: container,
    Button: defineComponent({
      props: { htmlType: { type: String, default: 'button' } },
      setup:
        (props, { slots, attrs }) =>
        () =>
          h(
            'button',
            { ...attrs, type: props.htmlType || 'button' },
            slots.default?.(),
          ),
    }),
    Input: defineComponent({
      props: { value: { type: String, default: '' } },
      emits: ['update:value'],
      setup:
        (props, { emit, attrs }) =>
        () =>
          h('input', {
            ...attrs,
            value: props.value,
            onInput: (event: Event) =>
              emit('update:value', (event.target as HTMLInputElement).value),
          }),
    }),
    Switch: defineComponent({
      emits: ['change'],
      setup:
        (_, { emit }) =>
        () =>
          h(
            'button',
            { 'data-status': '', onClick: () => emit('change') },
            'status',
          ),
    }),
    message: { success: vi.fn() },
  };
});

let app: App;
let host: HTMLDivElement;
function deferred<T>() {
  let resolve!: (result: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}
async function settle() {
  await nextTick();
  await Promise.resolve();
  await nextTick();
}
function mount() {
  app = createApp(ElementList);
  app.mount(host);
}
function click(selector: string) {
  host.querySelector<HTMLButtonElement>(selector)!.click();
}
const response = (name: string) => ({
  list: [
    {
      id: 1,
      name,
      engName: 'CODE',
      code: 'DE1',
      status: 0,
      categoryId: 1,
      columnType: 'VARCHAR',
      description: '',
      remark: '',
    },
  ],
  total: 25,
});
beforeEach(() => {
  vi.resetAllMocks();
  harness.access = reactive({
    accessToken: 'actor-a',
    refreshToken: '',
    accessCodes: [
      'governance:standard-element:query',
      'governance:standard-element:update',
    ],
  });
  vi.mocked(getStandardElementPage).mockResolvedValue(response('current'));
  host = document.createElement('div');
  document.body.append(host);
});
afterEach(() => {
  app?.unmount();
  host.remove();
});
describe('数据元目录查询', () => {
  it('批量删除勾选列保持可见，已移除的工具栏操作不再显示', async () => {
    mount();
    await settle();
    expect(host.querySelector('[data-selection]')).not.toBeNull();
    expect(host.textContent).toContain('批量删除');
    for (const label of ['导入', '批量操作', '退出批量操作', '导出', '统计概览']) {
      expect(host.textContent).not.toContain(label);
    }
    click('[data-next-page]');
    await settle();
    expect(host.querySelector('[data-selection]')).not.toBeNull();
  });
  it('真实分页后选择大 ID 类目会回到第一页，查询保留类目', async () => {
    mount();
    await settle();
    expect(getStandardElementPage).toHaveBeenLastCalledWith(
      expect.objectContaining({
        pageNo: 1,
        pageSize: 10,
        sortField: 'createTime',
        sortOrder: 'desc',
      }),
    );
    click('[data-next-page]');
    await settle();
    expect(getStandardElementPage).toHaveBeenLastCalledWith(
      expect.objectContaining({ pageNo: 2 }),
    );
    click('[data-category]');
    await settle();
    const input = host.querySelector<HTMLInputElement>('input')!;
    input.value = '水位';
    input.dispatchEvent(new Event('input'));
    await settle();
    host
      .querySelector('form')!
      .dispatchEvent(new Event('submit', { cancelable: true }));
    await settle();
    expect(getStandardElementPage).toHaveBeenLastCalledWith(
      expect.objectContaining({
        categoryId: '9007199254740993',
        pageNo: 1,
        name: '水位',
      }),
    );
  });
  it('后返回的旧分页不能覆盖新分类结果', async () => {
    const old = deferred<ReturnType<typeof response>>();
    vi.mocked(getStandardElementPage)
      .mockReturnValueOnce(old.promise)
      .mockResolvedValueOnce(response('new-category'));
    mount();
    click('[data-category]');
    await settle();
    old.resolve(response('old-page'));
    await settle();
    expect(host.querySelector('[data-rows]')?.textContent).toContain(
      'new-category',
    );
    expect(host.textContent).not.toContain('old-page');
  });
  it('确认状态变更期间切换会话，不读取或写入旧记录', async () => {
    const confirmation = deferred<undefined>();
    harness.confirm.mockReturnValue(confirmation.promise);
    mount();
    await settle();
    click('[data-status]');
    harness.access.accessToken = 'actor-b';
    await settle();
    confirmation.resolve(undefined);
    await settle();
    expect(getStandardElement).not.toHaveBeenCalled();
  });
  it('状态接口仅提交当前版本号，不覆盖完整定义', async () => {
    harness.confirm.mockResolvedValue(undefined);
    vi.mocked(getStandardElement).mockResolvedValue({
      ...response('current').list[0]!,
      revision: '9007199254740993',
    });
    mount();
    await settle();
    click('[data-status]');
    await settle();
    await settle();
    expect(updateStandardElementStatus).toHaveBeenCalledWith(
      1,
      1,
      '9007199254740993',
    );
  });
});
