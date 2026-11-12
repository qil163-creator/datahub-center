/* eslint-disable vue/one-component-per-file -- 页面桩只验证异步查询归属 */
import type { App } from 'vue';

import {
  createApp,
  defineComponent,
  h,
  KeepAlive,
  nextTick,
  reactive,
  ref,
} from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import DataQuery from './index.vue';

const mock = vi.hoisted(() => ({
  access: {
    accessCodes: [
      'asset:data-query:query',
      'asset:data-query:execute',
      'asset:data-query:save',
    ],
    accessToken: 'session',
    refreshToken: '',
    loginExpired: false,
  },
  user: { userInfo: { userId: '1' }, userRoles: [] as string[] },
  execute: vi.fn(),
  datasources: vi.fn(),
  tables: vi.fn(),
  columns: vi.fn(),
  file: vi.fn(),
  save: vi.fn(),
  completions: vi.fn(),
  loader: vi.fn(),
  gridOptions: vi.fn(),
  loading: vi.fn(),
  reloadData: vi.fn(),
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
vi.mock('#/api/asset/dataquery', () => ({
  DataQueryApi: { FILE_NODE_FILE: 2, FILE_VISIBILITY_PRIVATE: 2 },
  executeDataQuery: mock.execute,
  getDataQueryDatasourceList: mock.datasources,
  getDataQueryTableList: mock.tables,
  getDataQueryColumnList: mock.columns,
  getDataQueryFile: mock.file,
  updateDataQueryFile: mock.save,
}));
vi.mock('#/adapter/vxe-table', () => ({
  useVbenVxeGrid: () => [
    defineComponent(() => () => h('div')),
    {
      setGridOptions: mock.gridOptions,
      setLoading: mock.loading,
      grid: { reloadColumn: vi.fn(), reloadData: mock.reloadData },
    },
  ],
}));
vi.mock('#/components/monaco/setup', () => ({
  resolveSqlLanguage: () => 'sql',
  setCompletionTables: mock.completions,
  setColumnLoader: mock.loader,
}));
vi.mock('#/components/monaco/sql-editor.vue', () => ({
  default: defineComponent({
    setup: (_, { expose }) => {
      expose({ getSelectionOrValue: () => undefined });
      return () => h('div');
    },
  }),
}));
vi.mock('./modules/file-tree.vue', () => ({
  default: defineComponent({
    setup: (_, { expose }) => {
      expose({ reload: vi.fn() });
      return () => h('div');
    },
  }),
}));
// 导出入口与水印由各自测试覆盖；这里只验证查询结果归属。
vi.mock('./modules/export-panel.vue', () => ({
  default: defineComponent({ setup: () => () => h('span') }),
}));
vi.mock('#/components/data-watermark', () => ({
  DataWatermark: defineComponent({
    setup: (_, { slots }) => () => h('div', slots.default?.()),
  }),
}));
vi.mock('../market/map/data', () => ({
  formatQueryCell: (value: unknown) => value,
}));
vi.mock('#/components/connection-icon', () => ({
  ConnectionIcon: defineComponent(() => () => h('span')),
}));
vi.mock('@vben/icons', () => ({
  IconifyIcon: defineComponent(() => () => h('span')),
}));
vi.mock('@vben/common-ui', () => ({
  Page: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.()),
  }),
}));
vi.mock('antdv-next', () => {
  const box = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('section', slots.default?.()),
  });
  return {
    Alert: box,
    Button: box,
    Card: box,
    Empty: box,
    Input: box,
    InputNumber: box,
    Segmented: box,
    Select: box,
    Spin: box,
    Tooltip: box,
    Tree: box,
    message: { warning: vi.fn(), success: vi.fn() },
  };
});

