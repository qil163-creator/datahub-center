import { beforeEach, describe, expect, it, vi } from 'vitest';

import { loadSupplyOverview } from './data';

const api = vi.hoisted(() => ({
  getDashboardApiLogPage: vi.fn(),
  getDashboardApiLogStatistics: vi.fn(),
  getDashboardSupplyAppPage: vi.fn(),
}));

vi.mock('#/api/dashboard', () => api);
vi.mock('@vben/utils', () => ({ formatDate: () => '' }));

beforeEach(() => {
  vi.clearAllMocks();
});

describe('服务概览数据', () => {
  it('无调用时成功率与耗时显示暂无，不沿用后端默认 100%', async () => {
    api.getDashboardApiLogStatistics.mockResolvedValue({
      apiCount: 2,
      onlineApiCount: 1,
      totalCount: 0,
      todayCount: 0,
      successRate: 100,
      avgDurationMs: 0,
      trend: [{ date: '2026-11-11', successCount: 0, failCount: 0 }],
      topApis: [],
    });
    api.getDashboardSupplyAppPage.mockResolvedValue({ list: [], total: 3 });
    api.getDashboardApiLogPage.mockResolvedValue({
      list: [
        { id: 9, apiName: '查询', status: 0, durationMs: 12, callTime: 1000 },
      ],
      total: 1,
    });
    const result = await loadSupplyOverview();
    expect(result.metrics.api).toMatchObject({ value: 2 });
    expect(result.metrics.online?.hint).toBe('上线率 50.0%');
    expect(result.metrics.app).toMatchObject({ value: 3 });
    expect(result.metrics.successRate?.display).toBe('—');
    expect(result.metrics.duration?.display).toBe('—');
    expect(result.distributions.trend?.rows).toEqual([
      { key: '2026-11-11', name: '11-11', count: 0 },
    ]);
    expect(result.activity?.items[0]).toMatchObject({
      id: 'log-9',
      tone: 'error',
      time: 1000,
    });
  });

  it('单个数据源失败只让对应卡片降级', async () => {
    api.getDashboardApiLogStatistics.mockRejectedValue(new Error('403'));
    api.getDashboardSupplyAppPage.mockResolvedValue({ list: [], total: 0 });
    api.getDashboardApiLogPage.mockRejectedValue(new Error('403'));
    const result = await loadSupplyOverview();
    expect(result.metrics.api).toBeUndefined();
    expect(result.metrics.successRate).toBeUndefined();
    expect(result.metrics.app).toMatchObject({ value: 0 });
    expect(result.distributions.trend).toBeUndefined();
    expect(result.activity).toBeUndefined();
  });
});
