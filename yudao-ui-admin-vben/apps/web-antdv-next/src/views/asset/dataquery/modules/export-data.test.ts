import { describe, expect, it } from 'vitest';

import { canDownload, exportFormError, exportStatusView } from './export-data';

describe('data export helpers', () => {
  it('allows downloads only for approved, unexpired requests', () => {
    const now = new Date(2026, 10, 12, 10, 0).getTime();
    const base = { id: 1, datasourceId: 1, reason: 'r', rowLimit: 10 };
    expect(
      canDownload({ ...base, status: 'APPROVED', expireTime: now + 1000 }, now),
    ).toBe(true);
    expect(
      canDownload({ ...base, status: 'APPROVED', expireTime: now - 1 }, now),
    ).toBe(false);
    expect(
      canDownload(
        { ...base, status: 'DOWNLOADED', expireTime: now + 1000 },
        now,
      ),
    ).toBe(false);
    expect(canDownload({ ...base, status: 'APPROVED' }, now)).toBe(false);
  });

  it('validates the application form', () => {
    expect(exportFormError('', 'r', 10)).toContain('SELECT');
    expect(exportFormError('select 1', ' ', 10)).toBe('请填写申请原因');
    expect(exportFormError('select 1', 'r', 1001)).toContain('1-1000');
    expect(exportFormError('select 1', 'r', 1000)).toBeUndefined();
    expect(exportStatusView('PENDING').label).toBe('审批中');
    expect(exportStatusView('NEW').label).toBe('NEW');
  });
});
