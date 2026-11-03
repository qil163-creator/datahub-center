import { beforeEach, describe, expect, it, vi } from 'vitest';

import { requestClient } from '#/api/request';

import * as api from './index';

vi.mock('#/api/request', () => ({
  requestClient: { delete: vi.fn(), get: vi.fn(), post: vi.fn(), put: vi.fn() },
}));

beforeEach(() => vi.clearAllMocks());

describe('market renewal transport', () => {
  it('preserves an unsafe-range ID in the renewal request', async () => {
    const id = '9223372036854775807';
    vi.mocked(requestClient.post).mockResolvedValue('9007199254740993');
    expect(
      await api.createRenewalRequest({
        id,
        applyEndTime: 1_900_000_000_000,
        operationId: 'renew-1',
      }),
    ).toBe('9007199254740993');
    expect(requestClient.post).toHaveBeenCalledWith(
      '/asset/market/apply/renewal-request',
      { applyEndTime: 1_900_000_000_000, id, operationId: 'renew-1' },
    );
  });

  it('keeps the renewal operation id stable across a retry of the same submission', async () => {
    const request = {
      id: '1024',
      applyEndTime: 1_900_000_000_000,
      operationId: 'renew-42',
    };
    await api.createRenewalRequest(request);
    await api.createRenewalRequest(request);
    expect(requestClient.post).toHaveBeenNthCalledWith(
      1,
      '/asset/market/apply/renewal-request',
      request,
    );
    expect(requestClient.post).toHaveBeenNthCalledWith(
      2,
      '/asset/market/apply/renewal-request',
      request,
    );
  });

  it('does not extend the original approval through the retired in-place renew route', () => {
    // 旧 PUT /renew 已由后端拒绝；前端不得再提供延长原批准的入口。
    expect(api).not.toHaveProperty('renewMarketApply');
  });
});

describe('market delivery disposal transport', () => {
  const decisionRequestId = '11111111-1111-1111-1111-111111111111';

  it('always sends the bound review decision with the single disposal request', async () => {
    const request: api.MarketApi.DeliveryDisposalRequest = {
      requestId: '12345678-1234-1234-1234-123456789012',
      operationId: 'original:A',
      action: 'REVOKE',
      reason: '处置原因',
      basis: '处置依据',
      basisReviewRequestId: decisionRequestId,
    };
    await api.recordMarketDeliveryDisposal(request);
    expect(requestClient.post).toHaveBeenCalledWith(
      '/asset/market/apply/delivery-disposal',
      request,
    );
  });

  it('posts the whitelisted batch shape to the dedicated batch endpoint', async () => {
    const request: api.MarketApi.DeliveryDisposalBatchRequest = {
      items: [
        {
          requestId: '12345678-1234-1234-1234-123456789012',
          operationId: 'original:A',
          action: 'REVOKE',
          reason: '处置原因',
          basis: '处置依据',
          basisReviewRequestId: decisionRequestId,
        },
        {
          requestId: '87654321-1234-1234-1234-123456789012',
          operationId: 'original:B',
          action: 'CONFIRM_ORIGINAL',
          reason: '回执匹配',
          basis: '工单引用',
          basisReviewRequestId: '22222222-2222-2222-2222-222222222222',
        },
      ],
    };
    await api.recordMarketDeliveryDisposalBatch(request);
    expect(requestClient.post).toHaveBeenCalledWith(
      '/asset/market/apply/delivery-disposal-batch',
      {
        items: request.items.map((item) => ({
          requestId: item.requestId,
          operationId: item.operationId,
          action: item.action,
          reason: item.reason,
          basis: item.basis,
          basisReviewRequestId: item.basisReviewRequestId,
        })),
      },
    );
  });

  it('passes through per-item success and failure results without losing unsafe-range IDs', async () => {
    const response: api.MarketApi.DeliveryDisposalBatchResponse = {
      items: [
        {
          requestId: '12345678-1234-1234-1234-123456789012',
          operationId: 'original:A',
          success: true,
          result: {
            id: '9223372036854775806',
            requestId: '12345678-1234-1234-1234-123456789012',
            operationId: 'original:A',
            applyId: '9223372036854775807',
            sourceRevision: '9223372036854775804',
            decision: 'REVOKE',
            classification: 'ORIGINAL_APPLIED',
            kind: 'DISPOSAL',
            resultingOperationId: 'asset:asset:apply:9:r3:REVOKE',
            actorUserId: '9',
            reason: '处置原因',
            basis: '处置依据',
            occurredAt: 1_700_000_000_000,
          },
        },
        {
          requestId: '87654321-1234-1234-1234-123456789012',
          operationId: 'original:B',
          success: false,
          result: null,
          errorCode: '1_500_026_030',
          errorMsg: '处置被拒绝',
        },
      ],
    };
    vi.mocked(requestClient.post).mockResolvedValue(response);
    expect(
      await api.recordMarketDeliveryDisposalBatch({
        items: [
          {
            requestId: '12345678-1234-1234-1234-123456789012',
            operationId: 'original:A',
            action: 'REVOKE',
            reason: '处置原因',
            basis: '处置依据',
            basisReviewRequestId: decisionRequestId,
          },
          {
            requestId: '87654321-1234-1234-1234-123456789012',
            operationId: 'original:B',
            action: 'CONFIRM_ORIGINAL',
            reason: '回执匹配',
            basis: '工单引用',
            basisReviewRequestId: '22222222-2222-2222-2222-222222222222',
          },
        ],
      }),
    ).toEqual(response);
    expect(response.items[1]?.errorCode).toBe('1_500_026_030');
    expect(String(response.items[0]?.result?.id)).toBe('9223372036854775806');
  });
});

