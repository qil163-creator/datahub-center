import type { App, PropType } from 'vue';

import type { DataforgeEtlApi } from '#/api/dataforge/etl';

import {
  Comment,
  createApp,
  defineComponent,
  h,
  nextTick,
  reactive,
} from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

import RuntimeObservation from './runtime-observation.vue';

vi.mock('antdv-next', () => ({
  Alert: defineComponent({
    props: ['message'],
    setup: (props) => () => h('p', { role: 'alert' }, props.message),
  }),
  Tag: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('span', slots.default?.()),
  }),
  Table: defineComponent({
    props: {
      dataSource: {
        type: Array as PropType<Record<string, unknown>[]>,
        default: () => [],
      },
      columns: {
        type: Array as PropType<{ dataIndex?: string; key: string }[]>,
        default: () => [],
      },
    },
    // 与真实表格一致：插槽只渲染出 v-if 注释节点时回退到列的默认文本
    setup: (props, { slots }) => () =>
      h(
        'table',
        props.dataSource.map((record) =>
          h(
            'tr',
            props.columns.map((column) => {
              const rendered = (
                slots.bodyCell?.({ column, record }) ?? []
              ).filter((vnode) => vnode.type !== Comment);
              return h(
                'td',
                { 'data-key': column.key },
                rendered.length > 0
                  ? rendered
                  : String(record[column.dataIndex ?? ''] ?? ''),
              );
            }),
          ),
        ),
      ),
  }),
}));

const nodeRecords: DataforgeEtlApi.NodeRecord[] = [
  { nodeNo: 'read', nodeName: '读取源表' },
  { nodeNo: 'write', nodeName: '写入目标' },
];

function runningMetrics(): DataforgeEtlApi.RuntimeMetrics {
  return {
    elapsedMillis: 10_000,
    finished: false,
    transferredRows: 1500,
    transferredBytes: 1_572_864,
    bufferedBytes: 0,
    nodes: [],
    edges: [
      {
        sourceNo: 'read',
        targetNo: 'write',
        queued: 950,
        capacity: 1000,
        delivered: 1500,
        blockedMillis: 6000,
        starvedMillis: 0,
      },
    ],
    series: [
      {
        elapsedMillis: 4000,
        transferredRows: 0,
        transferredBytes: 0,
        bufferedBytes: 0,
      },
      {
        elapsedMillis: 7000,
        transferredRows: 900,
        transferredBytes: 0,
        bufferedBytes: 0,
      },
      {
        elapsedMillis: 10_000,
        transferredRows: 1500,
        transferredBytes: 0,
        bufferedBytes: 0,
      },
    ],
  };
}

let app: App | undefined;
let host: HTMLDivElement;
async function mount(metrics: DataforgeEtlApi.RuntimeMetrics | null) {
  host = document.createElement('div');
  document.body.append(host);
  const props = reactive({ metrics, nodeRecords });
  app = createApp({ render: () => h(RuntimeObservation, props) });
  app.mount(host);
  await nextTick();
}
function text(selector: string) {
  return host.querySelector(selector)?.textContent?.replaceAll(/\s+/g, ' ').trim();
}

afterEach(() => {
  app?.unmount();
  app = undefined;
  host?.remove();
});

describe('ETL 运行观测面板', () => {
  it('展示运行中采样、瓶颈提示、吞吐趋势与连线背压', async () => {
    await mount(runningMetrics());
    expect(text('.runtime-observation__head')).toContain('运行中采样');
    expect(text('.runtime-observation__head')).toContain(
      '已运行 10.0 s · 累计产生 1500 行（1.5 MB）',
    );
    expect(text('[role="alert"]')).toBe(
      '下游【写入目标】处理较慢：上游【读取源表】在该连线累计等待 6.0 s，占已运行时间 60%',
    );
    expect(
      host.querySelector('polyline')?.getAttribute('points')?.split(' '),
    ).toHaveLength(2);
    expect(text('.runtime-observation__trend')).toContain(
      '峰值 300.0 · 最近 200.0',
    );
    expect(text('td[data-key="edge"]')).toBe('读取源表 → 写入目标');
    expect(text('td[data-key="queue"]')).toBe('950/1000');
    expect(text('td[data-key="blocked"]')).toBe('6.0 s（60%）');
    expect(text('td[data-key="starved"]')).toBe('0 ms（0%）');
    expect(text('td[data-key="delivered"]')).toBe('1500');
  });

  it('最终快照采样不足时不画趋势，没有明显背压时不提示瓶颈', async () => {
    await mount({
      ...runningMetrics(),
      finished: true,
      edges: [
        {
          sourceNo: 'read',
          targetNo: 'write',
          queued: 0,
          capacity: 1000,
          delivered: 1500,
          blockedMillis: 100,
          starvedMillis: 2000,
        },
      ],
      series: runningMetrics().series.slice(0, 1),
    });
    expect(text('.runtime-observation__head')).toContain('最终快照');
    expect(host.querySelector('[role="alert"]')).toBeNull();
    expect(host.querySelector('polyline')).toBeNull();
    expect(text('.runtime-observation__trend')).toContain('采样点不足，暂无趋势');
    expect(text('td[data-key="starved"]')).toBe('2.0 s（20%）');
  });

  it('没有观测快照时明确为未记录，不显示为零流量', async () => {
    await mount(null);
    expect(host.querySelector('.runtime-observation')).toBeNull();
    expect(host.textContent).toContain('该运行未记录运行观测');
  });
});
