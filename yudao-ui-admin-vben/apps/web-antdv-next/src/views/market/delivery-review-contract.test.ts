import { describe, expect, it, vi } from 'vitest';

import {
  getMarketDeliveryEvidence,
  getMarketDeliveryOperation,
  getMarketDeliveryReviews,
  recheckMarketDelivery,
  recordMarketDeliveryDisposal,
  recordMarketDeliveryReview,
} from '#/api/asset/market';
import { requestClient } from '#/api/request';

vi.mock('#/api/request', () => ({
  requestClient: { get: vi.fn(), put: vi.fn(), post: vi.fn() },
}));

describe('original delivery review HTTP contract', () => {
  it('preserves independent approval conflicts and lossless IDs from an APPLIED receipt', async () => {
    const evidence = {
      applyId: '9223372036854775807',
      operationId: 'original:A',
      sourceRevision: '9223372036854775805',
      classification: 'ORIGINAL_APPLIED',
      approvalStatus: 'CONFLICT',
      providerOutcome: 'APPLIED',
    };
    vi.mocked(requestClient.get).mockResolvedValueOnce(evidence);
    expect(await getMarketDeliveryEvidence('original:A')).toEqual(evidence);
  });

  it('whitelists review inputs and never sends session, actor or claimed evidence', async () => {
    const request = {
      requestId: '12345678-1234-1234-1234-123456789012',
      operationId: 'original:9223372036854775807',
      decision: 'KEEP_PENDING' as const,
      reason: '核查原因',
      basis: '核查依据',
      accessToken: '2',
      actorUserId: '3',
      evidence: 'untrusted',
      approvalStatus: 'MATCHED',
    };
    await recordMarketDeliveryReview(request);
    expect(requestClient.post).toHaveBeenCalledWith(
      '/asset/market/apply/delivery-review',
      {
        requestId: request.requestId,
        operationId: request.operationId,
        decision: request.decision,
        reason: request.reason,
        basis: request.basis,
      },
    );
  });
  it('whitelists disposal inputs on the dedicated disposal endpoint', async () => {
    const request = {
      requestId: '12345678-1234-1234-1234-123456789012',
      operationId: 'original:9223372036854775807',
      action: 'REVOKE' as const,
      reason: '处置原因',
      basis: '处置依据',
      basisReviewRequestId: '11111111-1111-1111-1111-111111111111',
      accessToken: '2',
      actorUserId: '3',
      approvalStatus: 'MATCHED',
      force: true,
    };
    await recordMarketDeliveryDisposal(request);
    expect(requestClient.post).toHaveBeenCalledWith(
      '/asset/market/apply/delivery-disposal',
      {
        requestId: request.requestId,
        operationId: request.operationId,
        action: request.action,
        reason: request.reason,
        basis: request.basis,
        basisReviewRequestId: request.basisReviewRequestId,
      },
    );
  });
  it('preserves the history cursor without numeric conversion', async () => {
    await getMarketDeliveryReviews('original:A', '9223372036854775806');
    expect(requestClient.get).toHaveBeenCalledWith(
      '/asset/market/apply/delivery-reviews',
      {
        params: { operationId: 'original:A', afterId: '9223372036854775806' },
      },
    );
  });
  it('uses a read-only evidence endpoint and preserves the opaque original operation identity', async () => {
    const operationId = 'original:9223372036854775807/&?';
    await getMarketDeliveryEvidence(operationId);
    expect(requestClient.get).toHaveBeenCalledWith(
      '/asset/market/apply/delivery-evidence',
      { params: { operationId } },
    );
  });
  it('sends opaque operation identities as query params, not interpolated paths', async () => {
    const operationId = 'original:9223372036854775807/&?';
    await getMarketDeliveryOperation(operationId);
    expect(requestClient.get).toHaveBeenCalledWith(
      '/asset/market/apply/delivery-operation',
      { params: { operationId } },
    );
  });
  it('sends only original identity and note to the dedicated read-only recheck endpoint', async () => {
    const request = {
      operationId: 'original:9223372036854775807',
      note: '核查原回执',
      frozenCommand: 'must not send',
    };
    await recheckMarketDelivery(request);
    expect(requestClient.put).toHaveBeenCalledWith(
      '/asset/market/apply/recheck-delivery',
      {
        operationId: request.operationId,
        note: request.note,
      },
    );
  });
});
