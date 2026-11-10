/* eslint-disable vue/one-component-per-file -- 用隔离控件验证页面交互和异步生命周期。 */
import type { App, PropType } from 'vue';

import type { GovernanceStandardCodeSetApi as Api } from '#/api/governance/standardcodeset';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  deleteStandardCodeSetList,
  getStandardCodeSetPage,
} from '#/api/governance/standardcodeset';

import CodeSetPage from './index.vue';

interface Action {
  label: string;
  ifShow?: boolean;
  disabled?: boolean;
  onClick?: () => void;
}
interface GridState {
  data: Api.StandardCodeSet[];
  loading?: boolean;
  pagerConfig?: { currentPage: number; pageSize: number; total: number };
}
interface GridOptions {
  formOptions: {
    handleReset: () => Promise<void>;
    handleSubmit: (values: { name?: string; type?: string }) => Promise<void>;
  };
  gridOptions: {
    columns: {
      fixed?: string;
      showOverflow?: boolean;
      title?: string;
      type?: string;
      visible?: boolean;
      width?: number;
    }[];
  };
  gridEvents: {
    checkboxChange: (event: { records: Api.StandardCodeSet[] }) => void;
    pageChange: (event: { currentPage: number; pageSize: number }) => void;
    sortChange: (event: { field: string; order: string }) => void;
  };
}
const state = vi.hoisted(() => ({
  access: {} as { accessCodes: string[]; accessToken: string; refreshToken: string },
  grid: {} as GridState,
  options: undefined as GridOptions | undefined,
  confirm: undefined as undefined | { onOk: () => Promise<void> },
  clearCheckbox: vi.fn(),
  modalOpen: [] as ReturnType<typeof vi.fn>[],
}));
const Slot = defineComponent({
  setup: (_, { slots }) => () => h('div', slots.default?.()),
});
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@vben/access', () => ({
  useAccess: () => ({ hasAccessByCodes: () => true }),
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => state.access,
  useUserStore: () => ({ userInfo: { userId: '99' } }),
}));
vi.mock('@vben/icons', () => ({
  IconifyIcon: defineComponent(() => () => null),
}));
vi.mock('@vben/common-ui', () => ({
  Page: defineComponent({
    setup: (_, { slots }) => () => h('main', slots.default?.()),
  }),
  useVbenModal: () => {
    const open = vi.fn();
    state.modalOpen.push(open);
    return [
      defineComponent(() => () => null),
      { setData: vi.fn(), open, close: vi.fn() },
    ];
  },
}));
vi.mock('antdv-next', () => ({
  Alert: defineComponent(() => () => null),
  Button: defineComponent({
    setup: (_, { slots }) => () => h('button', slots.default?.()),
  }),
  Input: defineComponent(() => () => h('input')),
  Tree: defineComponent(() => () => null),
  Tooltip: defineComponent({ setup: (_, { slots }) => () => h('div', slots.default?.()) }),
  Tag: defineComponent(() => () => null),
  Switch: defineComponent(() => () => null),
  message: { success: vi.fn() },
  Modal: {
    confirm: (options: { onOk: () => Promise<void> }) => {
      state.confirm = options;
    },
  },
}));
vi.mock('../standardcommon/standard-list-layout.vue', () => ({
  default: defineComponent({
    setup: (_, { slots }) => () => h('div', [slots.tree?.(), slots.default?.()]),
  }),
}));
vi.mock('./modules/form.vue', () => ({ default: {} }));
vi.mock('./modules/import-form.vue', () => ({ default: {} }));
vi.mock('#/api/governance/standardcodeset', () => ({
  deleteStandardCodeSet: vi.fn(),
  deleteStandardCodeSetList: vi.fn(),
  getStandardCodeSet: vi.fn(),
  getStandardCodeSetCategories: vi.fn(async () => []),
  getStandardCodeSetPage: vi.fn(),
  updateStandardCodeSetStatus: vi.fn(),
}));
vi.mock('#/adapter/vxe-table', () => ({
  ACTION_ICON: {},
  TableAction: defineComponent({
    props: {
      actions: { type: Array as PropType<Action[]>, default: () => [] },
      dropDownActions: { type: Array as PropType<Action[]>, default: () => [] },
    },
    setup: (props) => () =>
      h(
        'div',
        [...props.actions.map((action) => ({ action, dropdown: false })),
          ...props.dropDownActions.map((action) => ({ action, dropdown: true }))]
          .filter(({ action }) => action.ifShow !== false)
          .map(({ action, dropdown }) =>
            h(
              'button',
              { disabled: action.disabled, onClick: action.onClick, 'data-dropdown': dropdown ? '' : undefined },
              action.label,
            ),
          ),
      ),
  }),
  useVbenVxeGrid: (options: GridOptions) => {
    state.options = options;
    return [
      defineComponent({
        setup: (_, { slots }) => () =>
          h('section', [
            slots['toolbar-tools']?.(),
            h('div', { 'data-rows': true }, state.grid.data.map((row) => row.name).join(',')),
          ]),
      }),
      {
        setGridOptions: (options: Partial<GridState>) => Object.assign(state.grid, options),
        formApi: { reset: vi.fn(async () => {}) },
        grid: { clearCheckboxRow: state.clearCheckbox },
      },
    ];
  },
}));

