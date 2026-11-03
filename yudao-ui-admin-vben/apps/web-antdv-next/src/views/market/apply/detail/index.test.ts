import type { DepartmentAssetsApi } from '#/api/asset/market/department-assets';

import { afterEach, beforeEach, expect, it } from 'vitest';

import { deferred, flush, mock, mountState, resetMocks } from '../test-support';
import Detail from './index.vue';

interface State {
  detail?: DepartmentAssetsApi.Detail;
  errorText: string;
  activeTab: string;
  tabErrors: { api: string; preview: string };
  load: () => Promise<void>;
  selectTab: (key: string) => void;
  loadTab: (key: 'api' | 'preview') => Promise<void>;
  back: () => void;
  invoke: () => void;
}
let mounted: ReturnType<typeof mountState<State>>;
beforeEach(resetMocks);
afterEach(() => mounted?.dispose());
async function mount() {
  mounted = mountState<State>(Detail);
  await flush();
  return mounted.state;
}
it('requests only the chosen native apply ID and exposes its three view tabs', async () => {
  const s = await mount();
  expect(mock.get).toHaveBeenCalledWith('9007199254740993');
  s.selectTab('preview');
  expect(s.activeTab).toBe('preview');
  s.selectTab('api');
  expect(s.activeTab).toBe('api');
  s.selectTab('other');
  expect(s.activeTab).toBe('api');
});
it('rejects an unrelated application returned by the API', async () => {
  mock.get.mockResolvedValueOnce({ asset: { id: '21', canView: true } });
  const s = await mount();
  expect(s.detail).toBeUndefined();
  expect(s.errorText).toBeTruthy();
});
it('clears previous department data and ignores late reads after login changes', async () => {
  const s = await mount();
  const old = deferred<DepartmentAssetsApi.Detail>();
  mock.get.mockReturnValueOnce(old.promise);
  const pending = s.load();
  mock.access.accessToken = 'new-login';
  await flush();
  old.resolve({
    asset: { id: '9007199254740993', displayId: 'OLD', canView: true },
  });
  await pending;
  expect(s.detail?.asset.displayId).not.toBe('OLD');
});
it('never falls back from an unavailable application to another grant', async () => {
  mock.get.mockRejectedValueOnce(new Error('scope-denied'));
  const s = await mount();
  expect(s.errorText).toBeTruthy();
  expect(mock.get).toHaveBeenCalledTimes(1);
  expect(s.detail).toBeUndefined();
});
it('blocks direct detail loading without the menu query permission', async () => {
  mock.access.accessCodes = [];
  await mount();
  expect(mock.get).not.toHaveBeenCalled();
});
it('returns to the registered department assets navigation', async () => {
  const s = await mount();
  s.back();
  expect(mock.closeTab).toHaveBeenCalled();
  expect(mock.push).toHaveBeenCalledWith('/asset/my-assets');
});
it('returns to the resolved menu when its visible location is overridden', async () => {
  mock.route.meta.activePath = '/asset/department-assets';
  const s = await mount();
  s.back();
  expect(mock.push).toHaveBeenCalledWith('/asset/department-assets');
});
it('uses already included copied business content without another read', async () => {
  mock.get.mockResolvedValueOnce({
    asset: { id: '9007199254740993', canView: true },
    canPreview: true,
    canViewApiDocument: true,
    preview: { columns: [], rows: [] },
    apiDocument: { name: '河流维表API' },
  });
  const s = await mount();
  s.selectTab('preview');
  s.selectTab('api');
  await flush();
  expect(mock.preview).not.toHaveBeenCalled();
  expect(mock.document).not.toHaveBeenCalled();
});
it('loads native data only on a permitted tab with the chosen native application', async () => {
  mock.get.mockResolvedValueOnce({
    asset: { id: '9007199254740993', canView: true },
    canPreview: true,
    canViewApiDocument: true,
  });
  const s = await mount();
  expect(mock.preview).not.toHaveBeenCalled();
  s.selectTab('preview');
  s.selectTab('preview');
  await flush();
  expect(mock.preview).toHaveBeenCalledTimes(1);
  expect(mock.preview).toHaveBeenCalledWith('9007199254740993');
  expect(mock.document).not.toHaveBeenCalled();
  s.selectTab('api');
  await flush();
  expect(mock.document).toHaveBeenCalledWith('9007199254740993');
});
it('does not borrow another department member grant for preview or API access', async () => {
  const s = await mount();
  s.selectTab('preview');
  s.selectTab('api');
  await flush();
  expect(mock.preview).not.toHaveBeenCalled();
  expect(mock.document).not.toHaveBeenCalled();
});
it('discards lazy content when the current identity changes', async () => {
  mock.get.mockResolvedValueOnce({
    asset: { id: '9007199254740993', canView: true },
    canPreview: true,
  });
  const old = deferred<DepartmentAssetsApi.Detail['preview']>();
  mock.preview.mockReturnValueOnce(old.promise);
  const s = await mount();
  s.selectTab('preview');
  mock.access.accessToken = 'changed-session';
  await flush();
  old.resolve({ columns: [{ name: 'OLD', label: '旧数据' }], rows: [] });
  await flush();
  expect(s.detail?.preview).toBeUndefined();
});
it('retries a failed lazy read without selecting a different application', async () => {
  mock.get.mockResolvedValueOnce({
    asset: { id: '9007199254740993', canView: true },
    canPreview: true,
  });
  mock.preview.mockRejectedValueOnce(new Error('grant changed'));
  const s = await mount();
  s.selectTab('preview');
  await flush();
  expect(s.tabErrors.preview).toBeTruthy();
  await s.loadTab('preview');
  expect(mock.preview.mock.calls).toEqual([['9007199254740993'], ['9007199254740993']]);
  expect(s.tabErrors.preview).toBe('');
});
it('matches the source required-ID warning before attempting any execution', async () => {
  mock.get.mockResolvedValueOnce({
    asset: { id: '9007199254740993', canView: true },
    canViewApiDocument: true,
    canInvoke: false,
    apiDocument: { requestParams: [{ name: 'ID', required: true }] },
  });
  const s = await mount();
  s.selectTab('api');
  s.invoke();
  expect(mock.warning).toHaveBeenCalledWith(expect.objectContaining({ content: '输入参数“ID”不能为空' }));
  expect(mock.preview).not.toHaveBeenCalled();
  expect(mock.document).not.toHaveBeenCalled();
});
it('accepts false and zero defaults without inventing a local service invocation', async () => {
  mock.get.mockResolvedValueOnce({
    asset: { id: '9007199254740993', canView: true },
    canViewApiDocument: true,
    apiDocument: { requestParams: [{ name: 'ID', required: true, defaultValue: 0 }, { name: 'ENABLED', required: true, defaultValue: false }] },
  });
  const s = await mount();
  s.selectTab('api');
  s.invoke();
  expect(mock.warning).toHaveBeenCalledWith(expect.objectContaining({ content: '当前申请未提供可执行的本地接口。' }));
});
it('closes only its own invocation warning and blocks checks after permission loss', async () => {
  mock.get.mockResolvedValueOnce({
    asset: { id: '9007199254740993', canView: true },
    canViewApiDocument: true,
    apiDocument: { requestParams: [{ name: 'ID', required: true }] },
  });
  const s = await mount();
  s.selectTab('api');
  s.invoke();
  const key = mock.warning.mock.calls[0]?.[0].key;
  mock.access.accessCodes = [];
  await flush();
  s.invoke();
  expect(mock.warning).toHaveBeenCalledTimes(1);
  expect(mock.destroyMessage).toHaveBeenCalledWith(key);
});
