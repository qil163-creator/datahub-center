import type { MarketApi } from '#/api/asset/market';

import { describe, expect, it } from 'vitest';

import { approvalStatusDisplay } from './approval-status';

describe('original approval evidence display', () => {
  it.each<{
    label: string;
    status: MarketApi.DeliveryApprovalStatus;
    type: string;
  }>([
    { status: 'MATCHED', label: '匹配', type: 'info' },
    { status: 'MISSING', label: '缺失', type: 'warning' },
    { status: 'CONFLICT', label: '冲突', type: 'error' },
    { status: 'UNVERIFIABLE', label: '无法核验', type: 'warning' },
    { status: 'NOT_APPLICABLE', label: '不适用', type: 'info' },
  ])('renders $status without implying access', ({ status, label, type }) => {
    const display = approvalStatusDisplay(status);
    expect(display.type).toBe(type);
    expect(display.message).toContain(`原批准凭证核对：${label}`);
  });

  it.each(
    [
      undefined,
      null,
      '',
      'FUTURE_STATUS',
      'APPLIED',
      'matched',
      ' MATCHED ',
      'constructor',
      'toString',
      '__proto__',
      1,
      true,
      ['MATCHED'],
      { approvalStatus: 'MATCHED' },
    ].map((value) => ({ value })),
  )('fails closed for missing or unknown status $value', ({ value }) => {
    expect(approvalStatusDisplay(value)).toEqual(
      approvalStatusDisplay('UNVERIFIABLE'),
    );
    expect(approvalStatusDisplay(value).message).toContain(
      '不能据此确认原批准',
    );
  });

  it('does not present a matching approval as current access or a missing one as rejection', () => {
    expect(approvalStatusDisplay('MATCHED').message).toContain(
      '不代表当前可访问',
    );
    expect(approvalStatusDisplay('MISSING').message).toContain(
      '未找到正式批准凭证',
    );
    expect(approvalStatusDisplay('MISSING').message).not.toContain('未批准');
    expect(approvalStatusDisplay('CONFLICT').message).toContain(
      '已执行回执不能消除该冲突',
    );
  });
});
