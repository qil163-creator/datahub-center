import type { App } from 'vue';

import type { DataforgeDataFileApi as Api } from '#/api/dataforge/datafile';

import { createApp, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { useFileManager } from './use-file-manager';

const harness = vi.hoisted(() => ({
  access: {} as {
    accessCodes: string[];
    accessToken: string;
    refreshToken: string;
  },
  user: {} as { userInfo: { userId: string }; userRoles: string[] },
  query: {} as Record<string, string>,
  allowed: true,
  connections: vi.fn(),
  page: vi.fn(),
  summary: vi.fn(),
  tree: vi.fn(),
  rename: vi.fn(),
  mkdir: vi.fn(),
  remove: vi.fn(),
  preview: vi.fn(),
  previewContent: vi.fn(),
  upload: vi.fn(),
  download: vi.fn(),
  downloadBatch: vi.fn(),
  confirm: vi.fn(),
  saveBlob: vi.fn(),
  warning: vi.fn(),
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => harness.access,
  useUserStore: () => harness.user,
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({ hasAccessByCodes: () => harness.allowed }),
}));
vi.mock('vue-router', () => ({ useRoute: () => ({ query: harness.query }) }));
vi.mock('@vben/common-ui', () => ({
  confirm: (...args: unknown[]) => harness.confirm(...args),
}));
vi.mock('@vben/utils', () => ({
  downloadFileFromBlobPart: (...args: unknown[]) => harness.saveBlob(...args),
}));
vi.mock('antdv-next', () => ({
  message: {
    success: vi.fn(),
    warning: (...args: unknown[]) => harness.warning(...args),
  },
}));
vi.mock('#/api/dataforge/datafile', () => ({
  getFileConnections: (...args: unknown[]) => harness.connections(...args),
  getDataFilePage: (...args: unknown[]) => harness.page(...args),
  getFileSummary: (...args: unknown[]) => harness.summary(...args),
  getFileDirectories: (...args: unknown[]) => harness.tree(...args),
  renameDataFile: (...args: unknown[]) => harness.rename(...args),
  createFileDirectory: (...args: unknown[]) => harness.mkdir(...args),
  deleteDataFiles: (...args: unknown[]) => harness.remove(...args),
  previewDataFile: (...args: unknown[]) => harness.preview(...args),
  previewDataFileContent: (...args: unknown[]) => harness.previewContent(...args),
  uploadDataFile: (...args: unknown[]) => harness.upload(...args),
  downloadDataFile: (...args: unknown[]) => harness.download(...args),
  downloadDataFiles: (...args: unknown[]) => harness.downloadBatch(...args),
}));
const first: Api.Connection = {
  id: '9007199254740993',
  name: '测试 HDFS',
  type: 'HDFS',
  status: 0,
  address: 'hdfs.test:9870',
  rootPath: '/',
  supportsOverwrite: true,
};
const second: Api.Connection = {
  ...first,
  id: '9007199254740995',
  name: '测试 FTP',
  type: 'FTP',
};
const file: Api.Entry = {
  name: 'report.csv',
  path: '/report.csv',
  directory: false,
  type: 'CSV',
  size: 10,
  updateTime: 1,
};
let app: App;
let host: HTMLElement;
let manager: ReturnType<typeof useFileManager>;
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}
async function flush() {
  await Promise.resolve();
  await Promise.resolve();
  await nextTick();
}
function mount() {
  host = document.createElement('div');
  app = createApp({
    setup() {
      manager = useFileManager();
      return () => h('div');
    },
  });
  app.mount(host);
}
beforeEach(() => {
  vi.clearAllMocks();
  harness.access = reactive({
    accessToken: 'session-a',
    refreshToken: 'refresh-a',
    accessCodes: ['query'],
  });
  harness.user = reactive({ userInfo: { userId: '1' }, userRoles: ['admin'] });
  harness.query = reactive({});
  harness.allowed = true;
  harness.connections.mockResolvedValue([first, second]);
  harness.page.mockResolvedValue({ list: [file], total: 1 });
  harness.summary.mockResolvedValue({
    connectionStatus: 'CONNECTED',
    fileCount: 1,
    directoryCount: 0,
    todayUploadCount: 0,
    storageBytes: 10,
    complete: true,
  });
  harness.tree.mockResolvedValue([]);
  harness.rename.mockResolvedValue(true);
  harness.mkdir.mockResolvedValue(true);
  harness.confirm.mockResolvedValue(undefined);
  harness.upload.mockResolvedValue(true);
  vi.stubGlobal('URL', class extends URL { static override createObjectURL = vi.fn(() => 'blob:test-preview'); static override revokeObjectURL = vi.fn(); });
});
afterEach(() => {
  app?.unmount();
  vi.unstubAllGlobals();
});

