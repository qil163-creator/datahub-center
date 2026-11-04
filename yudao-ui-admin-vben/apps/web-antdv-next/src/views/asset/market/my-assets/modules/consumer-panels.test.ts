import type { App, Component, ComponentPublicInstance } from 'vue';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import ApiDocument from './api-document.vue';
import Detail from './detail.vue';

const mock = vi.hoisted(() => ({
  access: {
    accessToken: 'session',
    refreshToken: '',
    loginExpired: false,
    accessCodes: ['asset:market:my-assets:query'],
  },
  user: { userInfo: { userId: '1' }, userRoles: [] as string[] },
  detail: vi.fn(),
  document: vi.fn(),
  drawerState: vi.fn(),
  drawerOpen: vi.fn(),
  preview: vi.fn(),
  openDocument: vi.fn(),
  closed: vi.fn(),
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => mock.access,
  useUserStore: () => mock.user,
}));
vi.mock('#/api/asset/my-assets', () => ({
  getMyAsset: mock.detail,
  getMyAssetApiDocument: mock.document,
}));
vi.mock('#/views/modeling/modelcommon/detail-field-form.vue', () => ({
  default: { render: () => null },
}));
vi.mock('@vben/common-ui', () => ({
  useVbenDrawer: () => {
    const api = {
      setState: (value: unknown) => {
        mock.drawerState(value);
        return api;
      },
      open: mock.drawerOpen,
    };
    return [
      defineComponent({
        setup:
          (_, { slots }) =>
          () =>
            h('section', slots.default?.()),
      }),
      api,
    ];
  },
}));
vi.mock('antdv-next', () => {
  const box = defineComponent({
    props: { message: String },
    setup:
      (props, { slots }) =>
      () =>
        h('div', [props.message, slots.default?.()]),
  });
  return Object.fromEntries(
    [
      'Alert',
      'Button',
      'Empty',
      'Spin',
      'Tag',
      'Timeline',
      'TimelineItem',
      'Table',
    ].map((name) => [name, box]),
  );
});

describe('授权详情内的显式消费', () => {
  it('使用真实get结果发出预览或文档事件，关闭后不再使用旧记录', async () => {
    mock.detail.mockResolvedValueOnce({ applyId: '99', assetId: '18', accessStatus: 'AVAILABLE', usable: true, deliveryType: 1, canPreview: true });
    const state = mount(Detail); state.open('99'); await flush();
    expect(mock.preview).not.toHaveBeenCalled(); state.useDelivery('preview');
    expect(mock.preview).toHaveBeenCalledWith(expect.objectContaining({ applyId: '99' }));
    mock.detail.mockResolvedValueOnce({ applyId: '98', assetId: '18', accessStatus: 'AVAILABLE', usable: true, deliveryType: 2, canViewApiDocument: true });
    state.open('98'); await flush(); state.useDelivery('document');
    expect(mock.openDocument).toHaveBeenCalledWith(expect.objectContaining({ applyId: '98' }));
    state.close(); mock.openDocument.mockClear(); state.useDelivery('document'); expect(mock.openDocument).not.toHaveBeenCalled();
    expect(mock.closed).toHaveBeenCalledWith('98');
  });
  it('过期和待确认记录保留详情但禁止消费，不信任单个canPreview标记', async () => {
    mock.detail.mockResolvedValueOnce({ applyId: '99', accessStatus: 'EXPIRED', usable: true, deliveryType: 1, canPreview: true });
    const state = mount(Detail); state.open('99'); await flush(); state.useDelivery('preview'); expect(mock.preview).not.toHaveBeenCalled();
  });
  it.each([
    new Error('该授权记录不属于当前用户或尚未批准'),
    { response: { data: { code: 4_000_000, msg: '该授权记录不属于当前用户或尚未批准' } } },
    { code: 4_000_000, msg: '该授权记录不属于当前用户或尚未批准' },
  ])('他人或未批准记录按原编号显示服务端拒绝，不回退其它记录 %s', async (failure) => {
    mock.detail.mockRejectedValueOnce(failure);
    const state = mount(Detail); state.open('9223372036854775807'); await flush();
    expect(state.loadError).toBe('该授权记录不属于当前用户或尚未批准'); expect(state.detail).toBeUndefined();
    expect(mock.detail).toHaveBeenCalledOnce(); expect(mock.detail).toHaveBeenCalledWith('9223372036854775807');
  });
  it('异常消息不是字符串时使用安全文案，不把对象当成业务提示', async () => {
    mock.detail.mockRejectedValueOnce({ code: 4_000_000, msg: { unsafe: '不应回显对象' } });
    const state = mount(Detail); state.open('99'); await flush();
    expect(state.loadError).toBe('资产详情读取失败，授权可能已变更，请重试。');
  });
});

