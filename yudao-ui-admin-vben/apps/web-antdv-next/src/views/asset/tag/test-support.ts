/* eslint-disable vue/one-component-per-file -- 共用测试组件替身。 */
import type { App, Component, ComponentPublicInstance } from 'vue';

import {
  createApp,
  defineComponent,
  h,
  KeepAlive,
  nextTick,
  reactive,
  ref,
} from 'vue';

import { vi } from 'vitest';
export const mock = {
  access: {
    accessToken: 'session',
    refreshToken: 'refresh',
    loginExpired: false,
    accessCodes: [
      'asset:tag:query',
      'asset:tag:create',
      'asset:tag:update',
      'asset:tag:delete',
    ],
  },
  user: { userInfo: { userId: '1' } },
  route: {
    params: { id: '9007199254740993' },
    meta: { activePath: '/asset/tag' },
  },
  modalOptions: {} as { onOpenChange: (open: boolean) => Promise<void> },
  modalData: {} as Record<string, unknown>,
  modalClose: vi.fn(),
  modalSetData: vi.fn(),
  modalOpen: vi.fn(),
  page: vi.fn(),
  categories: vi.fn(),
  get: vi.fn(),
  create: vi.fn(),
  update: vi.fn(),
  status: vi.fn(),
  remove: vi.fn(),
  removeList: vi.fn(),
  linked: vi.fn(),
  unbind: vi.fn(),
  push: vi.fn(),
  closeTab: vi.fn(),
  success: vi.fn(),
  warning: vi.fn(),
  error: vi.fn(),
  confirm: vi.fn(),
  destroy: vi.fn(),
  emit: vi.fn(),
  gridEvents: {} as {
    cellDblclick: (event: { row: unknown }) => void;
    checkboxChange: (event: { records: unknown[] }) => void;
    sortChange: (event: { field: string; order: null | string }) => void;
  },
};

