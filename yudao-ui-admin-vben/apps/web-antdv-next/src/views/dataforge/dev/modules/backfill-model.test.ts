import dayjs from 'dayjs';
import { describe, expect, it } from 'vitest';

import {
  BACKFILL_ITEM_STATUS,
  BACKFILL_STATUS,
  backfillDays,
  backfillProgress,
} from './backfill-model';

describe('backfill model', () => {
  it('counts both ends of the business date range', () => {
    expect(backfillDays(dayjs('2026-10-01'), dayjs('2026-10-01'))).toBe(1);
    expect(backfillDays(dayjs('2026-10-01'), dayjs('2026-10-31'))).toBe(31);
    expect(backfillDays(dayjs('2025-11-09'), dayjs('2026-11-09'))).toBe(366);
  });

  it('treats success, failure and cancellation as finished dates', () => {
    expect(
      backfillProgress({
        totalCount: 4,
        successCount: 1,
        failedCount: 1,
        cancelledCount: 0,
      }),
    ).toBe(50);
    expect(
      backfillProgress({
        totalCount: 3,
        successCount: 1,
        failedCount: 0,
        cancelledCount: 2,
      }),
    ).toBe(100);
    expect(
      backfillProgress({
        totalCount: 0,
        successCount: 0,
        failedCount: 0,
        cancelledCount: 0,
      }),
    ).toBe(0);
  });

  it('labels every backend status', () => {
    expect(Object.keys(BACKFILL_STATUS).toSorted()).toEqual([
      'CANCELLED',
      'FAILED',
      'RUNNING',
      'SUCCESS',
    ]);
    expect(Object.keys(BACKFILL_ITEM_STATUS).toSorted()).toEqual([
      'CANCELLED',
      'FAILED',
      'PENDING',
      'RUNNING',
      'SUCCESS',
    ]);
  });
});