interface State {
  detail?: { applyId: string };
  document?: { applyId: string };
  error: string;
  loadError?: string;
  loading: boolean;
  currentId?: string;
  open: (id: string) => void;
  close: () => void;
  load: () => Promise<void>;
  useDelivery: (kind: 'document' | 'preview') => void;
}
let app: App;
let host: HTMLDivElement;
function mount(component: Component) {
  app = createApp(component, { onPreview: mock.preview, onDocument: mock.openDocument, onClosed: mock.closed });
  const vm = app.mount(host) as ComponentPublicInstance;
  return (vm.$ as unknown as { setupState: State }).setupState;
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((yes) => {
    resolve = yes;
  });
  return { promise, resolve };
}
async function flush() {
  for (let index = 0; index < 10; index++) await Promise.resolve();
  await nextTick();
}
beforeEach(() => {
  vi.resetAllMocks();
  mock.access = reactive({
    accessToken: 'session',
    refreshToken: '',
    loginExpired: false,
    accessCodes: ['asset:market:my-assets:query'],
  });
  mock.user = reactive({
    userInfo: { userId: '1' },
    userRoles: [] as string[],
  });
  host = document.createElement('div');
  document.body.append(host);
});
afterEach(() => {
  app?.unmount();
  host.remove();
});

describe.each([
  ['资产详情', Detail, 'detail', mock.detail],
  ['API 文档', ApiDocument, 'document', mock.document],
] as const)('%s', (_name, component, field, api) => {
    it('同一弹窗切换申请时仅最新响应生效，申请编号保持精度', async () => {
      const old = deferred<{ applyId: string }>();
      const latest = deferred<{ applyId: string }>();
      api.mockReturnValueOnce(old.promise).mockReturnValueOnce(latest.promise);
      const state = mount(component);
      state.open('9007199254740993');
      state.open('9223372036854775807');
      old.resolve({ applyId: '9007199254740993' });
      await flush();
      expect(state[field]).toBeUndefined();
      expect(state.loading).toBe(true);
      latest.resolve({ applyId: '9223372036854775807' });
      await flush();
      expect(state[field]?.applyId).toBe('9223372036854775807');
      expect(api).toHaveBeenLastCalledWith('9223372036854775807');
    });
    it('身份变化关闭并清空弹窗，旧身份响应迟到不恢复', async () => {
      const pending = deferred<{ applyId: string }>();
      api.mockReturnValueOnce(pending.promise);
      const state = mount(component);
      state.open('1');
      mock.access.accessToken = 'another-user';
      await flush();
      expect(state[field]).toBeUndefined();
      expect(state.currentId).toBeUndefined();
      expect(mock.drawerState).toHaveBeenCalledWith({ isOpen: false });
      pending.resolve({ applyId: '1' });
      await flush();
      expect(state[field]).toBeUndefined();
    });
    it('拒绝串号响应并允许重试，关闭后旧请求失效', async () => {
      api
        .mockResolvedValueOnce({ applyId: '2' })
        .mockResolvedValueOnce({ applyId: '1' });
      const state = mount(component);
      state.open('1');
      await flush();
      expect(state.error ?? state.loadError).toContain('读取失败');
      expect(state[field]).toBeUndefined();
      await state.load();
      expect(state[field]?.applyId).toBe('1');
      const pending = deferred<{ applyId: string }>();
      api.mockReturnValueOnce(pending.promise);
      void state.load();
      state.close();
      pending.resolve({ applyId: '1' });
      await flush();
      expect(state[field]).toBeUndefined();
    });
});
