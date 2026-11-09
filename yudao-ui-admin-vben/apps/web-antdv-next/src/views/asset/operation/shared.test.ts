import { describe, expect, it } from 'vitest';

import {
  categoryBar,
  distributionPie,
  formatIdle,
  formatRate,
  formatScore,
  SUGGESTIONS,
  trendOption,
  VALUE_LEVELS,
} from './shared';

describe('asset operation shared', () => {
  it('formats scores, rates and idle days', () => {
    expect(formatScore(null)).toBe('未评估');
    expect(formatScore(72)).toBe('72.0');
    expect(formatRate(undefined)).toBe('-');
    expect(formatRate(33.333)).toBe('33.3%');
    expect(formatIdle(0)).toBe('今天有使用');
    expect(formatIdle(90)).toBe('90 天');
  });

  it('labels every backend level and suggestion', () => {
    expect(Object.keys(VALUE_LEVELS).toSorted()).toEqual([
      'HIGH',
      'IDLE',
      'LOW',
      'MEDIUM',
    ]);
    expect(Object.keys(SUGGESTIONS).toSorted()).toEqual([
      'COMPLETE_METADATA',
      'IMPROVE_QUALITY',
      'KEEP',
      'PROMOTE',
      'RETIRE_REVIEW',
    ]);
  });

  it('drops empty slices from distribution pies', () => {
    const option = distributionPie({ HIGH: 2, IDLE: 0, LOW: 1 }, VALUE_LEVELS);
    const series = (option.series as any[])[0];
    expect(series.data).toEqual([
      { name: '高价值', value: 2 },
      { name: '低价值', value: 1 },
    ]);
  });

  it('limits the category chart to twelve categories', () => {
    const items = Array.from({ length: 20 }, (_, index) => ({
      categoryName: `类目${index}`,
      count: 20 - index,
      idleCount: 1,
      publishedCount: 1,
    }));
    const option = categoryBar(items);
    expect((option.xAxis as any).data).toHaveLength(12);
  });

  it('keeps score gaps as nulls on the trend lines', () => {
    const option = trendOption([
      { date: '2026-11-08', browseCount: 1, usageCount: 0, applyCount: 0 },
      {
        date: '2026-11-09',
        browseCount: 2,
        usageCount: 1,
        applyCount: 1,
        heatScore: 40,
        valueScore: 55,
      },
    ]);
    const series = option.series as any[];
    expect((option.xAxis as any).data).toEqual(['11-08', '11-09']);
    expect(series.find((item) => item.name === '热度分').data).toEqual([
      null,
      40,
    ]);
  });
});