describe('market delivery redeliver transport', () => {
  const redeliverRequest: api.MarketApi.DeliveryRedeliverRequest = {
    requestId: '12345678-1234-1234-1234-123456789012',
    operationId: 'original:A',
    note: '按原操作重发一次',
    basisReviewRequestId: '11111111-1111-1111-1111-111111111111',
  };

  it('posts exactly the whitelisted four fields to the redeliver endpoint', async () => {
    const extra = {
      ...redeliverRequest,
      applyId: '9223372036854775807',
      tenantId: '1',
    };
    await api.recordMarketDeliveryRedeliver(
      extra as api.MarketApi.DeliveryRedeliverRequest,
    );
    expect(requestClient.post).toHaveBeenCalledWith(
      '/asset/market/apply/redeliver-delivery',
      {
        requestId: redeliverRequest.requestId,
        operationId: redeliverRequest.operationId,
        note: redeliverRequest.note,
        basisReviewRequestId: redeliverRequest.basisReviewRequestId,
      },
    );
  });

  it('passes through the delivery operation receipt without losing unsafe-range IDs', async () => {
    const receipt: api.MarketApi.DeliveryOperation = {
      id: '9223372036854775806',
      operationId: 'original:A',
      applyId: '9223372036854775807',
      action: 'REVOKE',
      opType: 'REVOKE',
      sourceRevision: '9223372036854775804',
      status: 'MANUAL',
      resolutionStatus: 'UNKNOWN',
      protocolVersion: 2,
      attemptCount: 2,
      resultRef: 'asset:asset:apply:9:r3:REVOKE',
      lastError: null,
      updateTime: 1_700_000_000_000,
    };
    vi.mocked(requestClient.post).mockResolvedValue(receipt);
    expect(await api.recordMarketDeliveryRedeliver(redeliverRequest)).toEqual(
      receipt,
    );
    expect(String(receipt.applyId)).toBe('9223372036854775807');
    expect(String(receipt.sourceRevision)).toBe('9223372036854775804');
    expect(receipt.updateTime).toBe(1_700_000_000_000);
  });
});

describe('asset mart transport', () => {
  it('preserves filters and long category ids in the dedicated published listing query', async () => {
    const filters: api.MarketApi.MartPageReq = {
      pageNo: 1,
      pageSize: 8,
      keyword: '订单',
      assetTypes: [1, 3],
      marketCategoryId: '9223372036854775807',
      authorizedOnly: true,
      favoriteOnly: false,
      sort: 'LATEST',
    };
    await api.getMarketMartPage(filters);
    expect(requestClient.get).toHaveBeenCalledWith('/asset/market/mart/page', {
      params: filters,
    });
  });
  it('uses real favorite and publish endpoints without editing production taxonomy', async () => {
    const request: api.MarketApi.MartPublishReq = {
      assetId: '9223372036854775807',
      marketCategoryId: '9007199254740993',
      name: '展示名',
      description: '描述',
      frequency: '每天',
      deliveryTypes: [1, 3],
      expectedRevision: '9007199254740997',
      expectedListingRevision: 0,
    };
    await api.publishMarketMart(request);
    expect(requestClient.put).toHaveBeenCalledWith(
      '/asset/market/mart/publish',
      request,
    );
    await api.updateMarketMartFavorite({
      assetId: request.assetId,
      favorite: true,
    });
    expect(requestClient.put).toHaveBeenCalledWith(
      '/asset/market/mart/favorite',
      { assetId: request.assetId, favorite: true },
    );
  });
  it('gets safe listing prefill, real statistics and enabled taxonomy topics separately', async () => {
    await api.getMarketMartListing('9223372036854775807');
    expect(requestClient.get).toHaveBeenCalledWith(
      '/asset/market/mart/listing',
      { params: { assetId: '9223372036854775807' } },
    );
    await api.getMarketMartStatistics();
    expect(requestClient.get).toHaveBeenCalledWith(
      '/asset/market/mart/statistics',
    );
    await api.getMarketMartTopics();
    expect(requestClient.get).toHaveBeenCalledWith('/asset/market/mart/topics');
  });
});