interface State {
  datasources: { id: number; name: string }[];
  datasourceId: number | undefined;
  sql: string;
  limit: number;
  executing: boolean;
  executed: boolean;
  error: string;
  result: { rows?: Record<string, unknown>[] };
  active: boolean;
  savingFile: boolean;
  treeLoading: boolean;
  expandedKeys: string[];
  expandingTree: boolean;
  treeKeyword: string;
  treeData: { children?: { key: string }[]; key: string; }[];
  toggleTreeExpanded: () => Promise<void>;
  loadDatasources: () => Promise<void>;
  loadedKeys: string[];
  onLoadData: (node: { key: string }) => Promise<void>;
  leftMode: 'datasource' | 'file';
  currentFile?: { id: number; name: string; sqlText?: string };
  handleExecute: () => Promise<void>;
  onDatasourceChange: (id: number) => Promise<void>;
  handleOpenFile: (node: { id: number }) => Promise<void>;
  handleSaveFile: () => Promise<void>;
}
let app: App | undefined;
let host: HTMLDivElement;
let state: State;
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: Error) => void;
  const promise = new Promise<T>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}
async function flush() {
  for (let i = 0; i < 6; i++) await Promise.resolve();
  await nextTick();
}
function mount() {
  app = createApp(DataQuery);
  app.directive('access', {});
  state = (app.mount(host).$ as unknown as { setupState: State }).setupState;
  state.datasourceId = 1;
  state.sql = 'select 1';
}
beforeEach(() => {
  vi.resetAllMocks();
  mock.access = reactive({
    accessCodes: [
      'asset:data-query:query',
      'asset:data-query:execute',
      'asset:data-query:save',
    ],
    accessToken: 'session',
    refreshToken: '',
    loginExpired: false,
  });
  mock.user = reactive({ userInfo: { userId: '1' }, userRoles: [] });
  mock.datasources.mockResolvedValue([
    { id: 1, name: 'A' },
    { id: 2, name: 'B' },
  ]);
  mock.tables.mockResolvedValue([]);
  mock.columns.mockResolvedValue([]);
  mock.save.mockResolvedValue(true);
  host = document.createElement('div');
  document.body.append(host);
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
});

