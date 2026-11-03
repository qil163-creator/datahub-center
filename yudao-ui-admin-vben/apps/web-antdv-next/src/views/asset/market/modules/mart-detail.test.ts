/* eslint-disable vue/one-component-per-file -- 测试桩集中模拟授权与弹窗 */

import type { App, ComponentPublicInstance } from 'vue';

import type { MarketApi } from '#/api/asset/market';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import MartDetail from './mart-detail.vue';

const mock = vi.hoisted(() => ({
  access: {
    accessToken: 'session',
    refreshToken: '',
    loginExpired: false,
    accessCodes: [] as string[],
  },
  user: { userInfo: { userId: '1' } as null | { userId: string } },
  detail: vi.fn(),
  check: vi.fn(),
  preview: vi.fn(),
  document: vi.fn(),
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({ hasAccessByCodes: () => false }),
}));
vi.mock('@vben/common-ui', () => ({
  useVbenModal: () => [
    defineComponent({ setup: () => () => null }),
    { setState: vi.fn() },
  ],
}));
vi.mock('./mart-apply-form.vue', () => ({
  default: defineComponent({ setup: () => () => null }),
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => mock.access,
  useUserStore: () => mock.user,
}));
vi.mock('#/api/asset/market', () => ({
  getMarketResourceDetail: mock.detail,
  checkMarketAccess: mock.check,
  previewMarketAsset: mock.preview,
}));
vi.mock('#/api/asset/my-assets', () => ({
  getMyAssetApiDocument: mock.document,
}));
vi.mock('antdv-next', () => {
  const box = defineComponent({
    props: {
      message: { type: String, default: '' },
      dataSource: { type: Array, default: () => [] },
    },
    setup:
      (props, { slots }) =>
      () =>
        h('section', [
          props.message,
          JSON.stringify(props.dataSource),
          slots.default?.(),
        ]),
  });
  return { Alert: box, Button: box, Spin: box, Table: box, Tag: box };
});
interface State {
  current?: MarketApi.Resource;
  permitted: boolean;
  detailLoading: boolean;
  open: (id: string) => Promise<void>;
  close: () => void;
  selectTab: (tab: string) => Promise<void>;
}
let app: App | undefined;
let host: HTMLDivElement;
let state: State;
const id = '9223372036854775807';
function deferred<T>() {
  let resolve!: (value: T) => void;
  return {
    promise: new Promise<T>((yes) => {
      resolve = yes;
    }),
    resolve: (value: T) => resolve(value),
  };
}
async function flush() {
  for (let count = 0; count < 8; count++) await Promise.resolve();
  await nextTick();
}
function mount() {
  app = createApp(MartDetail);
  const vm = app.mount(host) as ComponentPublicInstance;
  state = (vm.$ as unknown as { setupState: State }).setupState;
}
beforeEach(() => {
  vi.resetAllMocks();
  mock.access = reactive({
    accessToken: 'session',
    refreshToken: '',
    loginExpired: false,
    accessCodes: [],
  });
  mock.user = reactive({ userInfo: { userId: '1' } });
  mock.detail.mockResolvedValue({
    asset: {
      id,
      assetName: '河流维表',
      marketCategoryName: '部门分类',
      assetType: 1,
      deliveryTypes: [1, 2],
    },
    columns: [{ columnName: 'private_field' }],
  });
  mock.check.mockResolvedValue(false);
  mock.preview.mockResolvedValue({
    columns: [{ name: 'allowed_field' }],
    rows: [{ allowed_field: '数据' }],
  });
  host = document.createElement('div');
  document.body.append(host);
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
});
describe('集市专用详情', () => {
  it('未授权仍展示目录信息，三个页签均隐藏字段与数据', async () => {
    mount();
    await state.open(id);
    await flush();
    expect(mock.detail).toHaveBeenCalledWith(id);
    expect(mock.check).toHaveBeenCalledWith(id);
    expect(host.textContent).toContain('河流维表');
    expect(host.textContent).toContain('部门分类');
    expect(host.textContent).toContain('未授权');
    for (const tab of ['fields', 'preview', 'api']) {
      await state.selectTab(tab);
      await flush();
      expect(host.textContent).toContain('暂无权限');
      expect(host.textContent).not.toContain('private_field');
    }
    expect(mock.preview).not.toHaveBeenCalled();
    expect(mock.document).not.toHaveBeenCalled();
  });
  it('只有实时授权为true时展示字段，并在选择预览时加载', async () => {
    mock.check.mockResolvedValue(true);
    mount();
    await state.open(id);
    await flush();
    expect(host.textContent).toContain('private_field');
    expect(mock.preview).not.toHaveBeenCalled();
    await state.selectTab('preview');
    await flush();
    expect(mock.preview).toHaveBeenCalledWith(id, 20, expect.any(String));
    expect(host.textContent).toContain('allowed_field');
  });
  it('拒绝与所选资产不符的详情', async () => {
    mock.detail.mockResolvedValue({
      asset: { id: '2', assetName: '错误资产' },
    });
    mount();
    await state.open(id);
    await flush();
    expect(state.current).toBeUndefined();
    expect(mock.check).not.toHaveBeenCalled();
    expect(host.textContent).not.toContain('错误资产');
  });
  it('会话变化使旧授权响应失效并清理详情', async () => {
    const pending = deferred<boolean>();
    mock.check.mockReturnValueOnce(pending.promise);
    mount();
    const request = state.open(id);
    await flush();
    mock.access.accessToken = 'new-session';
    pending.resolve(true);
    await request;
    await flush();
    expect(state.current).toBeUndefined();
    expect(state.permitted).toBe(false);
  });
  it('切换页签后丢弃旧预览并保持当前状态', async () => {
    mock.check.mockResolvedValue(true);
    const pending = deferred<{ rows: { secret: string }[] }>();
    mock.preview.mockReturnValueOnce(pending.promise);
    mount();
    await state.open(id);
    const request = state.selectTab('preview');
    await state.selectTab('fields');
    pending.resolve({ rows: [{ secret: '旧预览' }] });
    await request;
    await flush();
    expect(host.textContent).not.toContain('旧预览');
    expect(host.textContent).toContain('private_field');
  });
  it('文件资产只展示文件页签，待审申请状态显示待授权', async () => {
    mock.detail.mockResolvedValue({
      asset: {
        id,
        assetName: '文件',
        sourceRecordId: '23',
        assetType: 3,
        deliveryTypes: [3],
        myApplyStatus: 1,
      },
    });
    mount();
    await state.open(id);
    await flush();
    const labels = [...host.querySelectorAll('[role="tab"]')].map((element) =>
      element.textContent?.trim(),
    );
    expect(labels).toEqual(['文件']);
    expect(host.textContent).toContain('待授权');
    expect(host.querySelector('.mart-detail__badge')?.textContent).toBe('23');
  });
});
