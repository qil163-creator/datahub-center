import type { App, SetupContext } from 'vue';

import type { ModelHomeApi } from '#/api/governance/model-home';

import { createApp, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import ModelHome from './index.vue';

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
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      codes.some((code) => harness.access.accessCodes.includes(code)),
  }),
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
  Alert: (
    props: { description?: string; message?: string },
    { slots }: SetupContext,
  ) => h('aside', [props.message, props.description, slots.action?.()]),
  Button: (_: unknown, { slots, attrs }: SetupContext) =>
    h('button', attrs, slots.default?.()),
  Skeleton: () => h('span', '正在加载'),
}));
vi.mock('#/api/governance/model-home', () => ({
  getModelOverview: () => harness.load(),
}));
vi.mock('#/views/dashboard/shared/route-resolver', () => ({
  DASHBOARD_TARGETS: {},
  useDashboardNav: () => ({
    navTo: harness.navTo,
    resolve: (target?: { names?: string[] }) =>
      target?.names?.[0] ? `/dynamic/${target.names[0]}` : undefined,
  }),
}));
vi.mock('./overview-artwork.vue', () => ({ default: () => null }));

let app: App | undefined;
let host: HTMLDivElement;

function overview(count: number): ModelHomeApi.ModelOverview {
  return {
    dataDomainCount: count,
    businessDomainCount: 0,
    modelCount: 0,
    publishedCount: 0,
    materializedCount: 0,
    factCount: 0,
    dimensionCount: 0,
    metrics: [
      {
        key: 'dataDomain',
        label: '数据域',
        count,
        recentCount: 0,
        available: true,
        description: '数据域总数',
      },
    ],
    layerDistribution: [],
    materializedDistribution: [],
    recentActivities: [],
    layerDistributionAvailable: true,
    materializedDistributionAvailable: true,
    recentActivitiesAvailable: true,
    generatedAt: 1_792_000_000_000,
  };
}

function deferred() {
  let resolve!: (value: ModelHomeApi.ModelOverview) => void;
  const promise = new Promise<ModelHomeApi.ModelOverview>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

async function flush() {
  await nextTick();
  await nextTick();
}

async function mount() {
  host = document.createElement('div');
  app = createApp(ModelHome);
  app.mount(host);
  await flush();
}

beforeEach(() => {
  vi.clearAllMocks();
  harness.access = reactive({
    accessCodes: ['modeling:model:query'],
    accessToken: 'session-a',
    refreshToken: '',
  });
  harness.user = reactive({
    userInfo: { userId: '1' },
    userRoles: ['modeler'],
  });
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host?.remove();
});

describe('建模概览请求状态', () => {
  it('概念与指标卡片按真实统计、查询权限和动态菜单开放入口', async () => {
    const result = overview(0);
    result.metrics = ['conceptModel', 'indicatorModel'].map((key) => ({
      key: key as ModelHomeApi.MetricKey,
      label: key === 'conceptModel' ? '概念模型' : '指标模型',
      count: 3,
      recentCount: 1,
      available: true,
      description: '真实模型数量',
    }));
    harness.access.accessCodes = ['modeling:concept-model:query'];
    harness.load.mockResolvedValue(result);
    await mount();
    const cards = [...host.querySelectorAll<HTMLButtonElement>('.metric-card')];
    const concept = cards.find((card) =>
      card.textContent?.includes('概念模型'),
    )!;
    const indicator = cards.find((card) =>
      card.textContent?.includes('指标模型'),
    )!;
    expect(concept.disabled).toBe(false);
    expect(indicator.disabled).toBe(true);
    concept.click();
    expect(harness.navTo).toHaveBeenCalledWith('/dynamic/ModelingConceptModel');
    harness.access.accessCodes = ['modeling:indicator-model:query'];
    await flush();
    expect(indicator.disabled).toBe(false);
    indicator.click();
    expect(harness.navTo).toHaveBeenLastCalledWith(
      '/dynamic/ModelingIndicatorModel',
    );
  });

  it('同一时刻切换会话后返回原会话，也不会回写旧统计', async () => {
    const previous = deferred();
    const switched = deferred();
    const current = deferred();
    harness.load
      .mockReturnValueOnce(previous.promise)
      .mockReturnValueOnce(switched.promise)
      .mockReturnValueOnce(current.promise);
    await mount();
    harness.access.accessToken = 'session-b';
    harness.access.accessToken = 'session-a';
    expect(harness.load).toHaveBeenCalledTimes(3);
    current.resolve(overview(23));
    await flush();
    previous.resolve(overview(777));
    switched.resolve(overview(888));
    await flush();
    expect(host.querySelector('.metric-card__number strong')?.textContent).toBe(
      '23',
    );
    expect(host.textContent).not.toContain('777');
    expect(host.textContent).not.toContain('888');
  });

  it('权限变化即时清空旧数据，失败显示不可用，重试后能展示真实零值', async () => {
    harness.load.mockResolvedValueOnce(overview(12));
    await mount();
    expect(host.querySelector('.metric-card__number strong')?.textContent).toBe(
      '12',
    );
    harness.load.mockRejectedValueOnce(new Error('unavailable'));
    harness.access.accessCodes = [];
    await flush();
    expect(host.querySelector('.metric-card__number strong')?.textContent).toBe(
      '—',
    );
    expect(host.textContent).toContain('统计数据加载失败');
    harness.load.mockResolvedValueOnce(overview(0));
    const retry = [...host.querySelectorAll('button')].find(
      (button) => button.textContent?.trim() === '重试',
    );
    expect(retry).toBeDefined();
    retry?.click();
    await flush();
    expect(host.querySelector('.metric-card__number strong')?.textContent).toBe(
      '0',
    );
    expect(host.textContent).not.toContain('统计数据加载失败');
  });
});
