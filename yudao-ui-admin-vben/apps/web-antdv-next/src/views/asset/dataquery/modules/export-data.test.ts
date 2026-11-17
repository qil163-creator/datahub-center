import { describe, expect, it } from 'vitest';

import { exportFormError, exportStatusView } from './export-data';

describe('data export helpers', () => {
  it('allows downloads only for approved, unexpired requests', () => {
    const now = new Date(2026, 10, 12, 10, 0).getTime();
    for (const expireTime of [now, String(now), new Date(now).toISOString()]) {
      const record = { expireTime, status: 'APPROVED' };
      expect(exportStatusView(record, now - 1)).toMatchObject({
        downloadUntil: now,
        label: '已批准，可下载',
      });
      expect(exportStatusView(record, now)).toEqual({
        color: 'default',
        label: '下载已过期',
      });
    }
    expect(exportStatusView({ status: 'APPROVED' }, now).label).toBe(
      '已批准，下载不可用',
    );
    expect(
      exportStatusView({ expireTime: 'invalid', status: 'APPROVED' }, now)
        .downloadUntil,
    ).toBeUndefined();
    expect(
      exportStatusView({ expireTime: now + 1, status: 'DOWNLOADED' }, now)
        .downloadUntil,
    ).toBeUndefined();
  });

  it('validates the application form', () => {
    expect(exportFormError('', 'r', 10)).toContain('SELECT');
    expect(exportFormError('select 1', ' ', 10)).toBe('请填写申请原因');
    expect(exportFormError('select 1', 'r', 1001)).toContain('1-1000');
    expect(exportFormError('select 1', 'r', 1000)).toBeUndefined();
    expect(exportStatusView({ status: 'PENDING' }).label).toBe('审批中');
    expect(exportStatusView({ status: 'NEW' }).label).toBe('NEW');
  });
});
