/* eslint-disable vue/one-component-per-file -- Isolated chart and control doubles exercise monitor data presentation. */
import type { App, SetupContext } from 'vue';

import type { FoundationDatabaseApi } from '#/api/dataforge/database';
import type { DatabaseMonitorApi } from '#/api/dataforge/database/monitor';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import MonitorPanel from './monitor-panel.vue';

const harness = vi.hoisted(() => ({ renderChart: vi.fn() }));

vi.mock('@vben/icons', () => ({ IconifyIcon: () => null }));
vi.mock('@vben/utils', () => ({
  formatDateTime: (value: number) => `time:${value}`,
}));
vi.mock('@vben/plugins/echarts', () => ({
  EchartsUI: defineComponent({
    setup: () => () => h('div', { class: 'chart' }),
  }),
  useEcharts: () => ({ renderEcharts: harness.renderChart }),
}));
vi.mock('antdv-next', () => {
  const block = (_: unknown, { slots }: SetupContext) =>
    h('div', slots.default?.());
  return {
    Descriptions: block,
    DescriptionsItem: block,
    Tag: block,
    RadioButton: () => null,
    Button: defineComponent({
      props: { disabled: Boolean, loading: Boolean },
      setup:
        (props, { slots }) =>
        () =>
          h(
            'button',
            {
              disabled: props.disabled || props.loading,
              'aria-busy': props.loading,
            },
            slots.default?.(),
          ),
    }),
    RadioGroup: defineComponent({
      props: { value: { type: Number, default: 7 } },
      emits: ['update:value'],
      setup:
        (props, { emit }) =>
        () =>
          h(
            'select',
            {
              value: props.value,
              onChange: (event: Event) =>
                emit(
                  'update:value',
                  Number((event.target as HTMLSelectElement).value),
                ),
            },
            [7, 15, 30].map((days) =>
              h('option', { value: days }, String(days)),
            ),
          ),
    }),
  };
});

const database: FoundationDatabaseApi.Database = {
  id: '9007199254740993',
  connectionStatus: 1,
  lastTestTime: 1,
  status: 0,
  userName: '',
  password: '',
};
function makeOverview(): DatabaseMonitorApi.Overview {
  return {
    connectionStatus: 1,
    lastCollectedTime: 2,
    responseTimeMs: 25,
    todayOutputRows: '9007199254740993',
    todayInputRows: '0',
    totalTransferRows: '9007199254740993',
    pendingAlarmCount: 0,
    averageResponseTimeMs: 25,
    readSpeed: 12,
    writeSpeed: 8,
    throughputUnit: 'rows/s',
    capacityUsed: '9007199254740993',
    capacityTotal: null,
    supportedMetrics: ['CONNECTION_STATUS', 'RESPONSE_TIME'],
    logCoverage: '按同步任务执行记录统计',
  };
}
function makeTrend(): DatabaseMonitorApi.Trend {
  return {
    days: 7,
    points: [
      {
        date: '2026-10-12',
        responseTimeMs: null,
        readSpeed: null,
        writeSpeed: null,
        outputRows: '0',
        inputRows: '0',
        onlineRate: null,
      },
      {
        date: '2026-10-13',
        responseTimeMs: 25,
        readSpeed: 12,
        writeSpeed: 8,
        outputRows: '12',
        inputRows: '8',
        onlineRate: 100,
      },
    ],
    supportedMetrics: ['CONNECTION_STATUS', 'RESPONSE_TIME'],
  };
}

let app: App;
let host: HTMLDivElement;
const rangeChange = vi.fn();
const collect = vi.fn();
async function settle() {
  await nextTick();
  await nextTick();
  await nextTick();
}
async function mountPanel(
  overview?: DatabaseMonitorApi.Overview,
  trend?: DatabaseMonitorApi.Trend,
  pending: { collecting?: boolean; loading?: boolean } = {},
) {
  const props = reactive({
    database,
    overview,
    trend,
    collecting: pending.collecting ?? false,
    loading: pending.loading ?? false,
    onRangeChange: rangeChange,
    onCollect: collect,
  });
  host = document.createElement('div');
  app = createApp({ render: () => h(MonitorPanel, props) });
  app.mount(host);
  await settle();
  return props;
}
beforeEach(() => {
  vi.clearAllMocks();
  harness.renderChart.mockResolvedValue(null);
});
afterEach(() => app?.unmount());

