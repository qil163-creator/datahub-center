import { describe, expect, it } from 'vitest';

import { categoryTime } from './reference-data';

describe('安全分类时间显示', () => {
  it.each([
    [undefined, '-'],
    [null, '-'],
    ['', '-'],
    ['2026-11-04 01:35:43', '2026-11-04 01:35'],
    ['2026-11-04T01:35:43.123', '2026-11-04 01:35'],
    ['2026-11-04', '2026-11-04'],
    [0, '1970-01-01 08:00'],
    ['0', '1970-01-01 08:00'],
    ['2026-11-04T00:00:00Z', '2026-11-04 08:00'],
    ['2026-11-04T08:00:00+08:00', '2026-11-04 08:00'],
    ['无效时间', '无效时间'],
    [Number.NaN, 'NaN'],
    [Number.POSITIVE_INFINITY, 'Infinity'],
  ])('格式化 %s 为 %s', (value, expected) => {
    expect(categoryTime(value)).toBe(expected);
  });
});
