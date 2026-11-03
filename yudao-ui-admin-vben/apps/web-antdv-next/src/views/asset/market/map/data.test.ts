import { describe, expect, it } from 'vitest';

import { formatQueryCell } from './data';

describe('formatQueryCell', () => {
  it('formats timestamp and datetime columns as YYYY-MM-DD HH:mm:ss', () => {
    expect(formatQueryCell('2026-09-16T10:20:30', 'timestamp')).toBe(
      '2026-09-16 10:20:30',
    );
    expect(formatQueryCell('2026-09-16 10:20:30.123456', 'datetime')).toBe(
      '2026-09-16 10:20:30',
    );
    expect(formatQueryCell('2026-09-16', 'date')).toBe('2026-09-16 00:00:00');
  });

  it('formats ISO datetime strings even without a type name', () => {
    expect(formatQueryCell('2026-09-16 23:53:00.0')).toBe(
      '2026-09-16 23:53:00',
    );
  });

  it('leaves non-datetime values unchanged', () => {
    expect(formatQueryCell('ORD-2026-09-16')).toBe('ORD-2026-09-16');
    expect(formatQueryCell(42, 'int4')).toBe('42');
    expect(formatQueryCell(null, 'timestamp')).toBe('');
  });
});
