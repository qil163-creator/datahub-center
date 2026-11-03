/* eslint-disable vue/one-component-per-file -- 测试桩隔离组件库并检查列表状态与身份边界 */
import type { App, ComponentPublicInstance } from 'vue';

import type { SensitiveListApi } from '#/api/asset/sensitive';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import List from './index.vue';

interface Confirm {
  onCancel: () => void;
  onOk: () => Promise<void>;
  destroy?: () => void;
}
const mocks = vi.hoisted(() => ({
  access: {
    accessToken: 'session',
    refreshToken: '',
    loginExpired: false,
    accessCodes: [
      'asset:sensitive-list:query',
      'asset:sensitive-list:update',
      'asset:sensitive-list:delete',
    ],
  },
  user: { userInfo: { userId: '1' } },
  page: vi.fn(),
  categories: vi.fn(),
  status: vi.fn(),
  remove: vi.fn(),
  batch: vi.fn(),
  success: vi.fn(),
  dialogs: [] as Confirm[],
}));

vi.mock('#/adapter/form', () => ({
  useVbenForm: () => [
    () => null,
    { reset: vi.fn(), validateAndSubmit: vi.fn() },
  ],
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => mocks.access,
  useUserStore: () => mocks.user,
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      codes.some((code) => mocks.access.accessCodes.includes(code)),
  }),
}));
vi.mock('@vben/icons', () => ({
  IconifyIcon: { render: () => null },
}));
vi.mock('@vben/utils', () => ({ formatDate: () => '' }));
vi.mock('#/api/asset/sensitive', () => ({
  getSensitiveListPage: mocks.page,
  getSensitiveListCategories: mocks.categories,
  changeSensitiveListStatus: mocks.status,
  deleteSensitiveList: mocks.remove,
  deleteSensitiveLists: mocks.batch,
}));
vi.mock('./modules/detail.vue', () => ({
  default: defineComponent({ render: () => null }),
}));
vi.mock('./modules/form.vue', () => ({
  default: defineComponent({ render: () => null }),
}));
vi.mock('@vben/common-ui', () => {
  const box = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.()),
  });
  return {
    Page: box,
    useVbenModal: () => [
      box,
      { close: vi.fn(), setData: vi.fn().mockReturnThis(), open: vi.fn() },
    ],
  };
});
vi.mock('antdv-next', () => {
  const box = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.()),
  });
  const table = defineComponent({
    props: {
      dataSource: { type: Array, default: () => [] },
      columns: { type: Array, default: () => [] },
    },
    setup:
      (props, { slots }) =>
      () =>
        h(
          'div',
          props.dataSource?.flatMap((row: unknown) =>
            props.columns.map((column: unknown) =>
              slots.bodyCell?.({ column, record: row }),
            ),
          ),
        ),
  });
  return {
    Alert: box,
    Button: box,
    CheckboxGroup: box,
    ConfigProvider: box,
    Dropdown: box,
    Input: box,
    Select: box,
    Switch: box,
    Table: table,
    Tag: box,
    Tooltip: box,
    TreeSelect: box,
    theme: { defaultAlgorithm: '' },
    message: { success: mocks.success },
    Modal: {
      confirm: (options: Confirm) => {
        const dialog = { ...options, destroy: vi.fn() };
        mocks.dialogs.push(dialog);
        return dialog;
      },
    },
  };
});
interface State {
  rows: SensitiveListApi.Record[];
  categories: SensitiveListApi.Category[];
  categoryFailed: boolean;
  loadCategories: () => Promise<void>;
  tree: { key: string; title: string }[];
  total: number;
  selectedRows: SensitiveListApi.Record[];
  changePage: (page: number, size: number) => void;
  handleTableChange: (
    pagination: { current?: number; pageSize?: number },
    filters: unknown,
    sorter: unknown,
  ) => void;
  toggle: (row: SensitiveListApi.Record, checked: boolean) => void;
  remove: (row: SensitiveListApi.Record) => void;
  removeSelected: () => void;
}
let app: App;
let host: HTMLDivElement;
let state: State;
async function flush() {
  await Promise.resolve();
  await nextTick();
  await Promise.resolve();
}
beforeEach(async () => {
  vi.resetAllMocks();
  mocks.dialogs = [];
  mocks.access = reactive({
    accessToken: 'session',
    refreshToken: '',
    loginExpired: false,
    accessCodes: [
      'asset:sensitive-list:query',
      'asset:sensitive-list:update',
      'asset:sensitive-list:delete',
    ],
  });
  mocks.user = reactive({ userInfo: { userId: '1' } });
  mocks.categories.mockResolvedValue([]);
  mocks.page.mockImplementation(async () => ({
    list: [
      {
        id: '9223372036854775807',
        revision: '9007199254740993',
        status: 1,
        assetName: '资产',
        assetDescription: '资产描述',
        description: '清单描述',
        columnName: 'STCD',
      },
      { id: '9223372036854775806', revision: '9007199254740994', status: 0 },
    ],
    total: 2,
  }));
  host = document.createElement('div');
  document.body.append(host);
  const vm = (app = createApp(List)).mount(host) as ComponentPublicInstance;
  state = (vm.$ as unknown as { setupState: State }).setupState;
  await flush();
});
afterEach(() => {
  app.unmount();
  host.remove();
});
describe('敏感清单列表', () => {
  it('分类读取失败时保留列表，并可重试恢复分类筛选', async () => {
    mocks.categories.mockRejectedValueOnce(new Error('分类服务异常'));
    await state.loadCategories();
    expect(state.categoryFailed).toBe(true);
    expect(state.rows).toHaveLength(2);

    const category: SensitiveListApi.Category = {
      key: 'category:9007199254740993',
      categoryId: '9007199254740993',
      name: '测站编码',
      selectable: true,
      status: 0,
      type: 'CATEGORY',
    };
    mocks.categories.mockResolvedValueOnce([category]);
    await state.loadCategories();
    expect(state.categoryFailed).toBe(false);
    expect(state.categories).toEqual([category]);
    expect(state.tree).toEqual([
      expect.objectContaining({ key: category.key, title: category.name }),
    ]);
    expect(state.rows).toHaveLength(2);
  });

  it('独立分页保留排序，表格排序不重置已选择的页容量', async () => {
    state.changePage(2, 20);
    await flush();
    expect(mocks.page).toHaveBeenLastCalledWith(
      expect.objectContaining({ pageNo: 1, pageSize: 20 }),
    );
    state.handleTableChange({}, {}, { field: 'createTime', order: 'ascend' });
    await flush();
    expect(mocks.page).toHaveBeenLastCalledWith(
      expect.objectContaining({
        pageNo: 1,
        pageSize: 20,
        sortField: 'createTime',
        sortOrder: 'asc',
      }),
    );
    state.changePage(3, 20);
    await flush();
    expect(mocks.page).toHaveBeenLastCalledWith(
      expect.objectContaining({
        pageNo: 3,
        pageSize: 20,
        sortField: 'createTime',
        sortOrder: 'asc',
      }),
    );
    state.changePage(3, 50);
    await flush();
    expect(mocks.page).toHaveBeenLastCalledWith(
      expect.objectContaining({
        pageNo: 1,
        pageSize: 50,
        sortField: 'createTime',
        sortOrder: 'asc',
      }),
    );
  });

  it('列表读取正式分页，资产描述与清单描述分开', () => {
    expect(mocks.page).toHaveBeenCalledWith(
      expect.objectContaining({
        pageNo: 1,
        pageSize: 10,
        sortField: 'id',
        sortOrder: 'desc',
      }),
    );
    expect(host.textContent).toContain('资产描述');
    expect(host.textContent).not.toContain('清单描述');
  });
  it('切换取消回滚，失败也回滚并重新读取', async () => {
    const row = state.rows[0]!;
    state.toggle(row, true);
    expect(row.status).toBe(0);
    mocks.dialogs[0]!.onCancel();
    expect(row.status).toBe(1);
    expect(mocks.status).not.toHaveBeenCalled();
    mocks.status.mockRejectedValueOnce(new Error('conflict'));
    state.toggle(row, true);
    await mocks.dialogs[1]!.onOk();
    await flush();
    expect(row.status).toBe(1);
    expect(state.rows[0]!.status).toBe(1);
    expect(mocks.status).toHaveBeenCalledExactlyOnceWith({
      id: '9223372036854775807',
      revision: '9007199254740993',
      status: 0,
    });
  });
  it('启用记录拒绝单删，批删使用本地ID与修订并报告服务器实际结果', async () => {
    state.remove(state.rows[1]!);
    expect(mocks.dialogs).toHaveLength(0);
    state.selectedRows = [...state.rows];
    mocks.batch.mockResolvedValue({
      deletedIds: ['9223372036854775807'],
      skippedIds: ['9223372036854775806'],
    });
    state.removeSelected();
    await mocks.dialogs[0]!.onOk();
    expect(mocks.batch).toHaveBeenCalledExactlyOnceWith([
      { id: '9223372036854775807', revision: '9007199254740993' },
      { id: '9223372036854775806', revision: '9007199254740994' },
    ]);
    expect(mocks.success).toHaveBeenCalledWith('已删除1条，跳过1条启用记录');
    expect(state.selectedRows).toEqual([]);
  });
  it('确认前会话过期销毁所属对话框，旧确认不能发写请求', async () => {
    state.toggle(state.rows[0]!, true);
    const dialog = mocks.dialogs[0]!;
    mocks.access.loginExpired = true;
    await nextTick();
    await dialog.onOk();
    expect(dialog.destroy).toHaveBeenCalledOnce();
    expect(mocks.status).not.toHaveBeenCalled();
    expect(state.rows).toEqual([]);
  });
});
