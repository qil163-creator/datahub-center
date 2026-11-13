import { describe, expect, it } from 'vitest';

import { nextWidgetKey, statValue, widgetError } from './board';

const widget = (overrides: Record<string, unknown> = {}) =>
  ({
    key: 'w1',
    title: '销售额',
    chartType: 'TABLE',
    query: { indicatorIds: ['1'] },
    ...overrides,
  }) as any;

describe('indicator board helpers', () => {
  it('allocates unique widget keys', () => {
    expect(nextWidgetKey([])).toBe('w1');
    expect(nextWidgetKey([widget({ key: 'w2' })])).toBe('w3');
    expect(nextWidgetKey([widget({ key: 'w1' }), widget({ key: 'w3' })])).toBe(
      'w4',
    );
  });

  it('validates widgets', () => {
    expect(widgetError(widget())).toBeUndefined();
    expect(widgetError(widget({ title: ' ' }))).toBe('请填写组件标题');
    expect(widgetError(widget({ query: { indicatorIds: [] } }))).toContain(
      '1-10',
    );
    expect(widgetError(widget({ chartType: 'LINE' }))).toContain('时间粒度');
  });

  it('keeps decimal strings for stat cards', () => {
    expect(
      statValue({
        columns: [
          { key: 'd', kind: 'DIMENSION', title: '地区' },
          { key: 'i', kind: 'INDICATOR', title: 'GMV' },
        ],
        rows: [{ d: '华东', i: '12345678901234567.89' }],
        truncated: false,
        calibers: [],
      } as any),
    ).toEqual({ title: 'GMV', value: '12345678901234567.89' });
    expect(statValue(undefined).value).toBe('-');
  });
});
