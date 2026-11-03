/* eslint-disable perfectionist/sort-imports -- 先初始化共用 API 替身。 */
import type { DepartmentAssetsApi } from '#/api/asset/market/department-assets';

import { afterEach, beforeEach, expect, it } from 'vitest';

import { deferred, flush, mock, mountState, resetMocks } from './test-support';
import List from './index.vue';

interface State {
  rows: DepartmentAssetsApi.Asset[];
  categories: DepartmentAssetsApi.Category[];
  query: DepartmentAssetsApi.PageReq;
  filters: {
    deliveryType?: number;
    keyword: string;
    marketCategoryId?: number | string;
  };
  load: () => Promise<void>;
  loadCategories: () => Promise<void>;
  search: () => void;
  resetFilters: () => void;
  openDetail: (row: DepartmentAssetsApi.Asset) => void;
  tableChange: (a: unknown, b: unknown, c: unknown) => void;
}
const row = (
  id = '9007199254740993',
  status = 1,
): DepartmentAssetsApi.Asset => ({
  id,
  displayId: '21',
  marketName: '河流维表',
  canView: true,
  status,
});
let mounted: ReturnType<typeof mountState<State>>;
beforeEach(resetMocks);
afterEach(() => mounted?.dispose());
async function mount() {
  mounted = mountState<State>(List);
  await flush();
  return mounted.state;
}
it('loads all supplied application states for ordinary department users without an admin role', async () => {
  mock.page.mockResolvedValueOnce({ list: [row(), row('2', 2)], total: 2 });
  const s = await mount();
  expect(s.rows.map((x) => x.status)).toEqual([1, 2]);
  expect(mock.page).toHaveBeenCalledWith({
    pageNo: 1,
    pageSize: 10,
    sortField: 'applyTime',
    sortOrder: 'desc',
  });
});
it('sends only search filters and leaves department identity to the server', async () => {
  const s = await mount();
  s.filters.keyword = ' 河流 ';
  s.filters.deliveryType = 2;
  s.filters.marketCategoryId = '9007199254740994';
  s.search();
  await flush();
  expect(mock.page).toHaveBeenLastCalledWith(
    expect.objectContaining({
      keyword: '河流',
      deliveryType: 2,
      marketCategoryId: '9007199254740994',
    }),
  );
  expect(mock.page.mock.calls.at(-1)?.[0]).not.toHaveProperty('deptId');
  s.resetFilters();
  await flush();
  expect(mock.page).toHaveBeenLastCalledWith(
    expect.objectContaining({
      keyword: undefined,
      deliveryType: undefined,
      marketCategoryId: undefined,
    }),
  );
});
it('keeps native application identity when opening the independent detail page', async () => {
  const value = row();
  mock.page.mockResolvedValueOnce({ list: [value], total: 1 });
  const s = await mount();
  s.openDetail(value);
  expect(mock.push).toHaveBeenCalledWith({
    name: 'MarketApplyAssetDetail',
    params: { id: '9007199254740993' },
  });
  s.openDetail(row('21'));
  expect(mock.push).toHaveBeenCalledTimes(1);
});
it('sorts the server collection by application ID rather than a displayed source number', async () => {
  const s = await mount();
  s.tableChange({}, {}, { columnKey: 'applyId', order: 'ascend' });
  await flush();
  expect(mock.page).toHaveBeenLastCalledWith(
    expect.objectContaining({ sortField: 'applyId', sortOrder: 'asc' }),
  );
});
it('drops old responses after filters change and after permission loss', async () => {
  const s = await mount();
  const old = deferred<{ list: DepartmentAssetsApi.Asset[]; total: number }>();
  mock.page.mockReturnValueOnce(old.promise);
  const first = s.load();
  mock.page.mockResolvedValueOnce({ list: [row('2')], total: 1 });
  await s.load();
  old.resolve({ list: [row('3')], total: 1 });
  await first;
  expect(s.rows[0]?.id).toBe('2');
  mock.access.accessCodes = [];
  await flush();
  expect(s.rows).toEqual([]);
  const calls = mock.page.mock.calls.length;
  await s.load();
  expect(mock.page).toHaveBeenCalledTimes(calls);
});
it('does not let late category options repopulate a new login', async () => {
  const s = await mount();
  const old = deferred<DepartmentAssetsApi.Category[]>();
  mock.categories.mockReturnValueOnce(old.promise);
  const pending = s.loadCategories();
  mock.access.accessToken = 'new-login';
  await flush();
  old.resolve([{ id: '3', name: '旧类目' }]);
  await pending;
  expect(s.categories).toEqual([]);
});
it('invalidates responses when the current user department changes without a new token', async () => {
  const s = await mount();
  const old = deferred<{ list: DepartmentAssetsApi.Asset[]; total: number }>();
  mock.page.mockReturnValueOnce(old.promise);
  const pending = s.load();
  Object.assign(mock.user.userInfo, { deptId: 'new-department' });
  await flush();
  old.resolve({ list: [row('3')], total: 1 });
  await pending;
  expect(s.rows).toEqual([]);
});
