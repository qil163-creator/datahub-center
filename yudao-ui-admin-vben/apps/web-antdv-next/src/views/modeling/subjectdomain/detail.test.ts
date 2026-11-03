import type { App, SetupContext } from 'vue';

import { createApp, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import SubjectDomainDetail from './detail.vue';

const harness = vi.hoisted(() => ({
  route: {} as { params: { id: string }; query: Record<string, string> },
  access: {} as { accessCodes: string[]; accessToken: string; refreshToken: string; },
  allowed: true,
  getDetail: vi.fn(), getBoard: vi.fn(), push: vi.fn(),
}));
vi.mock('vue-router', () => ({ useRoute: () => harness.route, useRouter: () => ({ push: harness.push }) }));
vi.mock('@vben/stores', () => ({ useAccessStore: () => harness.access }));
vi.mock('@vben/access', () => ({ useAccess: () => ({ hasAccessByCodes: () => harness.allowed }) }));
vi.mock('@vben/common-ui', () => ({ Page: (_: unknown, { slots }: SetupContext) => h('div', slots.default?.()) }));
vi.mock('antdv-next', () => {
  const Wrapper = (_: unknown, { slots }: SetupContext) => h('div', slots.default?.());
  return { Button: Wrapper, ConfigProvider: Wrapper, Spin: Wrapper, TabPane: Wrapper, Tabs: Wrapper, theme: {} };
});
vi.mock('#/api/modeling/subjectdomain', () => ({ getSubjectDomain: harness.getDetail }));
vi.mock('#/api/modeling/datadomain', () => ({ getDataDomain: harness.getBoard }));
vi.mock('../planning/detail-header.vue', () => ({ default: Object.assign((props: { item: { id?: string; name?: string } }) => h('div', `${props.item.id} ${props.item.name}`), { props: ['item'] }) }));
vi.mock('../planning/entity/table.vue', () => ({ default: () => null }));

let app: App | undefined;
let host: HTMLDivElement;
async function flush() { await nextTick(); await Promise.resolve(); await nextTick(); }
async function mount() { host = document.createElement('div'); app = createApp(SubjectDomainDetail); app.mount(host); await flush(); }
beforeEach(() => {
  vi.clearAllMocks(); harness.allowed = true;
  harness.route = reactive({ params: { id: '9007199254740995' }, query: {} });
  harness.access = reactive({ accessToken: 'a', refreshToken: '', accessCodes: ['modeling:subject-domain:query'] });
  harness.getDetail.mockResolvedValue({ id: '9007199254740995', name: '当前主题', dataDomainId: '29' });
  harness.getBoard.mockResolvedValue({ id: '29', name: '当前板块' });
});
afterEach(() => { app?.unmount(); app = undefined; host?.remove(); });

describe('主题详情页面请求隔离', () => {
  it('使用原始长编号加载详情', async () => { await mount(); expect(harness.getDetail).toHaveBeenCalledWith('9007199254740995'); expect(host.textContent).toContain('当前主题'); });
  it('拒绝无效编号和无查询权限时的请求', async () => { harness.route.params.id = '1.5'; await mount(); expect(harness.getDetail).not.toHaveBeenCalled(); harness.allowed = false; harness.route.params.id = '12'; await flush(); expect(harness.getDetail).not.toHaveBeenCalled(); });
  it('对象切换后旧响应不覆盖新主题', async () => {
    let finish!: (value: unknown) => void;
    harness.getDetail.mockReturnValueOnce(new Promise((resolve) => { finish = resolve; }));
    await mount();
    harness.getDetail.mockResolvedValue({ id: '24', name: '新主题' });
    harness.route.params.id = '24'; await flush();
    finish({ id: '9007199254740995', name: '过期主题' }); await flush();
    expect(host.textContent).toContain('新主题'); expect(host.textContent).not.toContain('过期主题');
  });
  it('登录会话切换立即清空内容并丢弃旧响应', async () => {
    let finish!: (value: unknown) => void;
    harness.getDetail.mockReturnValueOnce(new Promise((resolve) => { finish = resolve; }));
    await mount(); harness.allowed = false; harness.access.accessToken = 'b'; await flush();
    finish({ id: '9007199254740995', name: '旧会话主题' }); await flush();
    expect(host.textContent).not.toContain('旧会话主题');
  });
});
