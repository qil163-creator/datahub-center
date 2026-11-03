import { beforeEach, describe, expect, it, vi } from 'vitest';

import { requestClient } from '#/api/request';

import {
  authorizeWorkOrderNotifyRetry,
  getWorkOrderNotifyDetail,
  getWorkOrderNotifyPage,
  reviewWorkOrderNotify,
} from './workorder-notify';

vi.mock('#/api/request', () => ({
  requestClient: { get: vi.fn(), post: vi.fn() },
}));
beforeEach(() => vi.clearAllMocks());
describe('通知人工处置API契约', () => {
  it('分页和详情不丢失工单及通知编号', async () => {
    const params = { workOrderId: '9007199254740993', pageNo: 1, pageSize: 10 };
    await getWorkOrderNotifyPage(params);
    await getWorkOrderNotifyDetail('9007199254740995');
    expect(requestClient.get).toHaveBeenCalledWith(
      '/governance/quality-work-order/notify-page',
      { params },
    );
    expect(requestClient.get).toHaveBeenCalledWith(
      '/governance/quality-work-order/notify-detail',
      { params: { id: '9007199254740995' } },
    );
  });
  it('核验与授权重投是两个独立的幂等CAS请求', async () => {
    const review = {
      id: '9007199254740995',
      expectedResolutionVersion: 0,
      requestId: 'review-key',
      outcome: 'DELIVERED' as const,
      reason: '人工依据',
      operatorMessageId: '9007199254740997',
    };
    const retry = {
      id: review.id,
      expectedResolutionVersion: 1,
      requestId: 'retry-key',
      decisionId: '9007199254740999',
      reason: '人工授权',
    };
    await reviewWorkOrderNotify(review);
    await authorizeWorkOrderNotifyRetry(retry);
    expect(requestClient.post).toHaveBeenCalledWith(
      '/governance/quality-work-order/notify-review',
      review,
    );
    expect(requestClient.post).toHaveBeenCalledWith(
      '/governance/quality-work-order/notify-authorize-retry',
      retry,
    );
  });
});