let app: App | undefined;
let container: HTMLDivElement;
async function settle() {
  await Promise.resolve();
  await nextTick();
  await Promise.resolve();
  await nextTick();
}
function mount() {
  container = document.createElement('div');
  document.body.append(container);
  app = createApp({ render: () => h(Slot, () => h(CodeSetPage)) });
  app.mount(container);
}
function click(label: string) {
  const button = [...container.querySelectorAll('button')].find(
    (button) => button.textContent === label,
  );
  expect(button).toBeDefined();
  button!.click();
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}
beforeEach(() => {
  vi.clearAllMocks();
  state.access = reactive({ accessToken: 'token', refreshToken: 'refresh', accessCodes: [] });
  state.grid = reactive({ data: [] });
  state.options = undefined;
  state.confirm = undefined;
  state.modalOpen = [];
  vi.mocked(getStandardCodeSetPage).mockResolvedValue({ list: [], total: 0 });
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  container?.remove();
});

describe('代码表列表', () => {
  it('保留已提交筛选及远端分页排序，并固定完整操作列', async () => {
    mount();
    await settle();
    const options = state.options!;
    const actions = options.gridOptions.columns.find((column) => column.title === '操作');
    expect(actions).toMatchObject({ fixed: 'right', showOverflow: false });
    expect(actions!.width).toBeGreaterThanOrEqual(260);
    await options.formOptions.handleSubmit({ name: '性别', type: 'sex' });
    options.gridEvents.pageChange({ currentPage: 2, pageSize: 20 });
    await settle();
    expect(getStandardCodeSetPage).toHaveBeenLastCalledWith(expect.objectContaining({
      name: '性别', type: 'sex', pageNo: 2, pageSize: 20,
    }));
    options.gridEvents.sortChange({ field: 'id', order: 'asc' });
    await settle();
    expect(getStandardCodeSetPage).toHaveBeenLastCalledWith(expect.objectContaining({
      name: '性别', type: 'sex', pageNo: 1, sortField: 'id', sortOrder: 'asc',
    }));
  });

  it('较早请求结束时仍保留当前 loading，且不能覆盖新结果', async () => {
    const first = deferred<{ list: Api.StandardCodeSet[]; total: number }>();
    const second = deferred<{ list: Api.StandardCodeSet[]; total: number }>();
    vi.mocked(getStandardCodeSetPage)
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(second.promise);
    mount();
    const pending = state.options!.formOptions.handleSubmit({ name: '新条件' });
    first.resolve({ list: [{ id: '9007199254740993', name: '旧结果' }], total: 1 });
    await settle();
    expect(state.grid.loading).toBe(true);
    expect(state.grid.data).toEqual([]);
    second.resolve({ list: [{ id: '9007199254740995', name: '新结果' }], total: 1 });
    await pending;
    await settle();
    expect(container.querySelector('[data-rows]')?.textContent).toBe('新结果');
    expect(state.grid.loading).toBe(false);
  });

  it('批量删除传递无损 ID，成功后清理勾选', async () => {
    mount();
    await settle();
    expect(state.options!.gridOptions.columns).toContainEqual(
      expect.objectContaining({ type: 'checkbox' }),
    );
    expect(state.options!.gridOptions.columns.find((column) => column.type === 'checkbox')?.visible).not.toBe(false);
    const ids = ['9007199254740993', '9007199254740995'];
    state.options!.gridEvents.checkboxChange({ records: ids.map((id) => ({ id })) });
    await settle();
    click('批量删除');
    await state.confirm!.onOk();
    await settle();
    expect(deleteStandardCodeSetList).toHaveBeenCalledWith(ids);
    expect(state.clearCheckbox).toHaveBeenCalled();
    const remove = [...container.querySelectorAll('button')].find(
      (button) => button.textContent === '批量删除',
    );
    expect(remove?.disabled).toBe(true);
  });

  it('导入按钮位于批量删除右侧并打开导入弹窗', async () => {
    mount();
    await settle();
    const labels = [...container.querySelectorAll('button')].map(
      (button) => button.textContent,
    );
    expect(labels.indexOf('导入码表/码值')).toBe(labels.indexOf('批量删除') + 1);
    expect(labels).not.toContain('批量选择');
    expect(labels).not.toContain('退出批量选择');
    expect(container.querySelector('[data-dropdown]')).toBeNull();
    click('导入码表/码值');
    expect(state.modalOpen[1]).toHaveBeenCalledOnce();
  });
});