vi.mock('#/adapter/form', () => ({
  useVbenForm: () => [
    () => null,
    { reset: vi.fn(), validateAndSubmit: vi.fn() },
  ],
}));
vi.mock('#/adapter/business-vxe-table', () => ({
  ACTION_ICON: { ADD: 'add', DELETE: 'delete' },
  NativeVxeButton: defineComponent({ setup: () => () => null }),
  TableAction: defineComponent({ setup: () => () => null }),
  useVbenVxeGrid: (options: { gridEvents: typeof mock.gridEvents }) => {
    mock.gridEvents = options.gridEvents;
    return [
      defineComponent({ setup: () => () => null }),
      { grid: { clearCheckboxRow: vi.fn() }, setLoading: vi.fn() },
    ];
  },
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
vi.mock('@vben/hooks', () => ({
  useAntdDesignTokens: () => ({ tokens: { borderRadius: 8 } }),
  useTabs: () => ({ closeCurrentTab: mock.closeTab }),
}));
vi.mock('vue-router', () => ({
  useRoute: () => mock.route,
  useRouter: () => ({ push: mock.push }),
}));
vi.mock('@vben/utils', () => ({
  handleTree: (rows: unknown) => rows,
  formatDate: (value: unknown) => String(value ?? ''),
}));
vi.mock('#/api/asset/categorytag', () => ({
  getCategoryTagList: mock.categories,
}));
vi.mock('#/api/asset/tag', () => ({
  getAssetTagPage: mock.page,
  getAssetTag: mock.get,
  createAssetTag: mock.create,
  updateAssetTag: mock.update,
  updateAssetTagStatus: mock.status,
  deleteAssetTag: mock.remove,
  deleteAssetTagList: mock.removeList,
  getAssetTagLinkedAssets: mock.linked,
  unbindAssetTag: mock.unbind,
}));
vi.mock('#/api/asset/dataasset', () => ({
  ASSET_TYPE_OPTIONS: [
    { value: 1, label: '数据库表' },
    { value: 2, label: 'API接口' },
    { value: 3, label: '非结构化数据' },
  ],
}));
vi.mock('@vben/common-ui', () => {
  const box = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('section', slots.default?.()),
  });
  const api = {
    getData: () => mock.modalData,
    setData: (data: unknown) => {
      mock.modalSetData(data);
      return api;
    },
    setState: vi.fn(),
    open: mock.modalOpen,
    close: mock.modalClose,
    onCancel: mock.modalClose,
  };
  return {
    Page: box,
    useVbenModal: (options: typeof mock.modalOptions) => {
      mock.modalOptions = options;
      return [box, api];
    },
  };
});
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
    CheckboxGroup: box,
    ConfigProvider: box,
    Descriptions: box,
    DescriptionsItem: box,
    Dropdown: box,
    Form: box,
    FormItem: box,
    Input: Object.assign(box, { TextArea: box }),
    Pagination: box,
    RadioGroup: box,
    Select: box,
    Spin: box,
    Switch: box,
    Table: box,
    Tabs: box,
    Tag: box,
    Tooltip: box,
    Tree: box,
    TreeSelect: box,
    theme: { defaultAlgorithm: undefined },
    message: {
      success: mock.success,
      warning: mock.warning,
      error: mock.error,
    },
    Modal: Object.assign(box, { confirm: mock.confirm }),
  };
});
export function resetMocks() {
  vi.resetAllMocks();
  mock.access = reactive({
    accessToken: 'session',
    refreshToken: 'refresh',
    loginExpired: false,
    accessCodes: [
      'asset:tag:query',
      'asset:tag:create',
      'asset:tag:update',
      'asset:tag:delete',
    ],
  });
  mock.user = reactive({ userInfo: { userId: '1' } });
  mock.route = reactive({
    params: { id: '9007199254740993' },
    meta: { activePath: '/asset/tag' },
  });
  mock.modalData = {};
  mock.page.mockResolvedValue({ list: [], total: 0 });
  mock.categories.mockResolvedValue([
    { id: '9007199254740994', categoryName: '真实类目', parentId: 0 },
  ]);
  mock.create.mockResolvedValue('9007199254740995');
  mock.update.mockResolvedValue(true);
  mock.get.mockResolvedValue({
    id: '9007199254740993',
    name: '真实标签',
    categoryId: '9007199254740994',
    status: 1,
    revision: 7,
  });
  mock.linked.mockResolvedValue({ list: [], total: 0 });
  mock.confirm.mockReturnValue({ destroy: mock.destroy });
}
export function mountState<T>(
  component: Component,
  props: Record<string, unknown> = {},
) {
  const host = document.createElement('div');
  document.body.append(host);
  const app: App = createApp(component, props);
  const vm = app.mount(host) as ComponentPublicInstance;
  return {
    state: Reflect.get(vm.$, 'setupState') as T,
    dispose: () => {
      app.unmount();
      host.remove();
    },
  };
}
export async function flush() {
  for (let i = 0; i < 12; i++) await Promise.resolve();
  await nextTick();
}
export function mountKeptState<T>(component: Component) {
  const host = document.createElement('div');
  document.body.append(host);
  const visible = ref(true);
  let state!: T;
  const app = createApp(
    defineComponent({
      setup: () => () =>
        h(KeepAlive, null, {
          default: () =>
            visible.value
              ? h(component, {
                  ref: (value: unknown) => {
                    if (value)
                      state = Reflect.get(
                        (value as ComponentPublicInstance).$,
                        'setupState',
                      ) as T;
                  },
                })
              : undefined,
        }),
    }),
  );
  app.mount(host);
  return {
    get state() {
      return state;
    },
    setVisible: (value: boolean) => {
      visible.value = value;
    },
    dispose: () => {
      app.unmount();
      host.remove();
    },
  };
}
export function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: Error) => void;
  const promise = new Promise<T>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}
