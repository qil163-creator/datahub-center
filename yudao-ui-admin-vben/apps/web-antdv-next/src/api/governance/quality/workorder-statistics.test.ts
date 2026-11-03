import { beforeEach, describe, expect, it, vi } from 'vitest';

import { getDashboardWorkOrderStatistics } from '#/api/dashboard';
import { requestClient } from '#/api/request';

import { getWorkOrderStatistics } from './workorder';

vi.mock('#/api/request', () => ({ requestClient: { get: vi.fn() } }));
beforeEach(() => vi.resetAllMocks());

describe('work-order statistics transport', () => {
  it('uses the resource endpoint and returns the unwrapped payload without ID coercion', async () => {
    const payload = {
      ownerSummary: [
        {
          ownerUserId: '9223372036854775807',
          ownerUserName: 'fixture',
          count: 1,
        },
      ],
    };
    vi.mocked(requestClient.get).mockResolvedValue(payload);
    expect(await getWorkOrderStatistics(7)).toBe(payload);
    expect(requestClient.get).toHaveBeenCalledWith(
      '/governance/quality-work-order/statistics',
      { params: { days: 7 } },
    );
  });

  it('dashboard uses the identical endpoint and contract with its established local degradation', async () => {
    const payload = { ownerSummary: [], sampleStatus: 'NO_SAMPLE' };
    vi.mocked(requestClient.get).mockResolvedValue(payload);
    expect(await getDashboardWorkOrderStatistics(30)).toBe(payload);
    expect(requestClient.get).toHaveBeenCalledWith(
      '/governance/quality-work-order/statistics',
      { params: { days: 30 }, silentError: true },
    );
  });
});
