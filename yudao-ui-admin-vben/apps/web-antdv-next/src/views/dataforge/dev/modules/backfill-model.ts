import type { Dayjs } from 'dayjs';

import type { DataforgeDevBackfillApi } from '#/api/dataforge/dev';

/** 与后端单批次上限一致 */
export const BACKFILL_MAX_DAYS = 366;

export const BACKFILL_STATUS: Record<
  DataforgeDevBackfillApi.Status,
  { color: string; label: string }
> = {
  RUNNING: { color: 'processing', label: '进行中' },
  SUCCESS: { color: 'success', label: '全部成功' },
  FAILED: { color: 'error', label: '失败' },
  CANCELLED: { color: 'default', label: '已取消' },
};

export const BACKFILL_ITEM_STATUS: Record<
  DataforgeDevBackfillApi.ItemStatus,
  { color: string; label: string }
> = {
  PENDING: { color: 'default', label: '待执行' },
  RUNNING: { color: 'processing', label: '执行中' },
  SUCCESS: { color: 'success', label: '成功' },
  FAILED: { color: 'error', label: '失败' },
  CANCELLED: { color: 'default', label: '已取消' },
};

/** 起止均含的天数 */
export function backfillDays(start: Dayjs, end: Dayjs) {
  return end.startOf('day').diff(start.startOf('day'), 'day') + 1;
}

/** 已结束日期（成功、失败、取消）占比，取整百分数 */
export function backfillProgress(
  batch: Pick<
    DataforgeDevBackfillApi.Backfill,
    'cancelledCount' | 'failedCount' | 'successCount' | 'totalCount'
  >,
) {
  if (!batch.totalCount) return 0;
  const done =
    (batch.successCount ?? 0) +
    (batch.failedCount ?? 0) +
    (batch.cancelledCount ?? 0);
  return Math.min(100, Math.round((done * 100) / batch.totalCount));
}
