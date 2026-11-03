/* eslint-disable vue/one-component-per-file -- 集中声明测试组件桩 */
import type { App, ComponentPublicInstance } from 'vue';

import type { MyAssetsApi } from '#/api/asset/my-assets';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import Applications from './my-applications.vue';

const mock = vi.hoisted(() => ({
  access: { accessToken: 'session', refreshToken: '', loginExpired: false, accessCodes: ['asset:market:my-assets:query'] },
  user: { userInfo: { userId: '1' }, userRoles: [] as string[] },
  page: vi.fn(), detail: vi.fn(), open: vi.fn(), setState: vi.fn(),
}));
vi.mock('@vben/stores', () => ({ useAccessStore: () => mock.access, useUserStore: () => mock.user }));
vi.mock('@vben/icons', () => ({ IconifyIcon: () => null }));
vi.mock('#/api/asset/my-assets', () => ({ getMyApplicationsPage: mock.page, getMyApplication: mock.detail }));
vi.mock('#/components/list-pagination/index.vue', () => ({ default: { render: () => null } }));
vi.mock('#/components/table-viewport/index.vue', () => ({ default: { render: () => null } }));
vi.mock('#/views/modeling/modelcommon/detail-field-form.vue', () => ({ default: { render: () => null } }));
vi.mock('@vben/common-ui', () => ({
  useVbenDrawer: () => [defineComponent({ setup: (_, { slots }) => () => h('div', slots.default?.()) }), { open: mock.open, setState: mock.setState }],
}));
vi.mock('antdv-next', () => {
  const box = defineComponent({ props: { message: String, description: String }, setup: (props, { slots }) => () => h('div', [props.message, props.description, slots.default?.()]) });
  return Object.fromEntries(['Alert', 'Button', 'Empty', 'Input', 'Select', 'Spin', 'Tag', 'Timeline', 'TimelineItem'].map((name) => [name, box]));
});

interface State {
  rows: MyAssetsApi.Application[];
  total: number;
  loading: boolean;
  listError: string;
  detail?: MyAssetsApi.ApplicationDetail;
  detailError: string;
  filters: { keyword: string; status?: number };
  query: MyAssetsApi.ApplicationPageReq;
  statusLabel: (status: number) => string;
  search: () => void;
  changePage: (page: number, size: number) => void;
  openDetail: (row: MyAssetsApi.Application) => void;
  load: () => Promise<void>;
}
let app: App;
let host: HTMLElement;
let state: State;
async function flush() { for (let i = 0; i < 10; i++) await Promise.resolve(); await nextTick(); }
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => { resolve = done; });
  return { promise, resolve };
}
function mount() {
  app = createApp(Applications);
  const instance = app.mount(host) as ComponentPublicInstance;
  state = (instance.$ as unknown as { setupState: State }).setupState;
}
beforeEach(() => {
  vi.resetAllMocks();
  mock.access = reactive({ accessToken: 'session', refreshToken: '', loginExpired: false, accessCodes: ['asset:market:my-assets:query'] });
  mock.user = reactive({ userInfo: { userId: '1' }, userRoles: [] as string[] });
  mock.page.mockResolvedValue({ list: [
    { id: '9223372036854775807', status: 0 },
    { id: '2', status: 1 }, { id: '3', status: 3 }, { id: '4', status: 8 },
  ], total: 4 });
  mock.detail.mockResolvedValue({ id: '9223372036854775807', status: 0, processes: [] });
  host = document.createElement('div');
  document.body.append(host);
});
afterEach(() => { app?.unmount(); host.remove(); });

describe('本人申请记录', () => {
  it('显示全部申请状态，筛选和翻页仅发送实际查询字段', async () => {
    mount(); await flush();
    expect(state.rows.map((row) => state.statusLabel(row.status!))).toEqual(['草稿', '待审核', '已驳回', '待补正']);
    expect(state.statusLabel(99)).toContain('99');
    state.filters.keyword = '  标题  '; state.filters.status = 3; state.search(); await flush();
    expect(mock.page).toHaveBeenLastCalledWith({ pageNo: 1, pageSize: 10, keyword: '标题', status: 3 });
    state.changePage(2, 10); await flush();
    expect(mock.page).toHaveBeenLastCalledWith(expect.objectContaining({ pageNo: 2, keyword: '标题', status: 3 }));
  });
  it('详情使用无损申请编号且只接受匹配的响应', async () => {
    mount(); await flush(); state.openDetail(state.rows[0]!); await flush();
    expect(mock.detail).toHaveBeenCalledWith('9223372036854775807');
    expect(state.detail?.id).toBe('9223372036854775807');
    mock.detail.mockResolvedValueOnce({ id: '2' });
    state.openDetail(state.rows[0]!); await flush();
    expect(state.detail).toBeUndefined(); expect(state.detailError).toContain('读取失败');
  });
  it('旧请求或身份失效后的响应不能回填列表和详情', async () => {
    const list = deferred<{ list: MyAssetsApi.Application[]; total: number }>();
    const detail = deferred<MyAssetsApi.ApplicationDetail>();
    mount(); await flush();
    mock.detail.mockReturnValueOnce(detail.promise); state.openDetail(state.rows[0]!);
    mock.page.mockReturnValueOnce(list.promise); void state.load();
    mock.access.accessToken = ''; await flush();
    list.resolve({ list: [{ id: '7', status: 1 }], total: 1 });
    detail.resolve({ id: '9223372036854775807' }); await flush();
    expect(state.rows).toEqual([]); expect(state.detail).toBeUndefined(); expect(state.loading).toBe(false);
  });
  it('加载失败与空结果区分，重试后恢复', async () => {
    mock.page.mockRejectedValueOnce(new Error('timeout'));
    mount(); await flush(); expect(state.listError).toContain('读取失败');
    await state.load(); expect(state.listError).toBe(''); expect(state.total).toBe(4);
  });
});
