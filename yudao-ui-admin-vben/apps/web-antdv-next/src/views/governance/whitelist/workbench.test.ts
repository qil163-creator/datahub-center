/* eslint-disable vue/one-component-per-file -- 最小控件只验证异步状态和授权数据，不重复 Ant 渲染。 */
import type { Component, ComponentPublicInstance } from 'vue';

import type { SafetyWhitelistWorkbenchApi as Api } from '#/api/governance/whitelist/workbench';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, expect, it, vi } from 'vitest';

import List from './index.vue';
import Form from './modules/workbench-form.vue';
import Picker from './modules/workbench-users.vue';
import {
  newWhitelist,
  whitelistCategoryTree,
  whitelistDisplayId,
  whitelistError,
  whitelistUserNames,
} from './reference-data';

const calls = vi.hoisted(() => ({
  page: vi.fn(),
  options: vi.fn(),
  get: vi.fn(),
  users: vi.fn(),
  save: vi.fn(),
  status: vi.fn(),
  remove: vi.fn(),
  removeList: vi.fn(),
  confirm: vi.fn(),
  destroy: vi.fn(),
  success: vi.fn(),
  warning: vi.fn(),
  close: vi.fn(),
  select: vi.fn(),
}));
let session: ReturnType<typeof reactive<{ value: string }>>;
let permissions: Set<string>;
vi.mock('#/adapter/business-vxe-table', async () => import('#/views/governance/common/vxe-grid.test-support'));
vi.mock('#/views/governance/category/use-session', () => ({
  useCategorySession: () => ({
    identity: {
      get value() {
        return session.value;
      },
      __v_isRef: true,
    },
    can: (action: string, domain: string) =>
      permissions.has(`${domain}:${action}`),
  }),
}));
vi.mock('#/api/governance/whitelist/workbench', () => ({
  getWhitelistWorkbenchPage: calls.page,
  getWhitelistWorkbenchOptions: calls.options,
  getWhitelistWorkbench: calls.get,
  getWhitelistWorkbenchUsers: calls.users,
  saveWhitelistWorkbench: calls.save,
  changeWhitelistWorkbenchStatus: calls.status,
  deleteWhitelistWorkbench: calls.remove,
  deleteWhitelistWorkbenchList: calls.removeList,
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
vi.mock('#/adapter/component/date-picker', () => ({
  RangePicker: defineComponent({ setup: () => () => null }),
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
    Modal: Object.assign(box, { confirm: calls.confirm }),
    Pagination: box,
    RadioGroup: box,
    Select: box,
    Spin: box,
    Switch: box,
    Table: box,
    Tooltip: box,
    Tag: box,
    TreeSelect: box,
    theme: { defaultAlgorithm: undefined },
    message: { success: calls.success, warning: calls.warning },
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
const user: Api.User = {
  id: '9007199254740993',
  nickname: '本地真实昵称',
  deptName: '市级',
};
const row = (id = '9007199254740994'): Api.Row => ({
  ...newWhitelist(),
  id,
  sourceRecordId: '3',
  revision: '9007199254740995',
  name: '空间白名单',
  categoryId: '7',
  categoryName: '空间坐标',
  users: [user],
  userIds: [user.id],
  startTime: '2026-08-04',
  endTime: '2099-12-31',
  status: 0,
  creatorName: '来源创建人',
});
const context: Api.Options = {
  directories: [{ id: '6', parentId: 0, categoryName: '地理数据' }],
  categories: [
    {
      id: '7',
      categoryId: '6',
      name: '空间坐标',
      priority: 'HIGH',
      status: 0,
      whitelistConfigured: true,
    },
    {
      id: '8',
      categoryId: '6',
      name: '其他分类',
      priority: 'LOW',
      status: 0,
      whitelistConfigured: false,
    },
  ],
};
beforeEach(() => {
  vi.resetAllMocks();
  session = reactive({ value: 'session' });
  permissions = new Set([
    'whitelist:create',
    'whitelist:delete',
    'whitelist:query',
    'whitelist:update',
  ]);
  calls.page.mockResolvedValue({ list: [], total: 0 });
  calls.options.mockResolvedValue(context);
  calls.get.mockImplementation(async (id) => row(id));
  calls.users.mockResolvedValue({ list: [user], total: 1 });
  calls.save.mockResolvedValue(true);
  calls.confirm.mockReturnValue({ destroy: calls.destroy });
});
afterEach(() => {
  for (const dispose of mounted.splice(0)) dispose();
});
interface ListState {
  rows: Api.Row[];
  selected: string[];
  loadRows: () => Promise<void>;
  remove: (row?: Api.Row) => void;
  changeStatus: (row: Api.Row, checked: boolean) => void;
}
interface FormState {
  form: Api.Save;
  dates?: [string, string];
  ready: boolean;
  save: () => Promise<void>;
  selectUsers: (users: Api.User[]) => void;
}
interface PickerState {
  rows: Api.User[];
  chosen: Map<string, Api.User>;
  query: Api.UserPageReq;
  filter: { mobile?: string; username?: string };
  selection: { onSelect: (row: Api.User, selected: boolean) => void };
  load: () => Promise<void>;
  search: () => void;
  confirm: () => void;
}

it('shows source display identity separately and uses actual bound local nicknames', () => {
  expect(whitelistDisplayId(row())).toBe('3');
  expect(whitelistUserNames(row().users)).toBe('本地真实昵称');
  expect(whitelistError(row())).toBeUndefined();
});
it('hides already-configured classifications only for creation, preserving query options', () => {
  expect(
    whitelistCategoryTree(context)[0]?.children?.map((item) => item.key),
  ).toEqual(['8']);
  expect(
    whitelistCategoryTree(context, false)[0]?.children?.map((item) => item.key),
  ).toEqual(['7', '8']);
});
it('validates source name rules, real IDs and inclusive date-only periods', () => {
  expect(
    whitelistError({
      ...row(),
      startTime: '2026-08-04',
      endTime: '2026-08-04',
    }),
  ).toBeUndefined();
  expect(whitelistError({ ...row(), name: '不允许 空格' })).toBeTruthy();
  expect(
    whitelistError({ ...row(), userIds: [9_007_199_254_740_992] }),
  ).toBeTruthy();
  expect(whitelistError({ ...row(), startTime: '2026-02-30' })).toBeTruthy();
  expect(whitelistError({ ...row(), endTime: '2026-08-03' })).toBeTruthy();
});
it('discards old list responses and invalidates old confirmations on session loss', async () => {
  const state = mount<ListState>(List);
  await flush();
  const old = deferred<{ list: Api.Row[]; total: number }>();
  calls.page.mockReturnValueOnce(old.promise);
  const waiting = state.loadRows();
  calls.page.mockResolvedValueOnce({ list: [row('2')], total: 1 });
  await state.loadRows();
  old.resolve({ list: [row('1')], total: 1 });
  await waiting;
  expect(state.rows[0]?.id).toBe('2');
  state.remove({ ...row(), status: 1 });
  const confirm = calls.confirm.mock.calls[0]![0];
  permissions.clear();
  session.value = 'replacement';
  await flush();
  await confirm.onOk();
  expect(state.rows).toEqual([]);
  expect(calls.remove).not.toHaveBeenCalled();
  expect(calls.destroy).toHaveBeenCalled();
});
it('does not delete enabled rows and submits only selected disabled rows as one atomic set', async () => {
  const state = mount<ListState>(List);
  await flush();
  state.remove(row());
  expect(calls.confirm).not.toHaveBeenCalled();
  state.rows = [row('1'), { ...row('2'), status: 1 }];
  state.selected = ['1', '2'];
  state.remove();
  expect(calls.confirm.mock.calls[0]![0].content).toContain(
    '可删除1个，不可删除1个',
  );
  await calls.confirm.mock.calls[0]![0].onOk();
  expect(calls.removeList).toHaveBeenCalledWith([
    { id: '2', revision: '9007199254740995' },
  ]);
  expect(calls.remove).not.toHaveBeenCalled();
});
it('refreshes after each concurrent status completion without losing another row write', async () => {
  const state = mount<ListState>(List);
  await flush();
  const a = deferred<unknown>();
  const b = deferred<unknown>();
  calls.status.mockReturnValueOnce(a.promise).mockReturnValueOnce(b.promise);
  state.changeStatus(row('1'), false);
  state.changeStatus(row('2'), false);
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
it('loads fresh detail and submits actual user IDs without source identities or editable category movement', async () => {
  const state = mount<FormState>(Form, { id: row().id });
  await flush();
  state.form.categoryId = '8';
  state.selectUsers([{ ...user, id: '9007199254740999' }]);
  await state.save();
  expect(calls.save).toHaveBeenCalledWith(
    expect.objectContaining({
      id: row().id,
      revision: row().revision,
      categoryId: '7',
      userIds: ['9007199254740999'],
      startTime: '2026-08-04',
      endTime: '2099-12-31',
    }),
  );
  expect(calls.save.mock.calls[0]![0]).not.toHaveProperty('users');
  expect(calls.save.mock.calls[0]![0]).not.toHaveProperty('sourceRecordId');
});
it('creates disabled with date-only values and rejects configured categories', async () => {
  const state = mount<FormState>(Form);
  await flush();
  Object.assign(state.form, {
    name: '新白名单',
    categoryId: '7',
    userIds: [user.id],
  });
  state.dates = ['2026-08-04', '2099-12-31'];
  await state.save();
  expect(calls.save).not.toHaveBeenCalled();
  state.form.categoryId = '8';
  await state.save();
  expect(calls.save).toHaveBeenCalledWith(
    expect.objectContaining({
      status: 1,
      categoryId: '8',
      userIds: ['9007199254740993'],
      startTime: '2026-08-04',
    }),
  );
});
it('prevents read-only writes, mismatched fresh identities and stale success after session replacement', async () => {
  const readonly = mount<FormState>(Form, { id: row().id, readonly: true });
  await flush();
  await readonly.save();
  expect(calls.save).not.toHaveBeenCalled();
  calls.get.mockResolvedValueOnce(row('wrong'));
  const bad = mount<FormState>(Form, { id: row().id });
  await flush();
  await bad.save();
  expect(bad.ready).toBe(false);
  const state = mount<FormState>(Form, { id: row().id, onClose: calls.close });
  await flush();
  const pending = deferred<unknown>();
  calls.save.mockReturnValueOnce(pending.promise);
  const writing = state.save();
  await state.save();
  expect(calls.save).toHaveBeenCalledOnce();
  session.value = 'replacement';
  await flush();
  pending.resolve(true);
  await writing;
  expect(calls.success).not.toHaveBeenCalled();
});
it('keeps cross-page selections by lossless local IDs and ignores stale candidate responses', async () => {
  const state = mount<PickerState>(Picker, {
    selected: [user],
    onSelect: calls.select,
  });
  await flush();
  const another = { ...user, id: '9007199254740999', nickname: '另一用户' };
  state.selection.onSelect(another, true);
  const old = deferred<{ list: Api.User[]; total: number }>();
  calls.users.mockReturnValueOnce(old.promise);
  const waiting = state.load();
  calls.users.mockResolvedValueOnce({ list: [another], total: 1 });
  await state.load();
  old.resolve({ list: [user], total: 1 });
  await waiting;
  expect(state.rows[0]?.id).toBe(another.id);
  state.confirm();
  expect(calls.select).toHaveBeenCalledWith([user, another]);
});
it('queries real candidate fields and clears selected identities when authorization changes', async () => {
  const state = mount<PickerState>(Picker, {
    selected: [user],
    onSelect: calls.select,
  });
  await flush();
  state.filter.username = ' admin ';
  state.filter.mobile = ' 138 ';
  state.search();
  await flush();
  expect(calls.users).toHaveBeenLastCalledWith(
    expect.objectContaining({ username: 'admin', mobile: '138', pageNo: 1 }),
  );
  permissions.clear();
  session.value = 'replacement';
  await flush();
  state.confirm();
  expect(calls.select).not.toHaveBeenCalled();
  expect(state.chosen.size).toBe(0);
});
