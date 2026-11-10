/* eslint-disable perfectionist/sort-imports -- 共用 API 替身必须在组件之前初始化。 */
import type { AssetTagApi } from '#/api/asset/tag';

import { afterEach, beforeEach, expect, it } from 'vitest';

import { deferred, flush, mock, mountKeptState, mountState, resetMocks } from './test-support';
import List from './index.vue';
interface State {
  rows: AssetTagApi.Tag[];
  query: { creatorName?: string; name?: string; status?: number };
  selectedCategoryId?: string;
  selectedRowKeys: string[];
  pendingIds: Set<string>;
  pagination: { pageNo: number; pageSize: number };
  loadRows: () => Promise<void>;
  search: () => void;
  reset: () => void;
  selectCategory: (ids: string[]) => void;
  openEdit: (row: AssetTagApi.Tag) => void;
  openDetail: (row: AssetTagApi.Tag) => void;
  remove: (row?: AssetTagApi.Tag) => void;
  statusChange: (row: AssetTagApi.Tag, checked: boolean) => void;
  tableChange: (p: unknown, f: unknown, s: unknown) => void;
}
let handle: ReturnType<typeof mountState<State>>;
beforeEach(resetMocks);
afterEach(() => handle?.dispose());
async function mount() {
  handle = mountState<State>(List);
  await flush();
  return handle.state;
}
const row = (id = '9007199254740993', status = 1): AssetTagApi.Tag => ({
  id,
  name: '同名标签',
  categoryId: '9007199254740994',
  revision: 7,
  status,
});
it('sends free-text creator and category IDs through server filtering and resets the tree selection', async () => {
  const s = await mount();
  s.query.creatorName = ' 创建人甲 ';
  s.query.name = ' 标签 ';
  s.selectCategory(['9007199254740994']);
  await flush();
  expect(mock.page).toHaveBeenLastCalledWith(
    expect.objectContaining({
      categoryId: '9007199254740994',
      creatorName: '创建人甲',
      name: '标签',
      pageNo: 1,
    }),
  );
  s.reset();
  await flush();
  expect(mock.page).toHaveBeenLastCalledWith(
    expect.objectContaining({
      categoryId: undefined,
      creatorName: undefined,
      name: undefined,
    }),
  );
});
it('requests global scoped count sorting instead of sorting the current page', async () => {
  const s = await mount();
  s.tableChange({}, {}, { columnKey: 'assetCount', order: 'descend' });
  await flush();
  expect(mock.page).toHaveBeenLastCalledWith(
    expect.objectContaining({ sortField: 'assetCount', sortOrder: 'desc' }),
  );
});
it('routes grid selection, sorting and double-click to the existing list actions', async () => {
  const s = await mount();
  const item = row();
  mock.gridEvents.checkboxChange({ records: [item] });
  expect(s.selectedRowKeys).toEqual(['9007199254740993']);
  mock.gridEvents.sortChange({ field: 'assetCount', order: 'desc' });
  await flush();
  expect(mock.page).toHaveBeenLastCalledWith(
    expect.objectContaining({ sortField: 'assetCount', sortOrder: 'desc' }),
  );
  expect(s.selectedRowKeys).toEqual([]);
  mock.gridEvents.cellDblclick({ row: item });
  expect(mock.push).toHaveBeenCalledWith({
    name: 'AssetTagDetail',
    params: { id: '9007199254740993' },
  });
});
it('keeps the latest list and prevents reads without query permission', async () => {
  const s = await mount();
  const old = deferred<{ list: AssetTagApi.Tag[]; total: number }>();
  mock.page.mockReturnValueOnce(old.promise);
  const pending = s.loadRows();
  mock.page.mockResolvedValueOnce({ list: [row('2')], total: 1 });
  await s.loadRows();
  old.resolve({ list: [row('3')], total: 1 });
  await pending;
  expect(s.rows[0]?.id).toBe('2');
  mock.access.accessCodes = [];
  await flush();
  const calls = mock.page.mock.calls.length;
  await s.loadRows();
  expect(mock.page).toHaveBeenCalledTimes(calls);
  expect(s.rows).toEqual([]);
});
it('keeps enabled row edit and delete disabled while allowing detail by native identity', async () => {
  const s = await mount();
  s.openEdit(row('1', 0));
  s.remove(row('1', 0));
  expect(mock.modalOpen).not.toHaveBeenCalled();
  expect(mock.confirm).not.toHaveBeenCalled();
  s.openDetail(row('9007199254740993', 0));
  expect(mock.push).toHaveBeenCalledWith({
    name: 'AssetTagDetail',
    params: { id: '9007199254740993' },
  });
});
it('confirms status changes before sending the exact revision and never changes state on cancel', async () => {
  const s = await mount();
  const value = row();
  s.statusChange(value, true);
  expect(value.status).toBe(1);
  expect(mock.status).not.toHaveBeenCalled();
  await mock.confirm.mock.calls[0]![0].onOk();
  expect(mock.status).toHaveBeenCalledWith('9007199254740993', 0, 7);
});
it('uses a single atomic bulk endpoint including every selected revision', async () => {
  const s = await mount();
  s.rows = [row(), row('9007199254740995', 0)];
  s.selectedRowKeys = s.rows.map((r) => String(r.id));
  s.remove();
  await mock.confirm.mock.calls[0]![0].onOk();
  expect(mock.removeList).toHaveBeenCalledWith([
    { id: '9007199254740993', revision: 7 },
    { id: '9007199254740995', revision: 7 },
  ]);
  expect(mock.remove).not.toHaveBeenCalled();
});
it('destroys old confirms and prevents stale writes after identity changes', async () => {
  const s = await mount();
  s.remove(row());
  const options = mock.confirm.mock.calls[0]![0];
  mock.access.accessToken = 'new-session';
  await flush();
  expect(mock.destroy).toHaveBeenCalled();
  await options.onOk();
  expect(mock.remove).not.toHaveBeenCalled();
});
it('reports a rejected atomic deletion without individual fallback or success', async () => {
  const s = await mount();
  s.rows = [row(), row('2', 0)];
  s.selectedRowKeys = ['9007199254740993', '2'];
  mock.removeList.mockRejectedValueOnce(new Error('enabled'));
  s.remove();
  await mock.confirm.mock.calls[0]![0].onOk();
  expect(mock.remove).not.toHaveBeenCalled();
  expect(mock.success).not.toHaveBeenCalled();
  expect(mock.error).toHaveBeenCalled();
});
it('refreshes after every completed concurrent status write in the same context', async () => {
  const s = await mount();
  const first = deferred<unknown>();
  const second = deferred<unknown>();
  mock.status.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise);
  s.statusChange(row('1'), true);
  s.statusChange(row('2'), true);
  const pendingFirst = mock.confirm.mock.calls[0]![0].onOk();
  const pendingSecond = mock.confirm.mock.calls[1]![0].onOk();
  const reads = mock.page.mock.calls.length;
  second.resolve(true);
  await pendingSecond;
  first.resolve(true);
  await pendingFirst;
  expect(mock.page).toHaveBeenCalledTimes(reads + 2);
});
it('does not clear a new status operation after deactivation and reactivation', async () => {
  const kept = mountKeptState<State>(List);
  handle = kept;
  await flush();
  const s = kept.state;
  const old = deferred<unknown>();
  const current = deferred<unknown>();
  mock.status.mockReturnValueOnce(old.promise).mockReturnValueOnce(current.promise);
  s.statusChange(row('1'), true);
  const oldConfirm = mock.confirm.mock.calls[0]![0];
  const pendingOld = oldConfirm.onOk();
  kept.setVisible(false);
  await flush();
  kept.setVisible(true);
  await flush();
  s.statusChange(row('1'), true);
  const pendingCurrent = mock.confirm.mock.calls[1]![0].onOk();
  const reads = mock.page.mock.calls.length;
  old.resolve(true);
  await pendingOld;
  expect(s.pendingIds.has('1')).toBe(true);
  expect(mock.page).toHaveBeenCalledTimes(reads);
  await oldConfirm.onOk();
  expect(mock.status).toHaveBeenCalledTimes(2);
  current.resolve(true);
  await pendingCurrent;
  expect(s.pendingIds.has('1')).toBe(false);
});
