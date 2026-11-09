/* eslint-disable vue/one-component-per-file -- 隔离控件以验证列表加载、受控状态及删除原子交互。 */
import type { TableRowSelection } from 'antdv-next';

import type { App, PropType } from 'vue';

import type { CategoryNode } from './model';

import type { ProjectCategoryApi as Api } from '#/api/dataforge/projectcategory';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import CategoryPage from './page.vue';

const harness = vi.hoisted(() => ({
  access: undefined as
    | undefined
    | { accessCodes: string[]; accessToken: string; refreshToken: string },
  table: undefined as
    | undefined
    | {
        dataSource: CategoryNode[];
        loading: boolean;
        rowSelection: TableRowSelection<CategoryNode>;
      },
  list: vi.fn(),
  get: vi.fn(),
  remove: vi.fn(),
  removeList: vi.fn(),
  status: vi.fn(),
  openForm: vi.fn(),
  openDetail: vi.fn(),
  closeDetail: vi.fn(),
  warning: vi.fn(),
}));
vi.mock('@vben/stores', () => {
  harness.access = reactive({
    accessToken: 'one',
    refreshToken: '',
    accessCodes: ['integration', 'dev'].flatMap((kind) =>
      ['query', 'create', 'update', 'delete'].map(
        (action) => `dataforge:${kind}-category:${action}`,
      ),
    ),
  });
  return {
    useAccessStore: () => harness.access,
    useUserStore: () => ({ userInfo: { userId: '7' }, userRoles: ['editor'] }),
  };
});
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      codes.some((code) => harness.access!.accessCodes.includes(code)),
  }),
}));
vi.mock('@vben/utils', () => ({ formatDate: String }));
vi.mock('@vben/common-ui', () => ({
  Page: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.()),
  }),
}));
// 用原生 Grid 的公开事件和数据入口隔离领域行为，不依赖 Ant Table 的内部结构。
vi.mock('#/adapter/vxe-table', () => ({
  useVbenVxeGrid: (options: {
    formOptions: { submitOnEnter?: boolean };
    gridEvents: {
      checkboxChange: (event: { records: CategoryNode[] }) => void;
    };
    gridOptions: {
      proxyConfig: {
        ajax: {
          query: (
            params: unknown,
            values: { name?: string },
          ) => Promise<unknown>;
        };
      };
    };
  }) => {
    const state = reactive({
      data: [] as CategoryNode[],
      loading: false,
      selected: [] as string[],
    });
    harness.table = {
      get dataSource() {
        return state.data;
      },
      get loading() {
        return state.loading;
      },
      rowSelection: {
        get selectedRowKeys() {
          return state.selected;
        },
        onChange(keys, records) {
          state.selected = keys.map(String);
          options.gridEvents.checkboxChange({ records });
        },
      },
    };
    const api = {
      setGridOptions: (value: { data?: CategoryNode[]; loading?: boolean }) => {
        if (value.data) state.data = value.data;
        if (value.loading !== undefined) state.loading = value.loading;
      },
      grid: {
        clearCheckboxRow: () => {
          state.selected = [];
        },
        setTreeExpand: vi.fn(),
        setAllTreeExpand: vi.fn(),
        openCustom: vi.fn(),
      },
    };
    const Grid = defineComponent({
      setup:
        (_, { slots }) =>
        () =>
          h('section', [
            h('form', [
              h('input', {
                'data-search-name': '',
                onKeydown: (event: KeyboardEvent) => {
                  if (
                    event.key === 'Enter' &&
                    options.formOptions.submitOnEnter
                  ) {
                    event.preventDefault();
                    void options.gridOptions.proxyConfig.ajax.query(undefined, {
                      name: (event.target as HTMLInputElement).value,
                    });
                  }
                },
              }),
            ]),
            slots['toolbar-tools']?.(),
            ...state.data.map((row) =>
              h('div', { 'data-row-id': String(row.id) }, [
                row.name,
                slots.status?.({ row }),
                slots.actions?.({ row }),
              ]),
            ),
          ]),
    });
    return [Grid, api];
  },
}));
vi.mock('#/api/dataforge/projectcategory', () => ({
  getProjectCategoryList: harness.list,
  getProjectCategory: harness.get,
  deleteProjectCategory: harness.remove,
  deleteProjectCategoryList: harness.removeList,
  updateProjectCategoryStatus: harness.status,
}));
vi.mock('./detail.vue', () => ({
  default: defineComponent({
    setup: (_, { expose }) => {
      expose({ open: harness.openDetail, close: harness.closeDetail });
      return () => null;
    },
  }),
}));
vi.mock('./form.vue', () => ({
  default: defineComponent({
    setup: (_, { expose }) => {
      expose({ open: harness.openForm, close: vi.fn() });
      return () => null;
    },
  }),
}));
vi.mock('antdv-next', () => {
  const Container = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.()),
  });
  return {
    Alert: defineComponent({
      props: { message: { type: String, default: '' } },
      setup: (props) => () => h('div', { role: 'alert' }, props.message),
    }),
    Button: defineComponent({
      setup:
        (_, { slots, attrs }) =>
        () =>
          h('button', attrs, slots.default?.()),
    }),
    Checkbox: Container,
    Input: Container,
    TreeSelect: Container,
    Tooltip: Container,
    Popover: Container,
    Modal: defineComponent({
      props: { open: Boolean },
      emits: ['ok', 'cancel'],
      setup:
        (props, { emit, slots }) =>
        () =>
          props.open
            ? h('section', { role: 'alertdialog' }, [
                slots.default?.(),
                h(
                  'button',
                  { 'data-confirm': '', onClick: () => emit('ok') },
                  '确定',
                ),
                h(
                  'button',
                  { 'data-cancel': '', onClick: () => emit('cancel') },
                  '取消',
                ),
              ])
            : null,
    }),
    Switch: defineComponent({
      props: { checked: Boolean, disabled: Boolean },
      emits: ['change'],
      setup:
        (props, { emit }) =>
        () =>
          h(
            'button',
            {
              'data-switch': '',
              'data-checked': String(props.checked),
              disabled: props.disabled,
              onClick: () => emit('change', !props.checked),
            },
            '状态',
          ),
    }),
    Table: defineComponent({
      props: {
        dataSource: {
          type: Array as PropType<CategoryNode[]>,
          default: () => [],
        },
        loading: Boolean,
        rowSelection: {
          type: Object as PropType<TableRowSelection<CategoryNode>>,
          required: true,
        },
      },
      setup: (props, { slots }) => {
        harness.table = props;
        return () =>
          h(
            'div',
            props.dataSource.map((record) =>
              h('div', { 'data-row-id': String(record.id) }, [
                record.name,
                slots.bodyCell?.({ column: { key: 'status' }, record }),
                slots.bodyCell?.({ column: { key: 'actions' }, record }),
              ]),
            ),
          );
      },
    }),
    message: { success: vi.fn(), warning: harness.warning },
  };
});

