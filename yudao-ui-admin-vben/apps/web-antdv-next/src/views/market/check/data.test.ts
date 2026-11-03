import type { MarketCheckApi } from '#/api/asset/market/check';

import { describe, expect, it } from 'vitest';

import {
  canReviewMarketCheck,
  formatMarketCheckTime,
  marketCheckPeriod,
  marketCheckReviewError,
  marketCheckRowKey,
  marketCheckStatusLabel,
  marketCheckTime,
} from './data';

function ticket(
  overrides: Partial<MarketCheckApi.Ticket> = {},
): MarketCheckApi.Ticket {
  return {
    id: '9223372036854775807',
    origin: 'QDATA',
    displayId: '21',
    martName: '河流维表',
    applicantKey: 'QDATA:user',
    applicantName: '申请人',
    applyType: 1,
    status: '2',
    revision: 0,
    canApprove: true,
    canReject: true,
    canView: true,
    ...overrides,
  };
}

describe('审批工单展示与操作边界', () => {
  it('来源时间戳固定按上海时区回显，无时区原始文案保持不变', () => {
    expect(marketCheckTime(1_792_666_331_000)).toBe('2026-10-22 18:52');
    expect(formatMarketCheckTime('1785808775000')).toBe('2026-08-04 09:59:35');
    expect(formatMarketCheckTime('2026-10-22T10:52:11Z')).toBe('2026-10-22 18:52:11');
    expect(formatMarketCheckTime('2026-10-22 22:25:58')).toBe('2026-10-22 22:25:58');
    expect(formatMarketCheckTime(null)).toBe('-');
    expect(formatMarketCheckTime('未知时间')).toBe('未知时间');
  });
  it('审批与驳回理由去除空白后必填，最多256个字符', () => {
    expect(marketCheckReviewError('  \n  ', '3')).toBe('请输入审批意见');
    expect(marketCheckReviewError('  \t  ', '4')).toBe('请输入驳回理由');
    expect(marketCheckReviewError(`  ${'意'.repeat(256)}  `, '3')).toBe('');
    expect(marketCheckReviewError('意'.repeat(257), '3')).toBe(
      '审批意见不能超过256个字符',
    );
  });
  it('同号的本地与来源工单不共享表格身份', () => {
    expect(marketCheckRowKey(ticket())).toBe('QDATA:9223372036854775807');
    expect(marketCheckRowKey(ticket({ origin: 'NATIVE' }))).not.toBe(
      marketCheckRowKey(ticket()),
    );
  });

  it('同时满足待审批状态及服务端能力才能审批，未知状态不放行', () => {
    expect(canReviewMarketCheck(ticket(), '3')).toBe(true);
    expect(canReviewMarketCheck(ticket({ status: '3' }), '3')).toBe(false);
    expect(canReviewMarketCheck(ticket({ canApprove: false }), '3')).toBe(
      false,
    );
    expect(canReviewMarketCheck(ticket({ canReject: false }), '4')).toBe(false);
    expect(canReviewMarketCheck(ticket({ status: 'UNKNOWN' }), '3')).toBe(
      false,
    );
  });

  it('优先保留服务端状态文案，未知状态明确回显', () => {
    expect(
      marketCheckStatusLabel(ticket({ status: '3', statusLabel: '已通过' })),
    ).toBe('已通过');
    expect(marketCheckStatusLabel(ticket({ status: 'UNKNOWN' }))).toBe(
      'UNKNOWN',
    );
    expect(marketCheckPeriod(ticket())).toBe('长期有效');
    expect(
      marketCheckPeriod(
        ticket({
          applyType: 2,
          startTime: '2026-10-22 12:00:00',
          endTime: '2026-10-23 12:00:00',
        }),
      ),
    ).toBe('2026-10-22 12:00 ~ 2026-10-23 12:00');
  });
});
