import type { App, Component } from 'vue';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

import { getTrendChartOptions } from '../chart-options';
import RankChart from './rank-chart.vue';
import TrendChart from './trend-chart.vue';

const { renderEcharts } = vi.hoisted(() => ({ renderEcharts: vi.fn() }));

vi.mock('@vben/plugins/echarts', () => ({
  EchartsUI: defineComponent({ setup: () => () => h('div', { class: 'chart' }) }),
  useEcharts: () => ({ renderEcharts }),
}));

vi.mock('antdv-next', () => {
  const Empty = Object.assign(
    defineComponent({
      props: { description: String },
      setup: (props) => () => h('div', props.description),
    }),
    { PRESENTED_IMAGE_SIMPLE: 'simple' },
  );
  return {
    Card: defineComponent({ setup: (_, { slots }) => () => h('section', [slots.extra?.(), slots.default?.()]) }),
    Empty,
    RadioButton: defineComponent({ setup: (_, { slots }) => () => h('span', slots.default?.()) }),
    RadioGroup: defineComponent({
      emits: ['update:value', 'change'],
      setup: (_, { emit }) => () => h('button', { onClick: () => { emit('update:value', 7); emit('change'); } }, '切换7天'),
    }),
  };
});

let app: App | undefined;
let container: HTMLDivElement | undefined;

afterEach(() => {
  app?.unmount();
  container?.remove();
  renderEcharts.mockClear();
});

function mount(component: Component, props: object) {
  container = document.createElement('div');
  document.body.append(container);
  app = createApp({ setup: () => () => h(component, props) });
  app.mount(container);
  return container;
}

describe('概览图表', () => {
  it('趋势按实际调用量自动缩放，并保持整数刻度', () => {
    const options = getTrendChartOptions([
      { count: 0, date: '10-01' },
      { count: 2, date: '10-02' },
    ]);
    expect(options.yAxis).toMatchObject({ min: 0, minInterval: 1 });
    expect(options.series).toMatchObject([{ data: [0, 2] }]);
  });

  it('所选时间区间的全零提示随数据和区间变化', async () => {
    const props = reactive({ trend: [{ count: 2, date: '2026-10-01' }, ...Array.from({ length: 29 }, (_, i) => ({ count: 0, date: `2026-10-${String(i + 2).padStart(2, '0')}` }))] });
    const el = mount(TrendChart, props);
    expect(el.textContent).not.toContain('该时段暂无调用');
    el.querySelector('button')?.click();
    await nextTick();
    expect(el.textContent).toContain('该时段暂无调用');
    expect(renderEcharts).toHaveBeenLastCalledWith(expect.objectContaining({
      series: [expect.objectContaining({ data: [0, 0, 0, 0, 0, 0, 0] })],
    }));
    props.trend = [{ count: 1, date: '2026-10-27' }];
    await nextTick();
    expect(el.textContent).not.toContain('该时段暂无调用');
    props.trend = [];
    await nextTick();
    expect(el.textContent).toContain('该时段暂无调用');
  });

  it('排行显示独立的同名 API、完整名称提示及对应数值', async () => {
    const name = '一个很长的 API 名称，需要支持查看完整内容';
    const props = reactive({ list: [{ apiId: '1', name, value: 2 }, { apiId: '2', name, value: 1 }], title: '排行' });
    const el = mount(RankChart, props);
    const rows = el.querySelectorAll('.rank-item');
    expect(rows).toHaveLength(2);
    expect(rows[0]?.querySelector('.rank-item__name')?.getAttribute('title')).toBe(name);
    expect(rows[0]?.textContent).toContain('2 次');
    expect(rows[1]?.textContent).toContain('1 次');
    expect(rows[0]?.querySelector('.rank-item__bar')?.getAttribute('style')).toContain('100%');
    expect(rows[1]?.querySelector('.rank-item__bar')?.getAttribute('style')).toContain('50%');
    props.list = [];
    await nextTick();
    expect(el.textContent).toContain('暂无调用记录');
  });
});
