import type { App, ComponentPublicInstance, SetupContext } from 'vue';

import type { AssetMarketCategoryApi } from '#/api/asset/marketcategory';

import { createApp, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import CategoryPage from './index.vue';

type Category = AssetMarketCategoryApi.MarketCategory;
interface GridOptions {
  gridOptions: {
    proxyConfig: {
      ajax: {
        query: (
          params: unknown,
          values: Record<string, unknown>,
        ) => Promise<Category[]>;
      };
    };
  };
  gridEvents: { checkboxChange: (event: { records: Category[] }) => void };
}
const mock = vi.hoisted(() => ({
  access: {
    accessToken: 'session-a',
    refreshToken: '',
    accessCodes: [
      'asset:market-category:query',
      'asset:market-category:update',
      'asset:market-category:create',
      'asset:market-category:delete',
    ],
  },
  user: { userInfo: { userId: '1' }, userRoles: ['editor'] },
  options: undefined as GridOptions | undefined,
  gridState: {} as { row?: Category },
  list: vi.fn(),
  status: vi.fn(),
  remove: vi.fn(),
  removeBatch: vi.fn(),
  confirm: vi.fn(),
  success: vi.fn(),
  query: vi.fn(),
  clear: vi.fn(),
  load: vi.fn(),
  expand: vi.fn(),
  formData: vi.fn(),
  formOpen: vi.fn(),
  formClose: vi.fn(),
  detailData: vi.fn(),
  detailOpen: vi.fn(),
  detailClose: vi.fn(),
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => mock.access,
  useUserStore: () => mock.user,
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      codes.some((code) => mock.access.accessCodes.includes(code)),
  }),
}));
vi.mock('@vben/common-ui', () => ({
  Page: (_: unknown, { attrs, slots }: SetupContext) =>
    h('main', attrs, slots.default?.()),
  confirm: mock.confirm,
  useVbenModal: () => [
    () => null,
    {
      setData: (data: unknown) => {
        mock.formData(data);
        return { open: mock.formOpen };
      },
      close: mock.formClose,
    },
  ],
  useVbenDrawer: () => [
    () => null,
    {
      setData: (data: unknown) => {
        mock.detailData(data);
        return { open: mock.detailOpen };
      },
      close: mock.detailClose,
    },
  ],
}));
vi.mock('antdv-next', () => ({
  Switch: (props: { loading?: boolean }) =>
    h('button', {
      'data-testid': 'status-switch',
      'data-loading': String(!!props.loading),
    }),
  message: { success: mock.success, warning: vi.fn() },
}));
vi.mock('#/adapter/vxe-table', () => ({
  ACTION_ICON: {},
  TableAction: () => null,
  useVbenVxeGrid: (options: GridOptions) => {
    mock.options = options;
    return [
      (_: unknown, { slots }: SetupContext) =>
        mock.gridState.row
          ? h('div', slots.status?.({ row: mock.gridState.row }))
          : null,
      {
        query: mock.query,
        grid: {
          loadData: mock.load,
          clearCheckboxRow: mock.clear,
          setAllTreeExpand: mock.expand,
        },
      },
    ];
  },
}));
vi.mock('#/api/asset/marketcategory', () => ({
  getMarketCategoryList: mock.list,
  updateMarketCategoryStatus: mock.status,
  deleteMarketCategory: mock.remove,
  deleteMarketCategoryList: mock.removeBatch,
}));
vi.mock('./data', () => ({
  useGridColumns: () => [],
  useGridFormSchema: () => [],
}));
vi.mock('#/components/text-ellipsis-cell', () => ({
  TextEllipsisCell: () => null,
}));
vi.mock('./modules/detail.vue', () => ({ default: { render: () => null } }));
vi.mock('./modules/form.vue', () => ({ default: { render: () => null } }));
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((yes) => {
    resolve = yes;
  });
  return { promise, resolve };
}
const row = (id: number | string, parentId: number | string = 0): Category => ({
  id,
  parentId,
  name: '类目',
  status: 0,
  sort: 0,
});
let app: App;
let host: HTMLDivElement;
let state: {
  append: (row: Category) => void;
  changeStatus: (row: Category) => Promise<void>;
  checkedIds: (number | string)[];
  create: () => void;
  deleting: boolean;
  detail: (row: Category) => void;
  edit: (row: Category) => void;
  expanded: boolean;
  pendingStatus: Set<string>;
  remove: (ids: (number | string)[], ask: boolean) => Promise<void>;
  toggleExpand: () => void;
  updatingStatus: Set<string>;
};
beforeEach(() => {
  vi.resetAllMocks();
  mock.access = reactive({
    accessToken: 'session-a',
    refreshToken: '',
    accessCodes: [
      'asset:market-category:query',
      'asset:market-category:update',
      'asset:market-category:create',
      'asset:market-category:delete',
    ],
  });
  mock.user = reactive({ userInfo: { userId: '1' }, userRoles: ['editor'] });
  mock.gridState = reactive({ row: undefined });
  mock.confirm.mockResolvedValue(undefined);
  mock.clear.mockResolvedValue(undefined);
  mock.list.mockReset().mockResolvedValue([]);
  host = document.createElement('div');
  app = createApp(CategoryPage);
  const vm = app.mount(host) as ComponentPublicInstance;
  state = (vm.$ as unknown as { setupState: typeof state }).setupState;
});
afterEach(() => {
  app.unmount();
  host.remove();
});