let app: App;
let host: HTMLDivElement;
const id = '9007199254740993';
const row: Api.Category = {
  id,
  parentId: '0',
  name: '当前类目',
  status: 0,
  sort: 0,
  description: '',
  taskCount: 0,
  childCount: 0,
};
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((yes) => {
    resolve = yes;
  });
  return { promise, resolve };
}
async function flush() {
  for (let index = 0; index < 8; index++) await Promise.resolve();
  await nextTick();
}
function mount(kind: Api.Kind = 'integration') {
  app = createApp(CategoryPage, { kind });
  app.mount(host);
}
function toolbarButton(label: string) {
  return [
    ...host.querySelectorAll<HTMLButtonElement>('.qpc-toolbar-actions button'),
  ].find((button) => button.textContent?.trim() === label)!;
}
beforeEach(() => {
  vi.clearAllMocks();
  Object.assign(harness.access!, {
    accessToken: 'one',
    refreshToken: '',
    accessCodes: [
      ...['integration', 'dev'].flatMap((kind) =>
        ['query', 'create', 'update', 'delete'].map(
          (action) => `dataforge:${kind}-category:${action}`,
        ),
      ),
      'governance:master-push-category:query',
    ],
  });
  harness.list.mockResolvedValue([row]);
  harness.get.mockResolvedValue(row);
  harness.remove.mockResolvedValue(true);
  harness.removeList.mockResolvedValue(true);
  harness.status.mockResolvedValue(true);
  host = document.createElement('div');
  document.body.append(host);
});
afterEach(() => {
  app?.unmount();
  host.remove();
});