describe('sql query response ownership', () => {
  it('preserves a draft through KeepAlive and an inactive token refresh, reloading empty sources while rejecting the old result', async () => {
    const visible = ref(true);
    app = createApp(
      defineComponent({
        setup: () => () =>
          h(KeepAlive, () =>
            visible.value
              ? h(DataQuery, {
                  ref: (instance) => {
                    if (instance)
                      state = (
                        instance as unknown as { $: { setupState: State } }
                      ).$.setupState;
                  },
                })
              : h('div'),
          ),
      }),
    );
    app.directive('access', {});
    app.mount(host);
    state.datasourceId = 1;
    state.sql = 'select unsaved_draft';
    state.currentFile = { id: 1, name: 'My SQL file' };
    const pending = deferred<object>();
    mock.execute.mockReturnValueOnce(pending.promise);
    const task = state.handleExecute();
    visible.value = false;
    await nextTick();
    mock.access.accessToken = 'refreshed-while-inactive';
    mock.access.refreshToken = 'new-inactive-refresh-token';
    expect(state.sql).toBe('select unsaved_draft');
    expect(state.currentFile?.id).toBe(1);
    expect(state.datasourceId).toBe(1);
    expect(state.datasources).toEqual([]);
    visible.value = true;
    await nextTick();
    pending.resolve({ rows: [{ old: 1 }] });
    await task;
    expect(state.sql).toBe('select unsaved_draft');
    expect(state.currentFile?.id).toBe(1);
    expect(state.executed).toBe(false);
    expect(state.result.rows).toEqual([]);
    await flush();
    expect(mock.datasources).toHaveBeenCalledTimes(2);
    expect(state.datasources).toHaveLength(2);
  });

  it.each(['accessToken', 'refreshToken'] as const)(
    'preserves the same user draft on %s refresh and reloads sources and completion without accepting old responses',
    async (token) => {
      const oldSources = deferred<{ id: number; name: string }[]>();
      const oldTables = deferred<string[]>();
      const oldResult = deferred<object>();
      mock.datasources
        .mockReturnValueOnce(oldSources.promise)
        .mockResolvedValueOnce([{ id: 1, name: 'Refreshed source' }]);
      mock.tables
        .mockReturnValueOnce(oldTables.promise)
        .mockResolvedValueOnce(['current_table']);
      mount();
      state.currentFile = { id: 1, name: 'My SQL file' };
      state.sql = 'select unsaved_draft';
      state.limit = 321;
      state.leftMode = 'file';
      const completion = state.onDatasourceChange(1);
      mock.execute.mockReturnValueOnce(oldResult.promise);
      const query = state.handleExecute();
      expect(state.treeLoading).toBe(true);
      mock.access[token] = 'refreshed-token';
      expect(state.sql).toBe('select unsaved_draft');
      expect(state.currentFile).toEqual({ id: 1, name: 'My SQL file' });
      expect(state.datasourceId).toBe(1);
      expect(state.limit).toBe(321);
      expect(state.leftMode).toBe('file');
      expect(state.expandedKeys).toContain('ds:1');
      expect(state.executing).toBe(false);
      await flush();
      expect(state.datasources).toEqual([{ id: 1, name: 'Refreshed source' }]);
      expect(mock.completions.mock.lastCall).toEqual([[{ name: 'current_table' }]]);
      const loader = mock.loader.mock.lastCall?.[0];
      await loader('current_table');
      expect(mock.columns).toHaveBeenCalledWith(1, 'current_table');
      oldSources.resolve([{ id: 2, name: 'Old source' }]);
      oldTables.resolve(['old_table']);
      oldResult.resolve({ rows: [{ old: 1 }] });
      await completion;
      await query;
      await flush();
      expect(state.datasources).toEqual([{ id: 1, name: 'Refreshed source' }]);
      expect(mock.completions.mock.lastCall).toEqual([[{ name: 'current_table' }]]);
      expect(state.executed).toBe(false);
      expect(state.result.rows).toEqual([]);
      expect(state.treeLoading).toBe(false);
      expect(state.sql).toBe('select unsaved_draft');
      expect(state.currentFile?.id).toBe(1);
      expect(state.datasourceId).toBe(1);
    },
  );

  it('releases old file-save loading on token refresh while preserving the draft and rejecting late file and save responses', async () => {
    mount();
    state.currentFile = { id: 1, name: 'My SQL file' };
    state.sql = 'select unsaved_draft';
    const oldFile = deferred<object>();
    const oldSave = deferred<object>();
    mock.file.mockReturnValueOnce(oldFile.promise);
    mock.save.mockReturnValueOnce(oldSave.promise);
    const opening = state.handleOpenFile({ id: 2 });
    const saving = state.handleSaveFile();
    expect(state.savingFile).toBe(true);
    mock.access.accessToken = 'new-access-token';
    mock.access.refreshToken = 'new-refresh-token';
    expect(state.savingFile).toBe(false);
    oldFile.resolve({ id: 2, name: 'Other file', sqlText: 'select old_file', datasourceId: 2 });
    oldSave.resolve({});
    await opening;
    await saving;
    expect(state.sql).toBe('select unsaved_draft');
    expect(state.currentFile).toEqual({ id: 1, name: 'My SQL file' });
    expect(state.datasourceId).toBe(1);
    expect(state.savingFile).toBe(false);
  });

  it.each(['user', 'permission', 'expired', 'logout'] as const)(
    'clears the draft when the actual %s boundary changes',
    (boundary) => {
      mount();
      state.currentFile = { id: 1, name: 'My SQL file' };
      if (boundary === 'user') mock.user.userInfo.userId = '2';
      if (boundary === 'permission') mock.access.accessCodes = [];
      if (boundary === 'expired') mock.access.loginExpired = true;
      if (boundary === 'logout') mock.access.accessToken = '';
      expect(state.sql).toBe('');
      expect(state.currentFile).toBeUndefined();
      expect(state.datasourceId).toBeUndefined();
    },
  );

  it('allows Ctrl+Enter queries to overlap while only the latest owns results and loading', async () => {
    mount();
    const first = deferred<object>();
    const last = deferred<object>();
    mock.execute
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(last.promise);
    const a = state.handleExecute();
    const b = state.handleExecute();
    expect(mock.execute).toHaveBeenCalledTimes(2);
    first.resolve({
      columns: [{ name: 'old', label: 'old' }],
      rows: [{ old: 1 }],
    });
    await a;
    expect(state.executing).toBe(true);
    expect(state.executed).toBe(false);
    last.resolve({
      columns: [{ name: 'new', label: 'new' }],
      rows: [{ new: 2 }],
    });
    await b;
    await flush();
    expect(state.result.rows?.[0]?.__c0).toBe(2);
    expect(state.executing).toBe(false);
    expect(mock.reloadData.mock.lastCall?.[0][0].__c0).toBe(2);
  });

  it.each(['sql', 'datasourceId', 'limit'] as const)(
    'invalidates an in-flight result when %s changes',
    async (field) => {
      mount();
      const pending = deferred<object>();
      mock.execute.mockReturnValueOnce(pending.promise);
      const task = state.handleExecute();
      if (field === 'sql') state.sql = 'select 2';
      else state[field] = 2;
      pending.resolve({ columns: [{ name: 'old' }], rows: [{ old: 1 }] });
      await task;
      expect(state.executing).toBe(false);
      expect(state.executed).toBe(false);
      expect(state.result.rows).toEqual([]);
    },
  );

  it('does not let a stale error clear the newer query or its loading state', async () => {
    mount();
    const first = deferred<object>();
    const last = deferred<object>();
    mock.execute
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(last.promise);
    const a = state.handleExecute();
    state.datasourceId = 2;
    const b = state.handleExecute();
    first.reject(new Error('A source failed'));
    await a;
    expect(state.error).toBe('');
    expect(state.executing).toBe(true);
    last.resolve({ rows: [] });
    await b;
    expect(state.executed).toBe(true);
  });

  it.each(['permission', 'user', 'inactive', 'session'] as const)(
    'rejects late results after a %s boundary',
    async (boundary) => {
      mount();
      const pending = deferred<object>();
      mock.execute.mockReturnValueOnce(pending.promise);
      const task = state.handleExecute();
      if (boundary === 'permission') mock.access.accessCodes = [];
      if (boundary === 'user') mock.user.userInfo.userId = '2';
      if (boundary === 'inactive') state.active = false;
      if (boundary === 'session') mock.access.refreshToken = 'new';
      pending.resolve({ rows: [{ old: 1 }] });
      await task;
      expect(state.executed).toBe(false);
      expect(state.result.rows).toEqual([]);
    },
  );

  it('keeps completion tables and column loaders on the last selected datasource', async () => {
    mount();
    const a = deferred<string[]>();
    const b = deferred<string[]>();
    mock.tables.mockReturnValueOnce(a.promise).mockReturnValueOnce(b.promise);
    const first = state.onDatasourceChange(1);
    const last = state.onDatasourceChange(2);
    b.resolve(['new_table']);
    await last;
    a.resolve(['old_table']);
    await first;
    expect(mock.completions.mock.lastCall).toEqual([[{ name: 'new_table' }]]);
    const loader = mock.loader.mock.lastCall?.[0];
    await loader('new_table');
    expect(mock.columns).toHaveBeenCalledWith(2, 'new_table');
  });

  it('keeps the last opened SQL file when file detail requests finish out of order', async () => {
    mount();
    const a = deferred<object>();
    const b = deferred<object>();
    mock.file.mockReturnValueOnce(a.promise).mockReturnValueOnce(b.promise);
    const first = state.handleOpenFile({ id: 1 });
    const last = state.handleOpenFile({ id: 2 });
    b.resolve({ id: 2, name: 'B', sqlText: 'select 2', datasourceId: 2 });
    await last;
    a.resolve({ id: 1, name: 'A', sqlText: 'select 1', datasourceId: 1 });
    await first;
    expect(state.currentFile?.id).toBe(2);
    expect(state.sql).toBe('select 2');
    expect(state.datasourceId).toBe(2);
  });

  it('does not rewrite another file with a previous file save response', async () => {
    mount();
    state.currentFile = { id: 1, name: 'A' };
    const saved = deferred<object>();
    mock.save.mockReturnValueOnce(saved.promise);
    const task = state.handleSaveFile();
    state.currentFile = { id: 2, name: 'B', sqlText: 'select 2' };
    state.sql = 'select 2';
    saved.resolve({});
    await task;
    expect(state.currentFile).toEqual({
      id: 2,
      name: 'B',
      sqlText: 'select 2',
    });
    expect(mock.save.mock.calls[0]?.[0]).toMatchObject({
      id: 1,
      sqlText: 'select 1',
      datasourceId: 1,
    });
  });
});