describe('集市类目管理', () => {
  it('搜索保留缺父节点的记录，较旧请求及旧登录会话不能覆盖新列表', async () => {
    const query = mock.options!.gridOptions.proxyConfig.ajax.query;
    const first = deferred<Category[]>();
    mock.list
      .mockReturnValueOnce(first.promise)
      .mockResolvedValueOnce([row('9007199254740993', 999)]);
    const old = query({}, {});
    const latest = await query({}, { name: '筛选' });
    expect(latest[0]?.id).toBe('9007199254740993');
    first.resolve([row(1)]);
    expect(await old).toBe(latest);
    const previous = deferred<Category[]>();
    mock.list.mockReturnValueOnce(previous.promise);
    const stale = query({}, {});
    mock.access.accessToken = 'session-b';
    previous.resolve([row(2)]);
    expect(await stale).toEqual([]);
  });

  it('状态修改失败保留原值并解锁，同一行禁止重复请求', async () => {
    const value = row('9007199254740993');
    const pending = deferred<boolean>();
    mock.status.mockReturnValueOnce(pending.promise);
    const change = state.changeStatus(value);
    await state.changeStatus(value);
    expect(mock.status).toHaveBeenCalledExactlyOnceWith(value.id, 1);
    expect(state.updatingStatus.has(String(value.id))).toBe(true);
    pending.resolve(true);
    await change;
    expect(state.pendingStatus.size).toBe(0);
    expect(state.updatingStatus.size).toBe(0);
    mock.status.mockRejectedValueOnce(new Error('引用限制'));
    await expect(state.changeStatus(value)).resolves.toBeUndefined();
    expect(value.status).toBe(1);
    expect(state.pendingStatus.size).toBe(0);
    expect(state.updatingStatus.size).toBe(0);
    expect(mock.query).not.toHaveBeenCalled();
  });

  it('批量删除父子原始 ID，成功后清理选择，失败后允许重试', async () => {
    const ids = ['9007199254740993', 2];
    mock.options!.gridEvents.checkboxChange({
      records: [row(ids[0]!), row(ids[1]!, ids[0]!)],
    });
    mock.removeBatch.mockResolvedValueOnce(true);
    await state.remove([...state.checkedIds], true);
    expect(mock.removeBatch).toHaveBeenCalledWith(ids);
    expect(state.checkedIds).toEqual([]);
    expect(mock.clear).toHaveBeenCalledOnce();
    mock.removeBatch.mockRejectedValueOnce(new Error('仍有关联集市资源'));
    await expect(state.remove(ids, true)).resolves.toBeUndefined();
    expect(state.deleting).toBe(false);
    await nextTick();
  });
  it('forwards enabled status zero and disabled status without falling back to a parent filter', async () => {
    const query = mock.options!.gridOptions.proxyConfig.ajax.query;
    await query({}, { name: '类目', status: 0 });
    expect(mock.list).toHaveBeenLastCalledWith({ name: '类目', status: 0 });
    await query({}, { status: 1 });
    expect(mock.list).toHaveBeenLastCalledWith({ name: undefined, status: 1 });
  });
  it('starts expanded and toggles tree expansion with the shared toolbar action', () => {
    expect(state.expanded).toBe(true);
    state.toggleExpand();
    expect(mock.expand).toHaveBeenCalledWith(false);
    state.toggleExpand();
    expect(mock.expand).toHaveBeenLastCalledWith(true);
  });
  it('opens create, append, edit and fresh detail with original long ids', () => {
    const value = row('9223372036854775807');
    state.create();
    expect(mock.formData).toHaveBeenLastCalledWith(undefined);
    state.append(value);
    expect(mock.formData).toHaveBeenLastCalledWith({ parentId: value.id });
    state.edit(value);
    expect(mock.formData).toHaveBeenLastCalledWith({ id: value.id });
    state.detail(value);
    expect(mock.detailData).toHaveBeenCalledWith({ id: value.id });
    expect(mock.detailOpen).toHaveBeenCalledOnce();
  });
  it('checks action permissions before opening editors or issuing writes', async () => {
    mock.access.accessCodes = ['asset:market-category:query'];
    state.create();
    state.append(row(1));
    state.edit(row(1));
    await state.changeStatus(row(1));
    await state.remove([1], false);
    expect(mock.formOpen).not.toHaveBeenCalled();
    expect(mock.status).not.toHaveBeenCalled();
    expect(mock.remove).not.toHaveBeenCalled();
    mock.access.accessCodes = [];
    state.detail(row(1));
    expect(mock.detailOpen).not.toHaveBeenCalled();
  });
  it('ignores stale query failures after the latest filtered tree completes', async () => {
    let reject!: (error: Error) => void;
    const old = new Promise<Category[]>((_yes, no) => {
      reject = no;
    });
    mock.list
      .mockReturnValueOnce(old)
      .mockResolvedValueOnce([row('9223372036854775807', 123)]);
    const query = mock.options!.gridOptions.proxyConfig.ajax.query;
    const request = query({}, {});
    const latest = await query({}, { status: 0 });
    reject(new Error('old'));
    expect(await request).toBe(latest);
    expect(latest[0]?.id).toBe('9223372036854775807');
  });
  it('drops status success notifications from a previous identity and closes dependent popups', async () => {
    const pending = deferred<boolean>();
    mock.status.mockReturnValueOnce(pending.promise);
    const request = state.changeStatus(row(1));
    mock.access.accessToken = 'changed';
    pending.resolve(true);
    await request;
    expect(mock.success).not.toHaveBeenCalled();
    expect(mock.formClose).toHaveBeenCalled();
    expect(mock.detailClose).toHaveBeenCalled();
    expect(state.pendingStatus.size).toBe(0);
  });
  it('cancels a batch confirmation without issuing a delete and can retry', async () => {
    mock.confirm.mockRejectedValueOnce(new Error('cancel'));
    await state.remove([1], true);
    expect(mock.removeBatch).not.toHaveBeenCalled();
    expect(state.deleting).toBe(false);
    await state.remove([1], true);
    expect(mock.removeBatch).toHaveBeenCalledWith([1]);
  });
  it('keeps the selection on delete failure and clears only after a successful atomic retry', async () => {
    const ids = ['9007199254740993', 2];
    mock.options!.gridEvents.checkboxChange({
      records: [row(ids[0]!), row(ids[1]!, ids[0]!)],
    });
    mock.removeBatch.mockRejectedValueOnce(new Error('references'));
    await state.remove([...state.checkedIds], true);
    expect(state.checkedIds).toEqual(ids);
    expect(mock.clear).not.toHaveBeenCalled();
    await state.remove([...state.checkedIds], true);
    expect(state.checkedIds).toEqual([]);
    expect(mock.removeBatch).toHaveBeenNthCalledWith(2, ids);
  });
  it('requires query permission before querying the administrative tree', async () => {
    mock.access.accessCodes = [];
    expect(
      await mock.options!.gridOptions.proxyConfig.ajax.query({}, {}),
    ).toEqual([]);
    expect(mock.list).not.toHaveBeenCalled();
  });
  it('keeps a status row pending during confirmation and never updates after cancel', async () => {
    const confirmation = deferred<undefined>();
    mock.confirm.mockReturnValueOnce(confirmation.promise);
    const value = row(1);
    const first = state.changeStatus(value);
    await state.changeStatus(value);
    expect(mock.confirm).toHaveBeenCalledOnce();
    expect(state.pendingStatus.has('1')).toBe(true);
    expect(state.updatingStatus.has('1')).toBe(false);
    expect(mock.status).not.toHaveBeenCalled();
    confirmation.resolve(undefined);
    await first;
    expect(mock.status).toHaveBeenCalledWith(1, 1);
    mock.confirm.mockRejectedValueOnce(new Error('cancel'));
    mock.status.mockClear();
    await state.changeStatus(value);
    expect(mock.status).not.toHaveBeenCalled();
    expect(value.status).toBe(1);
    expect(state.pendingStatus.size).toBe(0);
    expect(state.updatingStatus.size).toBe(0);
    expect(mock.query).not.toHaveBeenCalled();
  });
  it('shows switch loading only while the confirmed status request is running', async () => {
    const confirmation = deferred<undefined>();
    const request = deferred<boolean>();
    mock.confirm.mockReturnValueOnce(confirmation.promise);
    mock.status.mockReturnValueOnce(request.promise);
    mock.gridState.row = row(1);
    await nextTick();
    const statusSwitch = host.querySelector<HTMLButtonElement>(
      '[data-testid="status-switch"]',
    );
    expect(statusSwitch?.dataset.loading).toBe('false');

    const change = state.changeStatus(mock.gridState.row);
    await nextTick();
    expect(statusSwitch?.dataset.loading).toBe('false');
    confirmation.resolve(undefined);
    await Promise.resolve();
    await nextTick();
    expect(statusSwitch?.dataset.loading).toBe('true');

    request.resolve(true);
    await change;
    await nextTick();
    expect(statusSwitch?.dataset.loading).toBe('false');
  });
  it('does not issue status writes after the user context changes while confirmation is open', async () => {
    const confirmation = deferred<undefined>();
    mock.confirm.mockReturnValueOnce(confirmation.promise);
    const first = state.changeStatus(row(1));
    mock.access.accessToken = 'changed';
    confirmation.resolve(undefined);
    await first;
    expect(mock.status).not.toHaveBeenCalled();
    expect(mock.success).not.toHaveBeenCalled();
  });
  it('updates the confirmed row without refreshing an unfiltered list', async () => {
    const value = row('9007199254740993');
    mock.status.mockResolvedValueOnce(true);
    await state.changeStatus(value);
    expect(value.status).toBe(1);
    expect(mock.status).toHaveBeenCalledWith(value.id, 1);
    expect(mock.query).not.toHaveBeenCalled();
  });
  it.each([0, 1])(
    'refreshes after a successful change with status filter %i',
    async (status) => {
      const query = mock.options!.gridOptions.proxyConfig.ajax.query;
      const item = { ...row(1), status };
      mock.list.mockResolvedValueOnce([item]);
      const rows = await query({}, { status });
      const value = rows[0]!;
      mock.status.mockResolvedValueOnce(true);
      await state.changeStatus(value);
      expect(value.status).toBe(status === 0 ? 1 : 0);
      expect(mock.query).toHaveBeenCalledOnce();
    },
  );
  it('does not refresh a status-filtered list after failure or cancellation', async () => {
    const query = mock.options!.gridOptions.proxyConfig.ajax.query;
    mock.list.mockResolvedValueOnce([row(1)]);
    const rows = await query({}, { status: 0 });
    mock.status.mockRejectedValueOnce(new Error('请求失败'));
    await state.changeStatus(rows[0]!);
    mock.confirm.mockRejectedValueOnce(new Error('取消'));
    await state.changeStatus(rows[0]!);
    expect(rows[0]?.status).toBe(0);
    expect(mock.query).not.toHaveBeenCalled();
  });
  it('retains the last successful status filter if a later list query fails', async () => {
    const query = mock.options!.gridOptions.proxyConfig.ajax.query;
    mock.list
      .mockResolvedValueOnce([row(1)])
      .mockRejectedValueOnce(new Error('查询失败'));
    const rows = await query({}, { status: 0 });
    await expect(query({}, {})).rejects.toThrow('查询失败');
    mock.status.mockResolvedValueOnce(true);
    await state.changeStatus(rows[0]!);
    expect(mock.query).toHaveBeenCalledOnce();
  });
  it('refreshes when a list request was already running at status update', async () => {
    const query = mock.options!.gridOptions.proxyConfig.ajax.query;
    const oldRequest = deferred<Category[]>();
    const current = row(1);
    mock.list
      .mockResolvedValueOnce([current])
      .mockReturnValueOnce(oldRequest.promise);
    const rows = await query({}, {});
    const old = query({}, {});
    mock.status.mockResolvedValueOnce(true);
    mock.list.mockResolvedValueOnce([{ ...current, status: 1 }]);
    let refreshed: Promise<Category[]> | undefined;
    mock.query.mockImplementationOnce(() => {
      refreshed = query({}, {});
    });
    await state.changeStatus(rows[0]!);
    expect(mock.query).toHaveBeenCalledOnce();
    const latest = await refreshed;
    oldRequest.resolve([current]);
    expect(await old).toEqual(latest);
    expect(latest?.[0]?.status).toBe(1);
  });
  it('refreshes when another list query starts while the status request is pending', async () => {
    const query = mock.options!.gridOptions.proxyConfig.ajax.query;
    mock.list.mockResolvedValueOnce([row(1)]).mockResolvedValueOnce([row(1)]);
    const rows = await query({}, {});
    const pending = deferred<boolean>();
    mock.status.mockReturnValueOnce(pending.promise);
    const change = state.changeStatus(rows[0]!);
    await Promise.resolve();
    await query({}, {});
    pending.resolve(true);
    await change;
    expect(mock.query).toHaveBeenCalledOnce();
  });
  it('refreshes when an already running query replaces the row before status update succeeds', async () => {
    const query = mock.options!.gridOptions.proxyConfig.ajax.query;
    const listRequest = deferred<Category[]>();
    const statusRequest = deferred<boolean>();
    mock.list
      .mockResolvedValueOnce([row(1)])
      .mockReturnValueOnce(listRequest.promise);
    const rows = await query({}, {});
    const list = query({}, {});
    mock.status.mockReturnValueOnce(statusRequest.promise);
    const change = state.changeStatus(rows[0]!);
    await Promise.resolve();
    listRequest.resolve([row(1)]);
    const replacedRows = await list;
    expect(replacedRows[0]).not.toBe(rows[0]);
    statusRequest.resolve(true);
    await change;
    expect(mock.query).toHaveBeenCalledOnce();
  });
});
