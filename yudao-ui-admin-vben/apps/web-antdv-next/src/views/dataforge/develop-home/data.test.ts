import { beforeEach, describe, expect, it, vi } from 'vitest';

import { developOverviewConfig, loadDevelopOverview } from './data';

const api = vi.hoisted(() => ({
  getDashboardAccessTaskPage: vi.fn(),
  getDashboardDataforgeSummary: vi.fn(),
  getDashboardDevRecordPage: vi.fn(),
  getDashboardIntegrationPage: vi.fn(),
  getDashboardIntegrationStatistics: vi.fn(),
  getDashboardJobPage: vi.fn(),
  getDashboardOpsInstancePage: vi.fn(),
  getDashboardOpsStatistics: vi.fn(),
}));

vi.mock('#/api/dashboard', () => api);
vi.mock('@vben/utils', () => ({ formatDate: () => '' }));

const page = (total: number, list: unknown[] = []) => ({ list, total });
const stats = (todayExecuted: number, todaySuccessRate = 0) => ({
  running: 0,
  todayExecuted,
  todayFailed: 0,
  todaySuccessRate,
});

beforeEach(() => {
  vi.clearAllMocks();
  api.getDashboardDataforgeSummary.mockResolvedValue({
    databaseCount: 7,
    databaseTypeDistribution: [],
    accessJobCount: 2,
    accessReleasedCount: 1,
    devTaskCount: 0,
    devTaskOnlineCount: 0,
    todayAccessRun: { totalCount: 4, successCount: 3 },
    todayDevRun: { totalCount: 0, successCount: 0 },
  });
  api.getDashboardIntegrationPage.mockResolvedValue(page(3));
  api.getDashboardIntegrationStatistics.mockResolvedValue(stats(0));
  api.getDashboardOpsStatistics.mockImplementation((kind: string) =>
    kind === 'JOB' ? Promise.resolve(stats(2, 50)) : Promise.reject(new Error('403')),
  );
  api.getDashboardJobPage.mockImplementation((status?: number) =>
    Promise.resolve(page(status === 1 ? 2 : 5)),
  );
  api.getDashboardDevRecordPage.mockResolvedValue(page(0));
  api.getDashboardAccessTaskPage.mockResolvedValue(page(0));
  api.getDashboardOpsInstancePage.mockImplementation((kind: string) =>
    Promise.resolve(
      page(1, [
        {
          id: kind === 'JOB' ? 1 : 2,
          kind,
          origin: 'PLATFORM',
          taskName: kind === 'JOB' ? '夜间作业' : '同步订单',
          instanceName: '',
          status: kind === 'JOB' ? 'FAILURE' : 'SUCCESS',
          startTime: kind === 'JOB' ? '2026-11-11 08:00:00' : '2026-11-11 09:00:00',
        },
      ]),
    ),
  );
});

describe('研发概览数据', () => {
  it('不包含可视化 ETL，作业卡片统计总数与已上线数', async () => {
    const texts = JSON.stringify(developOverviewConfig);
    expect(texts).not.toMatch(/ETL/);
    const result = await loadDevelopOverview();
    expect(result.metrics.job).toEqual({ value: 5, hint: '已上线 2 个' });
    expect(api.getDashboardJobPage).toHaveBeenCalledWith(1);
  });

  it('今日执行只展示可读取的数据源，无执行时成功率为暂无', async () => {
    const result = await loadDevelopOverview();
    const runs = result.distributions.todayRuns?.rows ?? [];
    expect(runs.map((row) => row.key)).toEqual([
      'access',
      'offline',
      'realtime',
      'dev',
      'job',
    ]);
    const rates = result.distributions.todaySuccess?.rows ?? [];
    expect(rates.find((row) => row.key === 'access')?.rate).toBe(75);
    expect(rates.find((row) => row.key === 'offline')?.rate).toBeNull();
    expect(rates.find((row) => row.key === 'job')?.rate).toBe(50);
  });

  it('最近运行合并集成与作业实例并按开始时间倒序', async () => {
    const result = await loadDevelopOverview();
    expect(result.activity?.items).toEqual([
      expect.objectContaining({
        id: 'ops-INTEGRATION-PLATFORM-2',
        title: '集成任务「同步订单」运行成功',
        tone: 'success',
      }),
      expect.objectContaining({
        id: 'ops-JOB-PLATFORM-1',
        title: '作业「夜间作业」运行失败',
        tone: 'error',
      }),
    ]);
  });
});