describe('database monitoring panel', () => {
  it('keeps large counters and capacity lossless and graphs only real samples', async () => {
    await mountPanel(makeOverview(), makeTrend());
    expect(host.textContent).toContain('9,007,199,254,740,993 条');
    expect(host.textContent).toContain('9,007,199,254,740,993 B');
    expect(host.querySelectorAll('.chart')).toHaveLength(3);
    const options = harness.renderChart.mock.calls.map(([option]) => option);
    for (const [name, value] of [
      ['读取速度', 12],
      ['写入速度', 8],
      ['响应时间', 25],
    ]) {
      expect(options).toContainEqual(
        expect.objectContaining({
          series: [
            expect.objectContaining({
              name,
              data: [null, value],
              connectNulls: false,
            }),
          ],
        }),
      );
    }
    host.querySelector('button')!.click();
    expect(collect).toHaveBeenCalledOnce();
  });

  it('does not turn missing metrics into zero or generated charts', async () => {
    await mountPanel();
    expect(host.textContent).toContain('在线');
    expect(host.textContent).toContain('暂无采集数据');
    expect(host.textContent).not.toContain('0 条');
    expect(host.querySelectorAll('.chart')).toHaveLength(0);
    expect(harness.renderChart).not.toHaveBeenCalled();
  });

  it('shows collecting and loading states before a completed empty result and prevents repeated collection', async () => {
    const props = await mountPanel(undefined, undefined, {
      collecting: true,
      loading: true,
    });
    const button = host.querySelector('button')!;
    expect(host.querySelector('[role="status"]')?.textContent).toContain(
      '正在采集数据…',
    );
    expect(host.textContent).not.toContain('暂无采集数据');
    expect(host.textContent).not.toContain('正在加载数据…');
    expect(button.textContent).toContain('立即采集');
    expect(button.disabled).toBe(true);
    button.click();
    expect(collect).not.toHaveBeenCalled();

    props.collecting = false;
    await settle();
    expect(host.querySelector('[role="status"]')?.textContent).toContain(
      '正在加载数据…',
    );
    expect(host.textContent).not.toContain('暂无采集数据');
    expect(button.disabled).toBe(true);
    button.click();
    expect(collect).not.toHaveBeenCalled();

    props.loading = false;
    await settle();
    expect(host.querySelector('[role="status"]')).toBeNull();
    expect(host.textContent).toContain('暂无采集数据');
    expect(host.textContent).toContain('未提供容量上限');
    expect(button.disabled).toBe(false);
    button.click();
    expect(collect).toHaveBeenCalledOnce();
  });

  it.each(['collecting', 'loading'] as const)(
    'keeps existing metrics and chart instances visible while %s',
    async (state) => {
      const props = await mountPanel(makeOverview(), makeTrend());
      const charts = [...host.querySelectorAll('.chart')];
      props[state] = true;
      await settle();
      expect(host.querySelector('[role="status"]')?.textContent).toContain(
        state === 'collecting' ? '正在采集数据…' : '正在加载数据…',
      );
      expect(host.textContent).toContain('9,007,199,254,740,993 条');
      expect(host.textContent).toContain('9,007,199,254,740,993 B');
      charts.forEach((chart, index) => {
        expect(host.querySelectorAll('.chart')[index]).toBe(chart);
      });
      expect(host.querySelector('button')!.disabled).toBe(true);

      props[state] = false;
      await settle();
      charts.forEach((chart, index) => {
        expect(host.querySelectorAll('.chart')[index]).toBe(chart);
      });
      expect(host.querySelector('[role="status"]')).toBeNull();
      expect(host.querySelector('button')!.disabled).toBe(false);
    },
  );

  it('hides stale range samples until the requested trend arrives', async () => {
    const props = await mountPanel(makeOverview(), makeTrend());
    const select = host.querySelector('select')!;
    select.value = '15';
    select.dispatchEvent(new Event('change'));
    await settle();
    expect(rangeChange).toHaveBeenCalledWith(15);
    expect(host.querySelectorAll('.chart')).toHaveLength(0);
    expect(host.textContent).toContain('近 15 天');
    props.trend = { ...makeTrend(), days: 15 };
    await settle();
    expect(host.querySelectorAll('.chart')).toHaveLength(3);
  });
});
