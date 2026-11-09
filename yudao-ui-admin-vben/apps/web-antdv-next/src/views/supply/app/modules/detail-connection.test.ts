import type { App, PropType } from 'vue';
import type { Router } from 'vue-router';

import type { ExtendedDrawerApi } from '../../../../../../../packages/@core/ui-kit/popup-ui/src/drawer/drawer';
import type { ExtendedModalApi } from '../../../../../../../packages/@core/ui-kit/popup-ui/src/modal/modal';

import type { SupplyAppApi } from '#/api/supply/app';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';
import { createMemoryHistory, createRouter, RouterView } from 'vue-router';

import { useAccessStore } from '@vben/stores';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { getAppApiList, getSupplyApp } from '#/api/supply/app';
import { getDataApiSimpleList } from '#/api/supply/dataapi';

import DetailPage from '../detail.vue';
import AppList from '../index.vue';

const harness = vi.hoisted(() => ({
  closeCurrentTab: vi.fn(),
  row: { id: '9007199254740993', name: '测试应用' },
  state: {
    accessCodes: ['supply:app:query', 'supply:app:grant'],
    accessToken: '1',
    refreshToken: '',
    userInfo: { userId: '9' },
  },
}));

vi.mock('@vben-core/preferences', () => ({
  usePreferences: () => ({ globalEscapeShortcutKey: { value: true } }),
}));
vi.mock('@vben/hooks', () => ({
  useTabs: () => ({ closeCurrentTab: harness.closeCurrentTab }),
}));
vi.mock(
  '../../../../../../../packages/@core/ui-kit/popup-ui/src/modal/modal.vue',
  () => ({
    default: defineComponent({
      name: 'ConnectedModalView',
      props: {
        modalApi: {
          type: Object as PropType<ExtendedModalApi>,
          required: true,
        },
        title: { type: String, default: undefined },
      },
      setup(props, { slots }) {
        const state = props.modalApi.useStore();
        return () =>
          state.value.isOpen
            ? h('section', { 'data-popup': 'modal' }, [
                h('h2', props.title ?? state.value.title),
                slots.default?.(),
                h(
                  'button',
                  { onClick: () => props.modalApi.close() },
                  '关闭弹窗',
                ),
              ])
            : null;
      },
    }),
  }),
);
vi.mock(
  '../../../../../../../packages/@core/ui-kit/popup-ui/src/drawer/drawer.vue',
  () => ({
    default: defineComponent({
      name: 'ConnectedDrawerView',
      props: {
        drawerApi: {
          type: Object as PropType<ExtendedDrawerApi>,
          required: true,
        },
        title: { type: String, default: undefined },
      },
      setup(props, { slots }) {
        const state = props.drawerApi.useStore();
        return () =>
          state.value.isOpen
            ? h(
                'aside',
                {
                  'data-popup': 'drawer',
                  'data-placement': state.value.placement,
                },
                [
                  h('h2', props.title ?? state.value.title),
                  slots.default?.(),
                  h(
                    'button',
                    { onClick: () => props.drawerApi.close() },
                    '关闭抽屉',
                  ),
                ],
              )
            : null;
      },
    }),
  }),
);
vi.mock('@vben/common-ui', async () => {
  const { useVbenModal } =
    await import('../../../../../../../packages/@core/ui-kit/popup-ui/src/modal/use-modal');
  const { useVbenDrawer } =
    await import('../../../../../../../packages/@core/ui-kit/popup-ui/src/drawer/use-drawer');
  return {
    useVbenModal,
    useVbenDrawer,
    confirm: vi.fn(),
    Page: defineComponent({
      setup:
        (_, { slots }) =>
        () =>
          h('main', slots.default?.()),
    }),
  };
});
vi.mock('@vben/stores', () => {
  const state = reactive(harness.state);
  return { useAccessStore: () => state, useUserStore: () => state };
});
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      codes.some((code) => harness.state.accessCodes.includes(code)),
  }),
}));
vi.mock('@vben/icons', () => ({ IconifyIcon: () => null }));
vi.mock('#/components/list-pagination/index.vue', () => ({
  default: defineComponent({ name: 'ListPaginationStub', render: () => null }),
}));
vi.mock('antdv-next', () => {
  const Container = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.()),
  });
  return {
    Alert: defineComponent({
      props: { message: { type: String, default: '' } },
      setup:
        (props, { slots }) =>
        () =>
          h('div', { role: 'alert' }, [props.message, slots.action?.()]),
    }),
    Button: defineComponent({
      props: { disabled: Boolean },
      setup:
        (props, { slots }) =>
        () =>
          h('button', { disabled: props.disabled }, slots.default?.()),
    }),
    Switch: defineComponent({
      props: { checked: Boolean, disabled: Boolean, loading: Boolean },
      emits: ['change'],
      setup:
        (props, { attrs, emit }) =>
        () =>
          h(
            'button',
            {
              role: 'switch',
              disabled: props.disabled,
              'aria-checked': String(props.checked),
              'data-loading': String(props.loading),
              onClick: () => emit('change', !props.checked),
            },
            String(attrs['aria-label'] ?? ''),
          ),
    }),
    Table: defineComponent({
      props: {
        dataSource: {
          type: Array as PropType<SupplyAppApi.AppApi[]>,
          default: () => [],
        },
      },
      setup:
        (props, { slots }) =>
        () =>
          h(
            'div',
            props.dataSource.map((record) =>
              slots.bodyCell?.({ column: { key: 'actions' }, record }),
            ),
          ),
    }),
    Tabs: Container,
    TabPane: Container,
    Tag: Container,
    Image: Container,
    Tooltip: Container,
    DatePicker: { RangePicker: Container },
    Descriptions: Container,
    DescriptionsItem: defineComponent({
      props: { label: { type: String, default: '' } },
      setup:
        (props, { slots }) =>
        () =>
          h('div', [props.label, '：', slots.default?.()]),
    }),
    RadioGroup: Container,
    Select: Container,
    TextArea: Container,
    message: { success: vi.fn(), warning: vi.fn() },
  };
});
vi.mock('#/adapter/vxe-table', () => ({
  ACTION_ICON: {},
  TableAction: defineComponent({
    props: {
      actions: {
        type: Array as PropType<{ label: string; onClick: () => void }[]>,
        default: () => [],
      },
    },
    setup: (props) => () =>
      h(
        'div',
        props.actions.map((action) =>
          h('button', { onClick: action.onClick }, action.label),
        ),
      ),
  }),
  useVbenVxeGrid: () => [
    defineComponent({
      setup:
        (_, { slots }) =>
        () =>
          h('div', slots.actions?.({ row: harness.row })),
    }),
    { query: vi.fn() },
  ],
}));
vi.mock('#/api/supply/app', () => ({
  getSupplyApp: vi.fn(),
  getAppApiList: vi.fn(),
  getAppApi: vi.fn(),
  getSupplyAppPage: vi.fn(),
  deleteSupplyApp: vi.fn(),
  resetSupplyAppSecret: vi.fn(),
  createAppApi: vi.fn(),
  revokeAdminGrant: vi.fn(),
  setGrantControl: vi.fn(),
  updateAppApi: vi.fn(),
}));
vi.mock('#/api/supply/dataapi', () => ({ getDataApiSimpleList: vi.fn() }));
vi.mock('#/utils', () => ({ getRangePickerDefaultProps: () => ({}) }));
vi.mock('#/locales', () => ({ $t: (key: string) => key }));
vi.mock('../data', () => ({
  useGridColumns: () => [],
  useGridFormSchema: () => [],
  getUserNickname: (id: string) => id,
}));
vi.mock('./form.vue', () => ({ default: () => null }));
vi.mock('#/views/governance/common/text-preview.vue', () => ({
  default: () => null,
}));
vi.mock('#/views/modeling/modelcommon/detail-field-form.vue', () => ({
  default: defineComponent({
    props: {
      fields: {
        type: Array as PropType<{ label: string; value: unknown }[]>,
        default: () => [],
      },
    },
    setup: (props) => () =>
      h(
        'dl',
        props.fields.map((field) =>
          h('div', `${field.label}：${field.value ?? ''}`),
        ),
      ),
  }),
}));

