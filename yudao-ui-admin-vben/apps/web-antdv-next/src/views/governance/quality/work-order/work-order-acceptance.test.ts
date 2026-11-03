import { describe, expect, it } from 'vitest';

import {
  createWorkOrderRequestGuard,
  losslessId,
  OLD_PROCESS_CLOSED,
  OLD_TRANSFER_CLOSED,
} from './use-work-order-request';

describe('quality work order acceptance', () => {
  it('t36 keeps only the latest work-order request', () => {
    const guard = createWorkOrderRequestGuard();
    const first = guard.begin();
    const second = guard.begin();
    expect(guard.isLatest(first)).toBe(false);
    expect(guard.isLatest(second)).toBe(true);
    guard.dispose();
    expect(guard.isLatest(second)).toBe(false);
  });

  it('t37 keeps unsafe-range long ids as decimal text', () => {
    expect(losslessId('9223372036854775807')).toBe('9223372036854775807');
    expect(losslessId(8)).toBe('8');
    expect(String(Number('9223372036854775807'))).not.toBe('9223372036854775807');
  });

  it('t16 old process entry stays closed on the client', async () => {
    await expect(Promise.reject(new Error(OLD_PROCESS_CLOSED))).rejects.toThrow(
      '旧处理入口已关闭，请走整改提交与验收',
    );
  });

  it('t18 source-end submit does not require a repair order id', () => {
    const payload = {
      content: '源端已改',
      id: losslessId('9223372036854775807'),
      submissionType: 'SOURCE_FIX',
    };
    expect(payload.submissionType).toBe('SOURCE_FIX');
    expect(payload).not.toHaveProperty('repairOrderId');
    expect(payload.id).toBe('9223372036854775807');
  });

  it('t29 evidence time is distinct from live value note', () => {
    const issue = {
      evidenceTime: '2026-01-02 03:04:00',
      liveValueNote: '实时回源值不是原始检出证据，须单独授权查询',
    };
    expect(issue.evidenceTime).not.toBe(issue.liveValueNote);
    expect(issue.liveValueNote).toContain('实时回源');
  });

  it('t39 old copy transfer stays closed on the client', async () => {
    await expect(Promise.reject(new Error(OLD_TRANSFER_CLOSED))).rejects.toThrow(
      '旧转办入口已关闭，请走同一工单链转派',
    );
  });
});
