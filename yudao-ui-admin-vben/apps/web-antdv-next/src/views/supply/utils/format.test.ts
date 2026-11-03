import dayjs from 'dayjs';
import { describe, expect, it } from 'vitest';

import {
  formatSupplyDate,
  formatSupplyDateTime,
  formatSupplyDateTimeRange,
  toSupplyDateTimeMillis,
} from './format';

describe('formatSupplyDateTime', () => {
  it('formats local datetime strings with seconds', () => {
    expect(formatSupplyDateTime('2026-09-16 12:34:56')).toBe(
      '2026-09-16 12:34:56',
    );
  });

  it('pads date-only values to midnight datetime', () => {
    expect(formatSupplyDateTime('2026-09-16')).toBe('2026-09-16 00:00:00');
    expect(formatSupplyDate('2026-09-16')).toBe('2026-09-16');
  });

  it('formats millisecond timestamps', () => {
    const millis = dayjs('2026-09-16 12:34:56').valueOf();
    expect(formatSupplyDateTime(millis)).toBe('2026-09-16 12:34:56');
    expect(formatSupplyDateTime(String(millis))).toBe('2026-09-16 12:34:56');
  });

  it('returns empty for blank or invalid values', () => {
    expect(formatSupplyDateTime()).toBe('');
    expect(formatSupplyDateTime(null)).toBe('');
    expect(formatSupplyDateTime('not-a-date')).toBe('');
  });

  it('joins a datetime range', () => {
    expect(
      formatSupplyDateTimeRange('2026-09-16 00:00:00', '2026-09-16 23:59:59'),
    ).toBe('2026-09-16 00:00:00 ~ 2026-09-16 23:59:59');
  });

  it('converts formatted datetime back to millis', () => {
    const millis = dayjs('2026-09-16 12:34:56').valueOf();
    expect(toSupplyDateTimeMillis('2026-09-16 12:34:56')).toBe(millis);
    expect(toSupplyDateTimeMillis(millis)).toBe(millis);
    expect(toSupplyDateTimeMillis('')).toBeUndefined();
  });
});
