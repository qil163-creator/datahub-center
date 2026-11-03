import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  authMarketApply,
  cancelMarketApply,
  checkMarketApply,
  pauseMarketApply,
  resubmitMarketApply,
  resumeMarketApply,
  revokeMarketApply,
  submitMarketApply,
  supplementMarketApply,
} from '#/api/asset/market';
import { requestClient } from '#/api/request';

vi.mock('#/api/request', () => ({
  requestClient: { put: vi.fn() },
}));

const id = '9223372036854775807';
const source = '9223372036854775806';

beforeEach(() => vi.mocked(requestClient.put).mockReset());

describe('client-seen application versions', () => {
  it('sends the seen content revision as an optional query parameter', async () => {
    await submitMarketApply(id, 4);
    await resubmitMarketApply(id, 5);
    await cancelMarketApply(id, 6);
    expect(vi.mocked(requestClient.put).mock.calls).toEqual([
      [
        '/asset/market/apply/submit',
        undefined,
        { params: { id, expectedRevision: 4 } },
      ],
      [
        '/asset/market/apply/resubmit',
        undefined,
        { params: { id, expectedRevision: 5 } },
      ],
      [
        '/asset/market/apply/cancel',
        undefined,
        { params: { id, expectedRevision: 6 } },
      ],
    ]);
  });

  it('omits missing versions so legacy callers keep server-side CAS', async () => {
    await submitMarketApply(id);
    await resumeMarketApply(id, null);
    await authMarketApply(id, 1, undefined);
    expect(vi.mocked(requestClient.put).mock.calls).toEqual([
      ['/asset/market/apply/submit', undefined, { params: { id } }],
      ['/asset/market/apply/resume', undefined, { params: { id } }],
      ['/asset/market/apply/auth', undefined, { params: { id, auth: 1 } }],
    ]);
  });

  it('keeps Long source revisions lossless in lifecycle requests', async () => {
    await authMarketApply(id, 0, source);
    await resumeMarketApply(id, source);
    await revokeMarketApply(id, '收回 & 说明', source);
    await pauseMarketApply({
      id,
      note: '暂停',
      expectedSourceRevision: source,
    });
    expect(vi.mocked(requestClient.put).mock.calls).toEqual([
      [
        '/asset/market/apply/auth',
        undefined,
        { params: { id, expectedSourceRevision: source, auth: 0 } },
      ],
      [
        '/asset/market/apply/resume',
        undefined,
        { params: { id, expectedSourceRevision: source } },
      ],
      [
        '/asset/market/apply/revoke',
        undefined,
        { params: { id, expectedSourceRevision: source, note: '收回 & 说明' } },
      ],
      [
        '/asset/market/apply/pause',
        { id, note: '暂停', expectedSourceRevision: source },
      ],
    ]);
  });

  it('carries the reviewer-seen revision in check and supplement bodies', async () => {
    await checkMarketApply({
      id,
      status: 2,
      checkNote: '通过',
      expectedRevision: 3,
    });
    await supplementMarketApply({ id, note: '补正', expectedRevision: 3 });
    expect(vi.mocked(requestClient.put).mock.calls).toEqual([
      [
        '/asset/market/apply/check',
        { id, status: 2, checkNote: '通过', expectedRevision: 3 },
      ],
      [
        '/asset/market/apply/supplement',
        { id, note: '补正', expectedRevision: 3 },
      ],
    ]);
  });
});