describe('文件工作台请求隔离与写操作', () => {
  it('全部展开逐层读取远端目录，收起不改变当前目录和搜索', async () => {
    const directories: Record<string, Api.Directory[]> = {
      '/': [{ name: 'a', path: '/a', directory: true }],
      '/a': [{ name: 'b', path: '/a/b', directory: true }],
      '/a/b': [],
    };
    harness.tree.mockImplementation(({ path }: { path: string }) => Promise.resolve(directories[path] ?? []));
    mount();
    await flush();
    await manager.selectConnection(first);
    await manager.navigate('/a');
    manager.directoryFilter.value = 'b';
    await manager.toggleExpandDirectories();
    expect(manager.expandedKeys.value).toEqual(['/', '/a']);
    expect(manager.allDirectoriesExpanded.value).toBe(true);
    expect(harness.tree).toHaveBeenCalledWith({ connectionId: first.id, path: '/a/b' });
    await manager.toggleExpandDirectories();
    expect(manager.expandedKeys.value).toEqual([]);
    expect(manager.path.value).toBe('/a');
    expect(manager.directoryFilter.value).toBe('b');
  });

  it('展开过程中收起会停止后续目录请求，之后可以继续展开', async () => {
    mount();
    await flush();
    harness.tree.mockResolvedValueOnce([{ name: 'a', path: '/a', directory: true }]);
    await manager.selectConnection(first);
    const pending = deferred<Api.Directory[]>();
    harness.tree.mockReturnValueOnce(pending.promise);
    const expanding = manager.toggleExpandDirectories();
    await flush();
    expect(manager.directoryOperation.value).toBe('expand');
    await manager.toggleExpandDirectories();
    pending.resolve([{ name: 'b', path: '/a/b', directory: true }]);
    await expanding;
    expect(manager.expandedKeys.value).toEqual([]);
    expect(harness.tree).not.toHaveBeenCalledWith({ connectionId: first.id, path: '/a/b' });
    await manager.toggleExpandDirectories();
    expect(manager.allDirectoriesExpanded.value).toBe(true);
    expect(manager.directoryOperation.value).toBeUndefined();
  });

  it('刷新目录保留导航状态，迟到的上次刷新不覆盖新目录', async () => {
    mount();
    await flush();
    await manager.selectConnection(first);
    manager.path.value = '/selected';
    manager.directoryFilter.value = '筛选';
    manager.expandedKeys.value = ['/', '/selected'];
    const old = deferred<Api.Directory[]>();
    const latest = deferred<Api.Directory[]>();
    harness.tree.mockReturnValueOnce(old.promise).mockReturnValueOnce(latest.promise);
    const previous = manager.refreshDirectories();
    const current = manager.refreshDirectories();
    latest.resolve([{ name: '新筛选目录', path: '/new', directory: true }]);
    await current;
    old.resolve([{ name: '旧筛选目录', path: '/old', directory: true }]);
    await previous;
    expect(JSON.stringify(manager.tree.value)).toContain('新筛选目录');
    expect(JSON.stringify(manager.tree.value)).not.toContain('旧筛选目录');
    expect(manager.path.value).toBe('/selected');
    expect(manager.directoryFilter.value).toBe('筛选');
    expect(manager.expandedKeys.value).toEqual(['/', '/selected']);
    expect(manager.directoryOperation.value).toBeUndefined();
  });

  it('远端返回重复祖先目录时不会循环加载或递归渲染', async () => {
    harness.tree.mockImplementation(({ path }: { path: string }) => Promise.resolve([
      path === '/' ? { name: 'a', path: '/a', directory: true } : { name: 'root', path: '/', directory: true },
    ]));
    mount();
    await flush();
    await manager.selectConnection(first);
    await manager.toggleExpandDirectories();
    expect(harness.tree).toHaveBeenCalledTimes(2);
    expect(manager.tree.value[0]?.children?.[0]?.children).toEqual([]);
  });

  it('图片预览限制MIME并在关闭时释放对象地址', async () => {
    mount(); await flush(); await manager.selectConnection(first);
    harness.preview.mockResolvedValueOnce({ name: 'picture.png', type: 'IMAGE', content: '', truncated: false });
    harness.previewContent.mockResolvedValueOnce(new Blob(['image'], { type: 'image/png' }));
    await manager.previewEntry({ ...file, name: 'picture.png', path: '/picture.png' });
    expect(manager.previewUrl.value).toBe('blob:test-preview');
    expect(harness.previewContent).toHaveBeenCalledWith({ connectionId: first.id, path: '/picture.png' });
    manager.previewOpen.value = false; await flush();
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:test-preview'); expect(manager.previewUrl.value).toBe('');
    harness.preview.mockResolvedValueOnce({ name: 'picture.svg', type: 'IMAGE', content: '', truncated: false });
    harness.previewContent.mockResolvedValueOnce(new Blob(['<svg onload="alert(1)"></svg>'], { type: 'image/svg+xml' }));
    await manager.previewEntry(file); expect(manager.previewError.value).toBe(true); expect(manager.previewUrl.value).toBe('');
  });
  it('已关闭预览的延迟二进制内容不会产生对象地址', async () => {
    mount(); await flush(); await manager.selectConnection(first);
    const late = deferred<Blob>();
    harness.preview.mockResolvedValueOnce({ name: 'report.pdf', type: 'PDF', content: '', truncated: false });
    harness.previewContent.mockReturnValueOnce(late.promise);
    const request = manager.previewEntry(file); await flush(); manager.previewOpen.value = false; await flush();
    late.resolve(new Blob(['%PDF'], { type: 'application/pdf' })); await request;
    expect(URL.createObjectURL).not.toHaveBeenCalled(); expect(manager.previewUrl.value).toBe('');
  });
  it('初始不自动选中连接；深链接匹配保留大编号', async () => {
    mount();
    await flush();
    expect(manager.connection.value).toBeUndefined();
    expect(harness.page).not.toHaveBeenCalled();
    harness.query.datasourceId = first.id as string;
    await flush();
    expect(harness.page).toHaveBeenCalledWith(
      expect.objectContaining({ connectionId: first.id, path: '/' }),
    );
  });
  it('更晚的查询响应获胜，排序与筛选使用真实分页契约', async () => {
    mount();
    await flush();
    await manager.selectConnection(first);
    const old = deferred<{ list: Api.Entry[]; total: number }>();
    const newest = deferred<{ list: Api.Entry[]; total: number }>();
    harness.page
      .mockReturnValueOnce(old.promise)
      .mockReturnValueOnce(newest.promise);
    manager.name.value = ' first ';
    void manager.search();
    manager.name.value = ' latest ';
    manager.type.value = 'CSV';
    manager.order.value = 'desc';
    const request = manager.search();
    newest.resolve({ list: [{ ...file, name: 'latest.csv' }], total: 1 });
    await request;
    old.resolve({ list: [{ ...file, name: 'old.csv' }], total: 1 });
    await flush();
    expect(manager.rows.value[0]?.name).toBe('latest.csv');
    expect(manager.loading.value).toBe(false);
    expect(harness.page).toHaveBeenLastCalledWith(
      expect.objectContaining({
        name: 'latest',
        type: 'CSV',
        order: 'desc',
        pageNo: 1,
        pageSize: 10,
      }),
    );
  });
  it('切换连接不会接收前一连接的列表、统计和目录', async () => {
    mount();
    await flush();
    const oldPage = deferred<{ list: Api.Entry[]; total: number }>();
    const oldSummary = deferred<Api.Summary>();
    const oldTree = deferred<Api.Directory[]>();
    harness.page.mockReturnValueOnce(oldPage.promise);
    harness.summary.mockReturnValueOnce(oldSummary.promise);
    harness.tree.mockReturnValueOnce(oldTree.promise);
    const old = manager.selectConnection(first);
    await manager.selectConnection(second);
    oldPage.resolve({ list: [{ ...file, name: '旧连接' }], total: 1 });
    oldTree.resolve([{ name: '旧目录', path: '/old', directory: true }]);
    oldSummary.resolve({
      connectionStatus: 'FAILED',
      fileCount: 999,
      directoryCount: 0,
      storageBytes: 0,
      todayUploadCount: 0,
      complete: true,
    });
    await old;
    expect(manager.connection.value?.id).toBe(second.id);
    expect(manager.rows.value[0]?.name).toBe(file.name);
    expect(manager.summary.value?.fileCount).toBe(1);
    expect(JSON.stringify(manager.tree.value)).not.toContain('旧目录');
  });
  it('切换连接清除文件和目录筛选并从根目录第一页加载', async () => {
    mount();
    await flush();
    await manager.selectConnection(first);
    manager.name.value = 'report';
    manager.type.value = 'CSV';
    manager.directoryFilter.value = 'old-folder';
    await manager.search();
    await manager.navigate('/old-folder');
    manager.pageNo.value = 3;
    await manager.selectConnection(second);
    expect(manager.name.value).toBe('');
    expect(manager.type.value).toBeUndefined();
    expect(manager.directoryFilter.value).toBe('');
    expect(manager.path.value).toBe('/');
    expect(harness.page).toHaveBeenLastCalledWith(expect.objectContaining({
      connectionId: second.id,
      path: '/',
      pageNo: 1,
      name: undefined,
      type: undefined,
    }));
  });
  it('会话变化使旧预览响应失效并清空敏感状态', async () => {
    mount();
    await flush();
    await manager.selectConnection(first);
    const delayed = deferred<Api.Preview>();
    harness.preview.mockReturnValueOnce(delayed.promise);
    const request = manager.previewEntry(file);
    harness.access.accessToken = 'session-b';
    await flush();
    delayed.resolve({
      name: 'old-secret',
      content: 'private',
      truncated: false,
      type: 'TEXT',
    });
    await request;
    expect(manager.preview.value).toBeUndefined();
    expect(manager.previewOpen.value).toBe(false);
    expect(manager.connection.value).toBeUndefined();
    expect(manager.rows.value).toEqual([]);
  });
  it('拒绝百分号编码歧义，按字面提交含百分号的文件名', async () => {
    mount();
    await flush();
    await manager.selectConnection(first);
    manager.openRename('/before.txt', 'before.txt');
    manager.editName.value = '%2e%2e.txt';
    await manager.saveName();
    expect(harness.rename).not.toHaveBeenCalled();
    manager.editName.value = '100%+中文.txt';
    await manager.saveName();
    expect(harness.rename).toHaveBeenCalledWith({
      connectionId: first.id,
      path: '/before.txt',
      name: '100%+中文.txt',
    });
  });
  it('目录重命名后当前后代路径同步；重复名字不提交', async () => {
    mount();
    await flush();
    await manager.selectConnection(first);
    manager.path.value = '/before/child';
    manager.openRename('/before', 'before');
    await manager.saveName();
    expect(harness.rename).not.toHaveBeenCalled();
    manager.openRename('/before', 'before');
    manager.editName.value = ' after ';
    await manager.saveName();
    expect(harness.rename).toHaveBeenCalledWith({
      connectionId: first.id,
      path: '/before',
      name: 'after',
    });
    expect(manager.path.value).toBe('/after/child');
    expect(manager.busy.value).toBe(false);
  });
  it('上传失败只重试失败项，保留原始连接和逻辑路径', async () => {
    mount();
    await flush();
    await manager.selectConnection(first);
    manager.path.value = '/upload';
    manager.openUpload(true);
    const a = new File(['a'], 'a.txt');
    const b = new File(['b'], 'b.txt');
    manager.addFiles([a, b]);
    harness.upload
      .mockResolvedValueOnce(true)
      .mockRejectedValueOnce(new Error('fail'));
    await manager.submitUploads();
    expect(manager.uploads.value.map((item) => item.state)).toEqual([
      'success',
      'error',
    ]);
    await manager.submitUploads();
    expect(harness.upload).toHaveBeenCalledTimes(3);
    expect(harness.upload).toHaveBeenLastCalledWith(
      b,
      { connectionId: first.id, path: '/upload' },
      false,
    );
    expect(manager.uploading.value).toBe(false);
  });
  it('上传批量上限和重名均在发送前拒绝', async () => {
    mount();
    await flush();
    await manager.selectConnection(first);
    manager.openUpload(true);
    manager.addFiles(
      Array.from({ length: 21 }, (_, i) => new File([''], `${i}.txt`)),
    );
    expect(manager.uploads.value).toHaveLength(0);
    manager.addFiles([
      new File(['a'], 'same.txt'),
      new File(['b'], 'same.txt'),
    ]);
    expect(manager.uploads.value).toHaveLength(0);
    expect(harness.upload).not.toHaveBeenCalled();
    expect(harness.warning).toHaveBeenCalledTimes(2);
  });
  it('部分删除成功保留失败详情并退出已删除的目录', async () => {
    mount();
    await flush();
    await manager.selectConnection(first);
    manager.path.value = '/deleted/child';
    manager.selected.value = [file];
    harness.remove.mockResolvedValueOnce({
      succeededPaths: ['/deleted'],
      failures: [{ path: '/failed', message: '无权限' }],
    });
    await manager.remove([
      { name: 'deleted', path: '/deleted' },
      { name: 'failed', path: '/failed' },
    ]);
    expect(manager.path.value).toBe('/');
    expect(manager.failures.value).toEqual([
      { path: '/failed', message: '无权限' },
    ]);
    expect(manager.selected.value).toEqual([]);
    expect(manager.busy.value).toBe(false);
  });
  it('缺少查询权限时不请求连接或文件源', async () => {
    harness.allowed = false;
    mount();
    await flush();
    expect(harness.connections).not.toHaveBeenCalled();
    expect(harness.page).not.toHaveBeenCalled();
  });
});