describe('项目类目列表', () => {
  it.each([
    ['integration', 'dataforge:integration-category:query'],
    ['development', 'dataforge:dev-category:query'],
    ['masterPush', 'governance:master-push-category:query'],
  ] as const)('%s 仅有查询权限时仍可查看只读详情', async (kind, permission) => {
    harness.access!.accessCodes = [permission];
    mount(kind);
    await flush();
    const actions = host.querySelector('.qpc-row-actions')!;
    const buttons = [...actions.querySelectorAll<HTMLButtonElement>('button')];
    expect(buttons.map((button) => button.textContent?.trim())).toEqual(['详情']);
    buttons[0]!.click();
    await flush();
    expect(harness.openDetail).toHaveBeenCalledExactlyOnceWith({ id });
    expect(harness.openForm).not.toHaveBeenCalled();
  });

  it('原生表单仍支持输入类目名称后按 Enter 查询', async () => {
    harness.list.mockResolvedValueOnce([
      row,
      { ...row, id: '9007199254740995', name: '其他类目' },
    ]);
    mount();
    await flush();
    const input = host.querySelector<HTMLInputElement>('[data-search-name]')!;
    input.value = '当前';
    input.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }),
    );
    await flush();
    expect(harness.table?.dataSource.map((item) => item.name)).toEqual([
      '当前类目',
    ]);
  });

  it('真实加载失败显示错误，刷新成功后才恢复正常列表', async () => {
    harness.list.mockRejectedValueOnce(new Error('服务不可用'));
    mount();
    await flush();
    expect(host.querySelector('[role="alert"]')?.textContent).toContain(
      '类目信息加载失败',
    );
    expect(harness.table?.loading).toBe(false);
    host.querySelector<HTMLButtonElement>('[aria-label="刷新"]')!.click();
    await flush();
    expect(host.querySelector('[role="alert"]')).toBeNull();
    expect(host.textContent).toContain('当前类目');
  });

  it('登录切换后旧列表不能覆盖新响应或提前结束新加载', async () => {
    const old = deferred<Api.Category[]>();
    const current = deferred<Api.Category[]>();
    harness.list
      .mockReturnValueOnce(old.promise)
      .mockReturnValueOnce(current.promise);
    mount();
    harness.access!.accessToken = 'two';
    await flush();
    old.resolve([{ ...row, name: '旧账号数据' }]);
    await flush();
    expect(host.textContent).not.toContain('旧账号数据');
    expect(harness.table?.loading).toBe(true);
    current.resolve([row]);
    await flush();
    expect(host.textContent).toContain('当前类目');
    expect(harness.table?.loading).toBe(false);
  });

  it('批量删除经确认后才请求，失败保留选择、成功清空', async () => {
    harness.list.mockResolvedValueOnce([{ ...row, status: 1 }]);
    mount();
    await flush();
    expect(toolbarButton('删除').disabled).toBe(true);
    harness.table!.rowSelection.onChange?.([id], [row as CategoryNode], {
      type: 'multiple',
    });
    await flush();
    harness.removeList.mockRejectedValueOnce(new Error('类目被任务引用'));
    toolbarButton('删除').click();
    await flush();
    expect(harness.removeList).not.toHaveBeenCalled();
    host.querySelector<HTMLButtonElement>('[data-confirm]')!.click();
    await flush();
    expect(harness.removeList).toHaveBeenCalledExactlyOnceWith('integration', [
      id,
    ]);
    expect(harness.table!.rowSelection.selectedRowKeys).toEqual([id]);
    expect(host.querySelector('[role="alertdialog"]')).not.toBeNull();
    host.querySelector<HTMLButtonElement>('[data-confirm]')!.click();
    await flush();
    expect(harness.table!.rowSelection.selectedRowKeys).toEqual([]);
    expect(host.querySelector('[role="alertdialog"]')).toBeNull();
    expect(toolbarButton('删除').disabled).toBe(true);
  });

  it('状态开关先确认并等服务端成功，不乐观改变原状态', async () => {
    mount();
    await flush();
    const pending = deferred<boolean>();
    harness.status.mockReturnValueOnce(pending.promise);
    const toggle = host.querySelector<HTMLButtonElement>('[data-switch]')!;
    toggle.click();
    await flush();
    expect(toggle.dataset.checked).toBe('true');
    expect(harness.status).not.toHaveBeenCalled();
    host.querySelector<HTMLButtonElement>('[data-confirm]')!.click();
    await flush();
    expect(harness.status).toHaveBeenCalledExactlyOnceWith(
      'integration',
      id,
      1,
    );
    expect(toggle.dataset.checked).toBe('true');
    harness.list.mockResolvedValueOnce([{ ...row, status: 1 }]);
    pending.resolve(true);
    await flush();
    expect(
      host.querySelector<HTMLButtonElement>('[data-switch]')!.dataset.checked,
    ).toBe('false');
  });

  it('启用类目行禁用删除，批量删除也先提示禁用', async () => {
    mount();
    await flush();
    expect(
      host.querySelector<HTMLButtonElement>('.qpc-row-actions button[danger]')!
        .disabled,
    ).toBe(true);
    harness.table!.rowSelection.onChange?.([id], [row as CategoryNode], {
      type: 'multiple',
    });
    await flush();
    toolbarButton('删除').click();
    await flush();
    expect(harness.warning).toHaveBeenCalledExactlyOnceWith(
      '请先禁用所选类目后再删除',
    );
    expect(host.querySelector('[role="alertdialog"]')).toBeNull();
    expect(harness.removeList).not.toHaveBeenCalled();
  });

  it.each([
    [2, 0, '该类目下存在2个子类目，不能直接删除。'],
    [0, 3, '该类目下存在3个任务，不能直接删除，请先迁移。'],
    [2, 3, '该类目包含2个子类目和3个任务，不能直接删除。'],
  ] as const)(
    '单删有%s个子类目、%s个任务时先告警并停止',
    async (childCount, taskCount, warning) => {
      harness.list.mockResolvedValueOnce([
        { ...row, status: 1, childCount, taskCount },
      ]);
      mount();
      await flush();
      host
        .querySelector<HTMLButtonElement>('.qpc-row-actions button[danger]')!
        .click();
      await flush();
      expect(harness.warning).toHaveBeenCalledExactlyOnceWith(warning);
      expect(host.querySelector('[role="alertdialog"]')).toBeNull();
      expect(harness.remove).not.toHaveBeenCalled();
    },
  );

  it('批量选择被任务引用的类目时不进入删除确认', async () => {
    const referenced = { ...row, status: 1, taskCount: 2 };
    harness.list.mockResolvedValueOnce([referenced]);
    mount();
    await flush();
    harness.table!.rowSelection.onChange?.([id], [referenced as CategoryNode], {
      type: 'multiple',
    });
    await flush();
    toolbarButton('删除').click();
    await flush();
    expect(harness.warning).toHaveBeenCalledExactlyOnceWith(
      '本次选择 1 个类目，存在被任务引用的类目，不能删除。',
    );
    expect(harness.removeList).not.toHaveBeenCalled();
  });

  it.each([
    [
      'integration',
      '确认停用该父类目？本次仅停用当前类目，子类目的启用状态保持不变。',
    ],
    [
      'development',
      '停用后，该类目及其子类目将不能用于新建或保存开发任务；子类目自身的启用状态保持不变。是否继续？',
    ],
  ] as const)(
    '禁用%s父类目前重取详情并准确提示作用范围，取消保留开关',
    async (kind, expected) => {
      harness.get.mockResolvedValueOnce({ ...row, childCount: 2 });
      mount(kind);
      await flush();
      host.querySelector<HTMLButtonElement>('[data-switch]')!.click();
      await flush();
      expect(harness.get).toHaveBeenCalledExactlyOnceWith(kind, id);
      expect(host.textContent).toContain(expected);
      host.querySelector<HTMLButtonElement>('[data-cancel]')!.click();
      await flush();
      expect(
        host.querySelector<HTMLButtonElement>('[data-switch]')!.dataset.checked,
      ).toBe('true');
      expect(harness.status).not.toHaveBeenCalled();
    },
  );
});
