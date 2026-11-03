/* eslint-disable vue/one-component-per-file, vue/require-prop-types -- Isolated UI doubles exercise the real detail request lifecycle. */
import type { App, SetupContext } from 'vue';

import type { FoundationDatabaseApi } from '#/api/dataforge/database';
import type { DatabaseMonitorApi } from '#/api/dataforge/database/monitor';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import DatabaseDetail from './index.vue';

const harness = vi.hoisted(() => ({
  route: { params: { id: '9007199254740993' as unknown } },
  access: {
    accessToken: '1',
    refreshToken: undefined as string | undefined,
    codes: ['dataforge:database:query'],
  },
  getDatabase: vi.fn(),
  getOverview: vi.fn(),
  getTrend: vi.fn(),
  collect: vi.fn(),
  push: vi.fn(),
}));
vi.mock('vue-router', () => ({
  useRoute: () => harness.route,
  useRouter: () => ({ push: harness.push }),
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      codes.some((code) => harness.access.codes.includes(code)),
  }),
}));
vi.mock('@vben/stores', () => ({ useAccessStore: () => harness.access }));
vi.mock('@vben/icons', () => ({ IconifyIcon: () => null }));
vi.mock('@vben/hooks', () => ({
  getDictOptions: () => [{ value: 0, label: '已启用' }],
}));
vi.mock('@vben/utils', () => ({
  formatDateTime: (value: number) => `time:${value}`,
}));
vi.mock('@vben/constants', () => ({
  DICT_TYPE: { COMMON_STATUS: 'common_status' },
}));
vi.mock('@vben/common-ui', () => ({
  Page: (_: unknown, { slots }: SetupContext) => h('main', slots.default?.()),
}));
vi.mock('#/api/dataforge/database', () => ({
  getDatabase: harness.getDatabase,
}));
vi.mock('#/api/dataforge/database/monitor', () => ({
  getDatabaseMonitorOverview: harness.getOverview,
  getDatabaseMonitorTrend: harness.getTrend,
  collectDatabaseMonitor: harness.collect,
}));
vi.mock('../data', () => ({
  DATABASE_USAGE_OPTIONS: [{ value: 1, label: '业务库' }],
}));
vi.mock('./records-panel.vue', () => ({ default: () => h('div', 'records') }));
vi.mock('./rules-panel.vue', () => ({ default: () => h('div', 'rules') }));
vi.mock('./monitor-panel.vue', () => ({
  default: defineComponent({
    props: ['database', 'overview', 'trend', 'loading', 'collecting'],
    emits: ['collect', 'rangeChange'],
    setup:
      (props, { emit }) =>
      () =>
        h(
          'section',
          { 'data-monitor': true, 'data-collecting': props.collecting },
          [
            h(
              'span',
              { 'data-overview': true },
              props.overview?.todayOutputRows ?? 'unknown',
            ),
            h('span', { 'data-trend': true }, props.trend?.days ?? 'unknown'),
            h(
              'button',
              {
                'data-collect': true,
                disabled:
                  props.collecting ||
                  props.loading ||
                  props.database.status !== 0,
                onClick: () => emit('collect'),
              },
              '立即采集',
            ),
            h('button', { onClick: () => emit('rangeChange', 15) }, '近15天'),
          ],
        ),
  }),
}));
vi.mock('antdv-next', () => {
  const box = defineComponent({
    props: ['message', 'label', 'tab', 'description'],
    setup:
      (props, { slots }) =>
      () =>
        h('section', [
          props.message ?? props.label ?? props.tab ?? props.description ?? '',
          slots.default?.(),
          slots.action?.(),
        ]),
  });
  return {
    Alert: box,
    Descriptions: box,
    DescriptionsItem: box,
    Empty: box,
    Tabs: box,
    TabPane: box,
    Tag: box,
    Button: (_: unknown, { slots }: SetupContext) =>
      h('button', slots.default?.()),
    Skeleton: () => h('div', { 'data-loading': true }, 'loading'),
  };
});

