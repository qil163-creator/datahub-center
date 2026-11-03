/* eslint-disable vue/one-component-per-file -- 共用真实页面的 API 与展示组件替身。 */
import type { Component, ComponentPublicInstance } from 'vue';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { vi } from 'vitest';

export const mock = {
  access: {
    accessToken: 'session',
    refreshToken: 'refresh',
    loginExpired: false,
    accessCodes: ['asset:market:query'],
  },
  user: { userInfo: { userId: '9' }, userRoles: ['user'] },
  route: { params: { id: '9007199254740993' }, meta: { activePath: '' } },
  page: vi.fn(),
  categories: vi.fn(),
  get: vi.fn(),
  preview: vi.fn(),
  document: vi.fn(),
  push: vi.fn(),
  closeTab: vi.fn(),
  warning: vi.fn(),
  destroyMessage: vi.fn(),
};

vi.mock('#/adapter/form', () => ({
  useVbenForm: () => [
    () => null,
    { reset: vi.fn(), validateAndSubmit: vi.fn() },
  ],
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => mock.access,
  useUserStore: () => mock.user,
}));
vi.mock('@vben/hooks', () => ({
  useAntdDesignTokens: () => ({ tokens: { borderRadius: 8 } }),
  useTabs: () => ({ closeCurrentTab: mock.closeTab }),
}));
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: mock.push }),
  useRoute: () => mock.route,
}));
vi.mock('#/api/asset/market/department-assets', () => ({
  getDepartmentAssetsPage: mock.page,
  getDepartmentAssetCategories: mock.categories,
  getDepartmentAsset: mock.get,
  getDepartmentAssetPreview: mock.preview,
  getDepartmentAssetApiDocument: mock.document,
}));
vi.mock('#/views/market/check/detail/modules/resource-summary.vue', () => ({
  default: {},
}));
vi.mock('#/views/market/check/detail/modules/fields-tab.vue', () => ({
  default: {},
}));
vi.mock('#/views/market/check/detail/modules/preview-tab.vue', () => ({
  default: {},
}));
vi.mock('#/views/market/check/detail/modules/api-document.vue', () => ({
  default: {},
}));
vi.mock('@vben/common-ui', () => ({
  Page: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('main', slots.default?.()),
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
    ...Object.fromEntries(
      [
        'Alert',
        'Button',
        'CheckboxGroup',
        'ConfigProvider',
        'Dropdown',
        'Input',
        'Modal',
        'Pagination',
        'Select',
        'Spin',
        'Table',
        'Tag',
      ].map((key) => [key, box]),
    ),
    theme: { defaultAlgorithm: {} },
    message: { warning: mock.warning, destroy: mock.destroyMessage },
  };
});
export function resetMocks() {
  vi.resetAllMocks();
  mock.access = reactive({
    accessToken: 'session',
    refreshToken: 'refresh',
    loginExpired: false,
    accessCodes: ['asset:market:query'],
  });
  mock.user = reactive({ userInfo: { userId: '9' }, userRoles: ['user'] });
  mock.route = reactive({
    params: { id: '9007199254740993' },
    meta: { activePath: '' },
  });
  mock.page.mockResolvedValue({ list: [], total: 0 });
  mock.categories.mockResolvedValue([]);
  mock.get.mockResolvedValue({
    asset: { id: '9007199254740993', displayId: '21', canView: true },
    columns: [],
    canInvoke: false,
  });
  mock.preview.mockResolvedValue({ columns: [], rows: [], total: 0 });
  mock.document.mockResolvedValue({ name: '本地接口', responseFields: [] });
}
export function mountState<T>(component: Component) {
  const host = document.createElement('div');
  document.body.append(host);
  const app = createApp(component);
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
export function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: Error) => void;
  const promise = new Promise<T>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}
