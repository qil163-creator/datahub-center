/* eslint-disable vue/one-component-per-file -- 测试桩集中模拟页面布局与只读内容 */
import type { App, ComponentPublicInstance } from 'vue';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import CheckDetail from './index.vue';

const mock = vi.hoisted(() => ({
  access: {
    accessToken: 'session',
    refreshToken: '',
    loginExpired: false,
    accessCodes: ['asset:market:check'],
  },
  user: {
    userInfo: { userId: '1' } as null | { userId: string },
    userRoles: [] as string[],
  },
  route: { params: { id: '9007199254740993' }, query: { origin: 'QDATA' }, meta: { activePath: '' } },
  get: vi.fn(),
  push: vi.fn(),
  drawer: { setState: vi.fn(), setData: vi.fn(), open: vi.fn() },
}));
vi.mock('vue-router', () => ({
  useRoute: () => mock.route,
  useRouter: () => ({ push: mock.push }),
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => mock.access,
  useUserStore: () => mock.user,
}));
vi.mock('@vben/hooks', () => ({
  useTabs: () => ({ closeCurrentTab: vi.fn() }),
}));
vi.mock('#/api/asset/market/check', () => ({ getMarketCheckDetail: mock.get }));
vi.mock('#/views/market/modules/apply-detail.vue', () => ({
  default: { render: () => null },
}));
vi.mock('@vben/common-ui', () => ({
  Page: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('main', slots.default?.()),
  }),
  useVbenDrawer: () => [{ render: () => null }, mock.drawer],
}));
vi.mock('antdv-next', () => {
  const box = defineComponent({
    props: { message: { type: String, default: '' } },
    setup:
      (props, { slots }) =>
      () =>
        h('section', [props.message, slots.default?.(), slots.action?.()]),
  });
  return { Alert: box, Button: box, Spin: box };
});
vi.mock('./modules/resource-summary.vue', () => ({
  default: defineComponent({
    props: {
      resource: { type: Object, default: undefined },
      statusLabel: { type: String, default: '' },
    },
    setup: (props) => () => h('section', JSON.stringify(props)),
  }),
}));
vi.mock('./modules/fields-tab.vue', () => ({
  default: defineComponent({
    props: { columns: { type: Array, default: () => [] } },
    setup: (props) => () => h('section', JSON.stringify(props.columns)),
  }),
}));
vi.mock('./modules/preview-tab.vue', () => ({
  default: defineComponent({
    props: { preview: { type: Object, default: undefined } },
    setup: (props) => () =>
      h('section', props.preview ? '允许的预览' : '无预览'),
  }),
}));
vi.mock('./modules/api-document.vue', () => ({
  default: defineComponent({
    props: {
      document: { type: Object, default: undefined },
      canInvoke: Boolean,
    },
    setup: (props) => () =>
      h('section', props.canInvoke ? '允许调用' : '不可调用'),
  }),
}));

interface State {
  detail?: { ticket: { id: string } };
  loading: boolean;
  selectTab: (key: string) => void;
  back: () => void;
}
let app: App | undefined;
let host: HTMLDivElement;
let state: State;
const id = '9007199254740993';
function result(ticketId = id, overrides: Record<string, unknown> = {}) {
  return {
    ticket: {
      id: ticketId,
      origin: 'QDATA',
      canView: true,
      statusLabel: '已通过',
    },
    resource: { sourceRecordId: '22', marketName: '河流维表' },
    columns: [{ columnName: 'RVNM' }],
    canPreview: true,
    canInvoke: false,
    ...overrides,
  };
}
function pending<T>() {
  let resolve!: (value: T) => void;
  return {
    promise: new Promise<T>((yes) => {
      resolve = yes;
    }),
    resolve: (value: T) => resolve(value),
  };
}
async function flush() {
  for (let index = 0; index < 8; index++) await Promise.resolve();
  await nextTick();
}
function mount() {
  app = createApp(CheckDetail);
  const vm = app.mount(host) as ComponentPublicInstance;
  state = (vm.$ as unknown as { setupState: State }).setupState;
}
beforeEach(() => {
  vi.clearAllMocks();
  mock.access = reactive({
    accessToken: 'session',
    refreshToken: '',
    loginExpired: false,
    accessCodes: ['asset:market:check'],
  });
  mock.user = reactive({ userInfo: { userId: '1' }, userRoles: [] });
  mock.route = reactive({ params: { id }, query: { origin: 'QDATA' }, meta: { activePath: '' } });
  mock.drawer.setData.mockReturnValue(mock.drawer);
  mock.get.mockResolvedValue(result());
  host = document.createElement('div');
  document.body.append(host);
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
});

describe('审批工单专用详情', () => {
  it('返回当前可见审批菜单，不再跳转旧辅助前缀', async () => {
    mount();
    await flush();
    state.back();
    expect(mock.push).toHaveBeenLastCalledWith('/asset/check');
    mock.route.meta.activePath = '/asset/review';
    state.back();
    expect(mock.push).toHaveBeenLastCalledWith('/asset/review');
  });
  it('按来源和长编号加载，资产徽标与工单身份保持独立', async () => {
    mount();
    await flush();
    expect(mock.get).toHaveBeenCalledWith(id, 'QDATA');
    expect(host.textContent).toContain('河流维表');
    expect(host.textContent).toContain('"sourceRecordId":"22"');
    expect(state.detail?.ticket.id).toBe(id);
    expect(host.textContent).toContain('RVNM');
    state.selectTab('api');
    await flush();
    expect(host.textContent).toContain('不可调用');
  });
  it('切换工单后旧详情不能覆盖新详情或结束新请求loading', async () => {
    const first = pending<ReturnType<typeof result>>();
    const second = pending<ReturnType<typeof result>>();
    mock.get
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(second.promise);
    mount();
    mock.route.params.id = '21';
    await flush();
    first.resolve(result());
    await flush();
    expect(state.loading).toBe(true);
    expect(state.detail).toBeUndefined();
    second.resolve(result('21'));
    await flush();
    expect(state.detail?.ticket.id).toBe('21');
    expect(state.loading).toBe(false);
  });
  it.each(['token', 'permission'])(
    '身份或权限撤销立即清空并丢弃旧响应：%s',
    async (change) => {
      const request = pending<ReturnType<typeof result>>();
      mock.get.mockReturnValueOnce(request.promise);
      mount();
      if (change === 'token') mock.access.accessToken = '';
      if (change === 'permission') mock.access.accessCodes = [];
      request.resolve(result());
      await flush();
      expect(state.detail).toBeUndefined();
      expect(host.textContent).not.toContain('河流维表');
      expect(mock.get).toHaveBeenCalledTimes(1);
    },
  );
  it.each([
    { id: '其他编号', origin: 'QDATA', canView: true },
    { id, origin: 'NATIVE', canView: true },
    { id, origin: 'QDATA', canView: false },
  ])('拒绝身份不符或已不可见的详情', async (ticket) => {
    mock.get.mockResolvedValue(result(id, { ticket }));
    mount();
    await flush();
    expect(state.detail).toBeUndefined();
    expect(host.textContent).not.toContain('RVNM');
  });
  it('预览能力未开放时即使返回了预览字段也不交给展示组件', async () => {
    mock.get.mockResolvedValue(
      result(id, {
        canPreview: false,
        preview: { rows: [{ secret: '不应展示' }] },
      }),
    );
    mount();
    await flush();
    state.selectTab('preview');
    await flush();
    expect(host.textContent).toContain('无预览');
    expect(host.textContent).not.toContain('允许的预览');
  });
});