function database(
  id = '9007199254740993',
  name = 'Example source',
): FoundationDatabaseApi.Database {
  return {
    id,
    name,
    userName: 'test-account',
    password: 'do-not-render-this-secret',
    jdbcUrl: 'do-not-render-connection-url',
    extendConfig: 'do-not-render-extra-parameters',
    databaseTypeName: 'PostgreSQL',
    typeCode: 'POSTGRESQL',
    databaseName: 'sample',
    databaseSchema: 'public',
    usageType: 1,
    status: 0,
  };
}
function overview(rows = '100'): DatabaseMonitorApi.Overview {
  return {
    connectionStatus: 1,
    lastCollectedTime: 1,
    responseTimeMs: 25,
    todayOutputRows: rows,
    todayInputRows: '0',
    totalTransferRows: rows,
    pendingAlarmCount: 0,
    averageResponseTimeMs: 25,
    readSpeed: null,
    writeSpeed: null,
    throughputUnit: 'rows/s',
    capacityUsed: null,
    capacityTotal: null,
    supportedMetrics: ['CONNECTION_STATUS', 'RESPONSE_TIME'],
    logCoverage: '',
  };
}
function trend(days = 7): DatabaseMonitorApi.Trend {
  return { days, points: [], supportedMetrics: [] };
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: Error) => void;
  const promise = new Promise<T>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}
let app: App | undefined;
let host: HTMLDivElement;
async function flush() {
  for (let index = 0; index < 8; index++) await Promise.resolve();
  await nextTick();
}
async function mountPage() {
  host = document.createElement('div');
  app = createApp(DatabaseDetail);
  app.mount(host);
  await flush();
}
function button(text: string) {
  const result = [...host.querySelectorAll('button')].find(
    (element) => element.textContent?.trim() === text,
  );
  expect(result).toBeDefined();
  return result!;
}
beforeEach(() => {
  vi.resetAllMocks();
  harness.route = reactive({ params: { id: '9007199254740993' } });
  harness.access = reactive({
    accessToken: '1',
    refreshToken: undefined as string | undefined,
    codes: ['dataforge:database:query'],
  });
  harness.getDatabase.mockResolvedValue(database());
  harness.getOverview.mockResolvedValue(overview());
  harness.getTrend.mockResolvedValue(trend());
  harness.collect.mockResolvedValue(overview());
});
afterEach(() => {
  app?.unmount();
  app = undefined;
});

