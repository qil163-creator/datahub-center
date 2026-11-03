/* eslint-disable vue/one-component-per-file -- 最小控件只验证状态与请求，不重复 Ant 渲染。 */
import type { Component, ComponentPublicInstance } from 'vue';

import type { SafetyRuleWorkbenchApi as Api } from '#/api/governance/rule/workbench';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, expect, it, vi } from 'vitest';

import {
  newSafetyMaskFields,
  safetyMaskFieldsError,
} from '../common/mask-fields';
import List from './index.vue';
import Form from './modules/workbench-form.vue';
import { ruleCategoryTree, ruleDisplayId, ruleVersion } from './reference-data';

const calls = vi.hoisted(() => ({
  page: vi.fn(),
  options: vi.fn(),
  get: vi.fn(),
  save: vi.fn(),
  status: vi.fn(),
  remove: vi.fn(),
  removeList: vi.fn(),
  confirm: vi.fn(),
  destroy: vi.fn(),
  success: vi.fn(),
  push: vi.fn(),
  close: vi.fn(),
}));
let session: ReturnType<typeof reactive<{ value: string }>>;
let permissions: Set<string>;
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
vi.mock('vue-router', () => ({ useRouter: () => ({ push: calls.push }) }));
vi.mock('#/api/governance/rule/workbench', () => ({
  getSafetyRules: calls.page,
  getSafetyRuleOptions: calls.options,
  getSafetyRule: calls.get,
  saveSafetyRule: calls.save,
  changeSafetyRuleStatus: calls.status,
  removeSafetyRule: calls.remove,
  removeSafetyRules: calls.removeList,
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
    Switch: box,
    Table: box,
    Tag: box,
    TreeSelect: box,
    theme: { defaultAlgorithm: undefined },
    message: { success: calls.success },
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
const row = (id = '9007199254740993'): Api.Rule => ({
  ...newSafetyMaskFields(),
  id,
  sourceRecordId: '50',
  categoryId: '9007199254740994',
  categoryRevision: '9007199254740995',
  categoryName: '网络地址',
  ruleVersionId: '9007199254740997',
  ruleRevisionToken: 'opaque:metadata-version',
  ruleName: '地址规则',
  applicationScene: ['1'],
  replaceContent: '******',
  intervalList: [{ startNum: 1, endNum: 1 }],
  status: 1,
});
const context: Api.Options = {
  directories: [{ id: '9007199254740994', parentId: 0, categoryName: '目录' }],
  categories: [
    {
      id: '9007199254740994',
      categoryId: '9007199254740994',
      revision: '9007199254740995',
      name: '网络地址',
      status: 0,
      priority: 'HIGH',
      configured: true,
    },
    {
      id: '8',
      categoryId: '9007199254740994',
      revision: '9',
      name: '新分类',
      status: 0,
      priority: 'LOW',
      configured: false,
    },
  ],
};
beforeEach(() => {
  vi.resetAllMocks();
  session = reactive({ value: 'session' });
  permissions = new Set([
    'rule:create',
    'rule:delete',
    'rule:query',
    'rule:update',
  ]);
  calls.page.mockResolvedValue({ list: [], total: 0 });
  calls.options.mockResolvedValue(context);
  calls.get.mockImplementation(async (id) => row(id));
  calls.save.mockResolvedValue(true);
  calls.confirm.mockReturnValue({ destroy: calls.destroy });
});
afterEach(() => {
  for (const dispose of mounted.splice(0)) dispose();
});
interface ListState {
  rows: Api.Rule[];
  selected: string[];
  loadRows: () => Promise<void>;
  changeStatus: (row: Api.Rule, checked: boolean) => void;
  remove: (row?: Api.Rule) => void;
  detail: (row: Api.Rule) => void;
}
interface FormState {
  form: Api.Save;
  ready: boolean;
  error: string;
  save: () => Promise<void>;
}

it('keeps local action identity distinct from source display numbers and requires all three CAS fields', () => {
  expect(ruleDisplayId(row())).toBe('50');
  expect(ruleVersion(row())).toEqual({
    id: '9007199254740993',
    categoryRevision: '9007199254740995',
    ruleVersionId: '9007199254740997',
    ruleRevisionToken: 'opaque:metadata-version',
  });
  expect(ruleVersion({ ...row(), ruleRevisionToken: '' })).toBeUndefined();
  expect(
    ruleVersion({ ...row(), categoryRevision: 9_007_199_254_740_992 }),
  ).toBeUndefined();
});
it('separates equal directory/category IDs, disables directories and excludes configured leaves', () => {
  const tree = ruleCategoryTree(context);
  expect(tree[0]).toMatchObject({
    key: 'directory:9007199254740994',
    disabled: true,
  });
  expect(tree[0]?.children).toEqual([
    expect.objectContaining({ key: '8', disabled: false }),
  ]);
  expect(
    ruleCategoryTree(context, '9007199254740994')[0]?.children?.[0]?.disabled,
  ).toBe(false);
});
it('accepts inclusive one-character ranges and rejects incomplete ranges or unsupported scenes', () => {
  expect(safetyMaskFieldsError(row())).toBeUndefined();
  expect(
    safetyMaskFieldsError({
      ...row(),
      intervalList: [{ startNum: 2, endNum: 1 }],
    }),
  ).toBeTruthy();
  expect(
    safetyMaskFieldsError({ ...row(), applicationScene: ['3'] }),
  ).toBeTruthy();
});
it('ignores stale reads and clears old rows and owned confirmations after identity loss', async () => {
  const state = mount<ListState>(List);
  await flush();
  const old = deferred<{ list: Api.Rule[]; total: number }>();
  calls.page.mockReturnValueOnce(old.promise);
  const waiting = state.loadRows();
  calls.page.mockResolvedValueOnce({ list: [row('2')], total: 1 });
  await state.loadRows();
  old.resolve({ list: [row('3')], total: 1 });
  await waiting;
  expect(state.rows[0]?.id).toBe('2');
  state.remove(row());
  const confirm = calls.confirm.mock.calls[0]![0];
  permissions.clear();
  session.value = 'replacement';
  await flush();
  await confirm.onOk();
  expect(state.rows).toEqual([]);
  expect(calls.remove).not.toHaveBeenCalled();
  expect(calls.destroy).toHaveBeenCalled();
});
it('refreshes after each same-context concurrent status write with exact independent rule tokens', async () => {
  const state = mount<ListState>(List);
  await flush();
  const a = deferred<unknown>();
  const b = deferred<unknown>();
  calls.status.mockReturnValueOnce(a.promise).mockReturnValueOnce(b.promise);
  state.changeStatus(row('1'), true);
  state.changeStatus(row('2'), true);
  const first = calls.confirm.mock.calls[0]![0].onOk();
  const second = calls.confirm.mock.calls[1]![0].onOk();
  const count = calls.page.mock.calls.length;
  b.resolve(true);
  await second;
  a.resolve(true);
  await first;
  expect(calls.page).toHaveBeenCalledTimes(count + 2);
  expect(calls.status).toHaveBeenCalledWith({
    ...ruleVersion(row('1')),
    status: 0,
  });
});
it('submits one atomic delete and routes details by local identity rather than source number', async () => {
  const state = mount<ListState>(List);
  await flush();
  state.rows = [row('1'), row('2')];
  state.selected = ['1', '2'];
  state.remove();
  await calls.confirm.mock.calls[0]![0].onOk();
  expect(calls.removeList).toHaveBeenCalledWith([
    ruleVersion(row('1')),
    ruleVersion(row('2')),
  ]);
  expect(calls.remove).not.toHaveBeenCalled();
  state.detail(row());
  expect(calls.push).toHaveBeenCalledWith({
    name: 'GovernanceSafetyRuleDetail',
    params: { id: '9007199254740993' },
  });
});
it('reads fresh edit state and saves only the flat source fields plus all CAS values', async () => {
  const state = mount<FormState>(Form, { id: row().id });
  await flush();
  state.form.ruleName = ' 新名字 ';
  await state.save();
  expect(calls.save).toHaveBeenCalledWith(
    expect.objectContaining({
      id: row().id,
      categoryRevision: row().categoryRevision,
      ruleVersionId: row().ruleVersionId,
      ruleRevisionToken: row().ruleRevisionToken,
      ruleName: '新名字',
      intervalList: [{ startNum: 1, endNum: 1 }],
    }),
  );
  expect(calls.save.mock.calls[0]![0]).not.toHaveProperty('sourceRecordId');
  expect(calls.save.mock.calls[0]![0]).not.toHaveProperty('creatorName');
});
it('creates disabled with the actual selected classification revision', async () => {
  const state = mount<FormState>(Form);
  await flush();
  Object.assign(state.form, {
    categoryId: '8',
    ruleName: '新规则',
    applicationScene: ['2'],
    replaceContent: '*',
    intervalList: [{ startNum: 3, endNum: 3 }],
  });
  await state.save();
  expect(calls.save).toHaveBeenCalledWith(
    expect.objectContaining({
      id: undefined,
      categoryId: '8',
      categoryRevision: '9',
      status: 1,
    }),
  );
});
it('rejects mismatched fresh identities and missing independent rule tokens', async () => {
  calls.get.mockResolvedValueOnce(row('wrong'));
  const bad = mount<FormState>(Form, { id: row().id });
  await flush();
  await bad.save();
  expect(bad.ready).toBe(false);
  expect(calls.save).not.toHaveBeenCalled();
  calls.get.mockResolvedValueOnce({ ...row(), ruleRevisionToken: '' });
  const missing = mount<FormState>(Form, { id: row().id });
  await flush();
  await missing.save();
  expect(calls.save).not.toHaveBeenCalled();
});
it('blocks duplicate submissions and discards write success after a session change', async () => {
  const state = mount<FormState>(Form, { id: row().id, onClose: calls.close });
  await flush();
  const pending = deferred<unknown>();
  calls.save.mockReturnValueOnce(pending.promise);
  const saving = state.save();
  await state.save();
  expect(calls.save).toHaveBeenCalledOnce();
  session.value = 'replacement';
  await flush();
  pending.resolve(true);
  await saving;
  expect(calls.close).toHaveBeenCalledOnce();
  expect(calls.success).not.toHaveBeenCalled();
});