describe('数据源树展开与刷新', () => {
  it('逐层加载所有表字段，并在收起后保留已加载数据供再次展开', async () => {
    mock.tables.mockResolvedValue(['orders']);
    mock.columns.mockResolvedValue([{ columnName: 'id', typeName: 'bigint' }]);
    mount();
    await flush();
    state.expandedKeys = ['ds:1', 'ds:2'];
    await state.toggleTreeExpanded();
    expect(mock.columns).toHaveBeenCalledTimes(2);
    expect(state.expandedKeys).toEqual(expect.arrayContaining([
      'ds:1', 'ds:2', 'table:1:orders', 'table:2:orders',
    ]));
    await state.toggleTreeExpanded();
    expect(state.expandedKeys).toEqual([]);
    await state.toggleTreeExpanded();
    expect(mock.columns).toHaveBeenCalledTimes(2);
  });

  it('刷新期间禁止从旧根节点启动懒加载或全部展开', async () => {
    mount();
    await flush();
    const oldRoot = state.treeData[0]!;
    const pending = deferred<{ id: number; name: string }[]>();
    mock.datasources.mockReturnValueOnce(pending.promise);
    const refresh = state.loadDatasources();
    expect(state.treeLoading).toBe(true);
    await state.toggleTreeExpanded();
    await expect(state.onLoadData(oldRoot)).rejects.toMatchObject({ name: 'AbortError' });
    expect(mock.tables).not.toHaveBeenCalled();
    expect(state.expandingTree).toBe(false);
    pending.resolve([{ id: 1, name: '刷新后的数据源' }]);
    await refresh;
    expect(state.treeData[0]).not.toBe(oldRoot);
    expect(state.treeData[0]!.children).toBeUndefined();
    expect(state.loadedKeys).toEqual([]);
  });

  it('加载中收起会丢弃晚到结果并停止后续请求', async () => {
    const pending = deferred<string[]>();
    mock.tables.mockReturnValue(pending.promise);
    mount();
    await flush();
    const expand = state.toggleTreeExpanded();
    expect(state.expandingTree).toBe(true);
    await state.toggleTreeExpanded();
    pending.resolve(['orders']);
    await expand;
    expect(state.expandedKeys).toEqual([]);
    expect(state.treeData[0]!.children).toBeUndefined();
    expect(mock.tables).toHaveBeenCalledTimes(1);
    expect(mock.columns).not.toHaveBeenCalled();
  });

  it('刷新与选择变化终止旧展开，刷新保留查询词与选中数据源', async () => {
    const pending = deferred<string[]>();
    mock.tables.mockReturnValueOnce(pending.promise).mockResolvedValue([]);
    mount();
    await flush();
    state.treeKeyword = 'A';
    await flush();
    const expand = state.toggleTreeExpanded();
    await state.loadDatasources();
    pending.resolve(['stale']);
    await expand;
    expect(state.treeData[0]!.children).toBeUndefined();
    expect(state.treeKeyword).toBe('A');
    expect(state.datasourceId).toBe(1);
    const second = deferred<string[]>();
    mock.tables.mockReturnValueOnce(second.promise).mockResolvedValue([]);
    const nextExpand = state.toggleTreeExpanded();
    await state.onDatasourceChange(2);
    second.resolve(['stale']);
    await nextExpand;
    expect(mock.columns).not.toHaveBeenCalled();
    expect(state.treeData[0]!.children).toBeUndefined();
    expect(state.datasourceId).toBe(2);
  });
});
