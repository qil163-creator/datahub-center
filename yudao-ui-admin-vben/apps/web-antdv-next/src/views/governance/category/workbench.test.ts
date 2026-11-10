/* eslint-disable vue/one-component-per-file -- Minimal controls exercise domain state without duplicating Ant rendering. */
import type { Component, ComponentPublicInstance } from 'vue';

import type { SafetyCategoryWorkbenchApi as Api } from '#/api/governance/category/workbench';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, expect, it, vi } from 'vitest';

import List from './index.vue';
import Dialog from './modules/workbench-dialog.vue';
import {
  categoryConfigured,
  categoryDescendants,
  validCategoryRevision,
} from './reference-data';

const calls = vi.hoisted(() => ({
  page: vi.fn(),
  options: vi.fn(),
  get: vi.fn(),
  getMask: vi.fn(),
  getDirectory: vi.fn(),
  save: vi.fn(),
  saveMask: vi.fn(),
  saveDirectory: vi.fn(),
  createLevel: vi.fn(),
  batch: vi.fn(),
  status: vi.fn(),
  remove: vi.fn(),
  removeList: vi.fn(),
  removeDirectory: vi.fn(),
  confirm: vi.fn(),
  destroy: vi.fn(),
  success: vi.fn(),
  warning: vi.fn(),
  close: vi.fn(),
}));
let identity: ReturnType<typeof reactive<{ value: string }>>;
let permissions: Set<string>;
vi.mock('#/adapter/business-vxe-table', async () => import('#/views/governance/common/vxe-grid.test-support'));
vi.mock('./use-session', () => ({
  useCategorySession: () => ({
    identity: {
      get value() {
        return identity.value;
      },
      __v_isRef: true,
    },
    can: (action: string, domain = 'category') =>
      permissions.has(`${domain}:${action}`),
  }),
}));
vi.mock('#/api/governance/category/workbench', () => ({
  getCategoryWorkbenchPage: calls.page,
  getCategoryWorkbenchOptions: calls.options,
  getCategoryWorkbench: calls.get,
  getCategoryWorkbenchMask: calls.getMask,
  getCategoryWorkbenchDirectory: calls.getDirectory,
  saveCategoryWorkbench: calls.save,
  saveCategoryWorkbenchMask: calls.saveMask,
  saveCategoryWorkbenchDirectory: calls.saveDirectory,
  createCategoryWorkbenchLevel: calls.createLevel,
  setCategoryWorkbenchLevel: calls.batch,
  changeCategoryWorkbenchStatus: calls.status,
  deleteCategoryWorkbench: calls.remove,
  deleteCategoryWorkbenchList: calls.removeList,
  deleteCategoryWorkbenchDirectory: calls.removeDirectory,
}));
vi.mock('#/views/governance/common/native-search.vue', () => ({
  default: defineComponent({ setup: () => () => null }),
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
        h('div', slots.default?.()),
  });
  return {
    Alert: box,
    Button: box,
    CheckboxGroup: box,
    ConfigProvider: box,
    Dropdown: box,
    Form: box,
    FormItem: box,
    Input: Object.assign(box, { TextArea: box }),
    InputNumber: box,
    Menu: box,
    MenuItem: box,
    Modal: Object.assign(box, { confirm: calls.confirm }),
    Pagination: box,
    RadioGroup: box,
    Select: box,
    Spin: box,
    Tooltip: box,
    Switch: box,
    Table: box,
    Tag: box,
    Tree: box,
    TreeSelect: box,
    theme: { defaultAlgorithm: undefined },
    message: { success: calls.success, warning: calls.warning, error: vi.fn() },
  };
});
const mounted: (() => void)[] = [];
function mount<T>(component: Component, props: Record<string, unknown> = {}) {
  const host = document.createElement('div');
  document.body.append(host);
  const app = createApp(component, props);
  const vm = app.mount(host) as ComponentPublicInstance;
  mounted.push(() => {
    app.unmount();
    host.remove();
  });
  return Reflect.get(vm.$, 'setupState') as T;
}
async function flush() {
  for (let index = 0; index < 15; index++) await Promise.resolve();
  await nextTick();
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}
const row = (id = '9007199254740993'): Api.Category => ({
  id,
  revision: '9007199254740995',
  categoryId: '9007199254740994',
  dataLevelId: '3',
  name: '系统运行日志',
  priority: 'HIGH',
  status: 0,
});
const context: Api.Options = {
  directories: [
    { id: '9007199254740994', parentId: 0, categoryName: '日志审计' },
    {
      id: '9007199254740996',
      parentId: '9007199254740994',
      categoryName: '审计明细',
    },
  ],
  levels: [{ id: '3', safetyLevel: 'L3', status: 0 }],
  priorities: [
    { value: 'HIGH', label: '高' },
    { value: 'LOWEST', label: '最低' },
  ],
};
beforeEach(() => {
  vi.resetAllMocks();
  identity = reactive({ value: 'session' });
  permissions = new Set([
    'category-data:create',
    'category-data:delete',
    'category-data:update',
    'category:create',
    'category:delete',
    'category:query',
    'category:update',
    'level:create',
  ]);
  calls.options.mockResolvedValue(context);
  calls.page.mockResolvedValue({ list: [], total: 0 });
  calls.get.mockImplementation(async (id) => row(id));
  calls.getMask.mockResolvedValue(null);
  calls.confirm.mockReturnValue({ destroy: calls.destroy });
  calls.save.mockResolvedValue('4');
  calls.saveMask.mockResolvedValue(true);
  calls.getDirectory.mockResolvedValue({
    id: '7',
    revision: '8',
    parentId: 0,
    categoryName: '目录',
    remark: '说明',
    status: 0,
  });
});
afterEach(() => {
  for (const dispose of mounted.splice(0)) dispose();
});
interface ListState {
  rows: Api.Category[];
  selectedRowKeys: string[];
  pending: Map<string, symbol>;
  dialog: unknown;
  loadRows: () => Promise<void>;
  selectDirectory: (id: string) => void;
  open: (request: unknown) => void;
  statusChange: (row: Api.Category, checked: boolean) => void;
  remove: () => void;
}
interface DialogState {
  form: Partial<Api.Save>;
  mask: Api.Mask;
  ready: boolean;
  errorText: string;
  saving: boolean;
  save: () => Promise<void>;
}
it('keeps long IDs and descendant filters lossless, including zero revisions and disabled configured rules', () => {
  expect(categoryDescendants('9007199254740994', context.directories)).toEqual([
    '9007199254740994',
    '9007199254740996',
  ]);
  expect(validCategoryRevision('0')).toBe(true);
  expect(validCategoryRevision(9_007_199_254_740_992)).toBe(false);
  expect(categoryConfigured({ ...row(), configured: true, status: 1 })).toBe(
    true,
  );
});
it('ignores old list reads and clears old rows when permissions or session change', async () => {
  const state = mount<ListState>(List);
  await flush();
  const old = deferred<{ list: Api.Category[]; total: number }>();
  calls.page.mockReturnValueOnce(old.promise);
  const waiting = state.loadRows();
  calls.page.mockResolvedValueOnce({ list: [row('2')], total: 1 });
  await state.loadRows();
  old.resolve({ list: [row('3')], total: 1 });
  await waiting;
  expect(state.rows[0]?.id).toBe('2');
  permissions.clear();
  identity.value = 'new-session';
  await flush();
  expect(state.rows).toEqual([]);
  const count = calls.page.mock.calls.length;
  await state.loadRows();
  expect(calls.page).toHaveBeenCalledTimes(count);
});
it('passes every descendant ID to the server and permits editing enabled classifications', async () => {
  const state = mount<ListState>(List);
  await flush();
  state.selectDirectory('9007199254740994');
  await flush();
  expect(calls.page).toHaveBeenLastCalledWith(
    expect.objectContaining({
      categoryIds: ['9007199254740994', '9007199254740996'],
      pageNo: 1,
    }),
  );
  state.open({ mode: 'edit', id: row().id });
  expect(state.dialog).toEqual({ mode: 'edit', id: row().id });
});
it('refreshes after each completed concurrent status write and carries exact CAS revisions', async () => {
  const state = mount<ListState>(List);
  await flush();
  const a = deferred<unknown>();
  const b = deferred<unknown>();
  calls.status.mockReturnValueOnce(a.promise).mockReturnValueOnce(b.promise);
  state.statusChange(row('1'), false);
  state.statusChange(row('2'), false);
  const first = calls.confirm.mock.calls[0]![0].onOk();
  const second = calls.confirm.mock.calls[1]![0].onOk();
  const count = calls.page.mock.calls.length;
  b.resolve(true);
  await second;
  a.resolve(true);
  await first;
  expect(calls.page).toHaveBeenCalledTimes(count + 2);
  expect(calls.status).toHaveBeenCalledWith({
    id: '1',
    revision: '9007199254740995',
    status: 1,
  });
});
it('sends a single atomic delete with all selected revisions and prevents old confirms after session changes', async () => {
  const state = mount<ListState>(List);
  await flush();
  state.rows = [row('1'), row('2')];
  state.selectedRowKeys = ['1', '2'];
  state.remove();
  await calls.confirm.mock.calls[0]![0].onOk();
  expect(calls.removeList).toHaveBeenCalledWith([
    { id: '1', revision: '9007199254740995' },
    { id: '2', revision: '9007199254740995' },
  ]);
  expect(calls.remove).not.toHaveBeenCalled();
  state.rows = [row('1')];
  state.selectedRowKeys = ['1'];
  state.remove();
  const old = calls.confirm.mock.calls[1]![0];
  identity.value = 'replacement';
  await flush();
  await old.onOk();
  expect(calls.removeList).toHaveBeenCalledOnce();
  expect(calls.destroy).toHaveBeenCalled();
});
it('creates disabled by default with trimmed name and no old sort or remark fields', async () => {
  const state = mount<DialogState>(Dialog, {
    request: { mode: 'create', categoryId: row().categoryId },
  });
  await flush();
  Object.assign(state.form, {
    name: ' 新分类 ',
    dataLevelId: '3',
    priority: 'LOWEST',
  });
  await state.save();
  expect(calls.save).toHaveBeenCalledWith(
    expect.objectContaining({
      name: '新分类',
      status: 1,
      priority: 'LOWEST',
      categoryId: '9007199254740994',
    }),
  );
  expect(calls.save.mock.calls[0]![0]).not.toHaveProperty('sort');
  expect(calls.save.mock.calls[0]![0]).not.toHaveProperty('remark');
});
it('reads fresh enabled detail and does not submit a stale or mismatched identity', async () => {
  const state = mount<DialogState>(Dialog, {
    request: { mode: 'edit', id: row().id },
  });
  await flush();
  state.form.name = '修改后';
  await state.save();
  expect(calls.save).toHaveBeenCalledWith(
    expect.objectContaining({
      id: row().id,
      revision: '9007199254740995',
      status: 0,
    }),
  );
  calls.get.mockResolvedValueOnce(row('different'));
  const bad = mount<DialogState>(Dialog, {
    request: { mode: 'edit', id: row().id },
  });
  await flush();
  await bad.save();
  expect(calls.save).toHaveBeenCalledOnce();
  expect(bad.ready).toBe(false);
});
it('preserves inclusive single-character intervals and replacement content while excluding rule identity from writes', async () => {
  calls.getMask.mockResolvedValue({
    id: row().id,
    revision: '12',
    ruleId: '72',
    ruleVersionId: '9007199254740997',
    ruleRevisionToken: 'opaque-rule-token',
    ruleName: '脱敏规则',
    applicationScene: ['1', '2'],
    maskType: '2',
    replaceRule: '1',
    replaceContent: '******',
    intervalList: [
      { startNum: 1, endNum: 1 },
      { startNum: 8, endNum: 10 },
    ],
    status: 1,
  });
  const state = mount<DialogState>(Dialog, {
    request: { mode: 'mask', id: row().id },
  });
  await flush();
  await state.save();
  expect(calls.saveMask).toHaveBeenCalledWith(
    expect.objectContaining({
      id: row().id,
      revision: '12',
      ruleVersionId: '9007199254740997',
      ruleRevisionToken: 'opaque-rule-token',
      replaceContent: '******',
      intervalList: [
        { startNum: 1, endNum: 1 },
        { startNum: 8, endNum: 10 },
      ],
    }),
  );
  expect(calls.saveMask.mock.calls[0]![0]).not.toHaveProperty('ruleId');
});
it('rejects incomplete ranges, read-only mask writes, duplicate submissions and stale write results', async () => {
  const state = mount<DialogState>(Dialog, {
    request: { mode: 'mask', id: row().id },
    onClose: calls.close,
  });
  await flush();
  Object.assign(state.mask, {
    ruleName: '规则',
    applicationScene: ['1'],
    replaceContent: '*',
    intervalList: [{ startNum: 2, endNum: 1 }],
  });
  await state.save();
  expect(calls.saveMask).not.toHaveBeenCalled();
  state.mask.intervalList = [{ startNum: 1, endNum: 1 }];
  const pending = deferred<unknown>();
  calls.saveMask.mockReturnValueOnce(pending.promise);
  const writing = state.save();
  await state.save();
  expect(calls.saveMask).toHaveBeenCalledOnce();
  identity.value = 'new';
  await flush();
  pending.resolve(true);
  await writing;
  expect(calls.success).not.toHaveBeenCalled();
  expect(calls.close).toHaveBeenCalledOnce();
  const view = mount<DialogState>(Dialog, {
    request: { mode: 'mask-view', id: row().id },
  });
  await flush();
  await view.save();
  expect(calls.saveMask).toHaveBeenCalledOnce();
});
it('requires the independent rule version and opaque revision token for an existing binding', async () => {
  calls.getMask.mockResolvedValue({ id: row().id, revision: '12', ruleId: '72', ruleName: '规则', applicationScene: ['1'], maskType: '2', replaceRule: '1', replaceContent: '*', intervalList: [{ startNum: 1, endNum: 1 }], status: 1 });
  const state = mount<DialogState>(Dialog, { request: { mode: 'mask', id: row().id } });
  await flush(); await state.save(); expect(calls.saveMask).not.toHaveBeenCalled();
  state.mask.ruleVersionId = '9007199254740997'; state.mask.ruleRevisionToken = 'opaque:metadata-only-change';
  await state.save(); expect(calls.saveMask).toHaveBeenCalledWith(expect.objectContaining({ ruleVersionId: '9007199254740997', ruleRevisionToken: 'opaque:metadata-only-change' }));
});
it('does not write if options fail and keeps the form available for correction after rejection', async () => {
  calls.options.mockRejectedValueOnce(new Error('options'));
  const state = mount<DialogState>(Dialog, { request: { mode: 'create' } });
  await flush();
  await state.save();
  expect(calls.save).not.toHaveBeenCalled();
  expect(state.errorText).toBeTruthy();
});
it('does not recreate a level after creation succeeds but refreshing options fails', async () => {
  const state = mount<DialogState & {
    levelError: string;
    levelForm: Api.LevelSave;
    openLevel: () => void;
    saveLevel: () => Promise<void>;
  }>(Dialog, { request: { mode: 'batch', items: [{ id: '7', revision: '8' }] } });
  await flush(); state.openLevel(); Object.assign(state.levelForm, { safetyName: '新分级', safetyLevel: 'L5', sensitiveLevel: 88, status: 0 });
  calls.createLevel.mockResolvedValue('9007199254740997'); calls.options.mockRejectedValueOnce(new Error('refresh'));
  await state.saveLevel(); expect(state.levelError).toContain('已新增');
  await state.saveLevel(); expect(calls.createLevel).toHaveBeenCalledOnce(); expect(state.form.dataLevelId).toBe('9007199254740997');
});
it('submits an atomic batch and fresh directory revision without leaking source-only fields', async () => {
  const batch = mount<DialogState>(Dialog, { request: { mode: 'batch', items: [{ id: row().id, revision: row().revision }] } });
  await flush(); batch.form.dataLevelId = '3'; await batch.save(); expect(calls.batch).toHaveBeenCalledWith([{ id: row().id, revision: row().revision }], '3');
  const directory = mount<DialogState>(Dialog, { request: { mode: 'directory', id: '7' } });
  await flush(); await directory.save(); expect(calls.saveDirectory).toHaveBeenCalledWith(expect.objectContaining({ id: '7', revision: '8', remark: '说明' })); expect(calls.saveDirectory.mock.calls[0]![0]).not.toHaveProperty('description');
});
it('requires confirmation before moving a directory and discards old-context confirmation', async () => {
  const state = mount<DialogState & { directory: Partial<Api.Directory> }>(Dialog, { request: { mode: 'directory', id: '7' } });
  await flush(); state.directory.parentId = '9007199254740994'; await state.save();
  expect(calls.saveDirectory).not.toHaveBeenCalled(); expect(calls.confirm).toHaveBeenCalledOnce();
  identity.value = 'replacement'; await flush(); await calls.confirm.mock.calls[0]![0].onOk();
  expect(calls.saveDirectory).not.toHaveBeenCalled(); expect(calls.destroy).toHaveBeenCalled();
});
