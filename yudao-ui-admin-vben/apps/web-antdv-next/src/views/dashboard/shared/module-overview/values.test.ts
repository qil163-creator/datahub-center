import { describe, expect, it } from 'vitest';

import {
  clampRate,
  displayCount,
  formatRate,
  ratioOf,
  relativeTime,
  sharePercent,
  sumCounts,
  toTimestamp,
} from './values';

describe('模块概览数值', () => {
  it('区分不可用与真实零值，保持大整数完整', () => {
    expect(displayCount(undefined)).toBe('—');
    expect(displayCount(null)).toBe('—');
    expect(displayCount(0)).toBe('0');
    expect(displayCount('9007199254740993')).toBe('9,007,199,254,740,993');
    expect(displayCount(1.5)).toBe('1.5');
  });

  it('按整数运算汇总并计算有界占比', () => {
    const total = sumCounts([
      { key: 'a', name: 'A', count: '9007199254740993' },
      { key: 'b', name: 'B', count: 1 },
      { key: 'c', name: 'C' },
    ]);
    expect(total).toBe('9007199254740994');
    expect(sharePercent(1, 3)).toBe(33.3);
    expect(sharePercent(undefined, 3)).toBe(0);
    expect(sharePercent(2, 0)).toBe(0);
    expect(sharePercent(5, 2)).toBe(100);
  });

  it('比率展示区分暂无统计，并钳制到 0~100', () => {
    expect(formatRate(undefined)).toBe('—');
    expect(formatRate(null)).toBe('—');
    expect(formatRate(0)).toBe('0.0%');
    expect(formatRate(120)).toBe('100.0%');
    expect(clampRate(-3)).toBe(0);
    expect(ratioOf(1, 0)).toBeNull();
    expect(ratioOf(1, 4)).toBe(25);
  });

  it('兼容后端时间戳、数字字符串、日期字符串与 Date', () => {
    expect(toTimestamp(1_790_000_000_000)).toBe(1_790_000_000_000);
    expect(toTimestamp('1790000000000')).toBe(1_790_000_000_000);
    expect(toTimestamp(new Date(1_790_000_000_000))).toBe(1_790_000_000_000);
    expect(toTimestamp('2026-11-11 10:00:00')).toBe(
      new Date('2026-11-11T10:00:00').getTime(),
    );
    expect(toTimestamp('')).toBeUndefined();
    expect(toTimestamp('not-a-date')).toBeUndefined();
    expect(toTimestamp(undefined)).toBeUndefined();
  });

  it('相对时间按区间展示，未来时间视为刚刚', () => {
    const now = 1_790_000_000_000;
    expect(relativeTime(now + 5000, now)).toBe('刚刚');
    expect(relativeTime(now - 5 * 60_000, now)).toBe('5 分钟前');
    expect(relativeTime(now - 3 * 3_600_000, now)).toBe('3 小时前');
    expect(relativeTime(now - 2 * 86_400_000, now)).toBe('2 天前');
  });
});