describe('database detail identity and state', () => {
  it('keeps route IDs lossless, shows all five tabs and excludes connection secrets', async () => {
    await mountPage();
    expect(harness.getDatabase).toHaveBeenCalledWith('9007199254740993');
    expect(harness.collect).toHaveBeenCalledExactlyOnceWith('9007199254740993');
    expect(harness.getOverview).toHaveBeenCalledWith('9007199254740993');
    expect(harness.getTrend).toHaveBeenCalledWith('9007199254740993', 7);
    for (const title of [
      '运行监控',
      '读写日志',
      '告警记录',
      '告警规则',
      '基本信息',
    ])
      expect(host.textContent).toContain(title);
    expect(host.textContent).toContain('test-account');
    expect(host.textContent).not.toContain('do-not-render');
    button('返回').click();
    expect(harness.push).toHaveBeenCalledWith('/develop/database');
  });

  it('shows existing data while automatically collecting once, then refreshes the result', async () => {
    const pending = deferred<DatabaseMonitorApi.Overview>();
    harness.collect.mockReturnValueOnce(pending.promise);
    harness.getOverview
      .mockResolvedValueOnce(overview('100'))
      .mockResolvedValue(overview('200'));
    await mountPage();
    expect(harness.collect).toHaveBeenCalledExactlyOnceWith('9007199254740993');
    expect(host.querySelector('[data-overview]')?.textContent).toBe('100');
    expect(
      host.querySelector<HTMLElement>('[data-monitor]')?.dataset.collecting,
    ).toBe('true');
    expect(button('立即采集').disabled).toBe(true);
    button('立即采集').click();
    expect(harness.collect).toHaveBeenCalledTimes(1);
    pending.resolve(overview('200'));
    await flush();
    expect(host.querySelector('[data-overview]')?.textContent).toBe('200');
    expect(harness.getOverview).toHaveBeenCalledTimes(2);
    expect(button('立即采集').disabled).toBe(false);
  });

  it('keeps existing data after automatic collection fails and allows a manual retry', async () => {
    harness.collect.mockRejectedValueOnce(new Error('unavailable'));
    await mountPage();
    expect(host.querySelector('[data-overview]')?.textContent).toBe('100');
    expect(host.textContent).toContain('监控数据采集失败');
    expect(button('立即采集').disabled).toBe(false);
    harness.getOverview.mockResolvedValue(overview('200'));
    button('立即采集').click();
    await flush();
    expect(harness.collect).toHaveBeenCalledTimes(2);
    expect(host.textContent).not.toContain('监控数据采集失败');
    expect(host.querySelector('[data-overview]')?.textContent).toBe('200');
  });

  it('preserves previous results if the refresh after collection fails', async () => {
    const pending = deferred<DatabaseMonitorApi.Overview>();
    harness.collect.mockReturnValueOnce(pending.promise);
    await mountPage();
    harness.getOverview.mockRejectedValueOnce(new Error('unavailable'));
    pending.resolve(overview());
    await flush();
    expect(host.textContent).toContain('监控数据加载失败');
    expect(host.querySelector('[data-overview]')?.textContent).toBe('100');
    expect(host.querySelector('[data-trend]')?.textContent).toBe('7');
    expect(button('立即采集').disabled).toBe(false);
  });

  it('changing the time range reads monitoring data without collecting again', async () => {
    await mountPage();
    button('近15天').click();
    await flush();
    expect(harness.getTrend).toHaveBeenLastCalledWith('9007199254740993', 15);
    expect(harness.collect).toHaveBeenCalledTimes(1);
  });

  it('reads historical monitoring for a disabled connection without collecting', async () => {
    harness.getDatabase.mockResolvedValue({ ...database(), status: 1 });
    await mountPage();
    expect(harness.getOverview).toHaveBeenCalledOnce();
    expect(harness.collect).not.toHaveBeenCalled();
    expect(button('立即采集').disabled).toBe(true);
  });

  it('does not collect or request monitoring for a connection without metadata support', async () => {
    harness.getDatabase.mockResolvedValue({ ...database(), typeCode: 'FTP' });
    await mountPage();
    expect(harness.getOverview).not.toHaveBeenCalled();
    expect(harness.getTrend).not.toHaveBeenCalled();
    expect(harness.collect).not.toHaveBeenCalled();
    expect(host.querySelector('[data-monitor]')).toBeNull();
  });

  it('discards a previous route response without ending the current loading state', async () => {
    const old = deferred<FoundationDatabaseApi.Database>();
    const current = deferred<FoundationDatabaseApi.Database>();
    harness.getDatabase
      .mockReturnValueOnce(old.promise)
      .mockReturnValueOnce(current.promise);
    await mountPage();
    harness.route.params.id = '9007199254740995';
    await flush();
    old.resolve(database('9007199254740993', 'old-route'));
    await flush();
    expect(host.textContent).not.toContain('old-route');
    expect(host.querySelector('[data-loading]')).not.toBeNull();
    expect(harness.getOverview).not.toHaveBeenCalled();
    current.resolve(database('9007199254740995', 'current-route'));
    await flush();
    expect(host.textContent).toContain('current-route');
    expect(harness.getOverview).toHaveBeenCalledWith('9007199254740995');
  });

  it.each(['accessToken', 'refreshToken'] as const)(
    'clears visible details when %s changes and rejects pending old-session monitor responses',
    async (key) => {
      const oldOverview = deferred<DatabaseMonitorApi.Overview>();
      const oldTrend = deferred<DatabaseMonitorApi.Trend>();
      harness.getOverview
        .mockReturnValueOnce(oldOverview.promise)
        .mockResolvedValue(overview('222'));
      harness.getTrend
        .mockReturnValueOnce(oldTrend.promise)
        .mockResolvedValue(trend(15));
      await mountPage();
      const current = deferred<FoundationDatabaseApi.Database>();
      harness.getDatabase.mockReturnValueOnce(current.promise);
      harness.access[key] = '2';
      await flush();
      expect(host.textContent).not.toContain('Example source');
      expect(host.querySelector('[data-monitor]')).toBeNull();
      current.resolve(database('9007199254740993', 'new-session'));
      await flush();
      oldOverview.resolve(overview('111'));
      oldTrend.resolve(trend(7));
      await flush();
      expect(host.textContent).toContain('new-session');
      expect(host.querySelector('[data-overview]')?.textContent).toBe('222');
      expect(host.querySelector('[data-trend]')?.textContent).toBe('15');
    },
  );

  it('does not restore old-session details after a newer session has loaded', async () => {
    const old = deferred<FoundationDatabaseApi.Database>();
    harness.getDatabase
      .mockReturnValueOnce(old.promise)
      .mockResolvedValue(database('9007199254740993', 'new-session'));
    await mountPage();
    harness.access.refreshToken = '2';
    await flush();
    old.resolve(database('9007199254740993', 'old-session'));
    await flush();
    expect(host.textContent).toContain('new-session');
    expect(host.textContent).not.toContain('old-session');
  });

  it('offers a working retry when the detail request fails', async () => {
    harness.getDatabase.mockRejectedValueOnce(new Error('unavailable'));
    await mountPage();
    expect(host.textContent).toContain('连接详情加载失败，请重试。');
    expect(harness.getOverview).not.toHaveBeenCalled();
    button('重试').click();
    await flush();
    expect(host.textContent).toContain('Example source');
    expect(host.textContent).not.toContain('数据库详情加载失败');
  });

  it('does not request detail or monitoring without query permission', async () => {
    harness.access.codes = [];
    await mountPage();
    expect(host.textContent).toContain('暂无连接查询权限');
    expect(harness.getDatabase).not.toHaveBeenCalled();
    expect(harness.getOverview).not.toHaveBeenCalled();
    expect(harness.getTrend).not.toHaveBeenCalled();
    expect(harness.collect).not.toHaveBeenCalled();
  });

  it.each(['bad', ['1'], 9_007_199_254_740_992])(
    'does not request an invalid route ID %s',
    async (id) => {
      harness.route.params.id = id;
      await mountPage();
      expect(host.textContent).toContain('无效的连接编号');
      expect(harness.getDatabase).not.toHaveBeenCalled();
    },
  );

  it.each(['resolve', 'reject'] as const)(
    'prevents old-session collection %s from updating or unlocking the new session',
    async (outcome) => {
      const oldCollect = deferred<DatabaseMonitorApi.Overview>();
      const currentCollect = deferred<DatabaseMonitorApi.Overview>();
      harness.collect
        .mockReturnValueOnce(oldCollect.promise)
        .mockReturnValueOnce(currentCollect.promise);
      await mountPage();
      expect(harness.collect).toHaveBeenCalledWith('9007199254740993');
      harness.access.refreshToken = '2';
      await flush();
      const overviewCalls = harness.getOverview.mock.calls.length;
      if (outcome === 'resolve') oldCollect.resolve(overview('111'));
      else oldCollect.reject(new Error('old-session-failure'));
      await flush();
      expect(harness.getOverview).toHaveBeenCalledTimes(overviewCalls);
      expect(host.textContent).not.toContain('监控数据采集失败');
      expect(button('立即采集').disabled).toBe(true);
      currentCollect.resolve(overview());
      await flush();
      expect(button('立即采集').disabled).toBe(false);
    },
  );

  it('does not refresh after an automatic collection completes on an unmounted page', async () => {
    const pending = deferred<DatabaseMonitorApi.Overview>();
    harness.collect.mockReturnValueOnce(pending.promise);
    await mountPage();
    app?.unmount();
    app = undefined;
    const overviewCalls = harness.getOverview.mock.calls.length;
    pending.resolve(overview());
    await flush();
    expect(harness.getOverview).toHaveBeenCalledTimes(overviewCalls);
  });
});