let app: App | undefined;
let host: HTMLDivElement;
let router: Router;
async function flush() {
  for (let i = 0; i < 10; i++) await Promise.resolve();
  await nextTick();
}
async function click(label: string) {
  const button = [...host.querySelectorAll('button')].find(
    (item) => item.textContent?.trim() === label,
  );
  expect(button, `缺少操作：${label}`).toBeDefined();
  button!.click();
  await flush();
}

beforeEach(async () => {
  vi.clearAllMocks();
  Object.assign(useAccessStore(), {
    accessCodes: ['supply:app:query', 'supply:app:grant'],
    accessToken: '1',
    refreshToken: '',
    userInfo: { userId: '9' },
  });
  vi.mocked(getSupplyApp).mockImplementation(async (id) => ({
    id,
    name: '测试应用',
    appKey: 'app-key',
    description: '应用描述',
  }));
  vi.mocked(getAppApiList).mockResolvedValue([
    { id: '11', appId: harness.row.id, apiId: '21', apiName: '测试 API' },
  ]);
  vi.mocked(getDataApiSimpleList).mockResolvedValue([
    { id: '21', name: '测试 API' },
  ]);
  host = document.createElement('div');
  document.body.append(host);
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/supply/app', name: 'SupplyApp', component: AppList },
      {
        path: '/supply/app/detail/:id',
        name: 'SupplyAppDetailPage',
        component: DetailPage,
      },
    ],
  });
  await router.push('/supply/app');
  app = createApp(RouterView);
  app.use(router);
  app.mount(host);
  await flush();
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
});

