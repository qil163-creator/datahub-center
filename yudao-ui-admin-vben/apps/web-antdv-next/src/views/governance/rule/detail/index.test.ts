/* eslint-disable vue/one-component-per-file -- 最小控件只验证详情身份、读取和操作边界。 */
import type { ComponentPublicInstance } from 'vue';

import type { SafetyRuleWorkbenchApi as Api } from '#/api/governance/rule/workbench';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, expect, it, vi } from 'vitest';

import Detail from './index.vue';

const calls = vi.hoisted(() => ({
  get: vi.fn(),
  page: vi.fn(),
  remove: vi.fn(),
  confirm: vi.fn(),
  destroy: vi.fn(),
  success: vi.fn(),
  close: vi.fn(),
  setData: vi.fn(),
  open: vi.fn(),
  push: vi.fn(),
}));
let route: ReturnType<
  typeof reactive<{ meta: { activePath: string }; params: { id: string }; }>
>;
let session: ReturnType<typeof reactive<{ value: string }>>;
let permissions: Set<string>;
vi.mock('#/adapter/business-vxe-table', async () => import('#/views/governance/common/vxe-grid.test-support'));
vi.mock('vue-router', () => ({
  useRoute: () => route,
  useRouter: () => ({ push: calls.push }),
}));
vi.mock('@vben/hooks', () => ({
  useAntdDesignTokens: () => ({ tokens: { borderRadius: 6 } }),
  useTabs: () => ({ closeCurrentTab: vi.fn() }),
}));
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
vi.mock('#/views/asset/sensitive/use-sensitive-access', () => ({
  useSensitiveAccess: () => ({
    can: (action: string) => permissions.has(`sensitive:${action}`),
  }),
}));
vi.mock('#/views/asset/sensitive/modules/detail.vue', () => ({
  default: defineComponent({ setup: () => () => null }),
}));
vi.mock('#/api/governance/rule/workbench', () => ({
  getSafetyRule: calls.get,
  getSafetyRuleSensitiveRows: calls.page,
}));
vi.mock('#/api/asset/sensitive', () => ({ deleteSensitiveList: calls.remove }));
vi.mock('@vben/common-ui', () => {
  const box = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.()),
  });
  const modal = {
    close: calls.close,
    setData: calls.setData,
    open: calls.open,
  };
  calls.setData.mockReturnValue(modal);
  return { Page: box, useVbenModal: () => [box, modal] };
});
vi.mock('antdv-next', () => {
  const box = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.()),
  });
  return {
    Alert: box,
    Input: box,
    Select: box,
    Button: box,
    CheckboxGroup: box,
    ConfigProvider: box,
    Dropdown: box,
    Modal: Object.assign(box, { confirm: calls.confirm }),
    Pagination: box,
    Spin: box,
    Table: box,
    Tabs: box,
    Tag: box,
    theme: { defaultAlgorithm: undefined },
    message: { success: calls.success },
  };
});
const mounted: (() => void)[] = [];
function mount() {
  const host = document.createElement('div');
  document.body.append(host);
  const app = createApp(Detail);
  const vm = app.mount(host) as ComponentPublicInstance;
  mounted.push(() => {
    app.unmount();
    host.remove();
  });
  return Reflect.get(vm.$, 'setupState') as {
    loadSensitive: () => Promise<void>;
    removeSensitive: (row: Api.SensitiveRow) => void;
    row?: Api.Rule;
    rows: Api.SensitiveRow[];
    showSensitive: (row: Api.SensitiveRow) => void;
  };
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
const rule = (id: string) => ({
  id,
  sourceRecordId: '20',
  ruleName: '测站编码',
  categoryId: '7',
  categoryRevision: '8',
  ruleVersionId: '9',
  ruleRevisionToken: 'opaque',
  applicationScene: ['1'],
  maskType: '2',
  replaceRule: '1',
  replaceContent: '*',
  intervalList: [{ startNum: 1, endNum: 1 }],
  status: 1,
});
const item: Api.SensitiveRow = {
  id: '9007199254740995',
  revision: '9007199254740997',
  sourceRecordId: '17',
  assetName: '测站',
  columnName: 'STCD',
  status: 1,
};
beforeEach(() => {
  vi.clearAllMocks();
  session = reactive({ value: 'session' });
  permissions = new Set(['rule:query', 'sensitive:delete', 'sensitive:query']);
  route = reactive({
    params: { id: '9007199254740993' },
    meta: { activePath: '/govern/safety/rule' },
  });
  calls.get.mockImplementation(async (id) => rule(id));
  calls.page.mockResolvedValue({ list: [item], total: 1 });
  calls.confirm.mockReturnValue({ destroy: calls.destroy });
});
afterEach(() => {
  for (const dispose of mounted.splice(0)) dispose();
});
it('queries exact local rule binding and displays nonempty rows without classification-based guessing', async () => {
  const state = mount();
  await flush();
  expect(calls.page).toHaveBeenCalledWith({
    ruleId: '9007199254740993',
    pageNo: 1,
    pageSize: 10,
    sortField: 'createTime',
    sortOrder: 'desc',
  });
  expect(state.rows[0]?.sourceRecordId).toBe('17');
});
it('does not query sensitive rows after a mismatched detail response', async () => {
  calls.get.mockResolvedValueOnce(rule('other'));
  const state = mount();
  await flush();
  expect(state.row).toBeUndefined();
  expect(calls.page).not.toHaveBeenCalled();
});
it('ignores pending rows when a route switches to another local rule', async () => {
  const old = deferred<{ list: Api.SensitiveRow[]; total: number }>();
  calls.page.mockReturnValueOnce(old.promise);
  const state = mount();
  await flush();
  route.params.id = '9007199254740999';
  calls.page.mockResolvedValueOnce({ list: [{ ...item, id: '2' }], total: 1 });
  await flush();
  old.resolve({ list: [item], total: 1 });
  await flush();
  expect(state.row?.id).toBe('9007199254740999');
  expect(state.rows[0]?.id).toBe('2');
});
it('uses original sensitive detail permission and the native sensitive ID', async () => {
  const state = mount();
  await flush();
  state.showSensitive(item);
  expect(calls.setData).toHaveBeenCalledWith({ id: '9007199254740995' });
  expect(calls.open).toHaveBeenCalledOnce();
  permissions.delete('sensitive:query');
  state.showSensitive(item);
  expect(calls.open).toHaveBeenCalledOnce();
});
it('deletes by native sensitive ID and exact revision, then reloads the chosen rule', async () => {
  const state = mount();
  await flush();
  state.removeSensitive(item);
  await calls.confirm.mock.calls[0]![0].onOk();
  expect(calls.remove).toHaveBeenCalledWith(
    '9007199254740995',
    '9007199254740997',
  );
  expect(calls.page).toHaveBeenCalledTimes(2);
});
it('retains the native enabled-delete restriction and checks sensitive delete permission', async () => {
  const state = mount();
  await flush();
  state.removeSensitive({ ...item, status: 0 });
  expect(calls.confirm).not.toHaveBeenCalled();
  permissions.delete('sensitive:delete');
  state.removeSensitive(item);
  expect(calls.confirm).not.toHaveBeenCalled();
});
it('destroys owned dialogs and blocks old deletion after session changes', async () => {
  const state = mount();
  await flush();
  state.removeSensitive(item);
  const confirm = calls.confirm.mock.calls[0]![0];
  session.value = 'replacement';
  await flush();
  await confirm.onOk();
  expect(calls.remove).not.toHaveBeenCalled();
  expect(calls.destroy).toHaveBeenCalled();
});
