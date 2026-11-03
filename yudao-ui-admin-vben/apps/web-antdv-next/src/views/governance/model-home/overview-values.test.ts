import { describe, expect, it } from 'vitest';

import {
  displayCount,
  distributionPercent,
  distributionTotal,
} from './overview-values';

describe('建模总览计数', () => {
  it('区分不可用与真实零值，保持大整数完整', () => {
    expect(displayCount(null)).toBe('—');
    expect(displayCount(undefined)).toBe('—');
    expect(displayCount(0)).toBe('0');
    expect(displayCount('9007199254740993')).toBe('9,007,199,254,740,993');
  });

  it('汇总混合传输类型，并计算有界的展示占比', () => {
    const total = distributionTotal([
      { key: 'a', name: 'A', code: 'A', count: '9007199254740993' },
      { key: 'b', name: 'B', code: 'B', count: 1 },
    ]);
    expect(total).toBe('9007199254740994');
    expect(distributionPercent(1, 3)).toBe(33.3);
    expect(distributionPercent(2, 3)).toBe(66.7);
    expect(distributionPercent(0, 0)).toBe(0);
    expect(distributionPercent(2, 1)).toBe(100);
  });
});