describe('应用详情父子连接', () => {
  it('列表通过无损路由打开独立详情，新增授权弹窗关闭后可返回列表再打开', async () => {
    await click('详情');
    await expect
      .poll(() => router.currentRoute.value.name)
      .toBe('SupplyAppDetailPage');
    await flush();
    expect(router.currentRoute.value.params.id).toBe(harness.row.id);
    expect(getSupplyApp).toHaveBeenCalledExactlyOnceWith(harness.row.id);
    expect(host.querySelector('[data-popup="drawer"]')).toBeNull();
    expect(host.textContent).toContain('测试应用');
    expect(host.textContent).toContain('AppKey：app-key');
    expect(host.textContent).toContain('应用描述');
    expect(host.textContent).not.toContain('刷新授权列表');
    expect(host.textContent).not.toContain('ETL 授权审计');
    expect(host.textContent).not.toContain('实际范围');
    expect(host.textContent).not.toContain('重新加载授权列表');
    expect(host.querySelector('[data-popup="modal"]')).toBeNull();
    expect(getDataApiSimpleList).not.toHaveBeenCalled();
    await click('新增');
    expect(getDataApiSimpleList).toHaveBeenCalledExactlyOnceWith(1, 'grant');
    expect(host.querySelector('[data-popup="modal"]')?.textContent).toContain(
      '添加API服务授权',
    );
    expect(host.textContent).toContain('测试应用');
    await click('关闭弹窗');
    expect(host.querySelector('[data-popup="modal"]')).toBeNull();
    expect(router.currentRoute.value.name).toBe('SupplyAppDetailPage');
    await click('返回');
    await expect.poll(() => router.currentRoute.value.path).toBe('/supply/app');
    await flush();
    expect(harness.closeCurrentTab).toHaveBeenCalledWith(
      expect.objectContaining({
        name: 'SupplyAppDetailPage',
        params: { id: harness.row.id },
      }),
    );
    expect(host.querySelector('[data-popup="drawer"]')).toBeNull();
    await click('详情');
    await expect.poll(() => vi.mocked(getSupplyApp).mock.calls.length).toBe(2);
    await flush();
    expect(getSupplyApp).toHaveBeenCalledTimes(2);
    expect(host.textContent).toContain('测试应用');
    expect(host.querySelector('[data-popup="modal"]')).toBeNull();
  });

  it.each(['01', '-1', '1e2', 'not-an-id'])(
    '直接进入无效应用身份 %s 不读取详情或授权',
    async (id) => {
      await router.push({ name: 'SupplyAppDetailPage', params: { id } });
      await flush();
      expect(getSupplyApp).not.toHaveBeenCalled();
      expect(getAppApiList).not.toHaveBeenCalled();
    },
  );

  it('无查询权限直接进入详情不发起请求', async () => {
    useAccessStore().accessCodes = ['supply:app:grant'];
    await router.push({
      name: 'SupplyAppDetailPage',
      params: { id: harness.row.id },
    });
    await flush();
    expect(getSupplyApp).not.toHaveBeenCalled();
    expect(getAppApiList).not.toHaveBeenCalled();
  });
});
