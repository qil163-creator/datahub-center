import type { App, SetupContext } from 'vue';

import type { ModuleOverviewConfig, ModuleOverviewData } from './types';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import ModuleOverview from './module-overview.vue';
import { useModuleOverview } from './use-module-overview';

const harness = vi.hoisted(() => ({
  access: {} as {
    accessCodes: string[];
    accessToken: string;
    refreshToken: string;
  },
  user: {} as { userInfo: { userId: string }; userRoles: string[] },
  load: vi.fn(),
  navTo: vi.fn(),
}));

vi.mock('@vben/stores', () => ({
  useAccessStore: () => harness.access,
  useUserStore: () => harness.user,
}));
vi.mock('@vben/common-ui', () => ({
  Page: (_: unknown, { slots }: SetupContext) => h('main', slots.default?.()),
}));
vi.mock('@vben/icons', () => ({ IconifyIcon: () => null }));
vi.mock('@vben/utils', () => ({
  formatDate: () => '10-15',
  formatDateTime: () => '2026-10-15 00:00:00',
}));
vi.mock('antdv-next', () => ({
  Button: (_: unknown, { slots, attrs }: SetupContext) =>
    h('button', attrs, slots.default?.()),
  Skeleton: () => h('span', '正在加载'),
}));
vi.mock('../route-resolver', () => ({
  useDashboardNav: () => ({
    navTo: harness.navTo,
    resolve: (target?: { names?: string[] }) =>
      target?.names?.[0] ? `/dynamic/${target.names[0]}` : undefined,
  }),
}));
vi.mock('./overview-artwork.vue', () => ({ default: () => null }));

const config: ModuleOverviewConfig = {
  name: '测试模块',
  banner: {
    title: '测试横幅',
    description: '说明',
    slogan: '口号',
    icons: ['a', 'b'],
  },
  metrics: [
    {
      key: 'count',
      label: '计数',
      icon: 'i',
      color: 'blue',
      unit: '条',
      target: { names: ['CountPage'] },
    },
    { key: 'missing', label: '无权限', icon: 'i', color: 'teal' },
    {
      key: 'rate',
      label: '成功率',
      icon: 'i',
      color: 'green',
      target: { names: ['RatePage'] },
    },
  ],
  process: { title: '主流程', note: '', steps: [] },
  downstream: { title: '下游', origin: '产出', items: [] },
  distributions: [
    { key: 'share', title: '占比分布', empty: '暂无占比数据' },
    { key: 'rates', title: '比率分布', mode: 'rate', empty: '暂无比率数据' },
  ],
  activity: { title: '最近动态', empty: '暂无动态' },
};

function overview(count: number): ModuleOverviewData {
  return {
    generatedAt: 1_792_000_000_000,
    metrics: {
      count: { value: count, hint: `启用 ${count}` },
      rate: { display: '98.0%', hint: '近 7 日' },
    },
    distributions: {
      share: { rows: [{ key: 'x', name: 'X', count: 0 }] },
      rates: {
        rows: [
          { key: 'a', name: '覆盖率', rate: 50 },
          { key: 'b', name: '通过率', rate: null },
        ],
      },
    },
    activity: { items: [] },
  };
}

function deferred() {
  let resolve!: (value: ModuleOverviewData) => void;
  const promise = new Promise<ModuleOverviewData>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

const Harness = defineComponent({
  setup() {
    const state = useModuleOverview(() => harness.load());
    return () =>
      h(ModuleOverview, {
        config,
        data: state.data.value,
        failed: state.failed.value,
        loading: state.loading.value,
        onRefresh: state.refresh,
      });
  },
});

let app: App | undefined;
let host: HTMLDivElement;

async function flush() {
  await nextTick();
  await nextTick();
}

async function mount() {
  host = document.createElement('div');
  app = createApp(Harness);
  app.mount(host);
  await flush();
}

function card(label: string) {
  return [...host.querySelectorAll<HTMLButtonElement>('.metric-card')].find(
    (item) => item.textContent?.includes(label),
  )!;
}

function panel(title: string) {
  return [...host.querySelectorAll<HTMLElement>('.overview-panel')].find(
    (item) => item.querySelector('h2')?.textContent === title,
  )!;
}

beforeEach(() => {
  vi.clearAllMocks();
  harness.access = reactive({
    accessCodes: ['a'],
    accessToken: 'session-a',
    refreshToken: '',
  });
  harness.user = reactive({ userInfo: { userId: '1' }, userRoles: ['r'] });
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host?.remove();
});

describe('模块概览布局', () => {
  it('区分真实零值与无权限，格式化数值不追加计数单位', async () => {
    harness.load.mockResolvedValue(overview(0));
    await mount();
    const count = card('计数');
    expect(count.querySelector('strong')?.textContent).toBe('0');
    expect(count.textContent).toContain('条');
    expect(count.disabled).toBe(false);
    count.click();
    expect(harness.navTo).toHaveBeenCalledWith('/dynamic/CountPage');

    const missing = card('无权限');
    expect(missing.querySelector('strong')?.textContent).toBe('—');
    expect(missing.textContent).toContain('暂无权限或服务不可用');
    expect(missing.textContent).not.toContain('个');
    expect(missing.disabled).toBe(true);

    const rate = card('成功率');
    expect(rate.querySelector('strong')?.textContent).toBe('98.0%');
    expect(rate.querySelector('.metric-card__number span')).toBeNull();
  });

  it('空分布展示配置的提示文案，比率面板区分暂无统计', async () => {
    harness.load.mockResolvedValue(overview(1));
    await mount();
    const share = panel('占比分布');
    expect(share.querySelector('.panel-empty')?.textContent).toContain(
      '暂无占比数据',
    );
    expect(share.textContent).not.toContain('true');

    const rates = panel('比率分布');
    expect(rates.querySelectorAll('.distribution-row')).toHaveLength(2);
    expect(rates.textContent).toContain('50.0%');
    expect(rates.textContent).toContain('—');
    expect(rates.querySelector('.panel-heading__total')).toBeNull();

    expect(panel('最近动态').textContent).toContain('暂无动态');
  });

  it('数据源不可用时面板显示无权限，而不是空数据', async () => {
    const result = overview(1);
    result.distributions = {};
    result.activity = undefined;
    harness.load.mockResolvedValue(result);
    await mount();
    expect(panel('占比分布').textContent).toContain('暂无权限或服务不可用');
    expect(panel('最近动态').textContent).toContain('暂无权限或服务不可用');
  });

  it('切换会话后只写回最新请求，失败后重试可恢复', async () => {
    const previous = deferred();
    const current = deferred();
    harness.load
      .mockReturnValueOnce(previous.promise)
      .mockReturnValueOnce(current.promise);
    await mount();
    harness.access.accessToken = 'session-b';
    expect(harness.load).toHaveBeenCalledTimes(2);
    current.resolve(overview(23));
    await flush();
    previous.resolve(overview(777));
    await flush();
    expect(card('计数').querySelector('strong')?.textContent).toBe('23');
    expect(host.textContent).not.toContain('777');

    harness.load.mockRejectedValueOnce(new Error('down'));
    harness.access.accessCodes = [];
    await flush();
    expect(card('计数').textContent).toContain('加载失败，请重试');

    harness.load.mockResolvedValueOnce(overview(5));
    host.querySelector<HTMLButtonElement>('.refresh-button')?.click();
    await flush();
    expect(card('计数').querySelector('strong')?.textContent).toBe('5');
  });
});
